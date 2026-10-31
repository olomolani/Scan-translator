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
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.screentranslate.STOP"
        const val ACTION_CONFIG = "com.example.screentranslate.CONFIG"
        @Volatile var running = false
        private const val BUTTON_DP = 56
        private const val SIG_W = 54
        private const val SIG_H = 96
        private const val VERIFY_MS = 5000L
        private val BLUE = 0xDD1565C0.toInt()
        private val GREEN = 0xDD2E7D32.toInt()
    }

    private class Item(val rect: Rect, val text: String, val lines: Int)

    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: VirtualDisplay? = null
    private var latest: Image? = null
    private var frameCount = 0L

    private var button: TextView? = null
    private var buttonBg: GradientDrawable? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var resultLayer: FrameLayout? = null
    private val pool = ArrayList<TextView>()

    private var recognizer: TextRecognizer? = null
    private var targetLang = TranslateLanguage.PERSIAN
    private var latinLang: String? = TranslateLanguage.ENGLISH   // null = auto-detect
    private var hanLang = TranslateLanguage.CHINESE
    private var maxSide = 1800
    private val translators = HashMap<String, Translator>()
    private val ready = HashSet<String>()
    private val cache = HashMap<String, String>()
    private val langId = LanguageIdentification.getClient()

    // live state
    private var live = false
    private var liveJob: Job? = null
    private var shown = false            // translations currently visible
    private var dirty = false            // user touched the screen / settings changed
    private var cleanPending = false     // we hid the overlay and need a fresh frame
    private var hideFrame = 0L
    private var lastItems: List<Item>? = null
    private var lastCleanSig: IntArray? = null
    private var refSig: IntArray? = null
    private var shownAt = 0L

    private var lastError: String? = null
    private var errorToastAt = 0L
    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        if (intent.action == ACTION_CONFIG) {
            if (projection == null) stopSelf() else { readConfig(intent); invalidate() }
            return START_NOT_STICKY
        }
        startAsForeground()
        if (projection != null) return START_NOT_STICKY

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else intent.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        readConfig(intent)
        if (recognizer == null) setRecognizer(null)

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

    private fun readConfig(intent: Intent) {
        intent.getStringExtra("target")?.let { targetLang = it }
        intent.getStringExtra("latin")?.let { latinLang = if (it == "auto") null else it }
        intent.getStringExtra("script")?.let {
            setRecognizer(it)
            hanLang = if (it == "ja") TranslateLanguage.JAPANESE else TranslateLanguage.CHINESE
        }
        val q = intent.getIntExtra("quality", -1)
        if (q >= 0) maxSide = when (q) { 0 -> 1280; 1 -> 1800; else -> Int.MAX_VALUE }
    }

    private fun invalidate() {
        lastItems = null
        lastCleanSig = null
        dirty = true
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

    // ---------- screen frames ----------

    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            latest?.close()
            latest = img
            frameCount++
        }, handler)
        return r
    }

    // Recreate the capture surface (used if frames ever stop arriving)
    private fun resetReader() {
        val vd = vDisplay ?: return
        latest?.close(); latest = null
        val old = reader
        val r = newReader()
        reader = r
        vd.surface = r.surface
        old?.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = vDisplay ?: return
        readScreenSize()
        latest?.close(); latest = null
        val old = reader
        val r = newReader()
        reader = r
        vd.resize(screenW, screenH, resources.displayMetrics.densityDpi)
        vd.surface = r.surface
        old?.close()
        invalidate()
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val pixelStride = p.pixelStride
        val rowPadding = p.rowStride - pixelStride * img.width
        val wide = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        val buf = p.buffer
        buf.rewind()   // IMPORTANT: the same frame can be read more than once
        wide.copyPixelsFromBuffer(buf)
        val out = Bitmap.createBitmap(wide, 0, 0, img.width, img.height)
        if (out !== wide) wide.recycle()
        return out
    }

    // Tiny fingerprint of the screen (cheap) used to notice that something changed
    private fun signature(img: Image): IntArray {
        val p = img.planes[0]
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val w = img.width
        val h = img.height
        val out = IntArray(SIG_W * SIG_H)
        var k = 0
        for (gy in 0 until SIG_H) {
            val y = (gy * h) / SIG_H + h / (2 * SIG_H)
            for (gx in 0 until SIG_W) {
                val x = (gx * w) / SIG_W + w / (2 * SIG_W)
                val i = y * rs + x * ps
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                out[k++] = (r + g + b) / 3
            }
        }
        return out
    }

    private fun changed(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null) return true
        var n = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 40) n++
        return n * 100 > a.size   // more than 1% of the sampled points differ
    }

    // ---------- floating button ----------

    private fun addButton() {
        val d = resources.displayMetrics.density
        val size = (BUTTON_DP * d).toInt()
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BLUE)
        }
        buttonBg = bg
        val tv = TextView(this).apply {
            text = "文A"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = bg
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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
                MotionEvent.ACTION_OUTSIDE -> onScreenTouched()
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

    private fun onButtonTap() {
        if (live) stopLive() else startLive()
    }

    // The user touched something outside our button (probably scrolling/tapping)
    private fun onScreenTouched() {
        if (!live) return
        dirty = true
        hideOverlays()
    }

    // ---------- live translation ----------

    private fun startLive() {
        if (live) return
        live = true
        buttonBg?.setColor(GREEN)
        ensureLayer()
        invalidate()
        shownAt = System.currentTimeMillis()
        toast("Live translation ON")
        liveJob = scope.launch {
            while (isActive) {
                try {
                    val img = latest
                    if (img != null) {
                        val now = System.currentTimeMillis()
                        val sig = signature(img)
                        if (refSig == null && now - shownAt > 350) refSig = sig
                        val changedNow = refSig != null && changed(refSig, sig)
                        if (dirty || changedNow || lastItems == null || now - shownAt > VERIFY_MS) {
                            scanOnce()
                            refSig = null
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    lastError = "Translation took too long"
                    recover()
                    delay(800)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    recover()
                    delay(1000)
                }
                val err = lastError
                val now2 = System.currentTimeMillis()
                if (err != null && now2 - errorToastAt > 5000) {
                    errorToastAt = now2
                    lastError = null
                    toast(err)
                }
                delay(150)
            }
        }
    }

    private fun stopLive() {
        live = false
        liveJob?.cancel()
        liveJob = null
        clearResults()
        shown = false
        dirty = false
        cleanPending = false
        lastItems = null
        lastCleanSig = null
        refSig = null
        buttonBg?.setColor(BLUE)
        toast("Live translation OFF")
    }

    private fun hideOverlays() {
        val layer = resultLayer ?: return
        if (!shown) return
        layer.animate().cancel()
        layer.alpha = 0f
        shown = false
        hideFrame = frameCount
        cleanPending = true
    }

    private fun showOverlays() {
        val layer = resultLayer ?: return
        val any = pool.any { it.visibility == View.VISIBLE }
        layer.animate().cancel()
        if (any) {
            layer.alpha = 0f
            layer.animate().alpha(1f).setDuration(120).start()
        }
        shown = any
        shownAt = System.currentTimeMillis()
    }

    private fun recover() {
        val layer = resultLayer ?: return
        layer.animate().cancel()
        layer.alpha = 1f
        shown = pool.any { it.visibility == View.VISIBLE }
        cleanPending = false
        shownAt = System.currentTimeMillis()
        if (lastItems == null) lastItems = emptyList()
    }

    private suspend fun scanOnce() {
        dirty = false
        if (shown) hideOverlays()

        // wait until the screen shows the real app without our translations on top
        if (cleanPending) {
            var waited = 0
            while (frameCount == hideFrame && waited < 400) { delay(16); waited += 16 }
            if (frameCount == hideFrame) { resetReader(); delay(200) }   // frames stalled -> restart capture
            delay(50)
            cleanPending = false
        }

        val first = latest ?: return
        val sigA = signature(first)
        if (lastItems != null && !changed(lastCleanSig, sigA)) {
            showOverlays()          // nothing changed: just bring the translations back
            return
        }

        // wait until scrolling / animation stops (max 3 seconds)
        var prev = sigA
        var stable = 0
        var t = 0
        while (stable < 2 && t < 3000) {
            delay(110); t += 110
            val cur = latest?.let { signature(it) } ?: break
            if (changed(prev, cur)) stable = 0 else stable++
            prev = cur
        }

        val img = latest ?: return
        val sigClean = signature(img)
        val full = toBitmap(img)
        val side = maxOf(full.width, full.height)
        val scale = if (side <= maxSide) 1f else maxSide.toFloat() / side
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
                .also { if (it !== full) full.recycle() }
        else full

        val items = try {
            withTimeout(15000) { analyze(bmp, scale) }
        } finally {
            bmp.recycle()
        }
        if (items != null) {
            render(items)
            lastItems = items
            lastCleanSig = sigClean
        }
        showOverlays()
    }

    private suspend fun analyze(bmp: Bitmap, scale: Float): List<Item>? {
        val rec = recognizer ?: return null
        val result = withContext(Dispatchers.Default) {
            rec.process(InputImage.fromBitmap(bmp, 0)).await()
        }
        val skip = buttonRect().apply { inset(-20, -20) }
        val items = ArrayList<Item>()
        for (block in result.textBlocks) {
            val r0 = block.boundingBox ?: continue
            val rect = if (scale < 1f) Rect(
                (r0.left / scale).toInt(), (r0.top / scale).toInt(),
                (r0.right / scale).toInt(), (r0.bottom / scale).toInt()
            ) else r0
            if (Rect.intersects(rect, skip)) continue
            val src = block.lines.joinToString(" ") { it.text }.trim()
            if (src.length < 2) continue
            val out = translate(src) ?: continue
            items.add(Item(rect, out, block.lines.size))
        }
        return items
    }

    // Decide the language by the letters themselves (reliable), not by guessing
    private suspend fun detectSource(text: String): String? {
        var han = 0; var kana = 0; var hangul = 0; var deva = 0; var latin = 0
        for (ch in text) {
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.DEVANAGARI -> deva++
                Character.UnicodeScript.LATIN -> latin++
                else -> {}
            }
        }
        return when {
            kana > 0 -> TranslateLanguage.JAPANESE
            hangul > 0 -> TranslateLanguage.KOREAN
            han > 0 -> hanLang
            deva > 0 -> TranslateLanguage.HINDI
            latin == 0 -> null
            latinLang != null -> latinLang
            else -> {
                val tag = langId.identifyLanguage(text).await()
                TranslateLanguage.fromLanguageTag(if (tag == "und") "en" else tag) ?: TranslateLanguage.ENGLISH
            }
        }
    }

    private suspend fun translate(text: String): String? {
        val src = detectSource(text) ?: return null
        if (src == targetLang) return null
        val pair = "$src>$targetLang"
        val ck = "$pair|$text"
        cache[ck]?.let { return it }
        val tr = translators.getOrPut(pair) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(targetLang).build()
            )
        }
        return try {
            if (pair !in ready) {
                tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                ready.add(pair)
            }
            val out = tr.translate(text).await()
            if (cache.size > 3000) cache.clear()
            cache[ck] = out
            out
        } catch (e: Exception) {
            lastError = "Language pack ($src) not downloaded yet - connect to the internet once"
            null
        }
    }

    // ---------- overlay drawing ----------

    private fun ensureLayer() {
        if (resultLayer != null) return
        val layer = FrameLayout(this)
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

    private fun newPoolView(layer: FrameLayout): TextView {
        val tv = TextView(this).apply {
            setTextColor(Color.BLACK)
            setBackgroundColor(0xF2FFFFFF.toInt())
            setPadding(6, 2, 6, 2)
            visibility = View.GONE
        }
        layer.addView(tv, FrameLayout.LayoutParams(10, FrameLayout.LayoutParams.WRAP_CONTENT))
        pool.add(tv)
        return tv
    }

    private fun render(items: List<Item>) {
        val layer = resultLayer ?: return
        for ((i, item) in items.withIndex()) {
            val tv = if (i < pool.size) pool[i] else newPoolView(layer)
            place(tv, item)
        }
        for (j in items.size until pool.size) pool[j].visibility = View.GONE
    }

    private fun place(tv: TextView, item: Item) {
        val lineH = item.rect.height().toFloat() / item.lines.coerceAtLeast(1)
        tv.text = item.text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, (lineH * 0.65f).coerceIn(22f, 64f))
        tv.minHeight = item.rect.height()
        val lp = tv.layoutParams as FrameLayout.LayoutParams
        if (lp.width != item.rect.width()) {
            lp.width = item.rect.width()
            tv.layoutParams = lp
        }
        tv.translationX = item.rect.left.toFloat()
        tv.translationY = item.rect.top.toFloat()
        tv.visibility = View.VISIBLE
    }

    private fun clearResults() {
        resultLayer?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        resultLayer = null
        pool.clear()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        running = false
        live = false
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
