package com.oroncal.semaphoreui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object SemaphoreIcons {
    @JvmField
    val PLUGIN: Icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", SemaphoreIcons::class.java)

    @JvmField
    val RELEASE_ACTION: Icon = IconLoader.getIcon("/icons/releaseAction.svg", SemaphoreIcons::class.java)
}
