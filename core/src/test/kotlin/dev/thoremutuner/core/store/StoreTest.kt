package dev.thoremutuner.core.store

import dev.thoremutuner.core.bench.IssueLevel
import dev.thoremutuner.core.bench.Outcome
import dev.thoremutuner.core.bench.Sample
import dev.thoremutuner.core.bench.SessionResult
import dev.thoremutuner.core.bench.TestSession
import dev.thoremutuner.core.model.DetectedId
import dev.thoremutuner.core.model.DetectionMethod
import dev.thoremutuner.core.model.Game
import dev.thoremutuner.core.model.IdKind
import dev.thoremutuner.core.model.SystemId
import dev.thoremutuner.core.profile.ProfileService
import dev.thoremutuner.core.profile.SettingValue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreTest {
    private val game = Game(
        key = "0123456789abcdef", title = "Synthetic", fileName = "Synthetic (USA) [ULUS-10041].iso", system = SystemId.PSP,
        treeUri = "content://com.android.externalstorage.documents/tree/primary%3AROMs",
        documentId = "primary:ROMs/psp/Synthetic (USA) [ULUS-10041].iso",
        sizeBytes = 1, lastModified = 2, id = DetectedId("ULUS10041", IdKind.PSP_DISC_ID, DetectionMethod.HEADER),
    )

    @Test fun settingsLibraryProfilesSessionsRoundTrip() = runBlocking {
        val store = InMemoryJsonStore()
        SettingsRepository(store).update { it.copy(onboardingDone = true, romFolders = listOf(FolderGrant("content://t", "primary:ROMs", "ROMs"))) }
        LibraryRepository(store).save(Library(games = listOf(game), lastScanAt = 5))
        ProfileRepository(store).update(game.key) { ProfileService.addRevision(it, game.key, "ppsspp", "thor-balanced", listOf(SettingValue("Graphics", "InternalResolution", "4")), "Baseline", 10).first }
        val session = TestSession(id = "s1", gameKey = game.key, emulatorId = "ppsspp", packageName = "org.ppsspp.ppsspp", emulatorVersion = "1.19", rev = 1,
            startedAt = 100, endedAt = 700, plannedDurationSec = 600, samples = listOf(Sample(0, -1_000_000, 4000)),
            result = SessionResult(60.0, 60.0, null, 1, IssueLevel.NONE, IssueLevel.NONE, Outcome.PASS))
        SessionRepository(store).upsert(session)

        // Fresh repositories read everything back from the store.
        assertTrue(SettingsRepository(store).get().onboardingDone)
        assertEquals(listOf(game), LibraryRepository(store).get().games)
        assertEquals(1, ProfileRepository(store).get(game.key).single().revisions.single().rev)
        assertEquals(listOf(session), SessionRepository(store).get(game.key))
        assertEquals(listOf(session), SessionRepository(store).all())
        assertTrue(store.read("settings.json")!!.contains("\"schemaVersion\": 1"))
    }

    @Test fun revisionsAreImmutableAndIncrement() {
        var profiles = emptyList<dev.thoremutuner.core.profile.GameProfile>()
        profiles = ProfileService.addRevision(profiles, "k", "ppsspp", "thor-balanced", emptyList(), "a", 1).first
        profiles = ProfileService.addRevision(profiles, "k", "ppsspp", null, emptyList(), "b", 2).first
        val (p3, rev3) = ProfileService.addRevision(profiles, "k", "ppsspp", null, emptyList(), "c", 3)
        assertEquals(3, rev3.rev)
        assertEquals(listOf(1, 2, 3), p3.single().revisions.map { it.rev })
        val applied = ProfileService.markApplied(p3, "ppsspp", 2, 99, "1.19")
        assertEquals(99L, applied.single().revision(2)!!.appliedAt)
        assertEquals("1.19", applied.single().revision(2)!!.appliedEmulatorVersion)
        assertEquals(null, applied.single().revision(1)!!.appliedAt)
        // A second emulator gets its own revision sequence.
        val (_, other) = ProfileService.addRevision(applied, "k", "retroarch", null, emptyList(), "x", 4)
        assertEquals(1, other.rev)
    }

    @Test fun exportContainsNoLocationData() {
        val bundle = Exporter.bundle(listOf(game), emptyList(), emptyList(), "0.1.0", 1)
        val json = Exporter.toJson(bundle)
        assertFalse("content://" in json)
        assertFalse("primary:" in json)
        assertFalse(game.fileName in json)
        assertFalse("treeUri" in json || "documentId" in json)
        assertTrue("ULUS10041" in json && "Synthetic" in json)
    }

    @Test fun corruptFilesFallBackAndNewerSchemasAreRefused() = runBlocking {
        val store = InMemoryJsonStore()
        store.write("settings.json", "{ not json")
        assertEquals(AppSettings(), SettingsRepository(store).get())
        assertTrue("settings.json.corrupt" in store.paths)
        store.write("library.json", "{\"schemaVersion\": 99}")
        assertFailsWith<NewerSchemaException> { LibraryRepository(store).get() }
    }

    @Test fun storageCheckFindsNewerSchemas() {
        val store = InMemoryJsonStore()
        store.write("settings.json", "{\"schemaVersion\": 1}")
        store.write("profiles/0123456789abcdef.json", "{\"schemaVersion\": 2, \"profiles\": []}")
        store.write("sessions/0123456789abcdef.json", "{\"schemaVersion\": 1}")
        assertEquals(listOf("profiles/0123456789abcdef.json"), StorageCheck.newerSchemaFiles(store))
        assertTrue(StorageCheck.newerSchemaFiles(InMemoryJsonStore()).isEmpty())
    }

    @Test fun sessionSummaryIndexTracksCountAndLastOutcome() = runBlocking {
        val store = InMemoryJsonStore()
        val repo = SessionRepository(store)
        fun s(id: String, at: Long, o: Outcome) = TestSession(id = id, gameKey = game.key, emulatorId = "ppsspp", packageName = null,
            emulatorVersion = null, rev = 1, startedAt = at, plannedDurationSec = 60,
            result = SessionResult(30.0, 30.0, null, 1, IssueLevel.NONE, IssueLevel.NONE, o))
        repo.upsert(s("a", 1, Outcome.PASS))
        repo.upsert(s("b", 2, Outcome.FAIL))
        assertEquals(SessionSummary(2, 2, Outcome.FAIL), repo.summaries()[game.key])
        repo.delete(game.key, "b")
        assertEquals(SessionSummary(1, 1, Outcome.PASS), repo.summaries()[game.key])
        repo.delete(game.key, "a")
        assertEquals(null, repo.summaries()[game.key])
        // A missing index (older data) is rebuilt from the session files once.
        repo.upsert(s("c", 3, Outcome.CRASH))
        store.delete(SessionRepository.INDEX)
        assertEquals(SessionSummary(1, 3, Outcome.CRASH), SessionRepository(store).summaries()[game.key])
        assertTrue(store.read(SessionRepository.INDEX) != null)
    }

    @Test fun sessionIndexIsNeverMistakenForAGameKey() = runBlocking {
        val store = InMemoryJsonStore()
        val repo = SessionRepository(store)
        repo.upsert(TestSession(id = "a", gameKey = game.key, emulatorId = "ppsspp", packageName = null, emulatorVersion = null,
            rev = 1, startedAt = 1, plannedDurationSec = 60))
        // The index lives outside sessions/, and stray non-key files there are ignored.
        assertFalse(SessionRepository.INDEX.startsWith("sessions/"))
        store.write("sessions/notes.json", "{}")
        store.write("sessions/session_index.json", "{\"schemaVersion\": 1}")
        assertEquals(listOf("a"), repo.all().map { it.id })
        store.delete(SessionRepository.INDEX)
        assertEquals(setOf(game.key), repo.summaries().keys)
        assertTrue(StorageCheck.newerSchemaFiles(store).isEmpty())
    }

    @Test fun appliedPathIsRecorded() {
        val (p, _) = ProfileService.addRevision(emptyList(), "k", "retroarch", null, emptyList(), "a", 1)
        val applied = ProfileService.markApplied(p, "retroarch", 1, 5, "1.19", "config/Snes9x/Game.cfg")
        assertEquals("config/Snes9x/Game.cfg", applied.single().revision(1)!!.appliedPath)
    }

    @Test fun gameKeysAreValidatedForPaths() {
        assertFailsWith<IllegalArgumentException> { ProfileRepository.path("../../etc") }
        assertEquals("sessions/0123456789abcdef.json", SessionRepository.path("0123456789abcdef"))
    }

    @Test fun backupRetention() {
        val stamps = (1..25).map { "202609%02d-120000".format(it) }
        val del = BackupPolicy.toDelete(stamps, protectFirst = false)
        assertEquals(5, del.size)
        assertEquals(stamps.take(5), del)
        val delAzahar = BackupPolicy.toDelete(stamps, protectFirst = true)
        assertFalse(stamps.first() in delAzahar, "first-ever Azahar backup is never deleted")
        assertEquals(4, delAzahar.size)
        assertTrue(BackupPolicy.toDelete(stamps.take(20), false).isEmpty())
        assertEquals("20260929-120000", BackupPolicy.stamp(java.time.Instant.parse("2026-09-29T12:00:00Z").toEpochMilli()))
    }
}
