package dev.thoremutuner.core.preset

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Serializable schema of `presets/<emulator>.json` (PLAN section 5). */
@Serializable
data class EmulatorDef(
    val schemaVersion: Int,
    val emulatorId: String,
    val name: String,
    val systems: List<String>,
    val supportLevel: SupportLevel,
    val packages: List<PackageDef>,
    val launch: LaunchDef,
    val alternateLaunch: LaunchDef? = null,
    val configTarget: ConfigTargetDef,
    val settings: List<SettingDef> = emptyList(),
    val presets: List<PresetDef> = emptyList(),
    val gameSpecific: List<GameSpecificDef> = emptyList(),
    val manualNotes: List<String> = emptyList(),
    val globalRecommendations: List<PresetValue> = emptyList(),
) {
    val isFull: Boolean get() = supportLevel == SupportLevel.FULL

    fun setting(section: String, key: String): SettingDef? =
        settings.firstOrNull { it.section == section && it.key == key }

    fun preset(id: String): PresetDef? = presets.firstOrNull { it.id == id }

    /** Distinct package names in preference order (a package may appear with several activities). */
    val distinctPackageNames: List<String> get() = packages.map { it.packageName }.distinct()

    /** Ordered (package, activity) launch targets, deduplicated. */
    fun launchTargets(spec: LaunchDef = launch): List<LaunchTarget> =
        packages.map { p ->
            val activity = if (spec.activity == PLACEHOLDER_PACKAGE_ACTIVITY) p.activity ?: "" else spec.activity
            LaunchTarget(p.packageName, activity, p.label)
        }.filter { it.activity.isNotEmpty() }.distinctBy { it.packageName to it.activity }

    companion object {
        const val PLACEHOLDER_PACKAGE_ACTIVITY = "{packageActivity}"
    }
}

data class LaunchTarget(val packageName: String, val activity: String, val label: String)

@Serializable
enum class SupportLevel {
    @SerialName("full") FULL,
    @SerialName("reference") REFERENCE,
}

@Serializable
data class PackageDef(
    val packageName: String,
    val activity: String? = null,
    val label: String,
    val source: String,
)

@Serializable
data class LaunchDef(
    val packageName: String,
    val activity: String,
    val action: String? = null,
    val categories: List<String> = emptyList(),
    val data: String? = null,
    val mimeType: String? = null,
    val extras: List<ExtraDef> = emptyList(),
    val flags: List<LaunchFlag> = emptyList(),
    val source: String? = null,
    val notes: String? = null,
)

@Serializable
data class ExtraDef(val name: String, val type: ExtraType, val value: String)

@Serializable
enum class ExtraType {
    @SerialName("string") STRING,
    @SerialName("bool") BOOL,
    @SerialName("int") INT,
    @SerialName("stringArray") STRING_ARRAY,
}

@Serializable
enum class LaunchFlag { NEW_TASK, CLEAR_TASK, CLEAR_TOP, GRANT_READ_URI }

@Serializable
data class ConfigTargetDef(
    val mode: ConfigMode,
    val folderToGrant: String? = null,
    val folderValidation: List<String> = emptyList(),
    val pathTemplate: String? = null,
    val gameIdKind: String? = null,
    val iniStyle: IniStyle? = null,
    val source: String? = null,
    val notes: String? = null,
    val cores: List<CoreDef> = emptyList(),
)

@Serializable
enum class ConfigMode {
    @SerialName("perGameIni") PER_GAME_INI,
    @SerialName("intentInlineIni") INTENT_INLINE_INI,
    @SerialName("globalIniMerge") GLOBAL_INI_MERGE,
    @SerialName("retroarchOverride") RETROARCH_OVERRIDE,
    @SerialName("manual") MANUAL,
}

@Serializable
data class IniStyle(
    val keyValueSeparator: String,
    val boolTrue: String,
    val boolFalse: String,
    val quoteValues: Boolean = false,
)

@Serializable
data class CoreDef(
    val coreFile: String,
    val libraryName: String,
    val systems: List<String>,
    val source: String,
)

@Serializable
enum class SettingType {
    @SerialName("enum") ENUM,
    @SerialName("bool") BOOL,
    @SerialName("int") INT,
    @SerialName("float") FLOAT,
    @SerialName("string") STRING,
}

@Serializable
enum class Impact {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW,
}

@Serializable
data class OptionDef(val value: String, val label: String)

/** One entry of the Tweak-screen catalog. */
@Serializable
data class SettingDef(
    val section: String,
    val key: String,
    val label: String,
    val type: SettingType,
    val group: String,
    val impact: Impact,
    val options: List<OptionDef> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    @SerialName("default") val defaultValue: String? = null,
    val advanced: Boolean = false,
    val description: String,
    val keySource: String,
) {
    fun optionLabel(value: String): String? = options.firstOrNull { it.value == value }?.label
}

/** Evidence classes, weakest last. The UI badge never says "tested" for [INFERRED]. */
@Serializable
enum class Evidence(val badge: String) {
    @SerialName("thor") THOR("Thor-tested"),
    @SerialName("odin2") ODIN2("Odin 2 (same chip)"),
    @SerialName("sd8g2-general") SD8G2_GENERAL("SD 8 Gen 2 general"),
    @SerialName("inferred") INFERRED("Starting guess (unverified)"),
}

@Serializable
data class PresetDef(
    val id: String,
    val name: String,
    val evidence: Evidence,
    val description: String,
    val values: List<PresetValue>,
)

@Serializable
data class PresetValue(
    val section: String,
    val key: String,
    val value: String,
    val description: String,
    val source: String,
    val reason: String? = null,
    val keySource: String? = null,
    val uiPath: String? = null,
    val uiValue: String? = null,
) {
    val isInferred: Boolean get() = source == SOURCE_INFERRED

    companion object {
        const val SOURCE_INFERRED = "inferred"
    }
}

@Serializable
data class GameSpecificDef(
    val gameId: String? = null,
    val gameIdPrefix: String? = null,
    val title: String,
    val appliedBy: String,
    val condition: String? = null,
    val note: String? = null,
    val values: List<GameSpecificValue>,
) {
    fun matches(id: String?): Boolean {
        if (id.isNullOrEmpty()) return false
        val upper = id.uppercase()
        return (gameId != null && gameId.equals(upper, ignoreCase = true)) ||
            (gameIdPrefix != null && upper.startsWith(gameIdPrefix.uppercase()))
    }
}

@Serializable
data class GameSpecificValue(
    val section: String,
    val key: String,
    val value: String,
    val description: String,
    val source: String,
)
