package com.qwer.app.backup

import com.qwer.app.core.ProfileRepository
import com.qwer.app.core.Protocol
import com.qwer.app.core.VpnProfile
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event

/**
 * Orchestrates export and import.
 *
 * ATOMICITY
 * ---------
 * Restore never touches existing data until the new set has been fully decrypted, parsed
 * and validated. The commit is then a single [ProfileRepository.replaceAll] call, so a
 * failure anywhere earlier leaves the device exactly as it was.
 *
 * CONFLICT POLICY
 * ---------------
 * Default is MERGE, keyed by profile id:
 *  - id exists locally  → the backup copy wins (the user explicitly chose to restore it)
 *  - id is new          → added
 *  - local-only profile → kept, never silently deleted
 * "Replace everything" is a separate, explicit choice the caller must make, because
 * silently discarding profiles is unrecoverable.
 */
class BackupManager(private val repository: ProfileRepository) {

    sealed interface ExportResult {
        data class Ok(val bytes: ByteArray, val profileCount: Int) : ExportResult
        data class Failed(val message: String) : ExportResult
    }

    sealed interface ImportResult {
        data class Ok(
            val added: Int,
            val replaced: Int,
            val skipped: Int,
            val activeProfileId: String?,
        ) : ImportResult
        data class Failed(val message: String) : ImportResult
    }

    /** @param includeConfigs false only makes sense for diagnostics; default is true. */
    fun export(password: CharArray, includeConfigs: Boolean = true): ExportResult {
        val profiles = repository.all()
        if (profiles.isEmpty()) {
            return ExportResult.Failed("Нет профилей для резервного копирования")
        }

        val entries = profiles.map { p ->
            BackupPayload.BackupProfile(
                profile = p,
                rawConfig = if (includeConfigs) repository.configFor(p.id) else null,
            )
        }

        val payload = BackupPayload(
            schema = BackupPayload.SCHEMA,
            profiles = entries,
            activeProfileId = repository.activeProfile()?.id,
        )

        return runCatching {
            val plaintext = payload.toJson().toByteArray(Charsets.UTF_8)
            val encrypted = BackupCodec.encrypt(plaintext, password)
            ExportResult.Ok(encrypted, profiles.size)
        }.getOrElse {
            Diagnostics.warn(Event.BACKUP, it, "stage" to "export")
            ExportResult.Failed("Не удалось создать резервную копию")
        }
    }

    /**
     * @param mode MERGE keeps local-only profiles; REPLACE drops them. The UI must ask
     *             before using REPLACE.
     */
    fun import(
        fileBytes: ByteArray,
        password: CharArray,
        mode: ImportMode = ImportMode.MERGE,
    ): ImportResult {
        if (fileBytes.size > BackupCodec.MAX_FILE_BYTES) {
            return ImportResult.Failed("Файл слишком большой для резервной копии")
        }

        val plaintext = when (val r = BackupCodec.decrypt(fileBytes, password)) {
            is BackupCodec.Result.Failed -> return ImportResult.Failed(
                when (r.reason) {
                    BackupCodec.Reason.NOT_A_BACKUP -> "Файл не является резервной копией qwer"
                    BackupCodec.Reason.UNSUPPORTED_VERSION ->
                        "Копия создана более новой версией приложения"
                    BackupCodec.Reason.BAD_HEADER -> "Повреждён заголовок резервной копии"
                    BackupCodec.Reason.TOO_LARGE -> "Файл слишком большой"
                    BackupCodec.Reason.BAD_PASSWORD_OR_CORRUPT ->
                        "Неверный пароль или файл повреждён"
                    BackupCodec.Reason.INTERNAL_ERROR -> "Не удалось расшифровать файл"
                }
            )
            is BackupCodec.Result.Ok -> r.plaintext
        }

        val payload = when (val p = BackupPayload.parse(String(plaintext, Charsets.UTF_8))) {
            is BackupParseResult.Failed -> return ImportResult.Failed(p.reason)
            is BackupParseResult.Ok -> p.payload
        }

        // Everything below is decided in memory first; the device is still untouched.
        val existing = repository.all().associateBy { it.id }
        val merged = LinkedHashMap<String, VpnProfile>()
        var added = 0
        var replaced = 0
        var skipped = 0

        if (mode == ImportMode.MERGE) {
            existing.values.forEach { merged[it.id] = it }
        }

        for (entry in payload.profiles) {
            if (!entry.isUsable) {
                // A profile without a config cannot connect; keep it out of the restore
                // rather than producing a dead entry the user must delete manually.
                skipped++
                continue
            }
            val id = entry.profile.id
            if (existing.containsKey(id)) replaced++ else added++
            merged[id] = entry.profile
        }

        if (merged.isEmpty()) {
            return ImportResult.Failed("В резервной копии нет пригодных профилей")
        }

        // Single atomic write: profiles plus their configs.
        runCatching {
            repository.replaceAll(
                profiles = merged.values.toList(),
                configs = payload.profiles
                    .filter { it.isUsable }
                    .associate { it.profile.id to it.rawConfig!! },
            )
        }.getOrElse {
            Diagnostics.warn(Event.BACKUP, it, "stage" to "commit")
            return ImportResult.Failed("Не удалось сохранить восстановленные профили")
        }

        // Restore the active profile only if it actually survived the merge.
        val activeId = payload.activeProfileId?.takeIf { merged.containsKey(it) }
        if (activeId != null) repository.setActive(activeId)

        // Only one failover target may exist; if the backup carried one, re-assert it.
        payload.profiles.firstOrNull { it.profile.isFailoverTarget && merged.containsKey(it.profile.id) }
            ?.let { repository.setFailoverTarget(it.profile.id) }

        Diagnostics.event(
            Event.BACKUP,
            "stage" to "import_ok",
            "added" to added,
            "replaced" to replaced,
            "skipped" to skipped,
        )
        return ImportResult.Ok(added, replaced, skipped, activeId)
    }

    /** Number of profiles that would change, for a "confirm before restore" dialog. */
    fun preview(fileBytes: ByteArray, password: CharArray): PreviewResult {
        val plaintext = when (val r = BackupCodec.decrypt(fileBytes, password)) {
            is BackupCodec.Result.Failed -> return PreviewResult.Failed(
                if (r.reason == BackupCodec.Reason.BAD_PASSWORD_OR_CORRUPT) {
                    "Неверный пароль или файл повреждён"
                } else {
                    "Файл не является корректной резервной копией"
                }
            )
            is BackupCodec.Result.Ok -> r.plaintext
        }
        val payload = when (val p = BackupPayload.parse(String(plaintext, Charsets.UTF_8))) {
            is BackupParseResult.Failed -> return PreviewResult.Failed(p.reason)
            is BackupParseResult.Ok -> p.payload
        }
        val existingIds = repository.all().map { it.id }.toSet()
        return PreviewResult.Ok(
            total = payload.profiles.size,
            newProfiles = payload.profiles.count { it.profile.id !in existingIds },
            conflicts = payload.profiles.count { it.profile.id in existingIds },
        )
    }

    sealed interface PreviewResult {
        data class Ok(val total: Int, val newProfiles: Int, val conflicts: Int) : PreviewResult
        data class Failed(val message: String) : PreviewResult
    }

    enum class ImportMode { MERGE, REPLACE }
}
