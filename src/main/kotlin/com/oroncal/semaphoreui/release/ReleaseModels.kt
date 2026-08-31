package com.oroncal.semaphoreui.release

import com.intellij.openapi.vfs.VirtualFile

data class ReleaseContext(
    val moduleName: String,
    val moduleDir: VirtualFile,
    val pomFile: VirtualFile,
)

data class ReleaseTarget(
    val context: ReleaseContext,
    val artifactId: String?,
    val displayName: String,
    val tagModuleName: String,
    val currentVersion: String,
    val parsedVersion: SemanticVersion,
)

data class ReleasePlan(
    val kind: ReleaseKind,
    val version: String,
    val tagName: String,
)
