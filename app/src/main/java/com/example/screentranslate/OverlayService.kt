package com.example.screentranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.screentranslate.STOP"
        const val ACTION_CONFIG = "com.example.screentranslate.CONFIG"
        @Volatile var running = false
        private const val BUTTON_DP = 56
    }

    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: VirtualDisplay? = null
    private var latest: Image? = null

    private var button: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var resultLayer: FrameLayout? = null

    private var recognizer: TextRecognizer? = null
    private var targetLang = TranslateLanguage.PERSIAN
    private val translators = HashMap<String, Translator>()
    private val langId = LanguageIdentification.getClient()

    private var busy = false
    private var lastError: String? = null
    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        if (intent.action == ACTION_CONFIG) {
            if (projection == null) stopSelf() else applyConfig(intent)
            return START_NOT_STICKY
        }
        startAsForeground()
        if (projection != null) return START_NOT_STICKY

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else intent.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        targetLang = intent.getStringExtra("target") ?: TranslateLanguage.PERSIAN
        setRecognizer(intent.getStringExtra("script"))

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        running = true
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { stopSelf() } }
        }, handler)

        readScreenSize()
        val r = newReader()
        reader = r
        vDisplay = mp.createVirtualDisplay(
            "ScreenTranslate", screenW, screenH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        addButton()
        return START_NOT_STICKY
    }

    private fun setRecognizer(script: String?) {
        recognizer?.close()
        recognizer = TextRecognition.getClient(
            when (script) {
                "zh" -> ChineseTextRecognizerOptions.Builder().build()
                "ja" -> JapaneseTextRecognizerOptions.Builder().build()
                "ko" -> KoreanTextRecognizerOptions.Builder().build()
                "dev" -> DevanagariTextRecognizerOptions.Builder().build()
                else -> TextRecognizerOptions.DEFAULT_OPTIONS
            }
        )
    }

    private fun applyConfig(intent: Intent) {
        intent.getStringExtra("target")?.let { targetLang = it }
        intent.getStringExtra("script")?.let { setRecognizer(it) }
        clearResults()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("st", "Screen Translate", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "st")
            .setContentTitle("Screen Translate is running")
            .setContentText("Tap to stop")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(stopPi)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun readScreenSize() {
        val b = wm.currentWindowMetrics.bounds
        screenW = b.width()
        screenH = b.height()
    }

    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            latest?.close()
            latest = img
        }, handler)
        return r
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = vDisplay ?: return
        clearResults()
        readScreenSize()
        latest?.close(); latest = null
        val old = reader
        val r = newReader()
        reader = r
        vd.resize(screenW, screenH, resources.displayMetrics.densityDpi)
        vd.surface = r.surface
        old?.close()
    }

    // ---------- floating button ----------

    private fun addButton() {
        val d = resources.displayMetrics.density
        val size = (BUTTON_DP * d).toInt()
        val tv = TextView(this).apply {
            text = "文A"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD1565C0.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenH / 3
        }

        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false; var longPressed = false
        val longPress = Runnable { if (!moved) { longPressed = true; stopSelf() } }

        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; longPressed = false
                    handler.postDelayed(longPress, 1500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || abs(dx) > 15 || abs(dy) > 15) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!moved && !longPressed) onButtonTap()
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            true
        }
        button = tv
        buttonParams = lp
        wm.addView(tv, lp)
    }

    private fun buttonRect(): Rect {
        val p = buttonParams ?: return Rect()
        return Rect(p.x, p.y, p.x + p.width, p.y + p.height)
    }

    private fun bringButtonToFront() {
        val b = button ?: return
        val p = buttonParams ?: return
        try { wm.removeView(b); wm.addView(b, p) } catch (_: Exception) {}
    }

    // ---------- capture + OCR + translate ----------

    private fun onButtonTap() {
        if (resultLayer != null) { clearResults(); return }
        if (busy) return
        val img = latest
        if (img == null) { toast("No screen frame yet, try again"); return }
        val bmp = try { toBitmap(img) } catch (e: Exception) { toast("Capture failed"); return }
        busy = true
        toast("Scanning...")
        process(bmp)
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val pixelStride = p.pixelStride
        val rowPadding = p.rowStride - pixelStride * img.width
        val wide = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        wide.copyPixelsFromBuffer(p.buffer)
        return Bitmap.createBitmap(wide, 0, 0, img.width, img.height)
    }

    private fun process(bmp: Bitmap) {
        scope.launch {
            try {
                val rec = recognizer ?: return@launch
                lastError = null
                val result = withContext(Dispatchers.Default) {
                    rec.process(InputImage.fromBitmap(bmp, 0)).await()
                }
                val skip = buttonRect().apply { inset(-20, -20) }
                val items = mutableListOf<Triple<Rect, String, Int>>()
                for (block in result.textBlocks) {
                    val rect = block.boundingBox ?: continue
                    if (Rect.intersects(rect, skip)) continue
                    val src = block.lines.joinToString(" ") { it.text }.trim()
                    if (src.length < 2) continue
                    val out = translate(src) ?: continue
                    items.add(Triple(rect, out, block.lines.size))
                }
                if (items.isEmpty()) {
                    toast(lastError ?: "No translatable text found")
                } else {
                    showResults(items)
                }
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private suspend fun translate(text: String): String? {
        val tag = langId.identifyLanguage(text).await()
        if (tag == "und") return null
        val src = TranslateLanguage.fromLanguageTag(tag) ?: return null
        if (src == targetLang) return null
        val key = "$src>$targetLang"
        val tr = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(targetLang).build()
            )
        }
        return try {
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            tr.translate(text).await()
        } catch (e: Exception) {
            lastError = "Language pack ($src) not downloaded yet - connect to the internet once"
            null
        }
    }

    private fun showResults(items: List<Triple<Rect, String, Int>>) {
        val layer = FrameLayout(this)
        for ((rect, text, lines) in items) {
            val lineH = rect.height().toFloat() / lines.coerceAtLeast(1)
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(Color.BLACK)
                setBackgroundColor(0xF2FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, (lineH * 0.65f).coerceIn(22f, 64f))
                setPadding(6, 2, 6, 2)
                minHeight = rect.height()
            }
            val lp = FrameLayout.LayoutParams(rect.width(), FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = rect.left
            lp.topMargin = rect.top
            layer.addView(tv, lp)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(layer, params)
        resultLayer = layer
        bringButtonToFront()
    }

    private fun clearResults() {
        resultLayer?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        resultLayer = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        running = false
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        clearResults()
        button?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        vDisplay?.release()
        latest?.close()
        reader?.close()
        projection?.stop()
        recognizer?.close()
        translators.values.forEach { it.close() }
        langId.close()
        super.onDestroy()
    }
}
