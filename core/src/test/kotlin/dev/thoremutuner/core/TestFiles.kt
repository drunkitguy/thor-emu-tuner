package dev.thoremutuner.core

import java.io.File

/** Locates files relative to the project root (works from Gradle and from an IDE). */
object TestFiles {
    val projectRoot: File by lazy {
        System.getProperty("thor.projectRoot")?.let { File(it) }?.takeIf { File(it, "settings.gradle.kts").exists() }
            ?: generateSequence(File("").absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").exists() }
    }

    fun file(relative: String): File = File(projectRoot, relative)

    fun resource(path: String): String =
        TestFiles::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("test resource $path missing")
}
