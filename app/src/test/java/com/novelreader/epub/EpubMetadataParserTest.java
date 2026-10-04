package com.novelreader.epub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class EpubMetadataParserTest {
    @Test
    public void readsOpfMetadataAndCover() throws Exception {
        File epub = File.createTempFile("novelreader", ".epub");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(epub))) {
            zip.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zip.write("<container><rootfiles><rootfile full-path=\"OPS/package.opf\"/></rootfiles></container>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("OPS/package.opf"));
            zip.write(("<package xmlns=\"http://www.idpf.org/2007/opf\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Mi novela</dc:title><dc:creator>Autora</dc:creator><meta name=\"cover\" content=\"cover-image\"/></metadata><manifest><item id=\"cover-image\" href=\"images/cover.jpg\" media-type=\"image/jpeg\"/></manifest></package>").getBytes());
            zip.closeEntry();
        }
        try {
            EpubMetadata metadata = EpubMetadataParser.INSTANCE.read(epub);
            assertEquals("Mi novela", metadata.getTitle());
            assertEquals("Autora", metadata.getAuthor());
            assertTrue(metadata.getCoverEntry().endsWith("images/cover.jpg"));
        } finally {
            assertTrue(epub.delete());
        }
    }
}
