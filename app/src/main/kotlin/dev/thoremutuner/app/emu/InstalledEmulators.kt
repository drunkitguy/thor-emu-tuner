package dev.thoremutuner.app.emu

import android.content.ComponentName
import android.content.pm.PackageManager
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.preset.LaunchTarget

/** An installed (package, activity) launch target and the package's versionName. */
data class InstalledTarget(val target: LaunchTarget, val versionName: String?)

/**
 * Detects installed emulators among the preset packages. Visibility works because every preset
 * package is declared in the manifest's `<queries>` (no QUERY_ALL_PACKAGES).
 */
class InstalledEmulators(private val pm: PackageManager) {

    @Suppress("DEPRECATION")
    fun versionName(packageName: String): String? = try {
        pm.getPackageInfo(packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun isPackageInstalled(packageName: String): Boolean = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun activityExists(packageName: String, className: String): Boolean = try {
        pm.getActivityInfo(ComponentName(packageName, className), 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Installed targets in preset preference order ((package, activity) pairs, de-duplicated). */
    fun installedTargets(def: EmulatorDef): List<InstalledTarget> =
        def.launchTargets()
            .filter { isPackageInstalled(it.packageName) && activityExists(it.packageName, it.activity) }
            .map { InstalledTarget(it, versionName(it.packageName)) }

    /** Distinct installed package names of an emulator. */
    fun installedPackages(def: EmulatorDef): List<String> =
        def.distinctPackageNames.filter { isPackageInstalled(it) }

    /** The target to launch: the user's preferred package if installed, else the first installed one. */
    fun resolve(def: EmulatorDef, preferredPackage: String?): InstalledTarget? {
        val all = installedTargets(def)
        return all.firstOrNull { it.target.packageName == preferredPackage } ?: all.firstOrNull()
    }

    fun isInstalled(def: EmulatorDef): Boolean = installedTargets(def).isNotEmpty()
}
