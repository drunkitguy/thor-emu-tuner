package dev.thoremutuner.app.emu

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import dev.thoremutuner.core.launch.IntentSpec
import dev.thoremutuner.core.preset.ExtraType
import dev.thoremutuner.core.preset.LaunchFlag

/** Maps a platform-independent [IntentSpec] to an explicit Android [Intent] (PLAN section 8). */
object IntentFactory {
    fun build(spec: IntentSpec): Intent {
        val intent = Intent()
        intent.setClassName(spec.packageName, spec.className)
        spec.action?.let { intent.action = it }
        spec.categories.forEach { intent.addCategory(it) }
        val data = spec.data?.let { Uri.parse(it) }
        when {
            data != null && spec.mimeType != null -> intent.setDataAndType(data, spec.mimeType)
            data != null -> intent.data = data
            spec.mimeType != null -> intent.type = spec.mimeType
        }
        for (e in spec.extras) {
            when (e.type) {
                ExtraType.STRING -> intent.putExtra(e.name, e.value)
                ExtraType.BOOL -> intent.putExtra(e.name, e.value.trim().equals("true", ignoreCase = true))
                ExtraType.INT -> intent.putExtra(e.name, e.value.trim().toInt())
                ExtraType.STRING_ARRAY -> intent.putExtra(e.name, e.values.toTypedArray())
            }
        }
        var flags = Intent.FLAG_ACTIVITY_NEW_TASK
        for (f in spec.flags) {
            flags = flags or when (f) {
                LaunchFlag.NEW_TASK -> Intent.FLAG_ACTIVITY_NEW_TASK
                LaunchFlag.CLEAR_TASK -> Intent.FLAG_ACTIVITY_CLEAR_TASK
                LaunchFlag.CLEAR_TOP -> Intent.FLAG_ACTIVITY_CLEAR_TOP
                LaunchFlag.GRANT_READ_URI -> Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
        }
        intent.addFlags(flags)
        // Data is null but an extra carries the ROM URI: ClipData makes the read grant apply to it.
        spec.clipDataUri?.let { intent.clipData = ClipData.newRawUri("", Uri.parse(it)) }
        return intent
    }
}
