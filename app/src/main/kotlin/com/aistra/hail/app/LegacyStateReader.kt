package com.aistra.hail.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Legacy v1 files are decoded completely before any record can replace live state. */
internal object LegacyStateReader {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_TAGS = 2_000
    private const val MAX_APPS = 20_000
    private const val MAX_ASSIGNMENTS = 100_000
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val appKeys = setOf("package", "pinned", "whitelisted", "tags", "tag", "userId")
    private val tagKeys = setOf("tag", "id")

    fun readApps(file: File, defaultUserId: Int): List<TagStateApp> {
        require(defaultUserId >= 0) { "Invalid Android user" }
        if (!file.exists()) return emptyList()
        val array = readArray(file)
        require(array.length() <= MAX_APPS) { "Too many stored apps" }
        var assignments = 0L
        val apps = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            requireKnownKeys(item, appKeys)
            val pkg = item.get("package")
            require(pkg is String && validPackage(pkg)) { "Invalid stored package name" }
            val userId = if (item.has("userId")) integer(item.get("userId")) else defaultUserId
            require(userId >= 0) { "Invalid stored Android user" }
            val ids = if (item.has("tags")) {
                val tags = item.getJSONArray("tags")
                assignments += tags.length()
                require(assignments <= MAX_ASSIGNMENTS) { "Too many stored tag assignments" }
                List(tags.length()) { integer(tags.get(it)) }
            } else {
                assignments++
                require(assignments <= MAX_ASSIGNMENTS) { "Too many stored tag assignments" }
                listOf(if (item.has("tag")) integer(item.get("tag")) else 0)
            }
            TagStateApp(pkg, boolean(item, "pinned"), boolean(item, "whitelisted"), userId, ids)
        }
        return mergeApps(apps)
    }

    fun readTags(file: File, defaultName: String): List<TagStateTag> {
        if (!file.exists()) return defaultTag(defaultName)
        val array = readArray(file)
        require(array.length() <= MAX_TAGS) { "Too many stored tags" }
        if (array.length() == 0) return defaultTag(defaultName)
        val ids = hashSetOf<Int>()
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            requireKnownKeys(item, tagKeys)
            val name = item.get("tag")
            require(name is String && name.length <= 1024) { "Invalid stored tag name" }
            val id = integer(item.get("id"))
            require(ids.add(id)) { "Duplicate stored tag identity" }
            TagStateTag(name, id)
        }
    }

    fun encodeApps(apps: List<TagStateApp>): String {
        require(apps.size <= MAX_APPS) { "Too many stored apps" }
        var assignments = 0L
        var estimatedBytes = 2L
        apps.forEach {
            require(validPackage(it.packageName) && it.userId >= 0) { "Invalid stored app identity" }
            assignments += it.tagIds.size
            require(assignments <= MAX_ASSIGNMENTS) { "Too many stored tag assignments" }
            estimatedBytes += 128L + it.packageName.length + it.tagIds.size * 12L
            require(estimatedBytes <= MAX_BYTES) { "Stored app data is too large" }
        }
        val array = JSONArray()
        mergeApps(apps).forEach {
            array.put(JSONObject().put("package", it.packageName).put("pinned", it.pinned)
                .put("whitelisted", it.whitelisted).put("tags", JSONArray(it.tagIds)).put("userId", it.userId))
        }
        return checkedEncoding(array)
    }

    fun encodeTags(tags: List<TagStateTag>): String {
        require(tags.size <= MAX_TAGS) { "Too many stored tags" }
        val ids = hashSetOf<Int>()
        var estimatedBytes = 2L
        tags.forEach {
            require(it.name.length <= 1024) { "Invalid stored tag name" }
            require(ids.add(it.id)) { "Duplicate stored tag identity" }
            estimatedBytes += 32 + JSONObject.quote(it.name).toByteArray(Charsets.UTF_8).size
            require(estimatedBytes <= MAX_BYTES) { "Stored tag data is too large" }
        }
        val array = JSONArray()
        tags.forEach { array.put(JSONObject().put("tag", it.name).put("id", it.id)) }
        return checkedEncoding(array)
    }

    private fun defaultTag(name: String): List<TagStateTag> {
        require(name.length <= 1024) { "Invalid default tag name" }
        return listOf(TagStateTag(name, 0))
    }

    private class AppMerge(val first: TagStateApp) {
        var pinned = first.pinned
        var whitelisted = first.whitelisted
        var union: LinkedHashSet<Int>? = null
    }

    private fun mergeApps(apps: List<TagStateApp>): List<TagStateApp> {
        val merged = linkedMapOf<Pair<String, Int>, AppMerge>()
        apps.forEach { item ->
            val key = item.packageName to item.userId
            val previous = merged[key]
            if (previous == null) merged[key] = AppMerge(item)
            else {
                previous.pinned = previous.pinned || item.pinned
                previous.whitelisted = previous.whitelisted || item.whitelisted
                val union = previous.union ?: LinkedHashSet(previous.first.tagIds).also { previous.union = it }
                union.addAll(item.tagIds)
            }
        }
        return merged.values.map { value ->
            val ids = value.union
            if (ids == null) value.first else TagStateApp(value.first.packageName,
                value.pinned, value.whitelisted, value.first.userId, ids)
        }
    }

    private fun checkedEncoding(array: JSONArray): String = array.toString().also {
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Stored data is too large" }
    }

    private fun validPackage(value: String): Boolean = value.length in 1..255 && packagePattern.matches(value)

    private fun integer(value: Any): Int {
        require(value is Int || value is Long) { "Stored value must be an integer" }
        val number = (value as Number).toLong()
        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Stored integer is out of range" }
        return number.toInt()
    }

    private fun boolean(item: JSONObject, key: String): Boolean {
        if (!item.has(key)) return false
        val value = item.get(key)
        require(value is Boolean) { "Invalid stored app flag" }
        return value
    }

    private fun requireKnownKeys(item: JSONObject, allowed: Set<String>) {
        require(item.keys().asSequence().all { it in allowed }) { "Unknown stored state fields" }
    }

    private fun readArray(file: File): JSONArray {
        require(file.isFile && file.length() <= MAX_BYTES) { "Stored state is missing or too large" }
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_BYTES) { "Stored state grew beyond the size limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        checkNesting(bytes)
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val tokener = JSONTokener(text)
            val value = tokener.nextValue()
            require(value is JSONArray && tokener.nextClean() == '\u0000') { "Invalid stored state array" }
            return value
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid stored state JSON", error)
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Invalid stored state UTF-8", error)
        }
    }

    private fun checkNesting(bytes: ByteArray) {
        var depth = 0
        var quoted = false
        var escaped = false
        bytes.forEach { byte ->
            val char = byte.toInt().toChar()
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) { "Stored state is too deeply nested" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid stored state structure" } }
            }
        }
        require(!quoted && depth == 0) { "Invalid stored state structure" }
    }
}
