package io.github.sim7b.tele.data.remote

/**
 * Size of the disk cache of the images, worked out at each start from the storage left: a share of what is free
 * beyond [RESERVE_BYTES] (kept for the system, the other apps and the live recordings), never past [MAX_BYTES].
 * Short of room it shrinks down to [MIN_BYTES], and its oldest images go at once.
 */
object ImageCacheSize {

    /** Left free on the device, whatever the cache. */
    const val RESERVE_BYTES = 3L * 1024 * 1024 * 1024
    const val MIN_BYTES = 20L * 1024 * 1024
    const val MAX_BYTES = 1024L * 1024 * 1024
    private const val SHARE = 0.10

    /**
     * Bytes the cache may hold, with [freeBytes] free on its storage and [cacheBytes] already used by it (counted
     * as available: the cache can give them back).
     */
    fun bytes(freeBytes: Long, cacheBytes: Long): Long {
        val available = freeBytes.coerceAtLeast(0) + cacheBytes.coerceAtLeast(0) - RESERVE_BYTES
        return (available * SHARE).toLong().coerceIn(MIN_BYTES, MAX_BYTES)
    }
}
