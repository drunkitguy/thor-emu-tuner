package dev.thoremutuner.core.store

import kotlinx.serialization.json.Json

/** kotlinx.serialization configuration shared by preset loading and all stored files. */
object ThorJson {
    /** Lenient reader for bundled preset JSON (unknown keys tolerated so newer presets still load). */
    val presets: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    /** Storage format: human-readable, stable, forward compatible. */
    val store: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        explicitNulls = false
    }
}
