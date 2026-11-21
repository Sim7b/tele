package io.github.sim7b.tele.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleAppearanceTest {

    @Test
    fun `the appearance is kept and broken values fall back to the default`() {
        val chosen = SubtitleAppearance(SubtitleSize.HUGE, SubtitleColor.YELLOW, SubtitleBackground.BOX, SubtitlePosition.RAISED)
        assertEquals(chosen, SubtitleAppearance.fromJson(chosen.toJson()))
        assertEquals(SubtitleAppearance(), SubtitleAppearance.fromJson(null))
        assertEquals(SubtitleAppearance(), SubtitleAppearance.fromJson("{broken"))
        assertEquals(
            SubtitleAppearance(color = SubtitleColor.CYAN),
            SubtitleAppearance.fromJson("""{"size": "GIANT", "color": "CYAN", "position": 3}""")
        )
    }

    @Test
    fun `each choice goes through its values and back`() {
        assertEquals(SubtitleSize.SMALL, SubtitleSize.HUGE.next())
        assertEquals(SubtitleColor.WHITE, SubtitleColor.GREEN.next())
        assertEquals(SubtitleBackground.OUTLINE, SubtitleBackground.OPAQUE.next())
        assertEquals(SubtitlePosition.BOTTOM, SubtitlePosition.HIGH.next())
    }
}
