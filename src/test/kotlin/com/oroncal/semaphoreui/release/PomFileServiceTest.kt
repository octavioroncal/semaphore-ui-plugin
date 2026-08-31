package com.oroncal.semaphoreui.release

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.vfs.VfsUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class PomFileServiceTest : BasePlatformTestCase() {
    fun testLoadsPomWithExplicitProjectVersion() {
        val pomFile = myFixture.addFileToProject(
            "module-a/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <artifactId>module-a-artifact</artifactId>
              <name>Module A</name>
              <version>1.2.3-SNAPSHOT</version>
            </project>
            """.trimIndent(),
        ).virtualFile

        val target = PomFileService.loadReleaseTarget(
            project,
            ReleaseContext("module-a", pomFile.parent, pomFile),
        )

        assertEquals("module-a-artifact", target.artifactId)
        assertEquals("Module A", target.displayName)
        assertEquals("Module-A", target.tagModuleName)
        assertEquals("1.2.3-SNAPSHOT", target.currentVersion)
        assertEquals(SemanticVersion(1, 2, 3, true), target.parsedVersion)
    }

    fun testFallsBackToConcreteModuleDirectoryForTagName() {
        val pomFile = myFixture.addFileToProject(
            "child-module/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>demo</groupId>
                <artifactId>parent-project</artifactId>
                <version>1.0.0</version>
              </parent>
              <artifactId>child-module-artifact</artifactId>
              <version>1.2.3</version>
            </project>
            """.trimIndent(),
        ).virtualFile

        val target = PomFileService.loadReleaseTarget(
            project,
            ReleaseContext("parent-project", pomFile.parent, pomFile),
        )

        assertEquals("child-module", target.displayName)
        assertEquals("child-module", target.tagModuleName)
    }

    fun testUsesModulePomNameInsteadOfParentProjectNameForTagName() {
        val pomFile = myFixture.addFileToProject(
            "child-module/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>demo</groupId>
                <artifactId>parent-project</artifactId>
                <version>1.0.0</version>
              </parent>
              <artifactId>child-module-artifact</artifactId>
              <name>Child Module UI</name>
              <version>1.2.3</version>
            </project>
            """.trimIndent(),
        ).virtualFile

        val target = PomFileService.loadReleaseTarget(
            project,
            ReleaseContext("parent-project", pomFile.parent, pomFile),
        )

        assertEquals("Child Module UI", target.displayName)
        assertEquals("Child-Module-UI", target.tagModuleName)
    }

    fun testRejectsPomWithoutDirectProjectVersion() {
        val pomFile = myFixture.addFileToProject(
            "module-b/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>demo</groupId>
                <artifactId>parent</artifactId>
                <version>9.9.9</version>
              </parent>
              <artifactId>module-b-artifact</artifactId>
            </project>
            """.trimIndent(),
        ).virtualFile

        var message: String? = null
        try {
            PomFileService.loadReleaseTarget(
                project,
                ReleaseContext("module-b", pomFile.parent, pomFile),
            )
            fail("Expected ReleaseException to be thrown")
        } catch (exception: ReleaseException) {
            message = exception.message
        }

        assertTrue(message!!.contains("project version"))
    }

    fun testUpdatesOnlyDirectProjectVersion() {
        val pomFile = myFixture.addFileToProject(
            "module-c/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>demo</groupId>
                <artifactId>parent</artifactId>
                <version>9.9.9</version>
              </parent>
              <artifactId>module-c-artifact</artifactId>
              <version>1.2.3</version>
            </project>
            """.trimIndent(),
        ).virtualFile

        PomFileService.updateVersion(project, pomFile, "1.2.4")

        val updatedText = VfsUtil.loadText(pomFile)
        assertTrue(updatedText.contains("<version>9.9.9</version>"))
        assertTrue(updatedText.contains("<version>1.2.4</version>"))
    }
}
