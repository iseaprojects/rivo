package io.github.iseaprojects.rivo.core.ui

/**
 * Compact numbers the way yt-x's preview prints them: `1.2K`, `3.4M`, `1.1B`.
 *
 * YouTube returns raw counts; a phone-width detail line needs the compact form, and
 * rounding at one decimal keeps `999_999` from reading as `1000K`.
 */
object CountFormat {

    fun compact(value: Long?): String {
        if (value == null || value < 0) return ""
        return when {
            value < 1_000 -> value.toString()
            value < 999_500 -> decimal(value / 1_000.0) + "K"
            value < 999_500_000 -> decimal(value / 1_000_000.0) + "M"
            else -> decimal(value / 1_000_000_000.0) + "B"
        }
    }

    /**
     * File sizes with the units Android's own download UI uses (1000-based). Zero and
     * negative mean "the server did not say", which is better left blank than shown as
     * "0 MB".
     *
     * The unit is chosen after rounding, not before: a 999 999-byte file used to be divided
     * once (999.999 kB) and then rounded up to "1000 kB" instead of "1 MB".
     */
    fun bytes(value: Long?): String {
        if (value == null || value <= 0) return ""
        val units = listOf("B", "kB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unit = 0
        while (unit < units.lastIndex && Math.round(size * 10.0) >= 10_000L) {
            size /= 1000
            unit++
        }
        val shown = if (unit == 0) size.toLong().toString() else decimal(size)
        return "$shown ${units[unit]}"
    }

    private fun decimal(value: Double): String {
        val tenths = Math.round(value * 10.0)
        return if (tenths % 10L == 0L) {
            (tenths / 10L).toString()
        } else {
            (tenths / 10.0).toString()
        }
    }
}
