package com.oroncal.semaphoreui.release

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val snapshot: Boolean,
) {
    fun propose(kind: ReleaseKind): SemanticVersion =
        when (kind) {
            ReleaseKind.FIX -> if (snapshot) copy(snapshot = false) else copy(patch = patch + 1, snapshot = false)
            ReleaseKind.MINOR -> copy(minor = minor + 1, patch = 0, snapshot = false)
            ReleaseKind.MAJOR -> copy(major = major + 1, minor = 0, patch = 0, snapshot = false)
        }

    override fun toString(): String = buildString {
        append(major)
        append('.')
        append(minor)
        append('.')
        append(patch)
        if (snapshot) {
            append("-SNAPSHOT")
        }
    }

    companion object {
        private val VERSION_PATTERN = Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?(-SNAPSHOT)?$""")

        fun parse(rawValue: String): SemanticVersion? {
            val match = VERSION_PATTERN.matchEntire(rawValue.trim()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
            val patch = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
            return SemanticVersion(
                major = major,
                minor = minor,
                patch = patch,
                snapshot = match.groupValues[4].isNotEmpty(),
            )
        }
    }
}
