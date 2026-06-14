package com.cocotte.overlayprototype

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.isGone
import androidx.core.view.isVisible
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class DexDisplayActivity : AppCompatActivity() {

    private var serverUrl: String = "http://10.0.2.2:3000"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentState: CocotteState? = null
    
    // Layers
    private lateinit var mainContainer: View
    private lateinit var layerFullscreen: View
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
        layerFullscreen = findViewById(R.id.layer_fullscreen)
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
        // Update dynamic content even if mode hasn't changed
        when (state.mode) {
            DisplayMode.PRE_MATCH -> {
                findViewById<TextView>(R.id.tvFullTitle).text = getString(R.string.mode_pre_match)
                val h = state.matchInfo?.homeTeam?.shortName ?: state.homeTeam?.shortName ?: ""
                val a = state.matchInfo?.awayTeam?.shortName ?: state.awayTeam?.shortName ?: ""
                findViewById<TextView>(R.id.tvFullTeams).text = String.format("%s vs %s", h, a)
                
                val spotify = state.spotify
                findViewById<TextView>(R.id.tvFullInfo).text = if (spotify != null && spotify.status == "connected") {
                    String.format("🎵 %s - %s", spotify.title ?: getString(R.string.unknown_title), spotify.artist ?: getString(R.string.unknown_artist))
                } else {
                    getString(R.string.match_starting)
                }
            }
            DisplayMode.MATCH_LIVE -> {
                findViewById<TextView>(R.id.tvLiveHome).text = state.matchInfo?.homeTeam?.shortName ?: state.homeTeam?.shortName ?: ""
                findViewById<TextView>(R.id.tvLiveAway).text = state.matchInfo?.awayTeam?.shortName ?: state.awayTeam?.shortName ?: ""
                findViewById<TextView>(R.id.tvLiveScore).text = String.format(Locale.getDefault(), "%d - %d", state.matchInfo?.homeScore ?: 0, state.matchInfo?.awayScore ?: 0)
            }
            DisplayMode.HAPPY_HOUR -> {
                val minutes = state.remainingSeconds / 60
                val seconds = state.remainingSeconds % 60
                findViewById<TextView>(R.id.tvHappyTimer).text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
            DisplayMode.HALF_TIME -> {
                findViewById<TextView>(R.id.tvFullTitle).text = getString(R.string.mode_half_time)
                findViewById<TextView>(R.id.tvFullTeams).text = String.format(Locale.getDefault(), "%d - %d", state.matchInfo?.homeScore ?: 0, state.matchInfo?.awayScore ?: 0)
                findViewById<TextView>(R.id.tvFullInfo).text = getString(R.string.match_resume)
            }
            DisplayMode.FULL_TIME -> {
                findViewById<TextView>(R.id.tvFullTitle).text = getString(R.string.mode_full_time)
                findViewById<TextView>(R.id.tvFullTeams).text = String.format(Locale.getDefault(), "%d - %d", state.matchInfo?.homeScore ?: 0, state.matchInfo?.awayScore ?: 0)
                findViewById<TextView>(R.id.tvFullInfo).text = getString(R.string.match_thanks)
            }
            DisplayMode.FULLSCREEN_MESSAGE -> {
                findViewById<TextView>(R.id.tvMessageText).text = state.fullscreenMessage ?: "MESSAGE"
            }
            DisplayMode.IDLE -> {}
        }

        // Handle mode transition
        if (state.mode != currentState?.mode) {
            transitionToMode(state.mode)
        }
        
        currentState = state
    }

    private fun transitionToMode(newMode: DisplayMode) {
        Log.d("DexDisplayActivity", "Transitioning to mode: $newMode")
        
        // Background color logic
        when (newMode) {
            DisplayMode.PRE_MATCH, DisplayMode.HALF_TIME, DisplayMode.FULL_TIME -> {
                mainContainer.setBackgroundColor("#F5F5DC".toColorInt())
            }
            DisplayMode.FULLSCREEN_MESSAGE -> {
                mainContainer.setBackgroundColor("#CC121212".toColorInt())
            }
            else -> {
                mainContainer.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        // Hide all layers
        hideWithAnimation(layerFullscreen)
        hideWithAnimation(layerMatchLive)
        hideWithAnimation(layerHappyHour, slide = true)
        hideWithAnimation(layerMessage)

        // Show target layer with delay
        mainHandler.postDelayed({
            when (newMode) {
                DisplayMode.PRE_MATCH, DisplayMode.HALF_TIME, DisplayMode.FULL_TIME -> {
                    showWithAnimation(layerFullscreen)
                }
                DisplayMode.MATCH_LIVE -> {
                    showWithAnimation(layerMatchLive)
                }
                DisplayMode.HAPPY_HOUR -> {
                    showWithAnimation(layerHappyHour, slide = true)
                }
                DisplayMode.FULLSCREEN_MESSAGE -> {
                    showWithAnimation(layerMessage)
                }
                DisplayMode.IDLE -> {}
            }
        }, 350)
    }

    private fun showWithAnimation(view: View, slide: Boolean = false) {
        view.isVisible = true
        view.alpha = 0f
        if (slide) {
            view.translationX = -view.width.toFloat() - 200f
            view.animate()
                .alpha(1f)
                .translationX(0f)
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
            anim.translationX(-view.width.toFloat() - 200f)
        }
        anim.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(pollRunnable)
    }
}
