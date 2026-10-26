package dev.quinnjr.android_drawer.settings

enum class Side { LEFT, RIGHT }

/** Identifies a launcher activity in a specific user profile. */
data class AppKey(val component: String, val userSerial: Long) {
    fun encode(): String = "$userSerial|$component"

    companion object {
        fun decode(s: String): AppKey? {
            val sep = s.indexOf('|')
            if (sep <= 0) return null
            val serial = s.substring(0, sep).toLongOrNull() ?: return null
            val component = s.substring(sep + 1)
            // flattenToString() form is always "package/class".
            if (!component.contains('/') || component.startsWith('/') || component.endsWith('/')) return null
            return AppKey(component, serial)
        }
    }
}

data class DrawerSettings(
    val enabled: Boolean = true,
    val side: Side = Side.RIGHT,
    val yFraction: Float = 0.35f,
    val handleHeightDp: Int = 120,
    val handleAlpha: Float = 0.6f,
    val handleColor: Long = 0xFFB0B0B0,
    val apps: List<AppKey> = emptyList(),
)

internal fun encodeApps(apps: List<AppKey>): String = apps.joinToString("\n") { it.encode() }

internal fun decodeApps(s: String): List<AppKey> = s.lineSequence().mapNotNull(AppKey::decode).toList()
