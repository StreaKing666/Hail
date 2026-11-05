package com.aistra.hail.app

import com.aistra.hail.enhanced.domain.BackupEntryMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class FullBackupFile(val path: String, val bytes: ByteArray)

internal data class FullBackupSelection(
    /** Tags and tracked-app assignments are always included to preserve a valid Hail state. */
    val includeAdditionalFiles: Boolean = true,
    val includePreferences: Boolean = true,
    val preferenceKeys: Set<String>? = null
)

internal enum class FullBackupRestoreMode { MERGE, REPLACE }

internal data class FullBackupPayload(
    val files: List<FullBackupFile>,
    val preferences: Map<String, Any>,
    val tagBackup: TagBackup,
    val sourceApplicationId: String,
    val sourceVersionName: String,
    val schemaVersion: Int,
    val selection: FullBackupSelection = FullBackupSelection()
)

internal data class FullBackupPreview(
    val schemaVersion: Int,
    val sourceApplicationId: String,
    val sourceVersionName: String,
    val tagCount: Int,
    val trackedAppCount: Int,
    val fileCount: Int,
    val preferenceCount: Int,
    val fileNames: List<String>
)

internal object FullBackupArchive {
    private const val FORMAT = "com.aistra.hail.full-backup"
    private const val SCHEMA_VERSION = 2
    private const val LEGACY_SCHEMA_VERSION = 1
    private const val MAX_ENTRIES = 256
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_FILE_BYTES = 40 * 1024 * 1024
    private const val MAX_TOTAL_ARCHIVE_BYTES = 48 * 1024 * 1024
    private const val MAX_PREFERENCES = 512
    private const val MAX_KEY_LENGTH = 256
    private const val MAX_STRING_LENGTH = 8192
    private const val MAX_SET_ITEMS = 256
    private val requiredStateFiles = setOf("v1/tags.json", "v1/apps.json")

    // These values are tied to Android permissions or device-local cryptographic material.
    // They must be configured again after restore instead of being copied from a backup.
    private val excludedPreferenceKeys = setOf(
        "working_mode",
        "biometric_login",
        "auto_freeze_after_lock"
    )
    private val sensitiveKeyFragments = listOf("password", "token", "secret", "credential")

    fun encode(
        files: List<FullBackupFile>,
        preferences: Map<String, *>,
        applicationId: String,
        versionName: String,
        versionCode: Int,
        selection: FullBackupSelection = FullBackupSelection()
    ): ByteArray {
        val allFiles = validateFiles(if (selection.includeAdditionalFiles) files else files.filter { it.path in requiredStateFiles })
        val selectedKeys = selection.preferenceKeys?.filterTo(linkedSetOf(), ::isAllowedPreferenceKeyInternal)
        require(selectedKeys == null || selectedKeys.size <= MAX_PREFERENCES) { "Too many selected preference keys" }
        val normalizedFiles = if (selection.includeAdditionalFiles) allFiles else {
            allFiles.filter { it.path in requiredStateFiles }
        }
        val selectedPreferences = if (selection.includePreferences) {
            preferences.filterKeys { key ->
                selectedKeys == null || key in selectedKeys
            }
        } else emptyMap<String, Any>()
        val safePreferences = normalizePreferences(selectedPreferences)
        val preferencesBytes = encodePreferences(safePreferences)
            .toString().toByteArray(StandardCharsets.UTF_8)

        val entriesForHash = linkedMapOf<String, ByteArray>()
        entriesForHash["preferences.json"] = preferencesBytes
        normalizedFiles.sortedBy { it.path }.forEach { file ->
            entriesForHash["files/" + file.path] = file.bytes
        }
        val manifestEntries = JSONArray()
        entriesForHash.forEach { (name, bytes) ->
            manifestEntries.put(
                JSONObject()
                    .put("name", name)
                    .put("size", bytes.size)
                    .put("sha256", sha256(bytes))
            )
        }

        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("applicationId", applicationId)
            .put("versionName", versionName)
            .put("versionCode", versionCode)
            .put("createdAtEpochMs", System.currentTimeMillis())
            .put("fileCount", normalizedFiles.size)
            .put("preferenceCount", safePreferences.size)
            .put("selection", JSONObject().apply {
                put("includeAdditionalFiles", selection.includeAdditionalFiles)
                put("includePreferences", selection.includePreferences)
                selectedKeys?.let { put("preferenceKeys", JSONArray(it.sorted())) }
            })
            .put("entries", manifestEntries)

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.writeEntry("manifest.json", manifest.toString().toByteArray(StandardCharsets.UTF_8))
            zip.writeEntry("preferences.json", preferencesBytes)
            normalizedFiles.sortedBy { it.path }.forEach { file ->
                zip.writeEntry("files/" + file.path, file.bytes)
            }
        }
        require(output.size() <= MAX_TOTAL_ARCHIVE_BYTES) { "Backup archive is too large" }
        return output.toByteArray()
    }

    fun decode(input: InputStream): FullBackupPayload {
        val files = linkedMapOf<String, ByteArray>()
        val archiveEntryBytes = linkedMapOf<String, ByteArray>()
        var manifestText: String? = null
        var preferencesText: String? = null
        var totalBytes = 0
        var entryCount = 0

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "Backup contains too many files" }

                if (!entry.isDirectory) {
                    val bytes = zip.readBounded(MAX_FILE_BYTES)
                    totalBytes += bytes.size
                    require(totalBytes <= MAX_TOTAL_ARCHIVE_BYTES) { "Backup is too large" }

                    when {
                        entry.name == "manifest.json" -> {
                            require(manifestText == null) { "Backup contains duplicate manifest.json" }
                            manifestText = String(bytes, StandardCharsets.UTF_8)
                        }
                        entry.name == "preferences.json" -> {
                            require(preferencesText == null) { "Backup contains duplicate preferences.json" }
                            preferencesText = String(bytes, StandardCharsets.UTF_8)
                            archiveEntryBytes[entry.name] = bytes
                        }
                        entry.name.startsWith("files/") -> {
                            val path = entry.name.removePrefix("files/")
                            validateRelativePath(path)
                            require(!files.containsKey(path)) { "Backup contains duplicate file: $path" }
                            files[path] = bytes
                            archiveEntryBytes[entry.name] = bytes
                        }
                        else -> throw IllegalArgumentException("Unsupported backup entry: " + entry.name)
                    }
                }
                zip.closeEntry()
            }
        }

        val manifest = JSONObject(manifestText ?: throw IllegalArgumentException("manifest.json is missing"))
        val schemaVersion = validateManifest(manifest)
        if (schemaVersion >= SCHEMA_VERSION) validateHashes(manifest, archiveEntryBytes)

        val preferenceJson = JSONObject(
            preferencesText ?: throw IllegalArgumentException("preferences.json is missing")
        )
        val decodedPreferences = decodePreferences(preferenceJson, schemaVersion)
        val normalizedFiles = validateFiles(files.map { FullBackupFile(it.key, it.value) })
        val tagBackup = decodeTagBackup(normalizedFiles)
        require(manifest.optInt("fileCount", -1) == normalizedFiles.size) {
            "Backup file count does not match its manifest"
        }
        require(manifest.optInt("preferenceCount", -1) == decodedPreferences.size) {
            "Backup preference count does not match its manifest"
        }

        val declaredSelection = manifest.optJSONObject("selection")
        val selectedKeysJson = declaredSelection?.optJSONArray("preferenceKeys")
        require(declaredSelection?.has("preferenceKeys") != true || selectedKeysJson != null) {
            "Invalid selected preference key list"
        }
        val selectedKeys = selectedKeysJson?.let { keys ->
            require(keys.length() <= MAX_PREFERENCES) { "Too many selected preference keys" }
            buildSet {
                for (index in 0 until keys.length()) {
                    val key = keys.getString(index)
                    require(isAllowedPreferenceKeyInternal(key)) { "Invalid selected preference key" }
                    add(key)
                }
            }
        }
        // Earlier archives did not record exclusions. An absent category must not be
        // interpreted as an instruction to erase that category during replacement.
        val selection = FullBackupSelection(
            includeAdditionalFiles = declaredSelection?.getBoolean("includeAdditionalFiles")
                ?: normalizedFiles.any { it.path !in requiredStateFiles },
            includePreferences = declaredSelection?.getBoolean("includePreferences")
                ?: decodedPreferences.isNotEmpty(),
            preferenceKeys = selectedKeys ?: if (declaredSelection == null) decodedPreferences.keys else null
        )
        require(selection.includeAdditionalFiles || normalizedFiles.all { it.path in requiredStateFiles }) {
            "Backup contains excluded app files"
        }
        require(selection.includePreferences || decodedPreferences.isEmpty()) {
            "Backup contains excluded preferences"
        }
        require(selectedKeys == null || decodedPreferences.keys.all { it in selectedKeys }) {
            "Backup contains unselected preferences"
        }

        return FullBackupPayload(
            files = normalizedFiles,
            preferences = decodedPreferences,
            tagBackup = tagBackup,
            sourceApplicationId = manifest.optString("applicationId", "unknown"),
            sourceVersionName = manifest.optString("versionName", "unknown"),
            schemaVersion = schemaVersion,
            selection = selection
        )
    }

    fun preview(input: InputStream): FullBackupPreview {
        val payload = decode(input)
        return FullBackupPreview(
            schemaVersion = payload.schemaVersion,
            sourceApplicationId = payload.sourceApplicationId,
            sourceVersionName = payload.sourceVersionName,
            tagCount = payload.tagBackup.tags.size,
            trackedAppCount = payload.tagBackup.apps.size,
            fileCount = payload.files.size,
            preferenceCount = payload.preferences.size,
            fileNames = payload.files.map { it.path }.sorted()
        )
    }

    internal fun isAllowedPreferenceKey(key: String): Boolean = isAllowedPreferenceKeyInternal(key)

    private fun validateManifest(manifest: JSONObject): Int {
        require(manifest.optString("format") == FORMAT) { "This is not a Hail full backup" }
        val version = manifest.optInt("schemaVersion", -1)
        require(version == LEGACY_SCHEMA_VERSION || version == SCHEMA_VERSION) {
            "Unsupported Hail full-backup version"
        }
        require(manifest.optLong("createdAtEpochMs", 0L) > 0L) {
            "Backup manifest has no valid creation time"
        }
        return version
    }

    private fun validateHashes(manifest: JSONObject, entries: Map<String, ByteArray>) {
        val metadata = manifest.optJSONArray("entries")
            ?: throw IllegalArgumentException("Backup entry hashes are missing")
        require(metadata.length() == entries.size) { "Backup entry hash count does not match the archive" }
        val seen = HashSet<String>()
        for (index in 0 until metadata.length()) {
            val item = metadata.getJSONObject(index)
            val name = item.getString("name")
            val size = item.getLong("size")
            val hash = item.getString("sha256")
            BackupEntryMetadata.validate(name, size, hash)
            require(seen.add(name)) { "Duplicate hash entry: " + name }
            val bytes = entries[name] ?: throw IllegalArgumentException("Missing archive entry: " + name)
            require(bytes.size.toLong() == size) { "Backup entry size mismatch: " + name }
            require(sha256(bytes) == hash) { "Backup entry checksum mismatch: " + name }
        }
        require(seen == entries.keys) { "Backup contains entries not listed in its manifest" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun validateFiles(files: List<FullBackupFile>): List<FullBackupFile> {
        require(files.isNotEmpty()) { "Backup contains no app files" }
        require(files.size <= MAX_ENTRIES - 2) { "Backup contains too many app files" }
        val paths = HashSet<String>()
        var totalBytes = 0
        files.forEach { file ->
            validateRelativePath(file.path)
            require(paths.add(file.path)) { "Duplicate app file: " + file.path }
            require(file.bytes.size <= MAX_FILE_BYTES) { "App file is too large: " + file.path }
            totalBytes += file.bytes.size
            require(totalBytes <= MAX_TOTAL_FILE_BYTES) { "App data is too large to back up" }
        }
        require(paths.containsAll(requiredStateFiles)) {
            "Hail app data is incomplete: tags.json or apps.json is missing"
        }
        return files
    }

    private fun validateRelativePath(path: String) {
        require(path.isNotBlank() && path.length <= 512) { "Invalid backup file path" }
        require(!path.startsWith("/") && '\\' !in path) { "Unsafe backup file path" }
        val segments = path.split('/')
        require(segments.all { it.isNotBlank() && it != "." && it != ".." }) {
            "Unsafe backup file path"
        }
    }

    private fun decodeTagBackup(files: List<FullBackupFile>): TagBackup {
        val byPath = files.associateBy { it.path }
        val tags = byPath["v1/tags.json"]?.bytes
            ?: throw IllegalArgumentException("Backup is missing files/v1/tags.json")
        val apps = byPath["v1/apps.json"]?.bytes
            ?: throw IllegalArgumentException("Backup is missing files/v1/apps.json")

        val nested = ByteArrayOutputStream()
        ZipOutputStream(nested).use { zip ->
            zip.writeEntry("tags.json", tags)
            zip.writeEntry("apps.json", apps)
        }
        return TagBackupArchive.decode(ByteArrayInputStream(nested.toByteArray()))
    }

    private fun normalizePreferences(preferences: Map<String, *>): Map<String, Any> {
        require(preferences.size <= MAX_PREFERENCES * 2) { "Too many app preferences" }
        val result = linkedMapOf<String, Any>()
        preferences.toSortedMap().forEach { (key, value) ->
            if (!isAllowedPreferenceKeyInternal(key)) return@forEach
            val normalized: Any? = when (value) {
                is String -> value.takeIf { it.length <= MAX_STRING_LENGTH }
                is Boolean, is Int, is Long -> value
                is Float -> value.takeIf { it.isFinite() }
                is Set<*> -> {
                    val strings = value.filterIsInstance<String>()
                    strings.takeIf { it.size == value.size && it.size <= MAX_SET_ITEMS }?.toSet()
                }
                else -> null
            }
            if (normalized != null) result[key] = normalized
        }
        require(result.size <= MAX_PREFERENCES) { "Too many restorable app preferences" }
        return result
    }

    private fun isAllowedPreferenceKeyInternal(key: String): Boolean {
        if (key.isBlank() || key.length > MAX_KEY_LENGTH || key in excludedPreferenceKeys) return false
        val normalized = key.lowercase()
        return sensitiveKeyFragments.none { it in normalized }
    }

    private fun encodePreferences(preferences: Map<String, Any>): JSONObject {
        val entries = JSONArray()
        preferences.forEach { (key, value) ->
            val type: String
            val encoded: Any
            when (value) {
                is String -> { type = "string"; encoded = value }
                is Boolean -> { type = "boolean"; encoded = value }
                is Int -> { type = "int"; encoded = value }
                is Long -> { type = "long"; encoded = value }
                is Float -> { type = "float"; encoded = value.toDouble() }
                is Set<*> -> {
                    type = "set"
                    encoded = JSONArray(value.filterIsInstance<String>().sorted())
                }
                else -> return@forEach
            }
            entries.put(JSONObject().put("key", key).put("type", type).put("value", encoded))
        }
        return JSONObject().put("schemaVersion", SCHEMA_VERSION).put("entries", entries)
    }

    private fun decodePreferences(json: JSONObject, manifestVersion: Int): Map<String, Any> {
        require(json.optInt("schemaVersion", -1) == manifestVersion) {
            "Unsupported preferences backup version"
        }
        val entries = json.optJSONArray("entries")
            ?: throw IllegalArgumentException("Preference entries are missing")
        require(entries.length() <= MAX_PREFERENCES) { "Backup contains too many preferences" }

        val result = linkedMapOf<String, Any>()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val key = entry.getString("key")
            require(isAllowedPreferenceKeyInternal(key)) { "Backup contains a protected preference" }
            require(!result.containsKey(key)) { "Backup contains duplicate preference: " + key }
            val value = entry.get("value")
            val decoded: Any = when (entry.getString("type")) {
                "string" -> (value as? String)?.also {
                    require(it.length <= MAX_STRING_LENGTH) { "Preference value is too long" }
                } ?: throw IllegalArgumentException("Invalid string preference: " + key)
                "boolean" -> value as? Boolean
                    ?: throw IllegalArgumentException("Invalid boolean preference: " + key)
                "int" -> (value as? Number)?.toInt()
                    ?: throw IllegalArgumentException("Invalid integer preference: " + key)
                "long" -> (value as? Number)?.toLong()
                    ?: throw IllegalArgumentException("Invalid long preference: " + key)
                "float" -> {
                    val number = (value as? Number)?.toFloat()
                        ?: throw IllegalArgumentException("Invalid float preference: " + key)
                    require(number.isFinite()) { "Invalid float preference: " + key }
                    number
                }
                "set" -> {
                    val array = value as? JSONArray
                        ?: throw IllegalArgumentException("Invalid string-set preference: " + key)
                    require(array.length() <= MAX_SET_ITEMS) { "String set is too large: " + key }
                    buildSet {
                        for (itemIndex in 0 until array.length()) {
                            val item = array.getString(itemIndex)
                            require(item.length <= MAX_STRING_LENGTH) { "String set item is too long" }
                            add(item)
                        }
                    }
                }
                else -> throw IllegalArgumentException("Unsupported preference type for " + key)
            }
            result[key] = decoded
        }
        return result
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var count = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (read == 0) continue
            count += read
            require(count <= limit) { "A backup entry is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
