package com.ai.ui.report

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Coverage for unpackInto — the helper the bulk-export master zip
 * uses to flatten the per-format sub-zips (Zipped HTML, traces) into
 * directories instead of nesting archives.
 */
class BulkExportUnpackTest {

    private fun buildInnerZip(entries: List<Pair<String, String>>, includeDir: Boolean = false): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            if (includeDir) {
                val dirEntry = ZipEntry("dir/")
                zos.putNextEntry(dirEntry)
                zos.closeEntry()
            }
            entries.forEach { (path, content) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun readEntries(zipBytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) { zis.closeEntry(); continue }
                out[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
            }
        }
        return out
    }

    @Test fun unpackInto_prepends_prefix_to_every_entry() {
        val inner = buildInnerZip(
            listOf(
                "index.html" to "<html>root</html>",
                "Reports/01_x.html" to "<p>x</p>",
                "JSON/Report/01/index.html" to "{}"
            )
        )
        val outer = ByteArrayOutputStream()
        ZipOutputStream(outer).use { unpackInto(it, inner, "html/") }

        val entries = readEntries(outer.toByteArray())
        assertThat(entries.keys).containsExactly(
            "html/index.html",
            "html/Reports/01_x.html",
            "html/JSON/Report/01/index.html"
        )
        assertThat(entries["html/index.html"]).isEqualTo("<html>root</html>")
        assertThat(entries["html/Reports/01_x.html"]).isEqualTo("<p>x</p>")
    }

    @Test fun unpackInto_skips_directory_entries() {
        // Some zip writers emit explicit "dir/" entries; the master
        // bundle reconstructs directory structure implicitly from
        // file paths, so we shouldn't double-emit them.
        val inner = buildInnerZip(
            entries = listOf("dir/file.html" to "x"),
            includeDir = true
        )
        val outer = ByteArrayOutputStream()
        ZipOutputStream(outer).use { unpackInto(it, inner, "outer/") }

        val entries = readEntries(outer.toByteArray())
        // Only the file entry survives; the standalone directory
        // entry was filtered.
        assertThat(entries.keys).containsExactly("outer/dir/file.html")
    }

    @Test fun unpackInto_empty_zip_emits_nothing_under_prefix() {
        val inner = buildInnerZip(emptyList())
        val outer = ByteArrayOutputStream()
        ZipOutputStream(outer).use { zos ->
            // Add a sentinel entry so the outer zip is non-empty
            // (otherwise ZipInputStream returns no entries on read).
            zos.putNextEntry(ZipEntry("sentinel"))
            zos.write("x".toByteArray())
            zos.closeEntry()
            unpackInto(zos, inner, "html/")
        }

        val entries = readEntries(outer.toByteArray())
        assertThat(entries.keys).containsExactly("sentinel")
    }

    @Test fun unpackInto_works_with_empty_prefix() {
        val inner = buildInnerZip(listOf("a.txt" to "A"))
        val outer = ByteArrayOutputStream()
        ZipOutputStream(outer).use { unpackInto(it, inner, "") }

        val entries = readEntries(outer.toByteArray())
        assertThat(entries.keys).containsExactly("a.txt")
        assertThat(entries["a.txt"]).isEqualTo("A")
    }

    @Test fun unpackInto_preserves_file_contents_byte_for_byte() {
        val payload = (0..255).map { it.toByte() }.toByteArray()
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("blob"))
            zos.write(payload)
            zos.closeEntry()
        }

        val outer = ByteArrayOutputStream()
        ZipOutputStream(outer).use { unpackInto(it, baos.toByteArray(), "p/") }

        // Read back the byte payload from the outer zip and compare.
        ZipInputStream(ByteArrayInputStream(outer.toByteArray())).use { zis ->
            val entry = zis.nextEntry!!
            assertThat(entry.name).isEqualTo("p/blob")
            assertThat(zis.readBytes()).isEqualTo(payload)
        }
    }
}
