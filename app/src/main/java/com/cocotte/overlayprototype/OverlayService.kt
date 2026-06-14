package com.cocotte.overlayprototype

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.TextView
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
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        var isRunning = false
    }

    private enum class ConnectionStatus { GREEN, ORANGE, RED }

    private var targetWindowManager: WindowManager? = null
    private var fullScreenOverlayView: View? = null
    private var serverUrl: String = "http://10.0.2.2:3000"
    
    private var currentState: CocotteState? = null
    private var lastBannerMessage: String? = null
    private var lastConnectionStatus: ConnectionStatus? = null
    private var lastAlbumArtUrl: String? = null
    private var isViewAdded = false
    private var isAnimating = false
    private var lastApiSuccessTime: Long = 0
    
    // UI Layers
    private var mainContainer: View? = null
    private var layerPreMatch: View? = null
    private var layerFullscreen: View? = null
    private var layerMatchLive: View? = null
    private var layerHappyHour: View? = null
    private var layerMessage: View? = null
    private var layerBanner: View? = null
    private var layerError: View? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                checkConnectionStaleness()
                fetchState()
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    private val hideRunnable = Runnable {
        hideOverlayWithAnimation()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "Service started")
        
        val prefs = getSharedPreferences("cocotte_prefs", MODE_PRIVATE)
        serverUrl = prefs.getString("server_url", "http://10.0.2.2:3000") ?: "http://10.0.2.2:3000"
        
        setupWindowManagerForExternalDisplay()
        
        Log.d(TAG, "Starting polling...")
        mainHandler.post(pollRunnable)
    }

    private fun setupWindowManagerForExternalDisplay() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        
        var externalDisplay: Display? = null
        displays.forEach { display ->
            if (display.displayId != Display.DEFAULT_DISPLAY && display.state == Display.STATE_ON) {
                externalDisplay = display
            }
        }

        if (externalDisplay != null) {
            Log.d(TAG, "Choosing external display: ${externalDisplay.name}")
            try {
                val displayContext = createDisplayContext(externalDisplay)
                val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                } else {
                    displayContext
                }
                targetWindowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create context for external display", e)
                targetWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            }
        } else {
            Log.d(TAG, "No external display found, using default")
            targetWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
    }

    private fun ensureOverlayViewCreated(): View {
        var view = fullScreenOverlayView
        if (view == null) {
            Log.d(TAG, "Inflating V2 TV layout")
            view = LayoutInflater.from(this).inflate(R.layout.activity_dex_display, null)
            fullScreenOverlayView = view
            
            // Reference V2 layers
            mainContainer = view.findViewById(R.id.dex_main_container)
            layerPreMatch = view.findViewById(R.id.layer_pre_match)
            layerFullscreen = view.findViewById(R.id.layer_fullscreen)
            layerMatchLive = view.findViewById(R.id.layer_match_live)
            layerHappyHour = view.findViewById(R.id.layer_happy_hour)
            layerMessage = view.findViewById(R.id.layer_message)
            layerBanner = view.findViewById(R.id.layer_banner)
            layerError = view.findViewById(R.id.layer_error)
        }
        return view!!
    }

    private fun addViewToWindow() {
        if (isViewAdded || targetWindowManager == null) return
        val view = ensureOverlayViewCreated()
        
        // Full screen layout params for system overlay
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        
        try {
            Log.d(TAG, "Adding full-screen V2 overlay to WindowManager")
            targetWindowManager?.addView(view, layoutParams)
            isViewAdded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            stopSelf()
        }
    }

    private fun fetchState() {
        Thread {
            try {
                val url = URL("${serverUrl.trimEnd('/')}/api/state")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val newState = CocotteState.fromJson(json)
                    lastApiSuccessTime = System.currentTimeMillis()
                    mainHandler.post { 
                        updateConnectionIndicator(ConnectionStatus.GREEN)
                        updateUI(newState) 
                    }
                } else {
                    mainHandler.post { updateUIWithError("Erreur serveur (${conn.responseCode})") }
                }
            } catch (e: Exception) {
                mainHandler.post { updateUIWithError("Serveur indisponible") }
            }
        }.start()
    }

    private fun updateUI(state: CocotteState) {
        if (!isRunning) return
        
        layerError?.isGone = true

        if (state.mode == DisplayMode.IDLE) {
            if (isViewAdded && !isAnimating) {
                hideOverlayWithAnimation()
            }
            currentState = state
            return
        }

        if (!isViewAdded) {
            addViewToWindow()
        }

        val view = fullScreenOverlayView ?: return

        // Handle transitions
        if (state.mode != currentState?.mode) {
            transitionToMode(state.mode)
        }

        if (state.bannerMessage != lastBannerMessage) {
            handleBannerMessage(view, state.bannerMessage)
            lastBannerMessage = state.bannerMessage
        }

        when (state.mode) {
            DisplayMode.PRE_MATCH -> updatePreMatchUI(view, state)
            DisplayMode.MATCH_LIVE -> {
                view.findViewById<TextView>(R.id.tvLiveHome).text = state.homeTeam?.shortName ?: state.matchInfo?.homeTeam?.shortName ?: ""
                view.findViewById<TextView>(R.id.tvLiveAway).text = state.awayTeam?.shortName ?: state.matchInfo?.awayTeam?.shortName ?: ""
                view.findViewById<TextView>(R.id.tvLiveScore).text = String.format(Locale.getDefault(), "%d - %d", state.matchInfo?.homeScore ?: 0, state.matchInfo?.awayScore ?: 0)
            }
            DisplayMode.HAPPY_HOUR -> {
                val minutes = state.remainingSeconds / 60
                val seconds = state.remainingSeconds % 60
                view.findViewById<TextView>(R.id.tvHappyTimer).text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
            DisplayMode.HALF_TIME -> {
                view.findViewById<TextView>(R.id.tvFullTitle).text = getString(R.string.mode_half_time)
                view.findViewById<TextView>(R.id.tvFullTeams).text = String.format(Locale.getDefault(), "%d - %d", state.matchInfo?.homeScore ?: 0, state.matchInfo?.awayScore ?: 0)
                view.findViewById<TextView>(R.id.tvFullInfo).text = getString(R.string.match_resume)
            }
            DisplayMode.FULL_TIME -> {
                view.findViewById<TextView>(R.id.tvFullTitle).text = getString(R.string.mode_full_time)
                view.findViewById<TextView>(R.id.tvFullTeams).text = String.format(Locale.getDefault(), "%d - %d", state.matchInfo?.homeScore ?: 0, state.matchInfo?.awayScore ?: 0)
                view.findViewById<TextView>(R.id.tvFullInfo).text = getString(R.string.match_thanks)
            }
            DisplayMode.FULLSCREEN_MESSAGE -> {
                view.findViewById<TextView>(R.id.tvMessageText).text = state.fullscreenMessage ?: "MESSAGE"
            }
            else -> {}
        }
        
        currentState = state
    }

    private fun handleBannerMessage(view: View, message: String?) {
        val banner = layerBanner ?: return
        val tv = view.findViewById<TextView>(R.id.tvBannerText) ?: return

        if (message == null) {
            hideBannerAnimation(banner)
        } else {
            tv.text = message
            showBannerAnimation(banner)
        }
    }

    private fun showBannerAnimation(view: View) {
        if (view.isVisible) return
        view.isVisible = true
        view.alpha = 0f
        view.translationY = -200f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    private fun hideBannerAnimation(view: View) {
        if (view.isGone) return
        view.animate()
            .alpha(0f)
            .translationY(-200f)
            .setDuration(600)
            .withEndAction { view.isGone = true }
            .start()
    }

    private fun updatePreMatchUI(view: View, state: CocotteState) {
        // 1. Spotify logic
        val tvSpotifyArtist = view.findViewById<TextView>(R.id.tvSpotifyArtist)
        val tvSpotifyTrack = view.findViewById<TextView>(R.id.tvSpotifyTrack)
        val ivAlbumArt = view.findViewById<android.widget.ImageView>(R.id.ivAlbumArt)
        val spotify = state.spotify
        
        if (spotify == null || spotify.status == "not_configured") {
            tvSpotifyTrack.text = "Musique indisponible"
            tvSpotifyArtist.text = "Spotify non connecté"
            ivAlbumArt?.isGone = true
            lastAlbumArtUrl = null
        } else if (spotify.status == "error") {
            tvSpotifyTrack.text = "Erreur Spotify"
            tvSpotifyArtist.text = "-"
            ivAlbumArt?.isGone = true
            lastAlbumArtUrl = null
        } else if (spotify.status == "no_playback") {
            tvSpotifyTrack.text = "Aucune lecture Spotify"
            tvSpotifyArtist.text = "-"
            ivAlbumArt?.isGone = true
            lastAlbumArtUrl = null
        } else {
            val titleStr = spotify.title ?: "Titre inconnu"
            val artistStr = spotify.artist ?: "Artiste inconnu"
            
            // On n'affiche l'artiste que si disponible (secondaire)
            tvSpotifyTrack.text = titleStr
            tvSpotifyArtist.text = artistStr
            tvSpotifyArtist.isVisible = !spotify.artist.isNullOrBlank()

            if (spotify.albumArtUrl != lastAlbumArtUrl) {
                lastAlbumArtUrl = spotify.albumArtUrl
                if (spotify.albumArtUrl != null && ivAlbumArt != null) {
                    loadAlbumArt(ivAlbumArt, spotify.albumArtUrl)
                } else {
                    ivAlbumArt?.isGone = true
                }
            } else if (spotify.albumArtUrl != null) {
                ivAlbumArt?.isVisible = true
            }
        }

        // Animation visualiseur supprimée selon demande
        resetVisualizer(view)
        
        // 2. Match Info & Teams
        val tvHome = view.findViewById<TextView>(R.id.tvHomeTeamName)
        val tvAway = view.findViewById<TextView>(R.id.tvAwayTeamName)
        
        val homeTeamName = state.matchInfo?.homeTeamName 
            ?: state.matchInfo?.homeTeam?.name 
            ?: state.homeTeam?.name 
            ?: "Équipe indisponible"
            
        val awayTeamName = state.matchInfo?.awayTeamName 
            ?: state.matchInfo?.awayTeam?.name 
            ?: state.awayTeam?.name 
            ?: "Équipe indisponible"
            
        tvHome.text = homeTeamName.uppercase()
        tvAway.text = awayTeamName.uppercase()

        // 3. Countdown calculation
        val kickoffAtRaw = state.matchInfo?.kickoffAt ?: state.preMatch?.kickoffAt
        val tvTimer = view.findViewById<TextView>(R.id.tvKickoffTimer)
        
        Log.d(TAG, "kickoffAt (matchInfo): ${state.matchInfo?.kickoffAt}")
        Log.d(TAG, "kickoffAt (preMatch): ${state.preMatch?.kickoffAt}")
        Log.d(TAG, "kickoffAt chosen: $kickoffAtRaw")

        if (kickoffAtRaw != null) {
            try {
                // Support both .SSSZ and simple Z or offset
                val sdf = if (kickoffAtRaw.contains(".")) {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                } else if (kickoffAtRaw.contains("+")) {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US)
                } else {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                }
                
                val date = sdf.parse(kickoffAtRaw)
                if (date != null) {
                    Log.d(TAG, "Parsing successful: $date")
                    val diff = date.time - System.currentTimeMillis()
                    if (diff > 0) {
                        val days = diff / (1000 * 60 * 60 * 24)
                        val hours = (diff / (1000 * 60 * 60)) % 24
                        val mins = (diff / (1000 * 60)) % 60
                        val secs = (diff / 1000) % 60
                        
                        if (days > 0) {
                            tvTimer.text = String.format(Locale.getDefault(), "J-%d %02d:%02d:%02d", days, hours, mins, secs)
                        } else {
                            tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, mins, secs)
                        }
                        tvTimer.setTextColor("#B03A2E".toColorInt()) // Terracotta
                    } else {
                        tvTimer.text = "00:00:00"
                        tvTimer.setTextColor(Color.WHITE)
                    }
                } else {
                    tvTimer.text = "Date coup d’envoi invalide"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Parsing failed for $kickoffAtRaw: ${e.message}")
                tvTimer.text = "Date coup d’envoi invalide"
            }
        } else {
            tvTimer.text = "Coup d’envoi indisponible"
            tvTimer.setTextColor(Color.WHITE)
        }
        
        // 4. Program Items
        val tvProgram = view.findViewById<TextView>(R.id.tvProgramList)
        val programItems = state.preMatch?.programItems ?: emptyList()
        if (programItems.isNotEmpty()) {
            val sb = StringBuilder()
            programItems.take(4).forEach { item ->
                val iconPrefix = if (item.icon.isNotBlank()) "${item.icon} " else ""
                sb.append("$iconPrefix${item.text}\n")
            }
            tvProgram.text = sb.toString().trim()
        } else {
            tvProgram.text = "Programme non chargé"
        }
        
        // 5. Drinks with Table Style and Spans for Colors
        val tvDrinks = view.findViewById<TextView>(R.id.tvDrinkCard)
        val drinks = state.drinks
        if (drinks.isNotEmpty()) {
            val spannableBuilder = SpannableStringBuilder()
            val hhColor = ContextCompat.getColor(this, R.color.pre_match_happy_hour)
            
            // Header
            spannableBuilder.append("Boisson         Normal   Happy Hour\n")
            spannableBuilder.append("-----------------------------------\n")
            
            drinks.take(6).forEach { drink ->
                val iconPrefix = if (!drink.icon.isNullOrBlank()) "${drink.icon} " else ""
                val nameWithIcon = iconPrefix + drink.name
                val name = if (nameWithIcon.length > 15) nameWithIcon.take(13) + ".." else nameWithIcon.padEnd(15)
                val price = String.format(Locale.getDefault(), "%.1f€", drink.price).padStart(6)
                
                spannableBuilder.append(name)
                spannableBuilder.append("  ")
                spannableBuilder.append(price)
                
                if (drink.happyHourPrice != null) {
                    val hhPrice = String.format(Locale.getDefault(), "%.1f€", drink.happyHourPrice).padStart(11)
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

    private fun animateVisualizer(view: View) {
        // Visualizer supprimé du layout PRE_MATCH
    }

    private fun loadAlbumArt(imageView: android.widget.ImageView, url: String) {
        loadRemoteImage(imageView, url)
    }

    private fun loadRemoteImage(imageView: android.widget.ImageView, url: String) {
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()
                val input = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(input)
                mainHandler.post {
                    imageView.setImageBitmap(bitmap)
                    imageView.isVisible = true
                }
            } catch (e: Exception) {
                mainHandler.post { imageView.isGone = true }
            }
        }.start()
    }

    private fun resetVisualizer(view: View) {
        // Visualizer supprimé du layout PRE_MATCH
    }

    private fun transitionToMode(newMode: DisplayMode) {
        val container = mainContainer ?: return
        
        when (newMode) {
            DisplayMode.PRE_MATCH -> container.setBackgroundColor(Color.TRANSPARENT)
            DisplayMode.HALF_TIME, DisplayMode.FULL_TIME -> container.setBackgroundColor("#F5F5DC".toColorInt())
            DisplayMode.FULLSCREEN_MESSAGE -> container.setBackgroundColor("#CC121212".toColorInt())
            else -> container.setBackgroundColor(Color.TRANSPARENT)
        }

        layerPreMatch?.let { hideWithAnimation(it) }
        layerFullscreen?.let { hideWithAnimation(it) }
        layerMatchLive?.let { hideWithAnimation(it) }
        layerHappyHour?.let { hideWithAnimation(it, slide = true) }
        layerMessage?.let { hideWithAnimation(it) }

        mainHandler.postDelayed({
            when (newMode) {
                DisplayMode.PRE_MATCH -> layerPreMatch?.let { showWithAnimation(it) }
                DisplayMode.HALF_TIME, DisplayMode.FULL_TIME -> layerFullscreen?.let { showWithAnimation(it) }
                DisplayMode.MATCH_LIVE -> layerMatchLive?.let { showWithAnimation(it) }
                DisplayMode.HAPPY_HOUR -> layerHappyHour?.let { showWithAnimation(it, slide = true) }
                DisplayMode.FULLSCREEN_MESSAGE -> layerMessage?.let { showWithAnimation(it) }
                else -> {}
            }
        }, 400)
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
            view.animate().alpha(1f).setDuration(500).start()
        }
    }

    private fun hideWithAnimation(view: View, slide: Boolean = false) {
        if (view.isGone) return
        val anim = view.animate().alpha(0f).setDuration(400).withEndAction { view.isGone = true }
        if (slide) {
            anim.translationX(-view.width.toFloat() - 200f)
        }
        anim.start()
    }

    private fun checkConnectionStaleness() {
        if (lastApiSuccessTime == 0L) return
        val diff = System.currentTimeMillis() - lastApiSuccessTime
        mainHandler.post {
            when {
                diff > 10000 -> updateConnectionIndicator(ConnectionStatus.RED)
                diff > 3000 -> updateConnectionIndicator(ConnectionStatus.ORANGE)
                else -> updateConnectionIndicator(ConnectionStatus.GREEN)
            }
        }
    }

    private fun updateConnectionIndicator(status: ConnectionStatus) {
        val view = fullScreenOverlayView ?: return
        val indicator = view.findViewById<View>(R.id.connection_indicator) ?: return
        val tvError = view.findViewById<TextView>(R.id.tvConnectionError)
        
        val color = when (status) {
            ConnectionStatus.GREEN -> "#00FF00"
            ConnectionStatus.ORANGE -> "#FFA500"
            ConnectionStatus.RED -> "#FF0000"
        }
        
        indicator.background.setTint(color.toColorInt())
        
        if (status == ConnectionStatus.RED) {
            if (tvError != null && tvError.isGone) {
                tvError.isVisible = true
                mainHandler.postDelayed({ tvError.isGone = true }, 5000)
            }
            hideAllLayersForError()
        } else {
            tvError?.isGone = true
            if (lastConnectionStatus == ConnectionStatus.RED) {
                currentState?.let { transitionToMode(it.mode) }
            }
        }
        lastConnectionStatus = status
    }

    private fun hideAllLayersForError() {
        mainContainer?.setBackgroundColor(Color.TRANSPARENT)
        layerPreMatch?.isGone = true
        layerFullscreen?.isGone = true
        layerMatchLive?.isGone = true
        layerHappyHour?.isGone = true
        layerMessage?.isGone = true
        layerBanner?.isGone = true
    }

    private fun updateUIWithError(message: String) {
        Log.e(TAG, "updateUIWithError: $message")
        updateConnectionIndicator(ConnectionStatus.RED)
    }

    private fun hideOverlayWithAnimation() {
        val view = fullScreenOverlayView ?: return
        isAnimating = true
        view.animate()
            .alpha(0f)
            .setDuration(1000)
            .withEndAction {
                if (isViewAdded) {
                    try {
                        targetWindowManager?.removeView(view)
                    } catch (e: Exception) {
                        Log.w(TAG, "Remove view failed", e)
                    }
                    isViewAdded = false
                }
                isAnimating = false
            }
            .start()
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(hideRunnable)
        fullScreenOverlayView?.let {
            if (isViewAdded) {
                try {
                    targetWindowManager?.removeView(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Destroy remove failed", e)
                }
            }
        }
        super.onDestroy()
    }
}
