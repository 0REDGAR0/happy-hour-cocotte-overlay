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
import android.widget.ImageView
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
        private const val SHOW_MATCH_LIVE_DEBUG_BADGE = false // Set to true to verify overlay is active in MATCH_LIVE
        private const val FORCE_OVERLAY_DEBUG_VISIBLE = false // Set to true to always show debug "OVERLAY ACTIF" panel
    }

    private enum class ConnectionStatus { GREEN, ORANGE, RED }

    private var targetWindowManager: WindowManager? = null
    private var fullScreenOverlayView: View? = null
    private var serverUrl: String = "http://10.0.2.2:3000"
    
    private var currentState: CocotteState? = null
    private var lastMainDisplayMode: DisplayMode = DisplayMode.IDLE
    private var lastBannerMessage: String? = null
    private var lastConnectionStatus: ConnectionStatus? = null
    private var lastAlbumArtUrl: String? = null
    private var lastHalfAlbumArtUrl: String? = null
    private var lastFullAlbumArtUrl: String? = null
    private var isViewAdded = false
    private var isAnimating = false
    private var lastApiSuccessTime: Long = 0
    
    // UI Layers
    private var mainContainer: View? = null
    private var layerPreMatch: View? = null
    private var layerHalfTime: View? = null
    private var layerFullTime: View? = null
    private var layerMatchLive: View? = null
    private var layerHappyHour: View? = null // This is the banner
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
            layerHalfTime = view.findViewById(R.id.layer_half_time)
            layerFullTime = view.findViewById(R.id.layer_full_time)
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
            Log.d(TAG, "Adding full-screen V2 overlay to WindowManager (addViewToWindow)")
            targetWindowManager?.addView(view, layoutParams)
            isViewAdded = true
            Log.d(TAG, "Overlay view added successfully. isViewAdded=true")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            stopSelf()
        }
    }

    private fun fetchState() {
        Thread {
            try {
                val url = URL("${serverUrl.trimEnd('/')}/api/state")
                Log.d(TAG, "Fetching state from: $url")
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
                    Log.e(TAG, "Server error: ${conn.responseCode}")
                    mainHandler.post { updateUIWithError("Erreur serveur (${conn.responseCode})") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                mainHandler.post { updateUIWithError("Serveur indisponible") }
            }
        }.start()
    }

    private fun updateUI(state: CocotteState) {
        if (!isRunning) return
        
        Log.d(TAG, "updateUI: displayMode=${state.mode}, happyHourActive=${state.happyHourActive}")
        
        layerError?.isGone = true

        if (state.mode == DisplayMode.IDLE && !state.happyHourActive) {
            Log.d(TAG, "Mode IDLE and no HH: hide overlay")
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

        // Always update Debug Panel visibility
        val debugPanel = view.findViewById<View>(R.id.tvForceDebugPanel)
        debugPanel?.isVisible = FORCE_OVERLAY_DEBUG_VISIBLE

        // Update lastMainDisplayMode if current mode is a "base" mode
        if (state.mode != DisplayMode.HAPPY_HOUR && state.mode != DisplayMode.IDLE) {
            lastMainDisplayMode = state.mode
        }

        // Handle transitions for main display modes
        val modeToDisplay = if (state.mode == DisplayMode.HAPPY_HOUR) lastMainDisplayMode else state.mode
        
        if (modeToDisplay != currentState?.mode || state.mode == DisplayMode.HAPPY_HOUR) {
            Log.d(TAG, "Main mode transition: ${currentState?.mode} -> $modeToDisplay (Actual mode: ${state.mode})")
            transitionToMode(modeToDisplay)
        }

        // Always update Happy Hour Banner independently
        updateHappyHourBanner(view, state)

        if (state.bannerMessage != lastBannerMessage) {
            handleBannerMessage(view, state.bannerMessage)
            lastBannerMessage = state.bannerMessage
        }

        // Update data for current mode
        when (modeToDisplay) {
            DisplayMode.PRE_MATCH -> updatePreMatchUI(view, state)
            DisplayMode.MATCH_LIVE -> updateMatchLiveUI(view, state)
            DisplayMode.HALF_TIME -> updateHalfTimeUI(view, state)
            DisplayMode.FULL_TIME -> updateFullTimeUI(view, state)
            DisplayMode.FULLSCREEN_MESSAGE -> {
                view.findViewById<TextView>(R.id.tvMessageText).text = state.fullscreenMessage ?: "MESSAGE"
            }
            else -> {}
        }
        
        currentState = state
    }

    private fun updateMatchLiveUI(view: View, state: CocotteState) {
        // Match Live is discreet, scoreboard removed as requested
        Log.d(TAG, "MATCH_LIVE actif : overlay volontairement transparent/discret")
        
        val debugBadge = view.findViewById<TextView>(R.id.tvMatchLiveDebugBadge)
        debugBadge?.isVisible = SHOW_MATCH_LIVE_DEBUG_BADGE
    }

    private fun updateHalfTimeUI(view: View, state: CocotteState) {
        val tvHomeScore = view.findViewById<TextView>(R.id.tvHalfHomeScore)
        val tvAwayScore = view.findViewById<TextView>(R.id.tvHalfAwayScore)
        val tvHomeTeamName = view.findViewById<TextView>(R.id.tvHalfHomeTeamName)
        val tvAwayTeamName = view.findViewById<TextView>(R.id.tvHalfAwayTeamName)
        val tvDrinks = view.findViewById<TextView>(R.id.tvHalfTimeDrinkCard)

        val matchInfo = state.matchInfo
        val homeName = matchInfo?.homeTeamName ?: matchInfo?.homeTeam?.name ?: state.homeTeam?.name ?: "Équipe 1"
        val awayName = matchInfo?.awayTeamName ?: matchInfo?.awayTeam?.name ?: state.awayTeam?.name ?: "Équipe 2"
        
        tvHomeScore.text = matchInfo?.homeScore?.toString() ?: "0"
        tvAwayScore.text = matchInfo?.awayScore?.toString() ?: "0"
        
        tvHomeTeamName.text = homeName.uppercase()
        tvAwayTeamName.text = awayName.uppercase()

        updateHalfTimeDrinks(tvDrinks, state)
        
        // Spotify
        val tvTrack = view.findViewById<TextView>(R.id.tvHalfSpotifyTrack)
        val tvArtist = view.findViewById<TextView>(R.id.tvHalfSpotifyArtist)
        updateMatchSpotify(tvTrack, tvArtist, state.spotify)
    }

    private fun updateFullTimeUI(view: View, state: CocotteState) {
        val tvHomeScore = view.findViewById<TextView>(R.id.tvFullHomeScore)
        val tvAwayScore = view.findViewById<TextView>(R.id.tvFullAwayScore)
        val tvHomeTeamName = view.findViewById<TextView>(R.id.tvFullHomeTeamName)
        val tvAwayTeamName = view.findViewById<TextView>(R.id.tvFullAwayTeamName)

        val matchInfo = state.matchInfo
        val homeName = matchInfo?.homeTeamName ?: matchInfo?.homeTeam?.name ?: state.homeTeam?.name ?: "Équipe 1"
        val awayName = matchInfo?.awayTeamName ?: matchInfo?.awayTeam?.name ?: state.awayTeam?.name ?: "Équipe 2"
        
        tvHomeScore.text = matchInfo?.homeScore?.toString() ?: "0"
        tvAwayScore.text = matchInfo?.awayScore?.toString() ?: "0"
        
        tvHomeTeamName.text = homeName.uppercase()
        tvAwayTeamName.text = awayName.uppercase()

        // Spotify
        val tvTrack = view.findViewById<TextView>(R.id.tvFullSpotifyTrack)
        val tvArtist = view.findViewById<TextView>(R.id.tvFullSpotifyArtist)
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

    private fun updateHappyHourBanner(view: View, state: CocotteState) {
        val layer = layerHappyHour ?: return
        val tvTimer = view.findViewById<TextView>(R.id.tvHappyTimer)
        val tvBarOnly = view.findViewById<TextView>(R.id.tvBarOnly)

        // Show banner if active OR if mode is HAPPY_HOUR
        val shouldShowBanner = state.happyHourActive || state.mode == DisplayMode.HAPPY_HOUR

        if (shouldShowBanner) {
            if (layer.isGone) {
                Log.d(TAG, "Showing Happy Hour Banner")
                showWithAnimation(layer, slide = true)
            }
            
            var remainingSeconds = state.remainingSeconds
            // Fallback calculation
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
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse happyHourEndsAt: ${state.happyHourEndsAt}")
                }
            }

            if (remainingSeconds > 0 || state.happyHourActive) {
                val minutes = remainingSeconds / 60
                val seconds = remainingSeconds % 60
                tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            } else {
                tvTimer.text = "Happy Hour active"
            }
            
            tvBarOnly.text = "Prix réduits pendant 5 minutes !"
            
        } else {
            if (layer.isVisible) {
                Log.d(TAG, "Hiding Happy Hour Banner")
                hideWithAnimation(layer, slide = true)
            }
        }
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
        val ivAlbumArt = view.findViewById<ImageView>(R.id.ivAlbumArt)
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
        
        if (kickoffAtRaw != null) {
            try {
                val sdf = if (kickoffAtRaw.contains(".")) {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                } else {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                }
                
                val date = sdf.parse(kickoffAtRaw)
                if (date != null) {
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
                        tvTimer.setTextColor(ContextCompat.getColor(this, R.color.terracotta))
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
        val programItems = state.preMatch?.programItems?.filter { it.active }?.sortedBy { it.sortOrder } ?: emptyList()
        if (programItems.isNotEmpty()) {
            val sb = StringBuilder()
            programItems.take(4).forEach { item ->
                val iconPrefix = if (!item.icon.isNullOrBlank()) "${item.icon} " else ""
                sb.append("$iconPrefix${item.text}\n")
            }
            tvProgram.text = sb.toString().trim()
        } else {
            tvProgram.text = "Programme non chargé"
        }
        
        // 5. Drinks
        val tvDrinks = view.findViewById<TextView>(R.id.tvDrinkCard)
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

    private fun loadAlbumArt(imageView: ImageView, url: String) {
        loadRemoteImage(imageView, url)
    }

    private fun loadRemoteImage(imageView: ImageView, url: String) {
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
        // Visualizer suppressed
    }

    private fun transitionToMode(newMode: DisplayMode) {
        val container = mainContainer ?: return
        
        when (newMode) {
            DisplayMode.PRE_MATCH -> container.setBackgroundColor(Color.TRANSPARENT)
            DisplayMode.HALF_TIME, DisplayMode.FULL_TIME, DisplayMode.FULLSCREEN_MESSAGE -> container.setBackgroundColor("#CC000000".toColorInt())
            else -> container.setBackgroundColor(Color.TRANSPARENT)
        }

        layerPreMatch?.let { hideWithAnimation(it) }
        layerHalfTime?.let { hideWithAnimation(it) }
        layerFullTime?.let { hideWithAnimation(it) }
        layerMatchLive?.let { hideWithAnimation(it) }
        // Do NOT hide Happy Hour layer here, it's managed independently by updateHappyHourBanner
        layerMessage?.let { hideWithAnimation(it) }

        mainHandler.postDelayed({
            when (newMode) {
                DisplayMode.PRE_MATCH -> layerPreMatch?.let { showWithAnimation(it) }
                DisplayMode.HALF_TIME -> layerHalfTime?.let { showWithAnimation(it) }
                DisplayMode.FULL_TIME -> layerFullTime?.let { showWithAnimation(it) }
                DisplayMode.MATCH_LIVE -> layerMatchLive?.let { showWithAnimation(it) }
                DisplayMode.FULLSCREEN_MESSAGE -> layerMessage?.let { showWithAnimation(it) }
                else -> {}
            }
        }, 400)
    }

    private fun showWithAnimation(view: View, slide: Boolean = false) {
        view.isVisible = true
        view.alpha = 0f
        if (slide) {
            view.translationY = 200f // Slide up from bottom for banner
            view.animate()
                .alpha(1f)
                .translationY(0f)
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
            anim.translationY(200f)
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
        layerHalfTime?.isGone = true
        layerFullTime?.isGone = true
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
