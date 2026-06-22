package com.cocotte.overlayprototype

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isGone
import androidx.core.view.isVisible
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DexDisplayActivity : AppCompatActivity() {

    private var serverUrl: String = "http://10.0.2.2:3000"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentState: CocotteState? = null
    private var lastMainDisplayMode: DisplayMode = DisplayMode.IDLE
    
    // Layers
    private lateinit var mainContainer: View
    private lateinit var layerPreMatch: View
    private lateinit var layerHalfTime: View
    private lateinit var layerFullTime: View
    private lateinit var layerMatchLive: View
    private lateinit var layerHappyHour: View
    private lateinit var layerMessage: View
    
    private val pollRunnable = object : Runnable {
        override fun run() {
            fetchState()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dex_display)

        // Initialize layers
        mainContainer = findViewById(R.id.dex_main_container)
        layerPreMatch = findViewById(R.id.layer_pre_match)
        layerHalfTime = findViewById(R.id.layer_half_time)
        layerFullTime = findViewById(R.id.layer_full_time)
        layerMatchLive = findViewById(R.id.layer_match_live)
        layerHappyHour = findViewById(R.id.layer_happy_hour)
        layerMessage = findViewById(R.id.layer_message)

        val prefs = getSharedPreferences("cocotte_prefs", MODE_PRIVATE)
        serverUrl = prefs.getString("server_url", "http://10.0.2.2:3000") ?: "http://10.0.2.2:3000"

        mainHandler.post(pollRunnable)
        
        hideSystemUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    private fun fetchState() {
        Thread {
            try {
                val url = URL("${serverUrl.trimEnd('/')}/api/state")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                
                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()
                    val json = JSONObject(response)
                    val newState = CocotteState.fromJson(json)
                    mainHandler.post { updateUI(newState) }
                }
            } catch (e: Exception) {
                Log.e("DexDisplayActivity", "Error fetching state: ${e.message}")
            }
        }.start()
    }

    private fun updateUI(state: CocotteState) {
        if (state.mode != DisplayMode.HAPPY_HOUR && state.mode != DisplayMode.IDLE) {
            lastMainDisplayMode = state.mode
        }

        val modeToDisplay = if (state.mode == DisplayMode.HAPPY_HOUR) lastMainDisplayMode else state.mode

        Log.d("DexDisplayActivity", "Updating UI: mode=${state.mode}, displaying=$modeToDisplay")

        // Update content based on modeToDisplay
        when (modeToDisplay) {
            DisplayMode.PRE_MATCH -> {
                // PreMatch is handled by its dedicated include
            }
            DisplayMode.MATCH_LIVE -> {
                // Discreet - scoreboard removed
            }
            DisplayMode.HALF_TIME -> {
                updateHalfTimeUI(state)
            }
            DisplayMode.FULL_TIME -> {
                updateFullTimeUI(state)
            }
            DisplayMode.FULLSCREEN_MESSAGE -> {
                findViewById<TextView>(R.id.tvMessageText).text = state.fullscreenMessage ?: "MESSAGE"
            }
            else -> {}
        }

        // Happy Hour Banner logic
        updateHappyHourBanner(state)

        // Handle mode transition
        val currentEffectiveMode = if (currentState?.mode == DisplayMode.HAPPY_HOUR) lastMainDisplayMode else currentState?.mode
        if (modeToDisplay != currentEffectiveMode) {
            transitionToMode(modeToDisplay)
        }
        
        currentState = state
    }

    private fun updateHalfTimeUI(state: CocotteState) {
        val tvHomeScore = findViewById<TextView>(R.id.tvHalfHomeScore)
        val tvAwayScore = findViewById<TextView>(R.id.tvHalfAwayScore)
        val tvHomeTeamName = findViewById<TextView>(R.id.tvHalfHomeTeamName)
        val tvAwayTeamName = findViewById<TextView>(R.id.tvHalfAwayTeamName)
        val tvDrinks = findViewById<TextView>(R.id.tvHalfTimeDrinkCard)

        val matchInfo = state.matchInfo
        val homeName = matchInfo?.homeTeamName ?: matchInfo?.homeTeam?.name ?: state.homeTeam?.name ?: "Équipe 1"
        val awayName = matchInfo?.awayTeamName ?: matchInfo?.awayTeam?.name ?: state.awayTeam?.name ?: "Équipe 2"
        
        tvHomeScore.text = matchInfo?.homeScore?.toString() ?: "0"
        tvAwayScore.text = matchInfo?.awayScore?.toString() ?: "0"
        
        tvHomeTeamName.text = homeName.uppercase()
        tvAwayTeamName.text = awayName.uppercase()

        updateHalfTimeDrinks(tvDrinks, state)
        
        // Spotify
        val tvTrack = findViewById<TextView>(R.id.tvHalfSpotifyTrack)
        val tvArtist = findViewById<TextView>(R.id.tvHalfSpotifyArtist)
        updateMatchSpotify(tvTrack, tvArtist, state.spotify)
    }

    private fun updateFullTimeUI(state: CocotteState) {
        val tvHomeScore = findViewById<TextView>(R.id.tvFullHomeScore)
        val tvAwayScore = findViewById<TextView>(R.id.tvFullAwayScore)
        val tvHomeTeamName = findViewById<TextView>(R.id.tvFullHomeTeamName)
        val tvAwayTeamName = findViewById<TextView>(R.id.tvFullAwayTeamName)

        val matchInfo = state.matchInfo
        val homeName = matchInfo?.homeTeamName ?: matchInfo?.homeTeam?.name ?: state.homeTeam?.name ?: "Équipe 1"
        val awayName = matchInfo?.awayTeamName ?: matchInfo?.awayTeam?.name ?: state.awayTeam?.name ?: "Équipe 2"
        
        tvHomeScore.text = matchInfo?.homeScore?.toString() ?: "0"
        tvAwayScore.text = matchInfo?.awayScore?.toString() ?: "0"
        
        tvHomeTeamName.text = homeName.uppercase()
        tvAwayTeamName.text = awayName.uppercase()

        // Spotify
        val tvTrack = findViewById<TextView>(R.id.tvFullSpotifyTrack)
        val tvArtist = findViewById<TextView>(R.id.tvFullSpotifyArtist)
        updateMatchSpotify(tvTrack, tvArtist, state.spotify)
    }

    private fun updateMatchSpotify(tvTrack: TextView, tvArtist: TextView, spotify: SpotifyState?) {
        if (spotify == null || spotify.status == "not_configured" || spotify.status == "no_playback") {
            tvTrack.text = "Musique indisponible"
            tvArtist.text = "-"
        } else {
            tvTrack.text = spotify.title ?: "Titre inconnu"
            tvArtist.text = spotify.artist ?: "Artiste inconnu"
            tvArtist.isVisible = !spotify.artist.isNullOrBlank()
        }
    }

    private fun updateHalfTimeDrinks(tvDrinks: TextView, state: CocotteState) {
        val drinks = state.drinks.filter { it.active }
        if (drinks.isNotEmpty()) {
            val spannableBuilder = SpannableStringBuilder()
            val hhColor = ContextCompat.getColor(this, R.color.pre_match_happy_hour)
            
            spannableBuilder.append("Boisson         Normal   Happy Hour\n")
            spannableBuilder.append("-----------------------------------\n")
            
            drinks.take(6).forEach { drink ->
                val iconPrefix = if (!drink.icon.isNullOrBlank()) "${drink.icon} " else ""
                val nameWithIcon = iconPrefix + drink.name
                val name = if (nameWithIcon.length > 15) nameWithIcon.take(13) + ".." else nameWithIcon.padEnd(15)
                val price = String.format(Locale.getDefault(), "%.1f%s", drink.price, drink.currency ?: "€").padStart(6)
                
                spannableBuilder.append(name)
                spannableBuilder.append("  ")
                spannableBuilder.append(price)
                
                if (drink.happyHourPrice != null) {
                    val hhPrice = String.format(Locale.getDefault(), "%.1f%s", drink.happyHourPrice, drink.currency ?: "€").padStart(11)
                    val startHH = spannableBuilder.length
                    spannableBuilder.append(hhPrice)
                    spannableBuilder.setSpan(
                        ForegroundColorSpan(hhColor),
                        startHH,
                        spannableBuilder.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else {
                    spannableBuilder.append("          -")
                }
                spannableBuilder.append("\n")
            }
            tvDrinks.text = spannableBuilder
        } else {
            tvDrinks.text = "Carte indisponible"
        }
    }

    private fun updateHappyHourBanner(state: CocotteState) {
        val shouldShowBanner = state.happyHourActive || state.mode == DisplayMode.HAPPY_HOUR
        
        if (shouldShowBanner) {
            if (layerHappyHour.isGone) {
                Log.d("DexDisplayActivity", "Showing Happy Hour Banner")
                showWithAnimation(layerHappyHour, slide = true)
            }
            
            var remainingSeconds = state.remainingSeconds
            if (remainingSeconds <= 0 && state.happyHourActive && state.happyHourEndsAt != null) {
                try {
                    val sdf = if (state.happyHourEndsAt.contains(".")) {
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    } else {
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    }
                    val endsAt = sdf.parse(state.happyHourEndsAt)
                    if (endsAt != null) {
                        val diff = (endsAt.time - System.currentTimeMillis()) / 1000
                        remainingSeconds = if (diff > 0) diff.toInt() else 0
                    }
                } catch (e: Exception) {}
            }

            val tvTimer = findViewById<TextView>(R.id.tvHappyTimer)
            if (remainingSeconds > 0 || state.happyHourActive) {
                val minutes = remainingSeconds / 60
                val seconds = remainingSeconds % 60
                tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            } else {
                tvTimer.text = "Happy Hour"
            }
        } else {
            if (layerHappyHour.isVisible) {
                Log.d("DexDisplayActivity", "Hiding Happy Hour Banner")
                hideWithAnimation(layerHappyHour, slide = true)
            }
        }
    }

    private fun transitionToMode(newMode: DisplayMode) {
        Log.d("DexDisplayActivity", "Transitioning to main mode: $newMode")
        
        // Background color logic
        when (newMode) {
            DisplayMode.PRE_MATCH -> {
                mainContainer.setBackgroundColor(Color.TRANSPARENT)
            }
            DisplayMode.HALF_TIME, DisplayMode.FULL_TIME, DisplayMode.FULLSCREEN_MESSAGE -> {
                mainContainer.setBackgroundColor("#CC000000".toColorInt())
            }
            else -> {
                mainContainer.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        // Hide main layers
        hideWithAnimation(layerPreMatch)
        hideWithAnimation(layerHalfTime)
        hideWithAnimation(layerFullTime)
        hideWithAnimation(layerMatchLive)
        hideWithAnimation(layerMessage)

        // Show target layer with delay
        mainHandler.postDelayed({
            when (newMode) {
                DisplayMode.PRE_MATCH -> showWithAnimation(layerPreMatch)
                DisplayMode.HALF_TIME -> {
                    Log.d("DexDisplayActivity", "Showing layerHalfTime")
                    showWithAnimation(layerHalfTime)
                }
                DisplayMode.FULL_TIME -> {
                    Log.d("DexDisplayActivity", "Showing layerFullTime")
                    showWithAnimation(layerFullTime)
                }
                DisplayMode.MATCH_LIVE -> showWithAnimation(layerMatchLive)
                DisplayMode.FULLSCREEN_MESSAGE -> showWithAnimation(layerMessage)
                else -> {}
            }
        }, 350)
    }

    private fun showWithAnimation(view: View, slide: Boolean = false) {
        view.isVisible = true
        view.alpha = 0f
        if (slide) {
            view.translationY = 200f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(800)
                .setInterpolator(OvershootInterpolator())
                .start()
        } else {
            view.animate()
                .alpha(1f)
                .setDuration(500)
                .start()
        }
    }

    private fun hideWithAnimation(view: View, slide: Boolean = false) {
        if (view.isGone) return
        
        val anim = view.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction { view.isGone = true }
            
        if (slide) {
            anim.translationY(200f)
        }
        anim.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(pollRunnable)
    }
}
