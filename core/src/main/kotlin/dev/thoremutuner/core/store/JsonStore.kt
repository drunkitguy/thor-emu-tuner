package dev.thoremutuner.core.store

/**
 * Minimal file store addressed by relative paths such as `profiles/<gameKey>.json`. The app
 * implements it under `filesDir/tuner` with atomic tmp+rename writes; tests use [InMemoryJsonStore].
 */
interface JsonStore {
    fun read(path: String): String?
    fun write(path: String, text: String)
    fun delete(path: String): Boolean
    /** Names (not paths) of the entries directly inside [dir]. */
    fun list(dir: String): List<String>
}

class InMemoryJsonStore : JsonStore {
    private val files = linkedMapOf<String, String>()
    val paths: Set<String> get() = files.keys

    @Synchronized override fun read(path: String): String? = files[path]
    @Synchronized override fun write(path: String, text: String) { files[path] = text }
    @Synchronized override fun delete(path: String): Boolean = files.remove(path) != null
    @Synchronized override fun list(dir: String): List<String> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        return files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
    }
}
