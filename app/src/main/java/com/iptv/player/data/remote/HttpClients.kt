package com.iptv.player.data.remote

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Client HTTP partagé par l'API, les playlists, les guides et le lecteur : un seul pool de connexions,
 * donc les poignées de main TLS et connexions keep-alive sont réutilisées (notamment à chaque zapping).
 */
object HttpClients {
    const val DEFAULT_USER_AGENT = "TV-AndroidTV/1.0 (Android TV)"

    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Message lisible pour une erreur réseau. */
    fun friendlyError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Nom de domaine introuvable : vérifiez l'adresse saisie."
        is java.net.ConnectException -> "Connexion refusée : vérifiez que le serveur et le port sont ouverts."
        is java.net.SocketTimeoutException -> "Délai d'attente dépassé : le serveur ne répond pas."
        is IllegalArgumentException -> "Adresse invalide."
        else -> e.localizedMessage ?: "Erreur réseau inconnue"
    }
}
