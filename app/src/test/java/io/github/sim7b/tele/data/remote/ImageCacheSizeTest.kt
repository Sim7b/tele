package io.github.sim7b.tele.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCacheSizeTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test
    fun `the cache takes a share of what is free beyond the reserve, up to its limit`() {
        // 13 GB free: a tenth of the 10 GB beyond the reserve
        assertEquals(gb, ImageCacheSize.bytes(freeBytes = 13 * gb, cacheBytes = 0))
        assertEquals(ImageCacheSize.MAX_BYTES, ImageCacheSize.bytes(freeBytes = 100 * gb, cacheBytes = 0))
        assertEquals(200 * mb, ImageCacheSize.bytes(freeBytes = 3 * gb + 2000 * mb, cacheBytes = 0))
    }

    @Test
    fun `short of room the cache shrinks to its minimum, never saturating the device`() {
        assertEquals(ImageCacheSize.MIN_BYTES, ImageCacheSize.bytes(freeBytes = 2 * gb, cacheBytes = 0))
        assertEquals(ImageCacheSize.MIN_BYTES, ImageCacheSize.bytes(freeBytes = 0, cacheBytes = 0))
        assertEquals(ImageCacheSize.MIN_BYTES, ImageCacheSize.bytes(freeBytes = -1, cacheBytes = -1))
    }

    @Test
    fun `what the cache already holds counts as available, it can give it back`() {
        // 3.5 GB free and 500 MB of images: 1 GB beyond the reserve, a tenth of it
        assertEquals(ImageCacheSize.bytes(freeBytes = 4 * gb, cacheBytes = 0), ImageCacheSize.bytes(freeBytes = 3 * gb + 512 * mb, cacheBytes = 512 * mb))
    }
}
