package com.cocotte.overlayprototype

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnAuthorize: Button
    private lateinit var btnShow: Button
    private lateinit var btnHide: Button
    private lateinit var btnKill: Button
    private lateinit var etServerUrl: EditText
    private lateinit var btnSaveServer: Button
    private lateinit var btnOpenDex: Button
    private lateinit var tvDiagnostics: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnAuthorize = findViewById(R.id.btnAuthorize)
        btnShow = findViewById(R.id.btnShow)
        btnHide = findViewById(R.id.btnHide)
        btnKill = findViewById(R.id.btnKill)
        etServerUrl = findViewById(R.id.etServerUrl)
        btnSaveServer = findViewById(R.id.btnSaveServer)
        btnOpenDex = findViewById(R.id.btnOpenDex)
        tvDiagnostics = findViewById(R.id.tvDiagnostics)

        val prefs = getSharedPreferences("cocotte_prefs", MODE_PRIVATE)
        etServerUrl.setText(prefs.getString("server_url", "http://10.0.2.2:3000"))

        btnSaveServer.setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                prefs.edit().putString("server_url", url).apply()
                Toast.makeText(this, getString(R.string.config_saved), Toast.LENGTH_SHORT).show()
            }
        }

        btnShow.setOnClickListener {
            startOverlayService()
        }

        btnHide.setOnClickListener {
            stopOverlayService()
        }

        btnKill.setOnClickListener {
            killOverlayService()
        }

        btnAuthorize.setOnClickListener {
            checkAndRequestOverlayPermission()
        }

        btnOpenDex.setOnClickListener {
            val intent = Intent(this, DexDisplayActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateDiagnostics()
    }

    private fun updateDiagnostics() {
        val displayManager = getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val displays = displayManager.displays
        val count = displays.size
        var externalFound = false
        displays.forEach { 
            if (it.displayId != android.view.Display.DEFAULT_DISPLAY) externalFound = true
        }
        
        val externalStr = if (externalFound) getString(R.string.yes) else getString(R.string.no)
        tvDiagnostics.text = getString(R.string.diagnostics_label, count, externalStr)
    }

    private fun checkAndRequestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "Permission déjà accordée", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Veuillez d'abord autoriser l'overlay", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, OverlayService::class.java)
        startService(intent)
        updateStatus()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
        updateStatus()
    }

    private fun killOverlayService() {
        // Kill is similar to stop but we can add more "brutal" logic if needed
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
        Toast.makeText(this, "Overlay Killed", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun updateStatus() {
        if (OverlayService.isRunning) {
            tvStatus.text = getString(R.string.status_active)
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            tvStatus.text = getString(R.string.status_inactive)
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }
}
