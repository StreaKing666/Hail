package com.aistra.hail.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

internal data class TagStateTag(val name: String, val id: Int)

internal class TagStateApp(
    val packageName: String,
    val pinned: Boolean,
    val whitelisted: Boolean,
    val userId: Int,
    tagIds: Collection<Int>
) {
    val tagIds: List<Int> = Collections.unmodifiableList(tagIds.toList())
    override fun equals(other: Any?): Boolean = other is TagStateApp &&
        packageName == other.packageName && pinned == other.pinned &&
        whitelisted == other.whitelisted && userId == other.userId && tagIds == other.tagIds
    override fun hashCode(): Int = arrayOf(packageName, pinned, whitelisted, userId, tagIds).contentHashCode()
}

internal class TagStateSnapshot(tags: Collection<TagStateTag>, apps: Collection<TagStateApp>) {
    val tags: List<TagStateTag> = Collections.unmodifiableList(tags.toList())
    val apps: List<TagStateApp> = Collections.unmodifiableList(apps.toList())
    override fun equals(other: Any?): Boolean = other is TagStateSnapshot && tags == other.tags && apps == other.apps
    override fun hashCode(): Int = 31 * tags.hashCode() + apps.hashCode()
}

/** Internal recovery format: preserves legacy empty assignments and never normalizes identity. */
internal object TagStateSnapshotCodec {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_TAGS = 2_000
    private const val MAX_APPS = 20_000
    private const val MAX_ASSIGNMENTS = 100_000
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")

    fun encode(snapshot: TagStateSnapshot): ByteArray {
        validate(snapshot)
        val tags = JSONArray()
        snapshot.tags.forEach { tags.put(JSONObject().put("name", it.name).put("id", it.id)) }
        val apps = JSONArray()
        snapshot.apps.forEach {
            apps.put(JSONObject().put("packageName", it.packageName).put("pinned", it.pinned)
                .put("whitelisted", it.whitelisted).put("userId", it.userId).put("tagIds", JSONArray(it.tagIds)))
        }
        return JSONObject().put("version", 1).put("tags", tags).put("apps", apps)
            .toString().toByteArray(Charsets.UTF_8).also {
                require(it.size <= MAX_BYTES) { "Tag recovery snapshot is too large" }
            }
    }

    fun decode(bytes: ByteArray): TagStateSnapshot {
        require(bytes.size <= MAX_BYTES) { "Tag recovery snapshot is too large" }
        checkNesting(bytes)
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val tokener = JSONTokener(text)
            val root = tokener.nextValue()
            require(root is JSONObject) { "Tag snapshot must be an object" }
            require(tokener.nextClean() == '\u0000') { "Trailing tag snapshot data" }
            requireKeys(root, setOf("version", "tags", "apps"))
            require(integer(root.get("version")) == 1) { "Unsupported tag recovery snapshot" }
            val tagArray = root.getJSONArray("tags")
            val appArray = root.getJSONArray("apps")
            require(tagArray.length() <= MAX_TAGS && appArray.length() <= MAX_APPS) { "Too many tag recovery records" }
            val tags = List(tagArray.length()) { index ->
                val item = tagArray.getJSONObject(index)
                requireKeys(item, setOf("name", "id"))
                val name = item.get("name")
                require(name is String && name.length <= 1024) { "Invalid tag name" }
                TagStateTag(name, integer(item.get("id")))
            }
            var assignments = 0L
            val apps = List(appArray.length()) { index ->
                val item = appArray.getJSONObject(index)
                requireKeys(item, setOf("packageName", "pinned", "whitelisted", "userId", "tagIds"))
                val pkg = item.get("packageName")
                require(pkg is String && validPackage(pkg)) { "Invalid package name" }
                val pinned = item.get("pinned")
                val whitelisted = item.get("whitelisted")
                require(pinned is Boolean && whitelisted is Boolean) { "Invalid app flags" }
                val userId = integer(item.get("userId"))
                require(userId >= 0) { "Invalid Android user" }
                val ids = item.getJSONArray("tagIds")
                assignments += ids.length()
                require(assignments <= MAX_ASSIGNMENTS) { "Too many tag assignments" }
                TagStateApp(pkg, pinned, whitelisted, userId, List(ids.length()) { integer(ids.get(it)) })
            }
            return TagStateSnapshot(tags, apps).also(::validate)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid tag recovery snapshot", error)
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Invalid tag recovery encoding", error)
        }
    }

    private fun validate(snapshot: TagStateSnapshot) {
        require(snapshot.tags.size <= MAX_TAGS && snapshot.apps.size <= MAX_APPS) { "Too many tag recovery records" }
        var assignments = 0L
        var estimatedBytes = 64L
        snapshot.tags.forEach {
            require(it.name.length <= 1024) { "Invalid tag name" }
            estimatedBytes += 32 + JSONObject.quote(it.name).toByteArray(Charsets.UTF_8).size
            require(estimatedBytes <= MAX_BYTES) { "Tag recovery snapshot is too large" }
        }
        snapshot.apps.forEach {
            require(validPackage(it.packageName) && it.userId >= 0) { "Invalid app identity" }
            assignments += it.tagIds.size
            require(assignments <= MAX_ASSIGNMENTS) { "Too many tag assignments" }
            estimatedBytes += 128L + it.packageName.length + it.tagIds.size * 12L
            require(estimatedBytes <= MAX_BYTES) { "Tag recovery snapshot is too large" }
        }
    }

    private fun validPackage(value: String): Boolean = value.length in 1..255 && packagePattern.matches(value)

    private fun integer(value: Any): Int {
        require(value is Int || value is Long) { "Expected an integer" }
        val number = (value as Number).toLong()
        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Integer is out of range" }
        return number.toInt()
    }

    private fun requireKeys(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected) { "Invalid tag snapshot fields" }
    }

    private fun checkNesting(bytes: ByteArray) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (byte in bytes) {
            val char = byte.toInt().toChar()
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) { "Tag snapshot is too deeply nested" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid tag snapshot structure" } }
            }
        }
        require(!quoted && depth == 0) { "Invalid tag snapshot structure" }
    }
}
