package io.github.sim7b.tele.data.model

/** Ultra light mode setting: by itself on small devices, always, or never. */
enum class UltraLightMode {
    AUTO, ON, OFF;

    /** The next value, for a setting row that goes through them. */
    fun next(): UltraLightMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(name: String?): UltraLightMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * Ultra light mode, for the devices short of memory and power: the classic home, no artwork (the logos stay),
 * no fades, no preview on the home screen, no timeshift on the storage, a small buffer, and no work in the
 * background (TMDB index, Google TV home row, sport, resume on another TV, program artwork lookups).
 * [active] is read by the parts outside the screens (repositories, player).
 */
object UltraLight {

    @Volatile
    var active: Boolean = false

    // A TV box with less memory than this, or an app heap this small, is a small device
    const val LOW_END_TOTAL_BYTES = 1_600L * 1024 * 1024
    const val LOW_END_HEAP_MB = 128

    /** Whether the device is a small one: declared low on memory, little memory in all, or a small app heap. */
    fun isLowEnd(totalMemoryBytes: Long, memoryClassMb: Int, lowRamDevice: Boolean): Boolean =
        lowRamDevice || totalMemoryBytes in 1 until LOW_END_TOTAL_BYTES || memoryClassMb in 1..LOW_END_HEAP_MB

    fun isActive(mode: UltraLightMode, lowEnd: Boolean): Boolean = mode == UltraLightMode.ON || (mode == UltraLightMode.AUTO && lowEnd)
}
