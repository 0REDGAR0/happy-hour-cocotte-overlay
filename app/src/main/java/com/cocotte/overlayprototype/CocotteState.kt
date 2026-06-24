package com.cocotte.overlayprototype

import org.json.JSONObject
import android.util.Log

enum class DisplayMode {
    IDLE,
    PRE_MATCH,
    MATCH_LIVE,
    HAPPY_HOUR,
    HALF_TIME,
    FULL_TIME,
    FULLSCREEN_MESSAGE
}

data class Team(
    val name: String,
    val shortName: String
)

data class MatchInfo(
    val homeTeam: Team?,
    val awayTeam: Team?,
    val homeTeamName: String?,
    val awayTeamName: String?,
    val homeScore: Int,
    val awayScore: Int,
    val matchTitle: String?,
    val matchDescription: String?,
    val kickoffAt: String?
)

data class Drink(
    val name: String,
    val price: Double,
    val happyHourPrice: Double? = null,
    val icon: String? = null,
    val active: Boolean = true,
    val currency: String? = "€"
)

data class ProgramItem(
    val icon: String?,
    val text: String,
    val active: Boolean = true,
    val sortOrder: Int = 0
)

data class HappyHourAnnouncement(
    val id: String,
    val startedAt: String?,
    val expiresAt: String?,
    val offer: String?,
    val message: String?
)

data class SoundboxEvent(
    val id: String,
    val type: String, // "klaxon" | "sifflet" | "cheer" | "goal"
    val asset: String?,
    val startedAt: String?,
    val expiresAt: String?
)

data class PreMatch(
    val enabled: Boolean,
    val kickoffAt: String?,
    val matchTitle: String?,
    val programItems: List<ProgramItem>,
    val homeFlag: String?,
    val awayFlag: String?
)

data class SpotifyState(
    val status: String, // "connected" | "not_configured" | "error" | "no_playback"
    val title: String?,
    val artist: String?,
    val albumArtUrl: String?,
    val isPlaying: Boolean,
    val progressMs: Long,
    val durationMs: Long,
    val deviceName: String?
)

data class CocotteState(
    val mode: DisplayMode,
    val matchStatus: String?,
    val matchTitle: String?,
    val homeTeam: Team?,
    val awayTeam: Team?,
    val matchInfo: MatchInfo?,
    val happyHourActive: Boolean,
    val happyHourDurationSeconds: Int,
    val remainingSeconds: Int,
    val happyHourEndsAt: String?,
    val fullscreenMessage: String? = null,
    val bannerMessage: String? = null,
    val spotify: SpotifyState? = null,
    val preMatch: PreMatch? = null,
    val drinks: List<Drink> = emptyList(),
    val happyHourAnnouncement: HappyHourAnnouncement? = null,
    val soundboxEvent: SoundboxEvent? = null
) {
    companion object {
        private const val TAG = "CocotteState"

        fun fromJson(json: JSONObject): CocotteState {
            val modeStr = json.optString("displayMode", "IDLE")
            val mode = try {
                DisplayMode.valueOf(modeStr)
            } catch (e: Exception) {
                if (json.optBoolean("happyHourActive", false)) {
                    DisplayMode.HAPPY_HOUR
                } else if (json.has("homeTeam") || json.has("matchInfo")) {
                    DisplayMode.MATCH_LIVE
                } else {
                    DisplayMode.IDLE
                }
            }

            val homeTeamJson = json.optJSONObject("homeTeam")
            val awayTeamJson = json.optJSONObject("awayTeam")
            val matchInfoJson = json.optJSONObject("matchInfo")
            val preMatchJson = json.optJSONObject("preMatch")
            val spotifyJson = json.optJSONObject("spotify")
            val menuJson = json.optJSONObject("menu")
            
            val programItems = mutableListOf<ProgramItem>()
            preMatchJson?.optJSONArray("programItems")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    if (item != null) {
                        programItems.add(ProgramItem(
                            icon = item.optString("icon", "").takeIf { it.isNotBlank() && it != "null" },
                            text = item.optString("text", ""),
                            active = item.optBoolean("active", true),
                            sortOrder = item.optInt("sortOrder", 0)
                        ))
                    }
                }
            }
            
            val drinks = mutableListOf<Drink>()
            val drinksArray = json.optJSONArray("drinks") ?: menuJson?.optJSONArray("drinks")
            drinksArray?.let { arr ->
                for (i in 0 until arr.length()) {
                    val drinkJson = arr.optJSONObject(i)
                    if (drinkJson != null) {
                        drinks.add(Drink(
                            name = drinkJson.optString("name", ""),
                            price = drinkJson.optDouble("price", 0.0),
                            happyHourPrice = if (drinkJson.has("happyHourPrice")) drinkJson.optDouble("happyHourPrice") else null,
                            icon = drinkJson.optString("icon", "").takeIf { it.isNotBlank() && it != "null" },
                            active = drinkJson.optBoolean("active", true),
                            currency = drinkJson.optString("currency", "€")
                        ))
                    }
                }
            }

            val hhAnnouncementJson = json.optJSONObject("happyHourAnnouncement")
            val hhAnnouncement = hhAnnouncementJson?.let {
                HappyHourAnnouncement(
                    id = it.optString("id", ""),
                    startedAt = it.optString("startedAt", "").takeIf { s -> s != "" && s != "null" },
                    expiresAt = it.optString("expiresAt", "").takeIf { s -> s != "" && s != "null" },
                    offer = it.optString("offer", "").takeIf { s -> s != "" && s != "null" },
                    message = it.optString("message", "").takeIf { s -> s != "" && s != "null" }
                ).takeIf { event -> event.id.isNotBlank() }
            }

            val soundboxEventJson = json.optJSONObject("soundboxEvent")
            val soundboxEvent = soundboxEventJson?.let {
                SoundboxEvent(
                    id = it.optString("id", ""),
                    type = it.optString("type", ""),
                    asset = it.optString("asset", "").takeIf { s -> s != "" && s != "null" },
                    startedAt = it.optString("startedAt", "").takeIf { s -> s != "" && s != "null" },
                    expiresAt = it.optString("expiresAt", "").takeIf { s -> s != "" && s != "null" }
                ).takeIf { event -> event.id.isNotBlank() }
            }

            val spotifyState = spotifyJson?.let {
                SpotifyState(
                    status = it.optString("status", "not_configured"),
                    title = it.optString("title", "").takeIf { s -> s != "" && s != "null" },
                    artist = it.optString("artist", "").takeIf { s -> s != "" && s != "null" },
                    albumArtUrl = it.optString("albumArtUrl", "").takeIf { s -> s != "" && s != "null" },
                    isPlaying = it.optBoolean("isPlaying", false),
                    progressMs = it.optLong("progressMs", 0),
                    durationMs = it.optLong("durationMs", 0),
                    deviceName = it.optString("deviceName", "").takeIf { s -> s != "" && s != "null" }
                )
            }

            return CocotteState(
                mode = mode,
                matchStatus = if (json.has("matchStatus")) json.optString("matchStatus") else null,
                matchTitle = if (json.has("matchTitle")) json.optString("matchTitle") else null,
                homeTeam = homeTeamJson?.let { Team(it.optString("name", ""), it.optString("shortName", "")) },
                awayTeam = awayTeamJson?.let { Team(it.optString("name", ""), it.optString("shortName", "")) },
                matchInfo = matchInfoJson?.let {
                    val miHomeTeamJson = it.optJSONObject("homeTeam")
                    val miAwayTeamJson = it.optJSONObject("awayTeam")
                    MatchInfo(
                        homeTeam = miHomeTeamJson?.let { t -> Team(t.optString("name", ""), t.optString("shortName", "")) },
                        awayTeam = miAwayTeamJson?.let { t -> Team(t.optString("name", ""), t.optString("shortName", "")) },
                        homeTeamName = if (it.has("homeTeamName")) it.optString("homeTeamName") else null,
                        awayTeamName = if (it.has("awayTeamName")) it.optString("awayTeamName") else null,
                        homeScore = it.optInt("homeScore", 0),
                        awayScore = it.optInt("awayScore", 0),
                        matchTitle = if (it.has("matchTitle")) it.optString("matchTitle") else null,
                        matchDescription = if (it.has("matchDescription")) it.optString("matchDescription") else null,
                        kickoffAt = if (it.has("kickoffAt")) it.optString("kickoffAt") else null
                    )
                },
                happyHourActive = json.optBoolean("happyHourActive", false),
                happyHourDurationSeconds = json.optInt("happyHourDurationSeconds", 300),
                remainingSeconds = json.optInt("remainingSeconds", 0),
                happyHourEndsAt = if (json.has("happyHourEndsAt")) json.optString("happyHourEndsAt") else null,
                fullscreenMessage = json.optString("fullscreenMessage", "").takeIf { it != "" && it != "null" },
                bannerMessage = json.optString("bannerMessage", "").takeIf { it != "" && it != "null" },
                spotify = spotifyState,
                preMatch = PreMatch(
                    enabled = preMatchJson?.optBoolean("enabled", false) ?: false,
                    kickoffAt = preMatchJson?.optString("kickoffAt", "").takeIf { it != "" && it != "null" },
                    matchTitle = preMatchJson?.optString("matchTitle", "").takeIf { it != "" && it != "null" },
                    programItems = programItems,
                    homeFlag = preMatchJson?.optString("homeFlag", "").takeIf { it != "" && it != "null" },
                    awayFlag = preMatchJson?.optString("awayFlag", "").takeIf { it != "" && it != "null" }
                ),
                drinks = drinks,
                happyHourAnnouncement = hhAnnouncement,
                soundboxEvent = soundboxEvent
            )
        }
    }
}
