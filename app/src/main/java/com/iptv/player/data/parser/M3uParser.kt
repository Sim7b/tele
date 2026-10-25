package com.iptv.player.data.parser

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Entrée de playlist M3U (chaîne en direct).
 */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val group: String?,
    val number: Int?
)

/**
 * Playlist M3U : chaînes et guides XMLTV annoncés dans l'en-tête (url-tvg / x-tvg-url).
 */
class M3uPlaylist(
    val entries: List<M3uEntry>,
    val epgUrls: List<String>
)

object M3uParser {

    private val ATTRIBUTE_REGEX = """([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""".toRegex()

    // Films et séries des playlists Xtream (type=m3u_plus) : l'application ne gère que le direct
    private val VOD_PATH_REGEX = """/(movie|series)/""".toRegex(RegexOption.IGNORE_CASE)
    private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v")

    /**
     * Lit la playlist ligne par ligne (les playlists de fournisseurs dépassent souvent 50 000 entrées).
     */
    fun parse(input: InputStream): M3uPlaylist {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        val entries = ArrayList<M3uEntry>()
        val epgUrls = mutableListOf<String>()

        var attributes: Map<String, String> = emptyMap()
        var title: String? = null
        var extGroup: String? = null

        reader.forEachLine { raw ->
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val header = parseAttributes(line)
                    listOfNotNull(header["url-tvg"], header["x-tvg-url"]).forEach { value ->
                        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(epgUrls::add)
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val commaIndex = commaOutsideQuotes(line)
                    attributes = parseAttributes(if (commaIndex >= 0) line.substring(0, commaIndex) else line)
                    title = if (commaIndex >= 0) line.substring(commaIndex + 1).trim() else null
                    extGroup = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                else -> {
                    // Ligne d'URL : termine l'entrée en cours
                    if (!isVod(line)) {
                        val name = title?.takeIf { it.isNotEmpty() }
                            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
                            ?: "Chaîne ${entries.size + 1}"
                        entries.add(
                            M3uEntry(
                                name = name,
                                url = line,
                                tvgId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
                                tvgName = attributes["tvg-name"]?.takeIf { it.isNotBlank() },
                                logo = attributes["tvg-logo"]?.takeIf { it.isNotBlank() },
                                // Catégories multiples ("News;Sports") : la première suffit pour le classement
                                group = (attributes["group-title"] ?: extGroup)?.substringBefore(";")?.trim()?.takeIf { it.isNotBlank() },
                                number = (attributes["tvg-chno"] ?: attributes["channel-number"])?.trim()?.toIntOrNull()
                            )
                        )
                    }
                    attributes = emptyMap()
                    title = null
                    extGroup = null
                }
            }
        }
        return M3uPlaylist(entries, epgUrls.distinct())
    }

    private fun parseAttributes(text: String): Map<String, String> =
        ATTRIBUTE_REGEX.findAll(text).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

    /** Virgule séparant les attributs du titre (les valeurs entre guillemets peuvent contenir des virgules). */
    private fun commaOutsideQuotes(line: String): Int {
        var inQuotes = false
        line.forEachIndexed { index, char ->
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> return index
            }
        }
        return -1
    }

    private fun isVod(url: String): Boolean {
        val path = url.substringBefore('?')
        if (VOD_PATH_REGEX.containsMatchIn(path)) return true
        return path.substringAfterLast('.', "").lowercase() in VOD_EXTENSIONS
    }
}
