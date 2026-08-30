package com.oroncal.semaphoreui.release

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.actionSystem.LangDataKeys

object ReleaseContextResolver {
    fun resolve(event: AnActionEvent): ReleaseContext? {
        val project = event.project ?: return null
        val selectedFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (selectedFiles != null && selectedFiles.size == 1) {
            resolveFromFile(project, selectedFiles.single())?.let { return it }
        }

        val module = event.getData(LangDataKeys.MODULE) ?: return null
        return resolveFromModule(module)
    }

    private fun resolveFromFile(project: com.intellij.openapi.project.Project, file: VirtualFile): ReleaseContext? {
        val moduleDir = when {
            file.isDirectory -> file
            file.name.equals(POM_FILE_NAME, ignoreCase = true) -> file.parent
            else -> null
        } ?: return null

        val pomFile = if (file.name.equals(POM_FILE_NAME, ignoreCase = true)) file else moduleDir.findChild(POM_FILE_NAME)
        if (pomFile == null || pomFile.isDirectory) {
            return null
        }

        val moduleName = ModuleUtilCore.findModuleForFile(moduleDir, project)?.name ?: moduleDir.name
        return ReleaseContext(
            moduleName = moduleName,
            moduleDir = moduleDir,
            pomFile = pomFile,
        )
    }

    private fun resolveFromModule(module: Module): ReleaseContext? {
        val moduleDir = ModuleRootManager.getInstance(module).contentRoots.firstOrNull {
            it.findChild(POM_FILE_NAME)?.isDirectory == false
        } ?: return null

        val pomFile = moduleDir.findChild(POM_FILE_NAME) ?: return null
        return ReleaseContext(
            moduleName = module.name,
            moduleDir = moduleDir,
            pomFile = pomFile,
        )
    }

    private const val POM_FILE_NAME = "pom.xml"
}
