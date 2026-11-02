package com.aistra.hail.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class FullBackupArchiveTest {
    private val tagsJson = """[{"tag":"Default","id":0},{"tag":"Work","id":101}]"""
    private val appsJson =
        """[{"package":"com.example.reader","pinned":true,"whitelisted":false,"tags":[0,101]}]"""

    private val files = listOf(
        FullBackupFile("v1/tags.json", tagsJson.toByteArray()),
        FullBackupFile("v1/apps.json", appsJson.toByteArray()),
        FullBackupFile("notes/state.json", """{"theme":"dark"}""".toByteArray())
    )

    @Test
    fun fullBackupRoundTripsFilesTagsAppsAndTypedPreferences() {
        val bytes = FullBackupArchive.encode(
            files = files,
            preferences = mapOf(
                "app_theme" to "theme_dark",
                "filter_system_apps" to true,
                "sort_limit" to 12,
                "long_value" to 1234567890123L,
                "font_scale" to 14.5f,
                "hidden_packages" to setOf("com.example.a", "com.example.b"),
                "working_mode" to "su_disable",
                "biometric_login" to true,
                "auto_freeze_after_lock" to true,
                "api_token" to "must-not-be-exported"
            ),
            applicationId = "com.aistra.hail.enhanced",
            versionName = "1.11.0-enhanced.9",
            versionCode = 111009
        )

        val restored = FullBackupArchive.decode(ByteArrayInputStream(bytes))
        assertEquals("com.aistra.hail.enhanced", restored.sourceApplicationId)
        assertEquals("1.11.0-enhanced.9", restored.sourceVersionName)
        assertEquals(setOf("v1/tags.json", "v1/apps.json", "notes/state.json"), restored.files.map { it.path }.toSet())
        assertEquals("""{"theme":"dark"}""", String(restored.files.single { it.path == "notes/state.json" }.bytes))
        assertEquals(listOf("Default", "Work"), restored.tagBackup.tags.map { it.name })
        assertEquals("com.example.reader", restored.tagBackup.apps.single().packageName)
        assertTrue(restored.tagBackup.apps.single().pinned)
        assertEquals("theme_dark", restored.preferences["app_theme"])
        assertEquals(true, restored.preferences["filter_system_apps"])
        assertEquals(12, restored.preferences["sort_limit"])
        assertEquals(1234567890123L, restored.preferences["long_value"])
        assertEquals(14.5f, restored.preferences["font_scale"])
        assertEquals(setOf("com.example.a", "com.example.b"), restored.preferences["hidden_packages"])
        assertFalse(restored.preferences.containsKey("working_mode"))
        assertFalse(restored.preferences.containsKey("biometric_login"))
        assertFalse(restored.preferences.containsKey("auto_freeze_after_lock"))
        assertFalse(restored.preferences.containsKey("api_token"))
    }

    @Test
    fun automationDefinitionsAreExportableButDeviceModeRemainsProtected() {
        val bytes = FullBackupArchive.encode(
            files = files,
            preferences = mapOf(
                "rule_engine_enabled" to true,
                "active_profile_id" to "profile-123",
                "working_mode" to "su_disable"
            ),
            applicationId = "com.aistra.hail.enhanced",
            versionName = "test",
            versionCode = 1
        )
        val payload = FullBackupArchive.decode(bytes.inputStream())
        assertEquals(true, payload.preferences["rule_engine_enabled"])
        assertEquals("profile-123", payload.preferences["active_profile_id"])
        assertFalse(payload.preferences.containsKey("working_mode"))
    }

    @Test
    fun missingTrackedAppFilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FullBackupArchive.encode(
                files = listOf(FullBackupFile("notes/state.json", byteArrayOf(1))),
                preferences = emptyMap<String, Any>(),
                applicationId = "com.aistra.hail.enhanced",
                versionName = "test",
                versionCode = 1
            )
        }
    }

    @Test
    fun zipSlipPathsAreRejected() {
        val archive = rawArchive(
            entries = listOf(
                "files/v1/tags.json" to tagsJson,
                "files/v1/apps.json" to appsJson,
                "files/../../shared_prefs/prefs.xml" to "bad"
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            FullBackupArchive.decode(ByteArrayInputStream(archive))
        }
    }

    @Test
    fun duplicateFilePathsAreRejectedBeforeExport() {
        assertThrows(IllegalArgumentException::class.java) {
            FullBackupArchive.encode(
                files = files + FullBackupFile("v1/tags.json", tagsJson.toByteArray()),
                preferences = emptyMap<String, Any>(),
                applicationId = "com.aistra.hail.enhanced",
                versionName = "test",
                versionCode = 1
            )
        }
    }

    @Test
    fun protectedPreferencesAreRejectedOnImport() {
        val preferences = JSONObject()
            .put("schemaVersion", 1)
            .put(
                "entries",
                JSONArray().put(
                    JSONObject().put("key", "working_mode")
                        .put("type", "string").put("value", "su_disable")
                )
            )
        val archive = rawArchive(
            entries = listOf(
                "files/v1/tags.json" to tagsJson,
                "files/v1/apps.json" to appsJson
            ),
            preferences = preferences.toString()
        )
        assertThrows(IllegalArgumentException::class.java) {
            FullBackupArchive.decode(ByteArrayInputStream(archive))
        }
    }

    @Test
    fun schemaV2DetectsModifiedArchiveEntries() {
        val original = FullBackupArchive.encode(
            files = files,
            preferences = mapOf("app_theme" to "theme_dark"),
            applicationId = "com.aistra.hail.enhanced",
            versionName = "1.12-test",
            versionCode = 112000
        )
        val tampered = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(original)).use { input ->
            ZipOutputStream(tampered).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    output.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "files/notes/state.json") {
                        output.write(bytes + byteArrayOf(0x21))
                    } else {
                        output.write(bytes)
                    }
                    output.closeEntry()
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullBackupArchive.decode(ByteArrayInputStream(tampered.toByteArray()))
        }
    }

    @Test
    fun selectiveExportAlwaysKeepsCoreTagState() {
        val bytes = FullBackupArchive.encode(
            files = files,
            preferences = mapOf("app_theme" to "theme_dark"),
            applicationId = "com.aistra.hail.enhanced",
            versionName = "test",
            versionCode = 1,
            selection = FullBackupSelection(
                includeAdditionalFiles = false,
                includePreferences = false
            )
        )
        val payload = FullBackupArchive.decode(ByteArrayInputStream(bytes))
        assertEquals(2, payload.schemaVersion)
        assertEquals(setOf("v1/tags.json", "v1/apps.json"), payload.files.map { it.path }.toSet())
        assertTrue(payload.preferences.isEmpty())
    }

    @Test
    fun selectiveExportRecordsExcludedCategoriesForSafeReplacement() {
        val bytes = FullBackupArchive.encode(
            files = files,
            preferences = mapOf("app_theme" to "theme_dark"),
            applicationId = "com.aistra.hail.enhanced",
            versionName = "test",
            versionCode = 1,
            selection = FullBackupSelection(includeAdditionalFiles = false, includePreferences = false)
        )
        val manifest = ZipInputStream(bytes.inputStream()).use { input ->
            var text: String? = null
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name == "manifest.json") text = input.readBytes().toString(StandardCharsets.UTF_8)
                input.closeEntry()
            }
            JSONObject(requireNotNull(text))
        }
        val selection = manifest.optJSONObject("selection")
        assertTrue("Backup must distinguish excluded categories from empty categories", selection != null)
        assertFalse(selection!!.getBoolean("includeAdditionalFiles"))
        assertFalse(selection.getBoolean("includePreferences"))
        val restored = FullBackupArchive.decode(bytes.inputStream())
        assertFalse(restored.selection.includeAdditionalFiles)
        assertFalse(restored.selection.includePreferences)
    }

    @Test
    fun legacyV1ArchiveRemainsReadable() {
        val archive = rawArchive(
            entries = listOf(
                "files/v1/tags.json" to tagsJson,
                "files/v1/apps.json" to appsJson
            )
        )
        val payload = FullBackupArchive.decode(ByteArrayInputStream(archive))
        assertEquals(1, payload.schemaVersion)
        assertEquals("com.example.legacy", payload.sourceApplicationId)
        assertFalse(payload.selection.includeAdditionalFiles)
        assertFalse(payload.selection.includePreferences)
    }

    @Test
    fun legacyArchiveWithUnknownPreferenceScopePreservesAbsentPreferences() {
        val preferences = JSONObject().put("schemaVersion", 1).put("entries", JSONArray().put(
            JSONObject().put("key", "app_theme").put("type", "string").put("value", "theme_dark")
        ))
        val payload = FullBackupArchive.decode(rawArchive(
            entries = listOf("files/v1/tags.json" to tagsJson, "files/v1/apps.json" to appsJson),
            preferences = preferences.toString()
        ).inputStream())
        assertTrue(FullBackupRestorePlan.preferencesToRemove(
            payload, FullBackupRestoreMode.REPLACE, setOf("app_theme", "home_font_size")
        ).isEmpty())
    }

    @Test
    fun selectingProtectedPreferenceKeysDoesNotProduceAnUnreadableArchive() {
        val bytes = FullBackupArchive.encode(
            files = files,
            preferences = mapOf("app_theme" to "theme_dark", "working_mode" to "su_disable"),
            applicationId = "com.aistra.hail.enhanced",
            versionName = "test",
            versionCode = 1,
            selection = FullBackupSelection(preferenceKeys = setOf("app_theme", "working_mode"))
        )
        val payload = FullBackupArchive.decode(bytes.inputStream())
        assertEquals(mapOf("app_theme" to "theme_dark"), payload.preferences)
        assertEquals(setOf("app_theme"), payload.selection.preferenceKeys)
    }

    @Test
    fun explicitlyIncludedEmptyCategoriesCanBeReplaced() {
        val bytes = FullBackupArchive.encode(
            files = files.take(2), preferences = emptyMap<String, Any>(),
            applicationId = "com.aistra.hail.enhanced", versionName = "test", versionCode = 1
        )
        val payload = FullBackupArchive.decode(bytes.inputStream())
        assertEquals(setOf("enhanced/local.json"), FullBackupRestorePlan.filesToDelete(
            payload, FullBackupRestoreMode.REPLACE, setOf("v1/apps.json", "enhanced/local.json")
        ))
        assertEquals(setOf("app_theme"), FullBackupRestorePlan.preferencesToRemove(
            payload, FullBackupRestoreMode.REPLACE, setOf("app_theme")
        ))
    }

    @Test
    fun metadataLessV2ArchivePreservesUnknownPreferenceScope() {
        val archive = FullBackupArchive.encode(
            files, mapOf("app_theme" to "theme_dark"), "com.aistra.hail.enhanced", "test", 1,
            FullBackupSelection(preferenceKeys = setOf("app_theme"))
        )
        val legacy = rewriteManifest(archive) { it.remove("selection") }
        val payload = FullBackupArchive.decode(legacy.inputStream())
        assertEquals(2, payload.schemaVersion)
        assertTrue(FullBackupRestorePlan.preferencesToRemove(
            payload, FullBackupRestoreMode.REPLACE, setOf("app_theme", "home_font_size")
        ).isEmpty())
    }

    @Test
    fun malformedPreferenceScopeIsRejectedRatherThanTreatedAsAllPreferences() {
        val archive = FullBackupArchive.encode(
            files, mapOf("app_theme" to "theme_dark"), "com.aistra.hail.enhanced", "test", 1
        )
        val malformed = rewriteManifest(archive) {
            it.getJSONObject("selection").put("preferenceKeys", "app_theme")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullBackupArchive.decode(malformed.inputStream())
        }
    }

    @Test
    fun unsupportedManifestVersionIsRejected() {
        val archive = rawArchive(
            manifestVersion = 99,
            entries = listOf(
                "files/v1/tags.json" to tagsJson,
                "files/v1/apps.json" to appsJson
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            FullBackupArchive.decode(ByteArrayInputStream(archive))
        }
    }

    private fun rewriteManifest(archive: ByteArray, change: (JSONObject) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        ZipInputStream(archive.inputStream()).use { input ->
            ZipOutputStream(output).use { zip ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "manifest.json") {
                        val manifest = JSONObject(bytes.toString(StandardCharsets.UTF_8))
                        change(manifest)
                        zip.write(manifest.toString().toByteArray(StandardCharsets.UTF_8))
                    } else zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private fun rawArchive(
        entries: List<Pair<String, String>>,
        manifestVersion: Int = 1,
        preferences: String = """{"schemaVersion":1,"entries":[]}"""
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val manifest = JSONObject()
                .put("format", "com.aistra.hail.full-backup")
                .put("schemaVersion", manifestVersion)
                .put("applicationId", "com.example.legacy")
                .put("versionName", "legacy")
                .put("createdAtEpochMs", 1L)
                .put("fileCount", entries.size)
                .put("preferenceCount", JSONObject(preferences).getJSONArray("entries").length())
            zip.writeEntry("manifest.json", manifest.toString())
            zip.writeEntry("preferences.json", preferences)
            entries.forEach { (name, value) -> zip.writeEntry(name, value) }
        }
        return output.toByteArray()
    }

    private fun ZipOutputStream.writeEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }
}
