package io.github.sim7b.tele.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CategoriesTest {

    private fun channel(id: String, group: String, source: String) =
        Channel(id = id, name = id, group = group, streamUrl = "http://x/$id", sourceId = source)

    private val channels = listOf(
        channel("a", "Sport", "neo"),
        channel("b", "FR| SPORTS", "cheaply"),
        channel("c", "Sport", "cheaply"),
        channel("d", "News", "neo"),
        channel("e", "", "neo"),
        channel("f", "", "cheaply")
    )
    private val sources = mapOf("neo" to "Neo", "cheaply" to "Cheaply")

    private fun groups(renames: Map<String, String>, mode: CategoryMode) =
        Categories.apply(channels, renames, mode, sources).map { it.group }

    @Test
    fun `merge mode keeps same names together and renames can merge`() {
        assertEquals(listOf("Sport", "FR| SPORTS", "Sport", "News", "", ""), groups(emptyMap(), CategoryMode.MERGE))
        assertEquals(listOf("Sport", "Sport", "Sport", "Actus", "", ""), groups(mapOf("FR| SPORTS" to "Sport", "News" to "Actus"), CategoryMode.MERGE))
    }

    @Test
    fun `separate mode adds the source name only to shared names`() {
        assertEquals(
            listOf("Sport · Neo", "FR| SPORTS", "Sport · Cheaply", "News", "", ""),
            groups(emptyMap(), CategoryMode.SEPARATE)
        )
        assertEquals(
            listOf("Sport · Neo", "Sport · Cheaply", "Sport · Cheaply", "News", "", ""),
            groups(mapOf("FR| SPORTS" to "Sport"), CategoryMode.SEPARATE)
        )
    }

    @Test
    fun `finds the source groups behind a displayed category`() {
        val renames = mapOf("FR| SPORTS" to "Sport")
        assertEquals(setOf("Sport", "FR| SPORTS"), Categories.sourceGroupsOf("Sport", channels, renames, CategoryMode.MERGE, sources))
        assertEquals(setOf("FR| SPORTS", "Sport"), Categories.sourceGroupsOf("Sport · Cheaply", channels, renames, CategoryMode.SEPARATE, sources))
        assertEquals(setOf("Sport"), Categories.sourceGroupsOf("Sport · Neo", channels, renames, CategoryMode.SEPARATE, sources))
    }

    @Test
    fun `orders and moves categories`() {
        val categories = listOf("TNT", "Sport", "Cinéma", "Info")
        assertEquals(listOf("Info", "Sport", "TNT", "Cinéma"), Categories.ordered(categories, listOf("Info", "Sport", "Removed")))
        assertEquals(categories, Categories.ordered(categories, emptyList()))
        assertEquals(listOf("TNT", "Cinéma", "Sport", "Info"), Categories.moved(categories, "Sport", +1))
        assertEquals(listOf("Sport", "TNT", "Cinéma", "Info"), Categories.moved(categories, "Sport", -1))
        assertEquals(categories, Categories.moved(categories, "TNT", -1))
    }

    @Test
    fun `nothing to do returns the same list`() {
        assertSame(channels, Categories.apply(channels, emptyMap(), CategoryMode.MERGE, sources))
    }
}
