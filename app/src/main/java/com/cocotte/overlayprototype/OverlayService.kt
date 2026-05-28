package com.cocotte.overlayprototype

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        var isRunning = false
    }

    private var targetWindowManager: WindowManager? = null
    private var overlayView: View? = null
    private var serverUrl: String = "http://10.0.2.2:3000"
    
    private var lastHappyHourActive = false
    private var isViewAdded = false
    private var isAnimating = false
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                fetchState()
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    private val hideRunnable = Runnable {
        Log.d(TAG, "hideRunnable triggered")
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
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        Log.d(TAG, "Detecting displays: ${displays.size} found")
        
        var externalDisplay: Display? = null
        displays.forEachIndexed { index, display ->
            Log.d(TAG, "Display #$index: ID=${display.displayId}, Name=${display.name}, State=${display.state}, Flags=${display.flags}")
            if (display.displayId != Display.DEFAULT_DISPLAY && display.state == Display.STATE_ON) {
                externalDisplay = display
            }
        }

        if (externalDisplay != null) {
            Log.d(TAG, "Choosing external display: ${externalDisplay.name}")
            try {
                val displayContext = createDisplayContext(externalDisplay)
                val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    displayContext.createWindowContext(
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        null
                    )
                } else {
                    displayContext
                }
                targetWindowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create context for external display, falling back to default", e)
                targetWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }
        } else {
            Log.d(TAG, "No external display found, using default")
            targetWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
    }

    private fun ensureOverlayViewCreated(): View {
        var view = overlayView
        if (view == null) {
            Log.d(TAG, "Inflating new overlay layout")
            view = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            overlayView = view
            applyStrikethrough(view)
        }
        return view
    }

    private fun applyStrikethrough(view: View) {
        val oldPriceIds = listOf(R.id.tvDrink1Old, R.id.tvDrink2Old, R.id.tvDrink3Old, R.id.tvDrink4Old, R.id.tvDrink5Old)
        oldPriceIds.forEach { id ->
            view.findViewById<TextView>(id)?.let { textView ->
                textView.paintFlags = textView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        }
    }

    private fun addViewToWindow() {
        if (isViewAdded) {
            Log.d(TAG, "View already added, skipping addView")
            return
        }
        if (targetWindowManager == null) {
            Log.e(TAG, "targetWindowManager is null, cannot add view")
            return
        }
        
        val view = ensureOverlayViewCreated()
        
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val density = resources.displayMetrics.density
            x = (32 * density).toInt()
            y = (220 * density).toInt()
        }
        
        try {
            Log.d(TAG, "Adding view to WindowManager")
            targetWindowManager?.addView(view, layoutParams)
            isViewAdded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            isViewAdded = false
        }
    }

    private fun showOverlayWithAnimation() {
        Log.d(TAG, "showOverlayWithAnimation requested (isAnimating=$isAnimating)")
        if (isAnimating) return
        
        val view = ensureOverlayViewCreated()
        val panel = view.findViewById<View>(R.id.overlay_panel) ?: view
        
        // Initial state for animation
        panel.alpha = 0f
        panel.translationX = -200f
        panel.scaleX = 0.8f
        panel.scaleY = 0.8f
        
        addViewToWindow()
        view.visibility = View.VISIBLE
        
        Log.d(TAG, "Starting show animation")
        isAnimating = true
        panel.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setInterpolator(OvershootInterpolator())
            .withEndAction { 
                Log.d(TAG, "Show animation finished")
                isAnimating = false 
            }
            .start()
    }

    private fun hideOverlayWithAnimation() {
        Log.d(TAG, "hideOverlayWithAnimation requested (isViewAdded=$isViewAdded, isAnimating=$isAnimating)")
        val view = overlayView ?: return
        if (!isViewAdded || isAnimating) return
        
        val panel = view.findViewById<View>(R.id.overlay_panel) ?: view
        
        Log.d(TAG, "Starting hide animation")
        isAnimating = true
        panel.animate()
            .alpha(0f)
            .translationX(-200f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(600)
            .withEndAction {
                Log.d(TAG, "Hide animation finished, removing view")
                if (isViewAdded) {
                    try {
                        targetWindowManager?.removeView(view)
                        Log.d(TAG, "View removed from WindowManager")
                    } catch (e: Exception) {
                        Log.w(TAG, "Remove view failed (maybe already removed)", e)
                    }
                    isViewAdded = false
                }
                isAnimating = false
            }
            .start()
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
                    mainHandler.post { updateUI(json) }
                } else {
                    mainHandler.post { updateUIWithError("Erreur serveur ($responseCode)") }
                }
            } catch (e: Exception) {
                mainHandler.post { updateUIWithError("Serveur indisponible") }
            }
        }.start()
    }

    private fun updateUI(json: JSONObject) {
        if (!isRunning) return

        val happyHourActive = json.optBoolean("happyHourActive", false)
        val remainingSeconds = json.optInt("remainingSeconds", 0)
        
        // Transition detection
        if (happyHourActive && !lastHappyHourActive) {
            Log.d(TAG, "Transition detected: INACTIVE -> ACTIVE")
            mainHandler.removeCallbacks(hideRunnable)
            showOverlayWithAnimation()
        } else if (!happyHourActive && lastHappyHourActive) {
            Log.d(TAG, "Transition detected: ACTIVE -> INACTIVE")
            val view = overlayView
            if (view != null && isViewAdded) {
                view.findViewById<TextView>(R.id.tvTitle)?.text = getString(R.string.finished_status)
                view.findViewById<TextView>(R.id.tvTitle)?.setTextColor(getColor(R.color.cocotte_text_secondary))
                view.findViewById<TextView>(R.id.tvSubtitle)?.visibility = View.GONE
                setPricingActive(false)
            }
            Log.d(TAG, "Scheduling hide in 5s")
            mainHandler.postDelayed(hideRunnable, 5000)
        }
        
        lastHappyHourActive = happyHourActive
        
        // Dynamic content updates
        if (isViewAdded) {
            val view = overlayView ?: return
            try {
                val minutes = remainingSeconds / 60
                val seconds = remainingSeconds % 60
                view.findViewById<TextView>(R.id.tvTimer)?.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

                if (happyHourActive) {
                    val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
                    val tvSubtitle = view.findViewById<TextView>(R.id.tvSubtitle)
                    tvTitle?.text = getString(R.string.happy_hour_title)
                    tvTitle?.setTextColor(getColor(R.color.cocotte_accent_main))
                    tvSubtitle?.visibility = View.VISIBLE
                    tvSubtitle?.text = getString(R.string.happy_hour_subtitle)
                    view.findViewById<View>(R.id.tvBarOnly)?.visibility = View.VISIBLE
                    setPricingActive(true)
                } else {
                    view.findViewById<View>(R.id.tvBarOnly)?.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "UI update failed", e)
            }
        }
    }

    private fun setPricingActive(active: Boolean) {
        val view = overlayView ?: return
        val oldPriceIds = listOf(R.id.tvDrink1Old, R.id.tvDrink2Old, R.id.tvDrink3Old, R.id.tvDrink4Old, R.id.tvDrink5Old)
        val newPriceIds = listOf(R.id.tvDrink1New, R.id.tvDrink2New, R.id.tvDrink3New, R.id.tvDrink4New, R.id.tvDrink5New)
        
        for (i in oldPriceIds.indices) {
            val tvOld = view.findViewById<TextView>(oldPriceIds[i])
            val tvNew = view.findViewById<TextView>(newPriceIds[i])
            
            if (active) {
                tvOld?.let {
                    it.paintFlags = it.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    it.setTextColor(getColor(R.color.cocotte_text_secondary))
                }
                tvNew?.visibility = View.VISIBLE
            } else {
                tvOld?.let {
                    it.paintFlags = it.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    it.setTextColor(getColor(R.color.cocotte_text_main))
                }
                tvNew?.visibility = View.GONE
            }
        }
    }

    private fun updateUIWithError(message: String) {
        if (isViewAdded) {
            val view = overlayView ?: return
            view.findViewById<TextView>(R.id.tvTitle)?.text = message
            view.findViewById<TextView>(R.id.tvTitle)?.setTextColor(getColor(android.R.color.holo_red_dark))
            view.findViewById<TextView>(R.id.tvSubtitle)?.visibility = View.GONE
            view.findViewById<TextView>(R.id.tvTimer)?.text = "--:--"
            setPricingActive(false)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service being destroyed")
        isRunning = false
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(hideRunnable)
        
        overlayView?.let {
            if (isViewAdded) {
                try {
                    Log.d(TAG, "Removing view in onDestroy")
                    targetWindowManager?.removeView(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Destroy remove view failed", e)
                }
            }
            overlayView = null
        }
        super.onDestroy()
    }
}
