package io.github.sim7b.tele.data.vod

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cases measured by the Télé API plugin (ffprobe on real files, exported from Dispatcharr): what Télé
 * estimates from the names must not name a language the file does not have. See
 * `src/test/resources/vod-language-cases/README.md`.
 */
class VodLanguageCasesTest {

    private data class Case(val file: String, val group: String, val name: String, val audio: Set<String>, val subtitles: Set<String>)

    private fun cases(): List<Case> {
        val folder = javaClass.classLoader?.getResource("vod-language-cases")?.let { File(it.toURI()) } ?: return emptyList()
        return folder.listFiles { file -> file.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.flatMap { file ->
            val root = JSONObject(file.readText())
            check(root.optString("format") == "tele-vod-language-cases") { "${file.name}: not a Télé VOD language case file" }
            val list = root.optJSONArray("cases") ?: JSONArray()
            (0 until list.length()).map { list.getJSONObject(it) }.filter { !it.has("skip") }.map { case ->
                Case(file.name, case.optString("group"), case.optString("name"), codes(case.optJSONArray("audio")), codes(case.optJSONArray("subtitles")))
            }
        }
    }

    private fun codes(array: JSONArray?): Set<String> = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }.toSet()

    @Test
    fun `estimates never name a language the measured file does not have`() {
        val wrong = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val all = cases()
        assertTrue("no case file found", all.isNotEmpty())
        all.forEach { case ->
            val estimate = VodLanguageGuess.estimate(case.name, VodNames.parse(case.name).languageTags, case.group)
            val label = "${case.file}: \"${case.group}\" / \"${case.name}\""
            val wrongAudio = estimate.audio - case.audio
            val wrongSubtitles = estimate.subtitles - case.subtitles
            if (wrongAudio.isNotEmpty() || wrongSubtitles.isNotEmpty()) {
                wrong += "$label: estimated audio ${estimate.audio}, subtitles ${estimate.subtitles}; " +
                    "measured audio ${case.audio}, subtitles ${case.subtitles}"
            }
            val missingAudio = case.audio - estimate.audio
            val missingSubtitles = case.subtitles - estimate.subtitles
            if (missingAudio.isNotEmpty() || missingSubtitles.isNotEmpty()) {
                missing += "$label: not told: audio $missingAudio, subtitles $missingSubtitles"
            }
        }
        if (missing.isNotEmpty()) println("Languages the names do not tell (${missing.size}):\n" + missing.joinToString("\n"))
        assertTrue("Estimates naming a language the file does not have (${wrong.size}):\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }
}
