package io.nisfeb.talon.ui

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Turning a typed town or postcode into coordinates, through
 * Open-Meteo's geocoding service.
 *
 * THIS SENDS WHAT SOMEBODY TYPES TO A THIRD PARTY. It runs only when
 * they type a place and ask for it, never on a timer and never from a
 * device fix, and the picker accepts raw coordinates without ever
 * coming here. That is the whole of the bargain: a person who does not
 * want their town leaving the machine can still say where they are.
 *
 * No key and no account, which is why it is this service rather than
 * one that would put a credential in the app.
 */
class OpenMeteoPlaces(private val http: HttpClient) {

    suspend fun search(query: String): Result<List<HomePlace>> = runCatching {
        val q = query.trim()
        if (q.isEmpty()) return@runCatching emptyList()
        val url = "$ENDPOINT?name=${q.encodeURLParameter()}&count=8&format=json"
        val resp = http.get(url)
        if (!resp.status.isSuccess()) error("HTTP ${resp.status.value}")
        val body = json.decodeFromString<Answer>(resp.bodyAsText())
        body.results.map { r ->
            HomePlace(
                lat = r.latitude,
                lon = r.longitude,
                label = listOfNotNull(
                    r.name,
                    r.admin1?.takeIf { it.isNotBlank() && it != r.name },
                    r.country?.takeIf { it.isNotBlank() },
                ).joinToString(", "),
                fromGps = false,
                // The service knows how high the place is, which the
                // dial uses to move the horizon. A device fix would
                // carry its own; this is the town's.
                elevationMetres = r.elevation,
            )
        }
    }

    /** As a [PlaceLookup], for handing to the picker. */
    fun asLookup(): PlaceLookup = { q -> search(q) }

    @Serializable
    private data class Answer(val results: List<Row> = emptyList())

    @Serializable
    private data class Row(
        val name: String = "",
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val elevation: Double? = null,
        @SerialName("admin1") val admin1: String? = null,
        val country: String? = null,
    )

    private companion object {
        const val ENDPOINT = "https://geocoding-api.open-meteo.com/v1/search"
        val json = Json { ignoreUnknownKeys = true }
    }
}
