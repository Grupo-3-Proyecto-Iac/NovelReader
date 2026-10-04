package com.novelreader.epub

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class EpubMetadata(val title: String, val author: String, val coverEntry: String?)

object EpubMetadataParser {
    fun read(file: File): EpubMetadata {
        ZipFile(file).use { zip ->
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val container = zip.getInputStream(zip.getEntry("META-INF/container.xml")).use { factory.newDocumentBuilder().parse(it) }
            val rootfile = container.getElementsByTagNameNS("*", "rootfile").item(0)
            val opfPath = rootfile?.attributes?.getNamedItem("full-path")?.nodeValue ?: error("EPUB sin OPF")
            val opf = zip.getInputStream(zip.getEntry(opfPath)).use { factory.newDocumentBuilder().parse(it) }
            val title = text(opf, "title") ?: file.nameWithoutExtension
            val author = text(opf, "creator").orEmpty()
            val coverId = opf.getElementsByTagNameNS("*", "meta").let { nodes ->
                (0 until nodes.length).firstNotNullOfOrNull { i ->
                    val n = nodes.item(i); if (n.attributes?.getNamedItem("name")?.nodeValue == "cover") n.attributes.getNamedItem("content")?.nodeValue else null
                }
            }
            val manifest = opf.getElementsByTagNameNS("*", "item")
            var coverHref: String? = null
            for (i in 0 until manifest.length) {
                val item = manifest.item(i)
                val id = item.attributes?.getNamedItem("id")?.nodeValue
                val properties = item.attributes?.getNamedItem("properties")?.nodeValue.orEmpty()
                if (id == coverId || properties.split(" ").contains("cover-image")) { coverHref = item.attributes?.getNamedItem("href")?.nodeValue; break }
            }
            return EpubMetadata(title.trim(), author.trim(), coverHref?.let { resolve(opfPath, it) })
        }
    }

    private fun text(document: org.w3c.dom.Document, localName: String): String? = document.getElementsByTagNameNS("*", localName).item(0)?.textContent

    private fun resolve(opfPath: String, href: String): String {
        val base = opfPath.substringBeforeLast('/', "")
        return if (base.isEmpty()) href else "$base/$href"
    }
}
