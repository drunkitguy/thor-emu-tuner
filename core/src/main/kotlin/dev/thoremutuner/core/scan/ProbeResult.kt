package dev.thoremutuner.core.scan

import dev.thoremutuner.core.model.DetectedId
import dev.thoremutuner.core.model.SystemId

/** What a header probe found: the system (if the header proves it), the id, and a header title. */
data class ProbeResult(
    val system: SystemId?,
    val id: DetectedId?,
    val headerTitle: String? = null,
)
