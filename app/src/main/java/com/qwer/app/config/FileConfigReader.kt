package com.qwer.app.config

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a config from a file the user picked (SAF) or shared into the app.
 *
 * Safety rules:
 *  - The declared size is checked BEFORE reading, so a 2 GB "config" cannot be
 *    pulled into memory.
 *  - Only text is accepted; anything binary is rejected by the validator that
 *    follows (`ConfigImporter.validate` checks for control characters).
 *  - The file is read as UTF-8 with a hard cap, never streamed unbounded.
 *  - No content is written to logs.
 */
object FileConfigReader {

    /** Same cap as the paste path; a WG/AWG config is a few KB. */
    private const val MAX_BYTES = 64 * 1024

    sealed interface Result {
        data class Ok(val text: String, val displayName: String) : Result
        data class Error(val message: String) : Result
    }

    suspend fun read(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(context, uri)

        // Size gate first — cheap and prevents reading something huge.
        val declaredSize = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (declaredSize != null && declaredSize > MAX_BYTES) {
            return@withContext Result.Error("Файл слишком большой (${declaredSize / 1024} КБ)")
        }

        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                // Read at most MAX_BYTES+1 to detect overflow even when size is unknown.
                val buffer = ByteArray(MAX_BYTES + 1)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                buffer.copyOf(total)
            }
        }.getOrElse { e ->
            return@withContext Result.Error("Не удалось прочитать файл")
        } ?: return@withContext Result.Error("Не удалось открыть файл")

        if (bytes.size > MAX_BYTES) {
            return@withContext Result.Error("Файл слишком большой")
        }
        if (bytes.isEmpty()) {
            return@withContext Result.Error("Файл пустой")
        }

        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrElse {
            return@withContext Result.Error("Файл не является текстом в кодировке UTF-8")
        }
        Result.Ok(text, name)
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "конфигурация.conf"
    }
}
