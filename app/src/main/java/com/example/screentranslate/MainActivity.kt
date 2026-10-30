package com.example.screentranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    private val reqOverlay = 1
    private val reqCapture = 2

    // label -> OCR script code
    private val sources = listOf(
        "Latin (English, French, German, Spanish, Turkish...)" to "latin",
        "Chinese" to "zh",
        "Japanese" to "ja",
        "Korean" to "ko",
        "Devanagari (Hindi, Marathi, Nepali)" to "dev"
    )

    // label -> ML Kit translate language code
    private val targets = listOf(
        "Persian (فارسی)" to "fa",
        "English" to "en",
        "Arabic" to "ar",
        "Turkish" to "tr",
        "Russian" to "ru",
        "German" to "de",
        "French" to "fr",
        "Spanish" to "es",
        "Chinese" to "zh",
        "Japanese" to "ja",
        "Korean" to "ko"
    )

    private lateinit var srcSpinner: Spinner
    private lateinit var tgtSpinner: Spinner
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, pad / 2, 0, 0) }

        root.addView(TextView(this).apply { text = "Screen Translate"; textSize = 26f })
        root.addView(label("Text language on screen (OCR):"))
        srcSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, sources.map { it.first })
        }
        root.addView(srcSpinner)
        root.addView(label("Translate to:"))
        tgtSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, targets.map { it.first })
        }
        root.addView(tgtSpinner)
        srcSpinner.setSelection(prefs.getInt("src", 0))
        tgtSpinner.setSelection(prefs.getInt("tgt", 0))
        val changed = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit()
                    .putInt("src", srcSpinner.selectedItemPosition)
                    .putInt("tgt", tgtSpinner.selectedItemPosition)
                    .apply()
                sendConfig()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        srcSpinner.onItemSelectedListener = changed
        tgtSpinner.onItemSelectedListener = changed
        root.addView(Button(this).apply {
            text = "Start floating button"
            setOnClickListener { start() }
        })
        root.addView(TextView(this).apply {
            text = "Tap the floating button to translate the screen. Tap again to hide the translation. Long-press (1.5s) the button to quit.\n\nTo change the languages while it is running: open this app and pick another language - it applies instantly."
            setPadding(0, pad, 0, 0)
        })
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        }
    }

    // Apply language changes immediately to the running floating-button service
    private fun sendConfig() {
        if (!OverlayService.running) return
        startService(
            Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_CONFIG)
                .putExtra("script", sources[srcSpinner.selectedItemPosition].second)
                .putExtra("target", targets[tgtSpinner.selectedItemPosition].second)
        )
    }

    private fun start() {
        if (OverlayService.running) {
            sendConfig()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                reqOverlay
            )
        } else {
            requestCapture()
        }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            reqOverlay -> if (Settings.canDrawOverlays(this)) requestCapture()
            reqCapture -> if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, OverlayService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                    .putExtra("script", sources[srcSpinner.selectedItemPosition].second)
                    .putExtra("target", targets[tgtSpinner.selectedItemPosition].second)
                startForegroundService(svc)
                moveTaskToBack(true)
            }
        }
    }
}
