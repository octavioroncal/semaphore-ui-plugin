package com.oroncal.semaphoreui.release

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.oroncal.semaphoreui.SemaphoreIcons
import java.nio.file.Path

class ReleaseArtifactAction : AnAction(
    "Release Artifact",
    "Create a release commit and tag from the selected Maven module",
    SemaphoreIcons.RELEASE_ACTION,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = ReleaseContextResolver.resolve(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val context = ReleaseContextResolver.resolve(event)
        if (context == null) {
            Messages.showErrorDialog(project, "Select a Maven module with pom.xml to create a release.", "Release Artifact")
            return
        }

        FileDocumentManager.getInstance().saveAllDocuments()
        val target = try {
            PomFileService.loadReleaseTarget(project, context)
        } catch (throwable: Throwable) {
            Messages.showErrorDialog(project, throwable.message ?: "Could not read the selected pom.xml.", "Release Artifact")
            return
        }

        val releasePlan = chooseReleasePlan(project, target) ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Create release for ${target.displayName}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Validating Git repository"
                val repoRoot = GitReleaseService.resolveRepositoryRoot(Path.of(context.moduleDir.path))
                confirmDirtyRepositoryIfNeeded(project, repoRoot)
                val pushTarget = GitReleaseService.resolvePushTarget(repoRoot)
                GitReleaseService.ensureValidTagName(repoRoot, releasePlan.tagName)
                GitReleaseService.ensureTagDoesNotExist(repoRoot, releasePlan.tagName)

                indicator.text = "Updating pom.xml version"
                PomFileService.updateVersion(project, context.pomFile, releasePlan.version)

                val relativePomPath = GitReleaseService.relativePath(repoRoot, Path.of(context.pomFile.path))
                val commitSubject = target.artifactId ?: target.tagModuleName

                indicator.text = "Creating Git commit and tag"
                try {
                    GitReleaseService.createCommitAndTag(
                        repoRoot = repoRoot,
                        relativePomPath = relativePomPath,
                        commitMessage = "release($commitSubject): ${releasePlan.version}",
                        tagName = releasePlan.tagName,
                        releasedVersion = releasePlan.version,
                    )
                } catch (throwable: Throwable) {
                    throw ReleaseException(
                        "pom.xml was updated to ${releasePlan.version}, but the Git operation did not complete successfully.",
                        throwable,
                    )
                }

                indicator.text = "Pushing release to Git remote"
                GitReleaseService.pushCommitAndTag(
                    repoRoot = repoRoot,
                    pushTarget = pushTarget,
                    tagName = releasePlan.tagName,
                    releasedVersion = releasePlan.version,
                )

                VfsUtil.markDirtyAndRefresh(false, false, false, context.pomFile)
            }

            override fun onSuccess() {
                Messages.showInfoMessage(
                    project,
                    "Release created and pushed for ${target.displayName}.\nVersion: ${releasePlan.version}\nTag: ${releasePlan.tagName}",
                    "Release Artifact",
                )
            }

            override fun onCancel() {
            }

            override fun onThrowable(error: Throwable) {
                if (error is ProcessCanceledException) {
                    return
                }
                val message = buildString {
                    append(error.message ?: "The release could not be completed.")
                    error.cause?.message?.takeIf { it.isNotBlank() }?.let {
                        append("\n\n")
                        append(it)
                    }
                }
                Messages.showErrorDialog(project, message, "Release Artifact")
            }
        })
    }

    private fun chooseReleasePlan(project: com.intellij.openapi.project.Project, target: ReleaseTarget): ReleasePlan? {
        val moduleName = target.displayName
        val fixVersion = target.parsedVersion.propose(ReleaseKind.FIX).toString()
        val minorVersion = target.parsedVersion.propose(ReleaseKind.MINOR).toString()
        val majorVersion = target.parsedVersion.propose(ReleaseKind.MAJOR).toString()
        val options = arrayOf(
            "Fix -> $fixVersion",
            "Minor -> $minorVersion",
            "Major -> $majorVersion",
            "Cancel",
        )
        val choice = Messages.showDialog(
            project,
            "Module: $moduleName\nCurrent version: ${target.currentVersion}\n\nSelect the release type.",
            "Release Artifact",
            options,
            0,
            Messages.getQuestionIcon(),
        )

        return when (choice) {
            0 -> ReleasePlan(ReleaseKind.FIX, fixVersion, "${target.tagModuleName}/$fixVersion")
            1 -> ReleasePlan(ReleaseKind.MINOR, minorVersion, "${target.tagModuleName}/$minorVersion")
            2 -> ReleasePlan(ReleaseKind.MAJOR, majorVersion, "${target.tagModuleName}/$majorVersion")
            else -> null
        }
    }

    private fun confirmDirtyRepositoryIfNeeded(project: com.intellij.openapi.project.Project, repoRoot: Path) {
        if (!GitReleaseService.hasUncommittedChanges(repoRoot)) {
            return
        }

        var shouldContinue = false
        ApplicationManager.getApplication().invokeAndWait {
            shouldContinue = Messages.showYesNoDialog(
                project,
                "The Git repository has uncommitted changes.\nOnly the selected pom.xml will be included in this release commit.\n\nDo you want to continue?",
                "Uncommitted Git Changes",
                Messages.getWarningIcon(),
            ) == Messages.YES
        }

        if (!shouldContinue) {
            throw ProcessCanceledException()
        }
    }
}
