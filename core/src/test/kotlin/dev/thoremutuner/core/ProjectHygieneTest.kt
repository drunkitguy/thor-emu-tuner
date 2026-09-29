package dev.thoremutuner.core

import dev.thoremutuner.core.preset.PresetRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Structural checks that run without the Android SDK (PLAN section 15, items 2 and 4). */
class ProjectHygieneTest {

    @Test
    fun coreHasNoAndroidImports() {
        val offenders = TestFiles.file("core/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { it.trimStart().startsWith("import android.") || it.trimStart().startsWith("import androidx.") } }
            .map { it.name }.toList()
        assertTrue(offenders.isEmpty(), "Android imports in :core: $offenders")
    }

    private val manifest: String by lazy { TestFiles.file("app/src/main/AndroidManifest.xml").readText() }

    @Test
    fun manifestQueriesListEveryPresetPackageOnce() {
        val queries = Regex("<queries>(.*?)</queries>", RegexOption.DOT_MATCHES_ALL).find(manifest)?.groupValues?.get(1)
            ?: fail("no <queries> block")
        val declared = Regex("<package\\s+android:name=\"([^\"]+)\"").findAll(queries).map { it.groupValues[1] }.toList()
        val missing = PresetRepository().allPackageNames.filterNot { it in declared }
        assertTrue(missing.isEmpty(), "packages missing from <queries>: $missing")
        assertEquals(declared.size, declared.toSet().size, "duplicate <queries> entries")
    }

    @Test
    fun manifestRequestsNoForbiddenPermissions() {
        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.QUERY_ALL_PACKAGES",
        )
        val requested = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertTrue(forbidden.none { it in requested }, "forbidden permission requested: ${requested intersect forbidden.toSet()}")
        assertTrue("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in requested)
    }

    @Test
    fun bundledSourcesAssetMatchesDocs() {
        val docs = TestFiles.file("docs/SOURCES.md").readText()
        val asset = TestFiles.file("app/src/main/assets/SOURCES.md").readText()
        assertEquals(docs, asset, "app/src/main/assets/SOURCES.md must be a copy of docs/SOURCES.md")
    }

    @Test
    fun rootAgpCoordinatesMatchVersionCatalog() {
        val catalog = TestFiles.file("gradle/libs.versions.toml").readText()
        val agp = Regex("(?m)^agp = \"([^\"]+)\"").find(catalog)!!.groupValues[1]
        val root = TestFiles.file("build.gradle.kts").readText()
        assertTrue("com.android.tools.build:gradle:$agp" in root, "root build.gradle.kts must use AGP $agp")
    }

    @Test
    fun thirdPartyFixturesAreLicensedSeparately() {
        val dir = TestFiles.file("core/src/test/resources/fixtures")
        val notice = TestFiles.file("core/src/test/resources/fixtures/NOTICE.md").readText()
        assertTrue("NOT covered by this repository's MIT license" in notice)
        for (spdx in listOf("GPL-2.0-or-later", "GPL-3.0-or-later")) assertTrue(spdx in notice, spdx)
        for (f in listOf("LICENSE-GPL-2.0.txt", "LICENSE-GPL-3.0.txt")) {
            val text = java.io.File(dir, f).readText()
            assertTrue("GNU GENERAL PUBLIC LICENSE" in text, f)
        }
        assertTrue("core/src/test/resources/fixtures" in TestFiles.file("LICENSE").readText())
    }

    @Test
    fun appBuildPinsSdkLevels() {
        val gradle = TestFiles.file("app/build.gradle.kts").readText()
        assertTrue(Regex("compileSdk\\s*=\\s*35").containsMatchIn(gradle))
        assertTrue(Regex("targetSdk\\s*=\\s*35").containsMatchIn(gradle))
        assertTrue(Regex("minSdk\\s*=\\s*30").containsMatchIn(gradle))
    }
}
