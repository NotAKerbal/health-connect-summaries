package dev.hcsummaries.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Exports live in the app's private files/exports directory (excluded from backup). They are
 * readable by another app only through a FileProvider URI granted for a single share.
 */
class ExportStore(private val context: Context) {
    private val dir: File = File(context.filesDir, "exports")
    private val nameFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun save(json: String, generatedAt: Instant): File {
        dir.mkdirs()
        val target = File(dir, "health-connect-export-${nameFormat.format(generatedAt)}.json")
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("could not finalize export file")
        }
        return target
    }

    fun list(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.name }

    fun read(file: File): String = owned(file).readText(Charsets.UTF_8)

    fun delete(file: File): Boolean = owned(file).delete()

    fun deleteAll(): Int {
        // Also removes any leftover temporary files.
        return (dir.listFiles() ?: emptyArray()).count { it.isFile && it.delete() }
    }

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.exports", owned(file))

    private fun owned(file: File): File {
        require(file.canonicalFile.parentFile == dir.canonicalFile) { "not an export file" }
        return file
    }
}
