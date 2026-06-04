package com.oroncal.semaphoreui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.oroncal.semaphoreui.api.SemaphoreConnectionStatus
import com.oroncal.semaphoreui.api.SemaphoreIntegrationService
import com.oroncal.semaphoreui.api.SemaphoreProject
import com.oroncal.semaphoreui.api.SemaphoreRealtimeConnection
import com.oroncal.semaphoreui.api.SemaphoreRealtimeListener
import com.oroncal.semaphoreui.api.SemaphoreRealtimeMessage
import com.oroncal.semaphoreui.api.SemaphoreTask
import com.oroncal.semaphoreui.api.SemaphoreTemplate
import com.oroncal.semaphoreui.api.SemaphoreView
import com.oroncal.semaphoreui.settings.SemaphoreSettingsConfigurable
import java.awt.Toolkit
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.text.DefaultHighlighter

class SemaphoreToolWindowPanel(
    private val ideProject: Project,
) : JPanel(BorderLayout()), Disposable {
    private val integrationService = service<SemaphoreIntegrationService>()
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val outputSyncAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val projectsModel = ProjectsListModel()
    private val projectList = JBList(projectsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "No projects loaded"
        minimumSize = Dimension(180, 200)
    }

    private val viewsTabs = JTabbedPane()
    private val outputArea = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12)))
        text = "Select a template to see the output from its latest task"
    }
    private val outputScrollPane = JBScrollPane(outputArea)
    private val outputSearchField = JBTextField().apply {
        emptyText.text = "Search output"
    }
    private val outputSearchPrevButton = JButton("<")
    private val outputSearchNextButton = JButton(">")
    private val outputSearchCloseButton = JButton("x")
    private val outputSearchStatusLabel = JBLabel("").apply {
        foreground = JBColor.GRAY
    }
    private val outputSearchPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
        background = JBColor(Color(0xF7, 0xF7, 0xF7), Color(0x3C, 0x3F, 0x41))
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(6),
        )
        add(outputSearchStatusLabel)
        add(outputSearchPrevButton)
        add(outputSearchNextButton)
        add(outputSearchField.apply { preferredSize = Dimension(JBUI.scale(220), preferredSize.height) })
        add(outputSearchCloseButton)
    }
    private val outputSearchHost = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply {
        isVisible = false
        isOpaque = false
        add(outputSearchPanel)
    }
    private val outputPanel = JPanel(BorderLayout()).apply {
        add(outputSearchHost, BorderLayout.NORTH)
        add(outputScrollPane, BorderLayout.CENTER)
    }
    private val statusLabel = JBLabel("").apply {
        isVisible = false
        foreground = JBColor.RED
    }
    private val statusPanel = JPanel(BorderLayout()).apply {
        isVisible = false
        border = JBUI.Borders.emptyBottom(8)
        add(statusLabel, BorderLayout.CENTER)
    }

    private val viewPanels = linkedMapOf<String, ViewTemplatesPanel>()
    private var disposed = false
    private var realtimeConnection: SemaphoreRealtimeConnection? = null
    @Volatile
    private var realtimeGeneration = 0
    private var displayedProjectId: Int? = null
    private var loadedOutputProjectId: Int? = null
    private var loadedOutputTaskId: Int? = null
    private val outputSearchMatchPainter = DefaultHighlighter.DefaultHighlightPainter(JBColor(Color(0xFF, 0xF1, 0xA8), Color(0x66, 0x57, 0x00)))
    private val outputSearchCurrentMatchPainter = DefaultHighlighter.DefaultHighlightPainter(JBColor(Color(0xFF, 0xD5, 0x4F), Color(0x9C, 0x73, 0x00)))
    private val outputSearchMatches = mutableListOf<IntRange>()
    private var selectedOutputSearchMatchIndex = -1

    init {
        border = JBUI.Borders.empty(8)
        add(statusPanel, BorderLayout.NORTH)
        add(buildContent(), BorderLayout.CENTER)
        installListeners()
        refreshAll()
    }

    override fun dispose() {
        disposed = true
        closeRealtimeConnection()
        refreshAlarm.cancelAllRequests()
        outputSyncAlarm.cancelAllRequests()
    }

    fun refreshContent() {
        refreshAll()
    }

    fun openSettingsDialog() {
        realtimeGeneration += 1
        closeRealtimeConnection()
        ShowSettingsUtil.getInstance().showSettingsDialog(ideProject, SemaphoreSettingsConfigurable::class.java)
        refreshAll()
    }

    private fun buildContent(): JComponent {
        val templatesPane = JPanel(BorderLayout()).apply {
            add(viewsTabs, BorderLayout.CENTER)
        }

        val rightSplit = OnePixelSplitter(true, 0.58f).apply {
            firstComponent = templatesPane
            secondComponent = wrapWithTitledPane("Latest Task Output", buildOutputPane())
        }

        return OnePixelSplitter(false, 0.16f).apply {
            firstComponent = wrapWithTitledPane("Projects", JBScrollPane(projectList))
            secondComponent = rightSplit
        }
    }

    private fun installListeners() {
        projectList.addListSelectionListener { event: ListSelectionEvent ->
            if (!event.valueIsAdjusting) {
                refreshSelectedProjectData(silent = false)
            }
        }

        viewsTabs.addChangeListener {
            loadSelectedTemplateOutput(forceReload = false)
        }

        outputSearchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = refreshOutputSearch(resetSelection = true)

            override fun removeUpdate(event: DocumentEvent) = refreshOutputSearch(resetSelection = true)

            override fun changedUpdate(event: DocumentEvent) = refreshOutputSearch(resetSelection = true)
        })
        outputSearchPrevButton.addActionListener { selectOutputSearchMatch(-1) }
        outputSearchNextButton.addActionListener { selectOutputSearchMatch(1) }
        outputSearchCloseButton.addActionListener { hideOutputSearch() }
        outputSearchField.addActionListener { selectOutputSearchMatch(1) }
        installOutputSearchKeyBinding(outputPanel)
        installOutputSearchKeyBinding(outputScrollPane)
        installOutputSearchKeyBinding(outputArea)
        installOutputSearchKeyBinding(outputSearchField)
        installOutputSearchDismissBinding(outputPanel)
        installOutputSearchDismissBinding(outputSearchField)
        updateOutputSearchControls()
    }

    private fun buildOutputPane(): JComponent {
        return outputPanel
    }

    private fun installOutputSearchKeyBinding(component: JComponent) {
        val findShortcut = KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(findShortcut, "showOutputSearch")
        component.actionMap.put("showOutputSearch", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                showOutputSearch()
            }
        })
    }

    private fun installOutputSearchDismissBinding(component: JComponent) {
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hideOutputSearch")
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "previousOutputSearchMatch")
        component.actionMap.put("hideOutputSearch", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                hideOutputSearch()
            }
        })
        component.actionMap.put("previousOutputSearchMatch", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                selectOutputSearchMatch(-1)
            }
        })
    }

    private fun showOutputSearch() {
        if (!outputSearchHost.isVisible) {
            outputSearchHost.isVisible = true
            val selectedText = outputArea.selectedText?.trim().orEmpty()
            if (outputSearchField.text.isBlank() && selectedText.isNotEmpty()) {
                outputSearchField.text = selectedText
            }
        }

        refreshOutputSearch(resetSelection = false)
        outputSearchField.requestFocusInWindow()
        outputSearchField.selectAll()
        outputPanel.revalidate()
        outputPanel.repaint()
    }

    private fun hideOutputSearch() {
        outputSearchHost.isVisible = false
        outputArea.highlighter.removeAllHighlights()
        outputArea.select(outputArea.caretPosition, outputArea.caretPosition)
        outputArea.requestFocusInWindow()
        outputPanel.revalidate()
        outputPanel.repaint()
    }

    private fun refreshAll() {
        runAsync(
            action = {
                LoadedProjects(
                    connection = integrationService.testConnection(),
                    projects = integrationService.fetchProjects().sortedBy { it.name.lowercase() },
                )
            },
            onSuccess = { loaded ->
                clearStatus()
                projectsModel.setItems(loaded.projects)
                if (loaded.projects.isEmpty()) {
                    rebuildViewTabs(projectId = null, views = emptyList(), templates = emptyList(), preferredViewKey = null, preferredTemplateId = null)
                    outputArea.text = "Semaphore returned no projects."
                    refreshOutputSearch(resetSelection = false)
                    displayedProjectId = null
                    loadedOutputProjectId = null
                    loadedOutputTaskId = null
                } else {
                    val currentSelection = selectedProject()?.id
                    val indexToSelect = loaded.projects.indexOfFirst { it.id == currentSelection }.takeIf { it >= 0 } ?: 0
                    projectList.selectedIndex = indexToSelect
                }
                restartRealtimeConnection()
            },
            onError = {
                closeRealtimeConnection()
                showError(it)
            },
        )
    }

    private fun refreshSelectedProjectData(silent: Boolean, preferredTemplateId: Int? = null) {
        val project = selectedProject() ?: return
        val keepUiState = displayedProjectId == project.id
        val selectedTemplateId = preferredTemplateId ?: selectedTemplate()?.id?.takeIf { keepUiState }
        val selectedViewKey = currentViewPanel()?.key?.takeIf { keepUiState }

        runAsync(
            action = {
                LoadedProjectData(
                    projectId = project.id,
                    templates = integrationService.fetchTemplates(project.id).sortedBy { it.name.lowercase() },
                    views = integrationService.fetchViews(project.id)
                        .filter { it.hidden != true }
                        .sortedWith(compareBy<SemaphoreView> { it.position ?: Int.MAX_VALUE }.thenBy { it.title.orEmpty().lowercase() }),
                )
            },
            onSuccess = onSuccess@{ loaded ->
                if (selectedProject()?.id != loaded.projectId) {
                    return@onSuccess
                }

                clearStatus()
                rebuildViewTabs(loaded.projectId, loaded.views, loaded.templates, selectedViewKey, selectedTemplateId)
                loadSelectedTemplateOutput(forceReload = false)
            },
            onError = { throwable ->
                if (!silent) {
                    showError(throwable)
                }
            },
        )
    }

    private fun rebuildViewTabs(
        projectId: Int?,
        views: List<SemaphoreView>,
        templates: List<SemaphoreTemplate>,
        preferredViewKey: String?,
        preferredTemplateId: Int?,
    ) {
        val desiredTabs = buildViewTabs(templates, views)
        val existingPanels = viewPanels.toMap()
        viewPanels.clear()
        viewsTabs.removeAll()

        desiredTabs.forEach { tab ->
            val panel = existingPanels[tab.key] ?: ViewTemplatesPanel(tab.key)
            panel.model.setItems(tab.templates)
            viewPanels[tab.key] = panel
            viewsTabs.addTab(tab.title, panel)
        }

        val targetKey = preferredViewKey?.takeIf { viewPanels.containsKey(it) } ?: viewPanels.keys.firstOrNull()
        if (targetKey != null) {
            viewsTabs.selectedComponent = viewPanels[targetKey]
        }

        displayedProjectId = projectId
        restoreTemplateSelection(preferredTemplateId)
    }

    private fun buildViewTabs(
        templates: List<SemaphoreTemplate>,
        views: List<SemaphoreView>,
    ): List<ViewTabData> {
        val tabs = mutableListOf<ViewTabData>()
        val hasAllView = views.any { it.type == "all" }

        views.forEach { view ->
            val key = viewKey(view)
            val tabTemplates = if (view.type == "all") {
                templates
            } else {
                templates.filter { it.view_id == view.id }
            }
            tabs += ViewTabData(key = key, title = viewTitle(view), templates = tabTemplates)
        }

        val unassignedTemplates = templates.filter { it.view_id == null }
        if (unassignedTemplates.isNotEmpty() && !hasAllView) {
            tabs += ViewTabData(key = UNASSIGNED_VIEW_KEY, title = "Unassigned", templates = unassignedTemplates)
        }

        if (tabs.isEmpty()) {
            tabs += ViewTabData(key = DEFAULT_VIEW_KEY, title = "Templates", templates = templates)
        }

        return tabs
    }

    private fun loadSelectedTemplateOutput(forceReload: Boolean) {
        val project = selectedProject() ?: return
        val template = selectedTemplate() ?: run {
            outputArea.text = "Select a template to see the output from its latest task"
            refreshOutputSearch(resetSelection = false)
            loadedOutputProjectId = null
            loadedOutputTaskId = null
            return
        }
        val lastTask = template.last_task ?: run {
            outputArea.text = "No task has been run for this template yet."
            refreshOutputSearch(resetSelection = false)
            loadedOutputProjectId = project.id
            loadedOutputTaskId = null
            return
        }

        if (!forceReload && loadedOutputProjectId == project.id && loadedOutputTaskId == lastTask.id) {
            return
        }

        runAsync(
            action = {
                LoadedTaskOutput(
                    projectId = project.id,
                    viewKey = currentViewPanel()?.key,
                    templateId = template.id,
                    taskId = lastTask.id,
                    output = integrationService.fetchTaskRawOutput(project.id, lastTask.id),
                )
            },
            onSuccess = onSuccess@{ loaded ->
                val selectedTemplate = selectedTemplate()
                val selectedLastTask = selectedTemplate?.last_task
                if (selectedProject()?.id != loaded.projectId || currentViewPanel()?.key != loaded.viewKey || selectedTemplate?.id != loaded.templateId || selectedLastTask?.id != loaded.taskId) {
                    return@onSuccess
                }
                clearStatus()
                loadedOutputProjectId = loaded.projectId
                loadedOutputTaskId = loaded.taskId
                outputArea.text = loaded.output.ifBlank {
                    if (canStopTask(selectedLastTask.status)) {
                        "Waiting for task output..."
                    } else {
                        "The latest task has no output yet."
                    }
                }
                refreshOutputSearch(resetSelection = false)
                outputArea.caretPosition = outputArea.document.length
            },
        )
    }

    private fun handleTemplateAction(template: SemaphoreTemplate) {
        if (canStopTask(template.last_task?.status)) {
            stopLatestTask(template)
        } else {
            runTemplate(template)
        }
    }

    private fun runTemplate(template: SemaphoreTemplate) {
        val project = selectedProject() ?: return
        val dialog = RunTemplateDialog(template)
        if (!dialog.showAndGet()) {
            return
        }

        runAsync(
            action = { integrationService.runTask(project.id, dialog.toRequest()) },
            onSuccess = { _ ->
                clearStatus()
                refreshSelectedProjectData(silent = false, preferredTemplateId = template.id)
            },
        )
    }

    private fun stopLatestTask(template: SemaphoreTemplate) {
        val project = selectedProject() ?: return
        val task = template.last_task ?: return
        val answer = Messages.showYesNoDialog(
            ideProject,
            "Stop the latest task #${task.id} for template ${template.name}?",
            "Stop Semaphore Task",
            null,
        )
        if (answer != Messages.YES) {
            return
        }

        runAsync(
            action = {
                integrationService.stopTask(project.id, task.id)
                task.id
            },
            onSuccess = { _ ->
                clearStatus()
                refreshSelectedProjectData(silent = false, preferredTemplateId = template.id)
            },
        )
    }

    private fun selectedProject(): SemaphoreProject? = projectList.selectedValue

    private fun currentViewPanel(): ViewTemplatesPanel? = viewsTabs.selectedComponent as? ViewTemplatesPanel

    private fun selectedTemplate(): SemaphoreTemplate? = currentViewPanel()?.selectedTemplate()

    private fun restoreTemplateSelection(templateId: Int?) {
        val panel = currentViewPanel() ?: return
        panel.restoreSelection(templateId)
    }

    private fun restartRealtimeConnection() {
        val generation = ++realtimeGeneration
        closeRealtimeConnection()
        openRealtimeConnection(generation)
    }

    private fun openRealtimeConnection(generation: Int) {
        runAsync(
            action = { integrationService.openRealtime(createRealtimeListener(generation)) },
            onSuccess = onSuccess@{ connection ->
                if (disposed || generation != realtimeGeneration) {
                    connection.close()
                    return@onSuccess
                }
                realtimeConnection = connection
            },
            onError = onError@{ throwable ->
                if (generation != realtimeGeneration || disposed) {
                    return@onError
                }
                realtimeConnection = null
                scheduleRealtimeReconnect(generation)
                showError(throwable)
            },
        )
    }

    private fun createRealtimeListener(generation: Int): SemaphoreRealtimeListener {
        return object : SemaphoreRealtimeListener {
            override fun onOpen() {
                invokeOnUiThread {
                    if (generation == realtimeGeneration) {
                        clearStatus()
                    }
                }
            }

            override fun onMessage(message: SemaphoreRealtimeMessage) {
                invokeOnUiThread {
                    if (generation == realtimeGeneration) {
                        handleRealtimeMessage(message)
                    }
                }
            }

            override fun onFailure(throwable: Throwable) {
                invokeOnUiThread {
                    if (generation != realtimeGeneration || disposed) {
                        return@invokeOnUiThread
                    }
                    realtimeConnection = null
                    scheduleRealtimeReconnect(generation)
                }
            }

            override fun onClosed() {
                invokeOnUiThread {
                    if (generation != realtimeGeneration || disposed) {
                        return@invokeOnUiThread
                    }
                    realtimeConnection = null
                    scheduleRealtimeReconnect(generation)
                }
            }
        }
    }

    private fun scheduleRealtimeReconnect(generation: Int) {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest(
            {
                if (!disposed && generation == realtimeGeneration && realtimeConnection == null) {
                    openRealtimeConnection(generation)
                }
            },
            REALTIME_RECONNECT_MS,
        )
    }

    private fun closeRealtimeConnection() {
        refreshAlarm.cancelAllRequests()
        realtimeConnection?.close()
        realtimeConnection = null
    }

    private fun handleRealtimeMessage(message: SemaphoreRealtimeMessage) {
        val selectedProjectId = selectedProject()?.id ?: return
        if (message.project_id != selectedProjectId) {
            return
        }

        when (message.type) {
            "update" -> handleRealtimeUpdate(message)
            "log" -> handleRealtimeLog(message)
        }
    }

    private fun handleRealtimeUpdate(message: SemaphoreRealtimeMessage) {
        val projectId = message.project_id ?: return
        val templateId = message.template_id ?: return
        val taskId = message.task_id ?: return
        val currentTemplate = findTemplate(templateId) ?: return
        val currentTask = currentTemplate.last_task

        if (currentTask == null || currentTask.id != taskId) {
            runAsync(
                action = {
                    RefreshedRealtimeTask(
                        projectId = projectId,
                        templateId = templateId,
                        taskId = taskId,
                        task = integrationService.fetchLatestTemplateTask(projectId, templateId),
                    )
                },
                onSuccess = onSuccess@{ refreshed ->
                    if (selectedProject()?.id != refreshed.projectId) {
                        return@onSuccess
                    }
                    val latestTask = refreshed.task?.takeIf { it.id == refreshed.taskId } ?: return@onSuccess
                    updateTemplateLastTask(refreshed.templateId, latestTask)
                    if (selectedTemplate()?.id == refreshed.templateId) {
                        loadSelectedTemplateOutput(forceReload = true)
                    }
                },
                onError = {},
            )
            return
        }

        updateTemplateLastTask(templateId, mergeRealtimeUpdate(currentTask, message))
    }

    private fun handleRealtimeLog(message: SemaphoreRealtimeMessage) {
        val selectedTask = selectedTemplate()?.last_task ?: return
        if (loadedOutputProjectId != message.project_id || loadedOutputTaskId != message.task_id || selectedTask.id != message.task_id) {
            return
        }

        if (outputArea.text == "Waiting for task output..." || outputArea.text == "The latest task has no output yet.") {
            outputArea.text = ""
        }

        scheduleOutputSync(message.project_id, message.task_id)
    }

    private fun findTemplate(templateId: Int): SemaphoreTemplate? {
        return viewPanels.values.asSequence()
            .mapNotNull { it.model.findTemplate(templateId) }
            .firstOrNull()
    }

    private fun updateTemplateLastTask(templateId: Int, task: SemaphoreTask) {
        viewPanels.values.forEach { panel ->
            panel.model.updateTemplate(templateId) { template -> template.copy(last_task = task) }
        }
    }

    private fun mergeRealtimeUpdate(task: SemaphoreTask, message: SemaphoreRealtimeMessage): SemaphoreTask {
        return task.copy(
            status = message.status ?: task.status,
            start = message.start ?: task.start,
            end = message.end ?: task.end,
            version = message.version ?: task.version,
        )
    }

    private fun scheduleOutputSync(projectId: Int?, taskId: Int?) {
        if (projectId == null || taskId == null) {
            return
        }

        outputSyncAlarm.cancelAllRequests()
        outputSyncAlarm.addRequest(
            {
                val selectedTask = selectedTemplate()?.last_task
                if (!disposed &&
                    loadedOutputProjectId == projectId &&
                    loadedOutputTaskId == taskId &&
                    selectedTask?.id == taskId &&
                    selectedProject()?.id == projectId
                ) {
                    loadSelectedTemplateOutput(forceReload = true)
                }
            },
            OUTPUT_SYNC_DEBOUNCE_MS,
        )
    }

    private fun refreshOutputSearch(resetSelection: Boolean) {
        outputArea.highlighter.removeAllHighlights()
        outputSearchMatches.clear()

        if (!outputSearchHost.isVisible) {
            selectedOutputSearchMatchIndex = -1
            return
        }

        val query = outputSearchField.text.trim()
        if (query.isEmpty()) {
            selectedOutputSearchMatchIndex = -1
            updateOutputSearchControls()
            return
        }

        val text = outputArea.text
        if (text.isEmpty()) {
            selectedOutputSearchMatchIndex = -1
            updateOutputSearchControls()
            return
        }

        val haystack = text.lowercase()
        val needle = query.lowercase()
        var startIndex = 0
        while (startIndex <= haystack.length - needle.length) {
            val matchIndex = haystack.indexOf(needle, startIndex)
            if (matchIndex < 0) {
                break
            }
            outputSearchMatches += matchIndex until (matchIndex + needle.length)
            startIndex = matchIndex + needle.length
        }

        if (outputSearchMatches.isEmpty()) {
            selectedOutputSearchMatchIndex = -1
            updateOutputSearchControls()
            return
        }

        selectedOutputSearchMatchIndex = when {
            resetSelection -> 0
            selectedOutputSearchMatchIndex in outputSearchMatches.indices -> selectedOutputSearchMatchIndex
            else -> outputSearchMatches.lastIndex
        }

        applyOutputSearchHighlights()
        focusCurrentOutputSearchMatch()
        updateOutputSearchControls()
    }

    private fun selectOutputSearchMatch(step: Int) {
        if (outputSearchMatches.isEmpty()) {
            return
        }

        selectedOutputSearchMatchIndex = if (selectedOutputSearchMatchIndex < 0) {
            if (step < 0) outputSearchMatches.lastIndex else 0
        } else {
            Math.floorMod(selectedOutputSearchMatchIndex + step, outputSearchMatches.size)
        }

        applyOutputSearchHighlights()
        focusCurrentOutputSearchMatch()
        updateOutputSearchControls()
    }

    private fun applyOutputSearchHighlights() {
        outputArea.highlighter.removeAllHighlights()
        outputSearchMatches.forEachIndexed { index, range ->
            val painter = if (index == selectedOutputSearchMatchIndex) {
                outputSearchCurrentMatchPainter
            } else {
                outputSearchMatchPainter
            }
            outputArea.highlighter.addHighlight(range.first, range.last + 1, painter)
        }
    }

    private fun focusCurrentOutputSearchMatch() {
        val match = outputSearchMatches.getOrNull(selectedOutputSearchMatchIndex) ?: return
        outputArea.caretPosition = match.last + 1
        outputArea.select(match.first, match.last + 1)
    }

    private fun updateOutputSearchControls() {
        val hasMatches = outputSearchMatches.isNotEmpty()
        outputSearchPrevButton.isEnabled = hasMatches
        outputSearchNextButton.isEnabled = hasMatches
        outputSearchStatusLabel.text = when {
            outputSearchField.text.trim().isEmpty() -> ""
            !hasMatches -> "No matches"
            else -> "${selectedOutputSearchMatchIndex + 1}/${outputSearchMatches.size}"
        }
    }

    private fun clearStatus() {
        statusLabel.text = ""
        statusLabel.isVisible = false
        statusPanel.isVisible = false
    }

    private fun invokeOnUiThread(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (!disposed) {
                    action()
                }
            },
            ModalityState.any(),
        )
    }

    private fun configureTable(table: JBTable) {
        table.setShowGrid(false)
        table.autoCreateRowSorter = true
        table.rowHeight = JBUI.scale(26)
        table.emptyText.text = "No data"
        table.fillsViewportHeight = true
    }

    private fun wrapWithTitledPane(title: String, component: JComponent): JComponent {
        return JPanel(BorderLayout()).apply {
            add(JBLabel(title).apply { border = JBUI.Borders.empty(0, 0, 6, 0) }, BorderLayout.NORTH)
            add(component, BorderLayout.CENTER)
        }
    }

    private fun <T> runAsync(
        action: () -> T,
        onSuccess: (T) -> Unit,
        onError: (Throwable) -> Unit = { showError(it) },
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = action()
                ApplicationManager.getApplication().invokeLater(
                    {
                        if (!disposed) {
                            onSuccess(result)
                        }
                    },
                    ModalityState.any(),
                )
            } catch (throwable: Throwable) {
                ApplicationManager.getApplication().invokeLater(
                    {
                        if (!disposed) {
                            onError(throwable)
                        }
                    },
                    ModalityState.any(),
                )
            }
        }
    }

    private fun showError(throwable: Throwable) {
        statusLabel.text = throwable.message ?: throwable.javaClass.simpleName
        statusLabel.isVisible = true
        statusPanel.isVisible = true
    }

    private fun canStopTask(status: String?): Boolean {
        return status != null && status.lowercase() !in FINISHED_STATUSES
    }

    private fun viewKey(view: SemaphoreView): String = "view:${view.id}"

    private fun viewTitle(view: SemaphoreView): String {
        return view.title?.takeIf { it.isNotBlank() }
            ?: if (view.type == "all") "All" else "View ${view.id}"
    }

    private data class LoadedProjects(
        val connection: SemaphoreConnectionStatus,
        val projects: List<SemaphoreProject>,
    )

    private data class LoadedProjectData(
        val projectId: Int,
        val templates: List<SemaphoreTemplate>,
        val views: List<SemaphoreView>,
    )

    private data class LoadedTaskOutput(
        val projectId: Int,
        val viewKey: String?,
        val templateId: Int,
        val taskId: Int,
        val output: String,
    )

    private data class RefreshedRealtimeTask(
        val projectId: Int,
        val templateId: Int,
        val taskId: Int,
        val task: SemaphoreTask?,
    )

    private data class ViewTabData(
        val key: String,
        val title: String,
        val templates: List<SemaphoreTemplate>,
    )

    private class ProjectsListModel : javax.swing.AbstractListModel<SemaphoreProject>() {
        private val items = mutableListOf<SemaphoreProject>()

        override fun getSize(): Int = items.size

        override fun getElementAt(index: Int): SemaphoreProject = items[index]

        fun setItems(newItems: List<SemaphoreProject>) {
            items.clear()
            items.addAll(newItems)
            fireContentsChanged(this, 0, size)
        }
    }

    private inner class ViewTemplatesPanel(
        val key: String,
    ) : JPanel(BorderLayout()) {
        val model = TemplatesTableModel()
        private val table = JBTable(model)

        init {
            configureTable(table)
            table.columnModel.getColumn(TemplatesTableModel.TEMPLATE_COLUMN).apply {
                preferredWidth = JBUI.scale(320)
                minWidth = JBUI.scale(220)
            }
            table.columnModel.getColumn(TemplatesTableModel.ACTION_COLUMN).cellRenderer = ActionCellRenderer()
            table.columnModel.getColumn(TemplatesTableModel.STATUS_COLUMN).cellRenderer = StatusCellRenderer()
            configureFixedColumnWidth(TemplatesTableModel.ACTION_COLUMN, minimumContentWidth = JBUI.scale(40))
            configureFixedColumnWidth(TemplatesTableModel.STATUS_COLUMN, minimumContentWidth = JBUI.scale(40))
            add(JBScrollPane(table), BorderLayout.CENTER)
            table.selectionModel.addListSelectionListener {
                if (!it.valueIsAdjusting && viewsTabs.selectedComponent === this) {
                    loadSelectedTemplateOutput(forceReload = false)
                }
            }
            table.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    handleActionClick(event)
                }
            })
        }

        private fun configureFixedColumnWidth(columnIndex: Int, minimumContentWidth: Int) {
            val column = table.columnModel.getColumn(columnIndex)
            val headerRenderer = table.tableHeader.defaultRenderer
            val headerComponent = headerRenderer.getTableCellRendererComponent(
                table,
                column.headerValue,
                false,
                false,
                -1,
                columnIndex,
            )
            val width = maxOf(headerComponent.preferredSize.width + JBUI.scale(16), minimumContentWidth)
            column.preferredWidth = width
            column.minWidth = width
            column.maxWidth = width
        }

        private fun handleActionClick(event: MouseEvent) {
            if (event.button != MouseEvent.BUTTON1 || event.clickCount != 1) {
                return
            }

            val viewRow = table.rowAtPoint(event.point)
            val viewColumn = table.columnAtPoint(event.point)
            if (viewRow < 0 || viewColumn < 0) {
                return
            }

            val modelColumn = table.convertColumnIndexToModel(viewColumn)
            if (modelColumn != TemplatesTableModel.ACTION_COLUMN) {
                return
            }

            val template = model.itemAt(table.convertRowIndexToModel(viewRow)) ?: return
            table.setRowSelectionInterval(viewRow, viewRow)
            handleTemplateAction(template)
        }

        fun selectedTemplate(): SemaphoreTemplate? {
            val viewRow = table.selectedRow
            if (viewRow < 0) {
                return null
            }
            return model.itemAt(table.convertRowIndexToModel(viewRow))
        }

        fun restoreSelection(templateId: Int?) {
            if (templateId == null) {
                if (model.rowCount > 0 && table.selectedRow < 0) {
                    table.setRowSelectionInterval(0, 0)
                }
                return
            }

            val row = model.indexOfTemplate(templateId)
            if (row >= 0) {
                val viewRow = table.convertRowIndexToView(row)
                if (viewRow >= 0) {
                    table.setRowSelectionInterval(viewRow, viewRow)
                }
            } else if (model.rowCount > 0) {
                table.setRowSelectionInterval(0, 0)
            }
        }
    }

    private class TemplatesTableModel : AbstractTableModel() {
        private val columns = listOf("Template", "Action", "Last Task", "Status", "Started By", "Last Run")
        private var items: List<SemaphoreTemplate> = emptyList()

        override fun getRowCount(): Int = items.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = items[rowIndex]
            val lastTask = item.last_task
            return when (columnIndex) {
                0 -> item.name
                1 -> lastTask?.status.orEmpty()
                2 -> lastTask?.id?.toString().orEmpty()
                3 -> lastTask?.status.orEmpty()
                4 -> launcherName(lastTask)
                5 -> formatTimestamp(lastTask?.start ?: lastTask?.created)
                else -> ""
            }
        }

        fun setItems(newItems: List<SemaphoreTemplate>) {
            items = newItems
            fireTableDataChanged()
        }

        fun itemAt(row: Int): SemaphoreTemplate? {
            return items.getOrNull(row.takeIf { it >= 0 } ?: return null)
        }

        fun findTemplate(templateId: Int): SemaphoreTemplate? = items.firstOrNull { it.id == templateId }

        fun indexOfTemplate(templateId: Int): Int = items.indexOfFirst { it.id == templateId }

        fun updateTemplate(templateId: Int, transform: (SemaphoreTemplate) -> SemaphoreTemplate) {
            val index = indexOfTemplate(templateId)
            if (index < 0) {
                return
            }

            val updatedItems = items.toMutableList()
            updatedItems[index] = transform(updatedItems[index])
            items = updatedItems
            fireTableRowsUpdated(index, index)
        }

        private fun launcherName(task: SemaphoreTask?): String {
            if (task == null) {
                return ""
            }
            return task.user_name
                ?: when {
                    task.schedule_id != null -> "Schedule"
                    task.integration_id != null -> "Integration"
                    task.user_id != null -> "User #${task.user_id}"
                    else -> "Unknown"
                }
        }

        private fun formatTimestamp(value: String?): String {
            if (value.isNullOrBlank()) {
                return ""
            }
            return value
                .replace('T', ' ')
                .substringBefore('.')
                .removeSuffix("Z")
        }

        companion object {
            const val TEMPLATE_COLUMN = 0
            const val ACTION_COLUMN = 1
            const val STATUS_COLUMN = 3
        }
    }

    private class ActionCellRenderer : JPanel(), TableCellRenderer {
        private var action: RowAction = RowAction.PLAY
        private var iconColor: Color = Color(0x2E, 0x9D, 0x57)

        init {
            isOpaque = true
            preferredSize = Dimension(JBUI.scale(72), JBUI.scale(18))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        override fun getTableCellRendererComponent(
            table: javax.swing.JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val status = value?.toString()
            action = if (status != null && status.lowercase() !in FINISHED_STATUSES) RowAction.STOP else RowAction.PLAY
            iconColor = when (action) {
                RowAction.PLAY -> JBColor(Color(0x2E, 0x9D, 0x57), Color(0x2E, 0x9D, 0x57))
                RowAction.STOP -> JBColor(Color(0xCC, 0x33, 0x33), Color(0xCC, 0x33, 0x33))
            }
            background = if (isSelected) table.selectionBackground else table.background
            toolTipText = if (action == RowAction.STOP) "Stop latest task" else "Run template"
            return this
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)

            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = iconColor
                when (action) {
                    RowAction.PLAY -> {
                        val size = JBUI.scale(14)
                        val x = (width - size) / 2
                        val y = (height - size) / 2
                        val xPoints = intArrayOf(x, x, x + size)
                        val yPoints = intArrayOf(y, y + size, y + size / 2)
                        g2.fillPolygon(xPoints, yPoints, 3)
                    }

                    RowAction.STOP -> {
                        val size = JBUI.scale(12)
                        val x = (width - size) / 2
                        val y = (height - size) / 2
                        g2.fillRoundRect(x, y, size, size, JBUI.scale(4), JBUI.scale(4))
                    }
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private class StatusCellRenderer : JPanel(), TableCellRenderer {
        private var indicatorColor: Color = Color(0x7A, 0x7F, 0x87)

        init {
            isOpaque = true
            preferredSize = Dimension(JBUI.scale(42), JBUI.scale(18))
        }

        override fun getTableCellRendererComponent(
            table: javax.swing.JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val status = value?.toString().orEmpty()
            indicatorColor = when (status.lowercase()) {
                "success" -> JBColor(Color(0x2E, 0x9D, 0x57), Color(0x2E, 0x9D, 0x57))
                "error", "fail", "failed" -> JBColor(Color(0xCC, 0x33, 0x33), Color(0xCC, 0x33, 0x33))
                "running" -> JBColor(Color(0x2D, 0x7F, 0xE3), Color(0x2D, 0x7F, 0xE3))
                else -> JBColor(Color(0x7A, 0x7F, 0x87), Color(0x7A, 0x7F, 0x87))
            }
            background = if (isSelected) table.selectionBackground else table.background
            toolTipText = status.ifBlank { null }

            return this
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)

            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val diameter = (minOf(width, height) - JBUI.scale(10)).coerceAtLeast(JBUI.scale(10))
                val x = (width - diameter) / 2
                val y = (height - diameter) / 2
                g2.color = indicatorColor
                g2.fillOval(x, y, diameter, diameter)
                g2.color = indicatorColor.darker()
                g2.drawOval(x, y, diameter, diameter)
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        private const val OUTPUT_SYNC_DEBOUNCE_MS = 150
        private const val REALTIME_RECONNECT_MS = 2_000
        private const val DEFAULT_VIEW_KEY = "default"
        private const val UNASSIGNED_VIEW_KEY = "unassigned"
        private val FINISHED_STATUSES = setOf("success", "error", "fail", "failed", "stopped")
    }

    private enum class RowAction {
        PLAY,
        STOP,
    }
}
