package com.novelreader.epub

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/** Helpers used to import PDFs and create a library thumbnail. */
object PdfMetadataParser {
    fun title(file: File): String = file.nameWithoutExtension
        .replace(Regex("[_-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "Documento PDF" }

    fun renderCover(pdf: File, destination: File): String? = runCatching {
        open(pdf).use { renderer ->
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val scale = 600f / page.width.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(0xFFFFFFFF.toInt())
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                destination.parentFile?.mkdirs()
                FileOutputStream(destination).use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output) }
                bitmap.recycle()
            }
        }
        destination.absolutePath
    }.getOrNull()

    fun open(file: File): PdfRenderer = PdfRenderer(
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    )
}
