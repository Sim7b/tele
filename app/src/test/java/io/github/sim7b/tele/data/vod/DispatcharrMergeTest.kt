package io.github.sim7b.tele.data.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DispatcharrMergeTest {

    private fun entry(id: String, name: String, kind: VodKind = VodKind.MOVIE) =
        VodEntry(sourceId = "s", kind = kind, providerId = id, name = name, title = name, year = null)

    @Test
    fun `added titles complete the catalog and replace the same ones`() {
        val cached = listOf(entry("1", "A"), entry("2", "B"), entry("2", "Series B", VodKind.SERIES))
        val merged = DispatcharrVod.merge(cached, listOf(entry("2", "B (new)"), entry("3", "C")))
        assertEquals(listOf("A", "Series B", "B (new)", "C"), merged.map { it.name })
        assertSame(cached, DispatcharrVod.merge(cached, emptyList()))
    }
}
