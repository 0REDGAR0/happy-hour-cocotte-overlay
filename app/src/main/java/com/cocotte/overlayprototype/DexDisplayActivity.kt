package com.cocotte.overlayprototype

import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class DexDisplayActivity : AppCompatActivity() {

    private var serverUrl: String = "http://10.0.2.2:3000"
    private val handler = Handler(Looper.getMainLooper())
    private var lastHappyHourActive = false
    
    private val pollRunnable = object : Runnable {
        override fun run() {
            fetchState()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dex_display)

        val prefs = getSharedPreferences("cocotte_prefs", MODE_PRIVATE)
        serverUrl = prefs.getString("server_url", "http://10.0.2.2:3000") ?: "http://10.0.2.2:3000"

        handler.post(pollRunnable)
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
                    handler.post { updateUI(json) }
                } else {
                    handler.post { updateUIWithError("Erreur serveur ($responseCode)") }
                }
            } catch (ignored: Exception) {
                handler.post { updateUIWithError("Serveur indisponible") }
            }
        }.start()
    }

    private fun updateUI(json: JSONObject) {
        try {
            val happyHourActive = json.optBoolean("happyHourActive", false)
            val remainingSeconds = json.optInt("remainingSeconds", 0)

            // Update Timer
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            findViewById<TextView>(R.id.tvTimer).text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

            // Update Title/Subtitle
            val tvTitle = findViewById<TextView>(R.id.tvTitle)
            val tvSubtitle = findViewById<TextView>(R.id.tvSubtitle)

            if (happyHourActive) {
                if (!lastHappyHourActive) {
                    playWawAnimation()
                }
                tvTitle.text = getString(R.string.happy_hour_title)
                tvTitle.setTextColor(getColor(R.color.cocotte_accent_main))
                tvSubtitle.visibility = View.VISIBLE
                tvSubtitle.text = getString(R.string.happy_hour_subtitle)
                setPricingActive(true)
            } else {
                if (lastHappyHourActive) {
                    tvTitle.text = getString(R.string.finished_status)
                    handler.postDelayed({
                        if (!lastHappyHourActive) {
                            tvTitle.text = getString(R.string.waiting_status)
                        }
                    }, 5000)
                } else if (tvTitle.text != getString(R.string.finished_status)) {
                    tvTitle.text = getString(R.string.waiting_status)
                }
                tvTitle.setTextColor(getColor(R.color.cocotte_text_secondary))
                tvSubtitle.visibility = View.GONE
                setPricingActive(false)
            }
            lastHappyHourActive = happyHourActive
        } catch (e: Exception) {
            updateUIWithError("Erreur données")
        }
    }

    private fun playWawAnimation() {
        val root = findViewById<View>(android.R.id.content)
        root.alpha = 0.5f
        root.scaleX = 0.95f
        root.scaleY = 0.95f
        root.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    private fun setPricingActive(active: Boolean) {
        val oldPriceIds = listOf<Int>(
            R.id.tvDrink1Old, R.id.tvDrink2Old, R.id.tvDrink3Old, 
            R.id.tvDrink4Old, R.id.tvDrink5Old
        )
        val newPriceIds = listOf<Int>(
            R.id.tvDrink1New, R.id.tvDrink2New, R.id.tvDrink3New, 
            R.id.tvDrink4New, R.id.tvDrink5New
        )
        
        for (i in oldPriceIds.indices) {
            val tvOld = findViewById<TextView>(oldPriceIds[i])
            val tvNew = findViewById<TextView>(newPriceIds[i])
            
            if (active) {
                tvOld.paintFlags = tvOld.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                tvOld.setTextColor(getColor(R.color.cocotte_text_secondary))
                tvNew.visibility = View.VISIBLE
            } else {
                tvOld.paintFlags = tvOld.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                tvOld.setTextColor(getColor(R.color.cocotte_text_main))
                tvNew.visibility = View.GONE
            }
        }
    }

    private fun updateUIWithError(message: String) {
        findViewById<TextView>(R.id.tvTitle).text = message
        findViewById<TextView>(R.id.tvTitle).setTextColor(getColor(android.R.color.holo_red_dark))
        findViewById<TextView>(R.id.tvSubtitle).visibility = View.GONE
        findViewById<TextView>(R.id.tvTimer).text = "--:--"
        setPricingActive(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
    }
}
