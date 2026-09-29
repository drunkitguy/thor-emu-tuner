package dev.thoremutuner.app.data

import dev.thoremutuner.core.store.JsonStore
import java.io.File
import java.io.IOException

/**
 * [JsonStore] under `filesDir/tuner`. Writes are atomic: `name.tmp` is written, then renamed over
 * the target (PLAN section 10).
 */
class FileJsonStore(private val root: File) : JsonStore {

    override fun read(path: String): String? {
        val f = file(path)
        return if (f.isFile) f.readText(Charsets.UTF_8) else null
    }

    @Synchronized
    override fun write(path: String, text: String) {
        val target = file(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not save ${target.name}")
        }
    }

    override fun delete(path: String): Boolean = file(path).delete()

    override fun list(dir: String): List<String> = file(dir).list()?.toList().orEmpty()

    private fun file(path: String): File {
        require(path.split('/').none { it == ".." }) { "invalid store path" }
        return File(root, path)
    }
}
