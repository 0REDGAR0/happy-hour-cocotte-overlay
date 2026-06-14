package com.cocotte.overlayprototype

import org.json.JSONObject

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
    val icon: String? = null
)

data class ProgramItem(
    val icon: String,
    val text: String
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
    val remainingSeconds: Int,
    val fullscreenMessage: String? = null,
    val bannerMessage: String? = null,
    val spotify: SpotifyState? = null,
    val preMatch: PreMatch? = null,
    val drinks: List<Drink> = emptyList()
) {
    companion object {
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
            
            val programItems = mutableListOf<ProgramItem>()
            preMatchJson?.optJSONArray("programItems")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    if (item != null) {
                        programItems.add(ProgramItem(
                            item.optString("icon", ""),
                            item.optString("text", "")
                        ))
                    }
                }
            }
            
            val drinks = mutableListOf<Drink>()
            json.optJSONArray("drinks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val drinkJson = arr.optJSONObject(i)
                    if (drinkJson != null) {
                        drinks.add(Drink(
                            drinkJson.optString("name", ""),
                            drinkJson.optDouble("price", 0.0),
                            if (drinkJson.has("happyHourPrice")) drinkJson.optDouble("happyHourPrice") else null,
                            drinkJson.optString("icon", "").takeIf { it != "" && it != "null" }
                        ))
                    }
                }
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
                remainingSeconds = json.optInt("remainingSeconds", 0),
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
                drinks = drinks
            )
        }
    }
}
