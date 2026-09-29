package dev.thoremutuner.core.preset

import dev.thoremutuner.core.model.SystemId
import dev.thoremutuner.core.store.ThorJson
import kotlinx.serialization.Serializable

@Serializable
private data class PresetIndex(val schemaVersion: Int, val files: List<String>)

/**
 * Loads `presets/index.json` and every emulator file it lists. [loader] maps a file name to its
 * text (null = missing); the default reads classpath resources, which also works on Android because
 * the :core jar's resources are packaged into the APK.
 */
class PresetRepository(
    private val loader: (String) -> String? = ::classpathLoader,
) {
    val emulators: List<EmulatorDef> by lazy { load() }

    val files: List<String> by lazy {
        val text = loader(INDEX) ?: error("presets/$INDEX missing")
        ThorJson.presets.decodeFromString(PresetIndex.serializer(), text).files
    }

    fun emulator(id: String): EmulatorDef? = emulators.firstOrNull { it.emulatorId == id }

    /** Emulators that support [system], FULL ones first, then JSON order. */
    fun emulatorsFor(system: SystemId): List<EmulatorDef> =
        emulators.filter { def -> def.systems.any { SystemId.fromId(it) == system } }
            .sortedBy { if (it.isFull) 0 else 1 }

    /** Every distinct package name across all presets (used for the manifest <queries> check). */
    val allPackageNames: List<String> get() = emulators.flatMap { it.distinctPackageNames }.distinct()

    private fun load(): List<EmulatorDef> = files.map { name ->
        val text = loader(name) ?: error("presets/$name listed in index but missing")
        try {
            ThorJson.presets.decodeFromString(EmulatorDef.serializer(), text)
        } catch (e: Exception) {
            throw IllegalStateException("presets/$name does not parse: ${e.message}", e)
        }
    }

    companion object {
        const val INDEX = "index.json"

        fun classpathLoader(name: String): String? =
            PresetRepository::class.java.classLoader
                ?.getResourceAsStream("presets/$name")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
