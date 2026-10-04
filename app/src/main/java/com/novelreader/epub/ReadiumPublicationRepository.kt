package com.novelreader.epub

import android.content.Context
import java.io.File
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class ReadiumPublicationRepository(private val context: Context) {
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = context,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null
        )
    )

    suspend fun open(file: File): Result<Publication> = runCatching {
        val asset = assetRetriever.retrieve(file).getOrNull() ?: error("No se pudo abrir el EPUB")
        opener.open(asset, allowUserInteraction = false).getOrNull() ?: error("EPUB no compatible")
    }
}
