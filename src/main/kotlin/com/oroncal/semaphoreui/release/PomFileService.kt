package com.oroncal.semaphoreui.release

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VfsUtil
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

object PomFileService {
    fun loadReleaseTarget(project: Project, context: ReleaseContext): ReleaseTarget {
        val parsedPom = parsePom(readPomText(context.pomFile), context.pomFile.name)
        val currentVersion = parsedPom.version
            ?: throw ReleaseException("pom.xml must declare the project version in <project><version>...</version>.")
        if (currentVersion.isBlank()) {
            throw ReleaseException("The pom.xml version is empty.")
        }

        val parsedVersion = SemanticVersion.parse(currentVersion)
            ?: throw ReleaseException("Version '$currentVersion' is not supported. Use a format like 1.2.3 or 1.2.3-SNAPSHOT.")

        return ReleaseTarget(
            context = context,
            artifactId = parsedPom.artifactId,
            currentVersion = currentVersion,
            parsedVersion = parsedVersion,
        )
    }

    fun updateVersion(project: Project, pomFile: VirtualFile, newVersion: String) {
        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val currentText = FileDocumentManager.getInstance().getDocument(pomFile)?.text ?: readPomText(pomFile)
                val versionRange = findDirectChildTagValueRange(currentText, "project", "version")
                    ?: throw ReleaseException("pom.xml no longer contains a direct project <version> tag.")
                val updatedText = currentText.replaceRange(versionRange.startOffset, versionRange.endOffset, newVersion)
                val status = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(pomFile)
                if (status.hasReadonlyFiles()) {
                    throw ReleaseException("Cannot write to ${pomFile.path}.")
                }

                WriteCommandAction.runWriteCommandAction(project, Runnable {
                    val document = FileDocumentManager.getInstance().getDocument(pomFile)
                    if (document != null) {
                        document.setText(updatedText)
                        FileDocumentManager.getInstance().saveDocument(document)
                    } else {
                        VfsUtil.saveText(pomFile, updatedText)
                    }
                })
            } catch (throwable: Throwable) {
                failure = throwable
            }
        }

        failure?.let { throw it }
    }

    private fun readPomText(pomFile: VirtualFile): String = VfsUtil.loadText(pomFile)

    private fun parsePom(xmlText: String, fileName: String): ParsedPom {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            setExpandEntityReferences(false)
            setSecureFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setSecureFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setSecureFeature("http://xml.org/sax/features/external-general-entities", false)
            setSecureFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setSecureFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        }

        val document = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlText.toByteArray(StandardCharsets.UTF_8)))
        } catch (throwable: Throwable) {
            throw ReleaseException("Could not interpret $fileName as a valid pom.xml.", throwable)
        }

        val rootElement = document.documentElement as? Element
            ?: throw ReleaseException("Could not read the <project> tag from $fileName.")
        if (rootElement.localNameOrTagName() != "project") {
            throw ReleaseException("Could not read the <project> tag from $fileName.")
        }

        var artifactId: String? = null
        var version: String? = null
        val childNodes = rootElement.childNodes
        for (index in 0 until childNodes.length) {
            val child = childNodes.item(index) as? Element ?: continue
            when (child.localNameOrTagName()) {
                "artifactId" -> if (artifactId == null) {
                    artifactId = child.textContent.trim().ifBlank { null }
                }
                "version" -> if (version == null) {
                    version = child.textContent.trim()
                }
            }
        }

        return ParsedPom(
            artifactId = artifactId,
            version = version,
        )
    }

    private fun findDirectChildTagValueRange(xmlText: String, rootLocalName: String, childLocalName: String): TextRange? {
        var depth = 0
        var rootDepth = -1
        var valueStartOffset: Int? = null

        for (match in XML_TOKEN_PATTERN.findAll(xmlText)) {
            val token = match.value
            if (token.startsWith("<?") || token.startsWith("<!")) {
                continue
            }

            val name = TAG_NAME_PATTERN.find(token)?.groupValues?.get(1) ?: continue
            val localName = name.substringAfter(':')
            val isEndTag = token.startsWith("</")
            val isSelfClosing = !isEndTag && token.endsWith("/>")

            if (!isEndTag) {
                depth += 1
                if (rootDepth == -1 && depth == 1 && localName == rootLocalName) {
                    rootDepth = depth
                } else if (rootDepth != -1 && depth == rootDepth + 1 && localName == childLocalName) {
                    if (isSelfClosing) {
                        return null
                    }
                    valueStartOffset = match.range.last + 1
                }

                if (isSelfClosing) {
                    if (rootDepth == depth && localName == rootLocalName) {
                        rootDepth = -1
                    }
                    depth -= 1
                }
                continue
            }

            if (rootDepth != -1 && depth == rootDepth + 1 && localName == childLocalName && valueStartOffset != null) {
                return TextRange(
                    startOffset = valueStartOffset,
                    endOffset = match.range.first,
                )
            }
            if (rootDepth != -1 && depth == rootDepth && localName == rootLocalName) {
                rootDepth = -1
            }
            depth -= 1
        }

        return null
    }

    private fun Element.localNameOrTagName(): String = localName ?: tagName.substringAfter(':')

    private fun DocumentBuilderFactory.setSecureFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private data class ParsedPom(
        val artifactId: String?,
        val version: String?,
    )

    private data class TextRange(
        val startOffset: Int,
        val endOffset: Int,
    )

    private val XML_TOKEN_PATTERN = Regex(
        pattern = """<!--.*?-->|<!\[CDATA\[.*?]]>|<\?.*?\?>|<![^>]*>|</?\s*[^>]+?>""",
        options = setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val TAG_NAME_PATTERN = Regex("""^</?\s*([^\s/>]+)""")
}
