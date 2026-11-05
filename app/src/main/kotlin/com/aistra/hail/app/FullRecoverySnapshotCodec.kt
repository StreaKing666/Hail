package com.aistra.hail.app

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class FullRecoverySnapshot(
    val files: List<FullBackupFile>,
    val tagState: TagStateSnapshot,
    val preferences: Map<String, Any>
)

/** Private rollback format. Its complete local preferences must never be used for export. */
internal object FullRecoverySnapshotCodec {
    private const val FORMAT = "hail-private-full-recovery"
    private const val MAX_ENTRY = 8 * 1024 * 1024
    private const val MAX_RAW = 40L * 1024 * 1024
    private const val MAX_ARCHIVE = 48 * 1024 * 1024
    private const val MAX_ENTRIES = 256

    fun encode(snapshot: FullRecoverySnapshot): ByteArray {
        require(snapshot.files.size <= MAX_ENTRIES - 3) { "Too many recovery files" }
        validateFilePaths(snapshot.files.map { it.path })
        var rawTotal = 0L
        snapshot.files.forEach {
            require(it.bytes.size <= MAX_ENTRY) { "Recovery file is too large" }
            rawTotal += it.bytes.size
            require(rawTotal <= MAX_RAW) { "Recovery files are too large" }
        }
        val entries = linkedMapOf(
            "state.json" to TagStateSnapshotCodec.encode(snapshot.tagState),
            "preferences.json" to encodePreferences(snapshot.preferences)
        )
        snapshot.files.forEach { entries["files/" + it.path] = it.bytes }
        val metadata = JSONArray()
        entries.forEach { (name, bytes) ->
            metadata.put(JSONObject().put("path", name).put("size", bytes.size).put("sha256", hash(bytes)))
        }
        val marker = JSONObject().put("format", FORMAT).put("version", 1).put("entries", metadata)
            .toString().toByteArray(Charsets.UTF_8)
        val allEntries = linkedMapOf("recovery.json" to marker).apply { putAll(entries) }
        require(allEntries.values.all { it.size <= MAX_ENTRY } &&
            allEntries.values.sumOf { it.size.toLong() } <= MAX_ARCHIVE) { "Recovery snapshot is too large" }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> allEntries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        require(output.size() <= MAX_ARCHIVE) { "Recovery archive is too large" }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): FullRecoverySnapshot =
        requireNotNull(decodeOrNull(bytes)) { "Private recovery marker is missing" }

    /** Only absence of the private marker permits the historical portable-snapshot reader. */
    fun decodeOrNull(bytes: ByteArray): FullRecoverySnapshot? {
        val entries = readZip(bytes)
        val markerBytes = entries["recovery.json"] ?: return null
        val marker = json(markerBytes)
        requireKeys(marker, setOf("format", "version", "entries"))
        require(marker.get("format") == FORMAT && int(marker.get("version")) == 1) { "Unsupported private recovery version" }
        val declared = marker.getJSONArray("entries")
        require(declared.length() == entries.size - 1) { "Recovery entry count mismatch" }
        val names = hashSetOf<String>()
        repeat(declared.length()) { index ->
            val item = declared.getJSONObject(index)
            requireKeys(item, setOf("path", "size", "sha256"))
            val name = item.get("path")
            require(name is String && name != "recovery.json" && names.add(name)) { "Invalid recovery entry" }
            val content = entries[name] ?: throw IllegalArgumentException("Missing recovery entry")
            val size = integer(item.get("size"))
            require(size == content.size.toLong() && item.get("sha256") == hash(content)) { "Recovery entry checksum mismatch" }
        }
        require(names == entries.keys - "recovery.json" && "state.json" in names && "preferences.json" in names) {
            "Recovery state entries are missing"
        }
        require(names.all { it == "state.json" || it == "preferences.json" || it.startsWith("files/") }) { "Unknown recovery entry" }
        val files = entries.filterKeys { it.startsWith("files/") }.map {
            FullBackupFile(it.key.removePrefix("files/"), it.value)
        }
        require(files.size <= MAX_ENTRIES - 3 && files.sumOf { it.bytes.size.toLong() } <= MAX_RAW) { "Recovery file limits exceeded" }
        validateFilePaths(files.map { it.path })
        return FullRecoverySnapshot(files, TagStateSnapshotCodec.decode(entries.getValue("state.json")),
            decodePreferences(entries.getValue("preferences.json")))
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        require(bytes.size <= MAX_ARCHIVE) { "Recovery archive is too large" }
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entries.size < MAX_ENTRIES && !entry.isDirectory) { "Invalid recovery entry count or directory" }
                validatePath(entry.name, 1024)
                require(entry.name !in entries && entry.size <= MAX_ENTRY) { "Duplicate or oversized recovery entry" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Recovery cancelled")
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(output.size().toLong() + count <= MAX_ENTRY && total <= MAX_ARCHIVE) { "Recovery data exceeds limits" }
                    output.write(buffer, 0, count)
                }
                entries[entry.name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun validateFilePaths(paths: List<String>) {
        require(paths.toSet().size == paths.size) { "Duplicate recovery file" }
        val names = paths.toSet()
        paths.forEach { path ->
            validatePath(path, 512)
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent !in names) { "Conflicting recovery file paths" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
    }

    private fun validatePath(path: String, limit: Int) {
        require(path.isNotEmpty() && path.length <= limit && !path.startsWith('/') && '\\' !in path &&
            path.none { it.code < 32 } && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Unsafe recovery file path"
        }
    }

    private fun encodePreferences(preferences: Map<String, Any>): ByteArray {
        require(preferences.size <= 4096) { "Too many recovery preferences" }
        var estimate = 64L
        fun stringCost(value: String): Int {
            require(value.length <= 1024 * 1024) { "Recovery preference string is too large" }
            return JSONObject.quote(value).toByteArray(Charsets.UTF_8).size
        }
        preferences.forEach { (key, value) ->
            require(key.length <= 4096) { "Recovery preference key is too long" }
            estimate += 80L + stringCost(key)
            estimate += when (value) {
                is String -> stringCost(value).toLong()
                is Boolean, is Int, is Long, is Float -> 32L
                is Set<*> -> {
                    require(value.size <= 20_000 && value.all { it is String }) { "Invalid recovery string set" }
                    value.sumOf { stringCost(it as String).toLong() + 1 }
                }
                else -> throw IllegalArgumentException("Unsupported recovery preference type")
            }
            require(estimate <= MAX_ENTRY) { "Recovery preferences are too large" }
        }
        val entries = JSONArray()
        preferences.forEach { (key, value) ->
            val type: String
            val encoded: Any
            when (value) {
                is String -> { type = "string"; encoded = value }
                is Boolean -> { type = "boolean"; encoded = value }
                is Int -> { type = "int"; encoded = value }
                is Long -> { type = "long"; encoded = value.toString() }
                is Float -> { type = "float-bits"; encoded = value.toRawBits() }
                is Set<*> -> { type = "string-set"; encoded = JSONArray(value.map { it as String }) }
                else -> throw IllegalArgumentException("Unsupported recovery preference type")
            }
            entries.put(JSONObject().put("key", key).put("type", type).put("value", encoded))
        }
        return JSONObject().put("version", 1).put("entries", entries).toString().toByteArray(Charsets.UTF_8)
            .also { require(it.size <= MAX_ENTRY) { "Recovery preferences are too large" } }
    }

    private fun decodePreferences(bytes: ByteArray): Map<String, Any> {
        val root = json(bytes)
        requireKeys(root, setOf("version", "entries"))
        require(int(root.get("version")) == 1) { "Unsupported recovery preferences" }
        val entries = root.getJSONArray("entries")
        require(entries.length() <= 4096) { "Too many recovery preferences" }
        val result = linkedMapOf<String, Any>()
        repeat(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            requireKeys(item, setOf("key", "type", "value"))
            val key = item.get("key")
            require(key is String && key.length <= 4096 && key !in result) { "Invalid recovery preference key" }
            val value = item.get("value")
            result[key] = when (item.get("type")) {
                "string" -> { require(value is String && value.length <= 1024 * 1024); value }
                "boolean" -> { require(value is Boolean); value }
                "int" -> int(value)
                "long" -> { require(value is String); requireNotNull(value.toLongOrNull()) }
                "float-bits" -> Float.fromBits(int(value))
                "string-set" -> {
                    require(value is JSONArray && value.length() <= 20_000)
                    linkedSetOf<String>().apply { repeat(value.length()) { at ->
                        val text = value.get(at)
                        require(text is String && text.length <= 1024 * 1024 && add(text)) { "Invalid recovery string set" }
                    } }
                }
                else -> throw IllegalArgumentException("Unknown recovery preference type")
            }
        }
        return result
    }

    private fun integer(value: Any): Long {
        require(value is Int || value is Long) { "Recovery value must be an integer" }
        return (value as Number).toLong()
    }
    private fun int(value: Any): Int = integer(value).also {
        require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Recovery integer out of range" }
    }.toInt()
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun requireKeys(value: JSONObject, keys: Set<String>) {
        require(value.keys().asSequence().toSet() == keys) { "Invalid recovery fields" }
    }
    private fun json(bytes: ByteArray): JSONObject {
        require(bytes.size <= MAX_ENTRY) { "Recovery JSON is too large" }
        var depth = 0
        var quoted = false
        var escaped = false
        bytes.forEach { byte ->
            val char = byte.toInt().toChar()
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 10) { "Recovery JSON is too deeply nested" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid recovery JSON" } }
            }
        }
        require(!quoted && depth == 0) { "Invalid recovery JSON" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val tokener = JSONTokener(text)
        val value = tokener.nextValue()
        require(value is JSONObject && tokener.nextClean() == '\u0000') { "Invalid recovery JSON object" }
        return value
    }
}
