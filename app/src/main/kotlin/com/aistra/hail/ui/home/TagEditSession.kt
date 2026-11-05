package com.aistra.hail.ui.home

import com.aistra.hail.app.TagAssignment
import com.aistra.hail.app.TagAssignmentManager
import com.aistra.hail.app.TagStateApp
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class TagEditConflictException : IllegalStateException("Tag data changed while the editor was open")

internal class TagEditForeignProfileConflictException : IllegalStateException("A deleted tag is used in another Android profile")

internal data class TagEditUserPlan(
    val tags: List<Pair<String, Int>>,
    val apps: List<TagStateApp>,
    val renamedTags: List<Pair<String, String>>,
    val deletedTagNames: List<String>
)

internal data class TagEditPlan(
    val tags: List<Pair<String, Int>>,
    val assignments: List<TagAssignment>,
    val renamedTags: List<Pair<String, String>>,
    val deletedTagNames: List<String>
)

/**
 * A complete, in-memory editing session. No disk changes occur until the user taps Save.
 * This includes reorder, rename, delete and bulk app assignment operations.
 */
internal class TagEditSession(
    initialTags: List<Pair<String, Int>>,
    initialApps: List<TagAssignment>,
    val userId: Int = 0
) {
    private val baselineTags = initialTags.toList()
    private val baselineApps = initialApps.map { it.copy(tagIds = it.tagIds.toList()) }
    val tags: MutableList<Pair<String, Int>> = baselineTags.toMutableList()
    private val appTags = linkedMapOf<String, List<Int>>().apply {
        baselineApps.forEach { put(it.packageName, it.tagIds) }
    }

    val isDirty: Boolean
        get() = tags != baselineTags ||
            baselineApps.any { appTags[it.packageName] != it.tagIds }

    fun appCount(tagId: Int): Int = appTags.values.count { tagId in it }
    fun packagesIn(tagId: Int): Set<String> = appTags.filterValues { tagId in it }.keys
    fun tagsFor(packageName: String): List<Int> = appTags[packageName].orEmpty()
    fun allAssignments(): Map<String, List<Int>> = appTags.mapValues { it.value.toList() }

    /** Packages explicitly orphaned by this edit, excluding preexisting untagged records. */
    fun newlyUnassignedPackages(): Set<String> = baselineApps
        .asSequence()
        .filter { it.tagIds.isNotEmpty() && tagsFor(it.packageName).isEmpty() }
        .map { it.packageName }
        .toSet()

    /** Compare only session-owned records; unrelated packages added later remain untouched. */
    fun planCommit(currentTags: List<Pair<String, Int>>, currentApps: List<TagAssignment>): TagEditPlan {
        val currentByPackage = currentApps.associateBy { it.packageName }
        if (currentTags != baselineTags || baselineApps.any {
            currentByPackage[it.packageName]?.tagIds != it.tagIds
        }) throw TagEditConflictException()
        val baselinePackages = baselineApps.map { it.packageName }.toSet()
        val removedIds = baselineTags.map { it.second }.toSet() - tags.map { it.second }.toSet()
        if (currentApps.any { it.packageName !in baselinePackages && it.tagIds.any { id -> id in removedIds } }) {
            throw TagEditConflictException()
        }
        val orphaned = newlyUnassignedPackages()
        val assignments = currentApps.filterNot { it.packageName in orphaned }.map {
            it.copy(tagIds = (appTags[it.packageName] ?: it.tagIds).toList())
        }
        val nextById = tags.associate { it.second to it.first }
        return TagEditPlan(tags.toList(), assignments,
            baselineTags.mapNotNull { (oldName, id) ->
                nextById[id]?.takeIf { it != oldName }?.let { oldName to it }
            }, baselineTags.filter { it.second !in nextById }.map { it.first })
    }

    fun requireUser(currentUserId: Int) {
        if (userId != currentUserId) throw TagEditConflictException()
    }

    fun planCommitForUser(
        currentTags: List<Pair<String, Int>>, allRecords: List<TagStateApp>, currentUserId: Int
    ): TagEditUserPlan {
        requireUser(currentUserId)
        val scoped = allRecords.filter { it.userId == currentUserId }
        val plan = planCommit(currentTags, scoped.map { TagAssignment(it.packageName, it.tagIds) })
        val removedIds = currentTags.map { it.second }.toSet() - plan.tags.map { it.second }.toSet()
        if (allRecords.any { it.userId != currentUserId && it.tagIds.any { id -> id in removedIds } }) {
            throw TagEditForeignProfileConflictException()
        }
        val nextByPackage = plan.assignments.associate { it.packageName to it.tagIds }
        val merged = allRecords.mapNotNull { record ->
            if (record.userId != currentUserId) record
            else nextByPackage[record.packageName]?.let { ids ->
                TagStateApp(record.packageName, record.pinned, record.whitelisted, record.userId, ids)
            }
        }
        return TagEditUserPlan(plan.tags, merged, plan.renamedTags, plan.deletedTagNames)
    }

    fun copyForCommit(): TagEditSession = TagEditSession(baselineTags, baselineApps, userId).also { copy ->
        copy.tags.clear()
        copy.tags.addAll(tags)
        copy.appTags.clear()
        appTags.forEach { (pkg, ids) -> copy.appTags[pkg] = ids.toList() }
    }

    fun exportSnapshot(): String = JSONObject().apply {
        put("version", 2)
        put("userId", userId)
        put("baselineTags", encodeTags(baselineTags))
        put("baselineApps", encodeApps(baselineApps))
        put("tags", encodeTags(tags))
        put("apps", encodeApps(appTags.map { TagAssignment(it.key, it.value) }))
    }.toString().also { require(it.length <= MAX_SNAPSHOT_CHARS) { "Tag draft is too large to restore" } }

    fun addTag(rawName: String): Int? {
        val name = rawName.trim()
        if (name.isEmpty() || tags.any { it.first.equals(name, ignoreCase = true) }) return null
        // Don't tie future identity to a name: collisions are possible.
        var id = name.hashCode()
        while (id == 0 || tags.any { it.second == id }) {
            id = if (id == Int.MAX_VALUE) 1 else id + 1
        }
        tags.add(name to id)
        return id
    }

    fun renameTag(tagId: Int, rawName: String): Boolean {
        if (tagId == 0) return false
        val name = rawName.trim()
        if (name.isEmpty() || tags.any { it.second != tagId && it.first.equals(name, true) })
            return false
        val position = tags.indexOfFirst { it.second == tagId }
        if (position < 1) return false
        tags[position] = name to tagId
        return true
    }

    fun deleteTag(tagId: Int): Boolean {
        if (tagId == 0 || tags.none { it.second == tagId }) return false
        tags.removeAll { it.second == tagId }
        appTags.keys.toList().forEach { key ->
            appTags[key] = appTags.getValue(key).filterNot { it == tagId }
        }
        return true
    }

    fun move(from: Int, to: Int): Boolean = TagOrder.move(tags, from, to)

    fun assign(sourceId: Int, targetId: Int, selected: Set<String>, move: Boolean): Int {
        if (sourceId == targetId || tags.none { it.second == sourceId } ||
            tags.none { it.second == targetId }) return 0

        val plan = TagAssignmentManager.apply(
            appTags.map { TagAssignment(it.key, it.value) },
            sourceId, targetId, selected, move
        )
        plan.assignments.forEach { appTags[it.packageName] = it.tagIds }
        return plan.changedCount
    }

    companion object {
        // Saved state shares Android's Binder budget with the rest of the Activity.
        const val MAX_SNAPSHOT_CHARS = 200_000
        private const val MAX_TAGS = 500
        private const val MAX_APPS = 20_000

        fun importSnapshot(snapshot: String): TagEditSession {
            require(snapshot.length <= MAX_SNAPSHOT_CHARS) { "Tag draft is too large to restore" }
            try {
                val json = JSONObject(snapshot)
                require(json.getInt("version") == 2) { "Unsupported tag draft" }
                val userId = json.getInt("userId")
                require(userId >= 0) { "Invalid Android user" }
                val baselineTags = decodeTags(json.getJSONArray("baselineTags"))
                val baselineApps = decodeApps(json.getJSONArray("baselineApps"))
                val draftTags = decodeTags(json.getJSONArray("tags"))
                val draftApps = decodeApps(json.getJSONArray("apps"))
                require(draftTags.first() == baselineTags.first()) { "Default tag must stay locked" }
                require(draftApps.map { it.packageName }.toSet() == baselineApps.map { it.packageName }.toSet())
                return TagEditSession(baselineTags, baselineApps, userId).also { restored ->
                    restored.tags.clear()
                    restored.tags.addAll(draftTags)
                    restored.appTags.clear()
                    draftApps.forEach { restored.appTags[it.packageName] = it.tagIds }
                }
            } catch (error: JSONException) {
                throw IllegalArgumentException("Invalid tag draft", error)
            }
        }

        private fun encodeTags(tags: List<Pair<String, Int>>) = JSONArray().apply {
            tags.forEach { put(JSONArray().put(it.first).put(it.second)) }
        }

        private fun encodeApps(apps: List<TagAssignment>) = JSONArray().apply {
            apps.forEach { put(JSONArray().put(it.packageName).put(JSONArray(it.tagIds))) }
        }

        private fun decodeTags(json: JSONArray): List<Pair<String, Int>> {
            require(json.length() in 1..MAX_TAGS) { "Invalid tag count" }
            val tags = List(json.length()) { at -> json.getJSONArray(at).let {
                val name = it.getString(0)
                require(name.isNotBlank() && name.length <= 1024)
                name to it.getInt(1)
            } }
            require(tags.first().second == 0 && tags.map { it.second }.distinct().size == tags.size)
            require(tags.map { it.first.lowercase() }.distinct().size == tags.size)
            return tags
        }

        private fun decodeApps(json: JSONArray): List<TagAssignment> {
            require(json.length() <= MAX_APPS) { "Invalid app assignment count" }
            val apps = List(json.length()) { at -> json.getJSONArray(at).let {
                val pkg = it.getString(0)
                require(pkg.isNotBlank() && pkg.length <= 512)
                val ids = it.getJSONArray(1)
                require(ids.length() <= MAX_TAGS)
                TagAssignment(pkg, List(ids.length()) { index -> ids.getInt(index) })
            } }
            require(apps.map { it.packageName }.distinct().size == apps.size)
            return apps
        }
    }

}
