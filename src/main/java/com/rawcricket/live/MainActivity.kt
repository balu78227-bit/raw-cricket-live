package com.rawcricket.live

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {

    data class BS(val r: Int = 0, val b: Int = 0)
    data class BW(val balls: Int = 0, val runs: Int = 0, val w: Int = 0)
    data class St(
        val runs: Int = 0, val wk: Int = 0, val legal: Int = 0, val strike: Int = 0,
        val bat: Map<String, BS> = emptyMap(), val bowl: Map<String, BW> = emptyMap(),
        val last: List<String> = emptyList()
    )

    private var st = St()
    private val hist = ArrayList<St>()
    private lateinit var cam: RtmpCamera2
    private lateinit var glView: OpenGlView
    private lateinit var tBat: EditText
    private lateinit var tBowl: EditText
    private lateinit var b1: EditText
    private lateinit var b2: EditText
    private lateinit var bowler: EditText
    private lateinit var url: EditText
    private lateinit var liveBtn: Button
    private lateinit var recBtn: Button
    private var recFile: File? = null
    private var filter = ImageObjectFilterRender()
    private var filterSet = false

    private val logo: Bitmap? by lazy {
        try {
            val id = resources.getIdentifier("logo", "drawable", packageName)
            if (id != 0) BitmapFactory.decodeResource(resources, id) else null
        } catch (e: Exception) { null }
    }

    private fun field(h: String, d: String, size: Float = 12f): EditText =
        EditText(this).apply {
            hint = h; setText(d); textSize = size; setSingleLine()
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            doAfterTextChanged { refresh() }
        }

    private fun lp(w: Float) = LinearLayout.LayoutParams(0, -2, w)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val root = FrameLayout(this)
        glView = OpenGlView(this)
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0x88000000.toInt()) }

        tBat = field("Batting team", "TEAM A")
        tBowl = field("Bowling team", "TEAM B")
        url = field("Stream URL + key", "", 11f)
        liveBtn = Button(this).apply { text = "GO LIVE"; setOnClickListener { toggleLive() } }
        recBtn = Button(this).apply { text = "REC"; setOnClickListener { toggleRec() } }
        val row1 = LinearLayout(this)
        row1.addView(tBat, lp(1f)); row1.addView(tBowl, lp(1f)); row1.addView(url, lp(2f))
        row1.addView(recBtn, LinearLayout.LayoutParams(-2, -2))
        row1.addView(liveBtn, LinearLayout.LayoutParams(-2, -2))
        top.addView(row1)

        b1 = field("Batter 1", "Batter 1")
        b2 = field("Batter 2", "Batter 2")
        bowler = field("Bowler", "Bowler")
        val row2 = LinearLayout(this)
        row2.addView(b1, lp(1f)); row2.addView(b2, lp(1f)); row2.addView(bowler, lp(1f))
        top.addView(row2)

        val row3 = LinearLayout(this)
        fun btn(t: String, a: () -> Unit) {
            row3.addView(Button(this).apply { text = t; textSize = 12f; setPadding(0, 0, 0, 0); setOnClickListener { a() } }, lp(1f))
        }
        for (r in listOf(0, 1, 2, 3, 4, 6)) btn("$r") { ball("run", r) }
        btn("Wd") { ball("wd") }
        btn("Nb") { ball("nb") }
        btn("W") { ball("w") }
        btn("Undo") { if (hist.isNotEmpty()) { st = hist.removeAt(hist.size - 1); refresh() } }
        top.addView(row3)

        root.addView(top, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
        setContentView(root)

        cam = RtmpCamera2(glView, this)
        glView.holder.addCallback(this)

        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 1)
        }
    }

    private fun hasPerms(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun installFilter() {
        try {
            filter = ImageObjectFilterRender()
            filter.setScale(100f, 100f)
            filter.setPosition(TranslateTo.CENTER)
            cam.getGlInterface().setFilter(filter)
            filter.setImage(drawOverlay())
            filterSet = true
        } catch (e: Exception) {
            toast("Overlay error: ${e.message}")
        }
    }

    private fun installFilterSoon() {
        installFilter()
        for (d in longArrayOf(300L, 1000L, 2500L)) {
            glView.postDelayed({
                if (cam.isOnPreview || cam.isStreaming || cam.isRecording) installFilter()
            }, d)
        }
    }

    private fun startCam() {
        if (!hasPerms()) return
        try {
            if (!cam.isOnPreview) cam.startPreview()
            if (!filterSet) installFilter()
        } catch (e: Exception) {
            toast("Camera error: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startCam()
    }

    private fun nm(e: EditText, d: String) = e.text.toString().trim().ifEmpty { d }

    private fun ball(kind: String, r: Int = 0) {
        val n1 = nm(b1, "Batter 1"); val n2 = nm(b2, "Batter 2"); val bn = nm(bowler, "Bowler")
        val sn = if (st.strike == 0) n1 else n2
        var runs = st.runs; var wk = st.wk; var legal = st.legal; var strike = st.strike
        val bat = st.bat.toMutableMap(); val bw = st.bowl.toMutableMap()
        val sb = bat[sn] ?: BS(); val bb = bw[bn] ?: BW()
        var label = ""
        when (kind) {
            "run" -> {
                runs += r; legal++
                bat[sn] = BS(sb.r + r, sb.b + 1)
                bw[bn] = BW(bb.balls + 1, bb.runs + r, bb.w)
                if (r % 2 == 1) strike = 1 - strike
                label = "$r"
            }
            "wd" -> { runs++; bw[bn] = BW(bb.balls, bb.runs + 1, bb.w); label = "Wd" }
            "nb" -> { runs++; bw[bn] = BW(bb.balls, bb.runs + 1, bb.w); label = "Nb" }
            else -> {
                wk++; legal++
                bat[sn] = BS(sb.r, sb.b + 1)
                bw[bn] = BW(bb.balls + 1, bb.runs, bb.w + 1)
                label = "W"
                toast("புதிய batter பெயரை மாற்றுங்கள்")
            }
        }
        if ((kind == "run" || kind == "w") && legal % 6 == 0) {
            strike = 1 - strike
            toast("ஓவர் முடிந்தது: bowler பெயரை மாற்றுங்கள்")
        }
        hist.add(st)
        st = St(runs, wk, legal, strike, bat, bw, (st.last + label).takeLast(6))
        refresh()
    }

    private fun refresh() {
        if (filterSet) {
            try { filter.setImage(drawOverlay()) } catch (e: Exception) {}
        }
    }

    private fun ov(b: Int) = "${b / 6}.${b % 6}"

    private fun fit(c: Canvas, t: String, x: Float, y: Float, maxW: Float, p: Paint) {
        var s = t
        while (s.length > 1 && p.measureText(s) > maxW) s = s.dropLast(1)
        c.drawText(s, x, y, p)
    }

    private fun drawOverlay(): Bitmap {
        val w = 1280; val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.DEFAULT_BOLD
        val navy = 0xF00B1F3A.toInt()
        val gold = 0xFFFFC107.toInt()

        var x = 24f
        val lg = logo
        if (lg != null && lg.height > 0) {
            val lh = 64f; val lw = lg.width * lh / lg.height
            c.drawBitmap(lg, null, RectF(x, 20f, x + lw, 20f + lh), p)
            x += lw + 10f
        }
        p.textSize = 26f
        val cw = p.measureText("RAW CRICKET TN")
        p.color = navy; c.drawRoundRect(x, 30f, x + cw + 28f, 74f, 10f, 10f, p)
        p.color = gold; c.drawText("RAW CRICKET TN", x + 14f, 61f, p)

        val n1 = nm(b1, "Batter 1"); val n2 = nm(b2, "Batter 2"); val bn = nm(bowler, "Bowler")
        val top = 640f; val bot = 704f

        val lb = st.last.joinToString(" ")
        if (lb.isNotEmpty()) {
            p.textSize = 24f
            val tw = p.measureText(lb)
            p.color = navy; c.drawRoundRect(20f, top - 44f, 20f + tw + 24f, top - 8f, 8f, 8f, p)
            p.color = Color.WHITE; c.drawText(lb, 32f, top - 17f, p)
        }

        p.color = navy; c.drawRect(20f, top, 1260f, bot, p)
        p.color = gold; c.drawRect(20f, top, 390f, bot, p)
        c.drawRect(1150f, top, 1260f, bot, p)

        p.color = Color.BLACK; p.textSize = 30f
        fit(c, nm(tBat, "TEAM A").uppercase(), 32f, top + 42f, 130f, p)
        p.textSize = 38f
        val sc = "${st.runs}-${st.wk}"
        c.drawText(sc, 172f, top + 44f, p)
        val sw = p.measureText(sc)
        p.textSize = 24f
        c.drawText(ov(st.legal), 172f + sw + 10f, top + 42f, p)

        for (i in 0..1) {
            val name = if (i == 0) n1 else n2
            val s = st.bat[name] ?: BS()
            val bx = 405f + i * 240f
            p.color = Color.WHITE; p.textSize = 26f
            fit(c, name + if (st.strike == i) "*" else "", bx, top + 41f, 125f, p)
            p.color = gold; p.textSize = 34f
            val rs = "${s.r}"
            c.drawText(rs, bx + 135f, top + 43f, p)
            val rw = p.measureText(rs)
            p.color = Color.WHITE; p.textSize = 20f
            c.drawText("${s.b}", bx + 135f + rw + 5f, top + 41f, p)
        }

        val bw = st.bowl[bn] ?: BW()
        p.color = Color.WHITE; p.textSize = 26f
        fit(c, bn, 890f, top + 41f, 125f, p)
        p.color = gold; p.textSize = 34f
        val ws = "${bw.w}-${bw.runs}"
        c.drawText(ws, 1025f, top + 43f, p)
        val wsw = p.measureText(ws)
        p.color = Color.WHITE; p.textSize = 20f
        c.drawText(ov(bw.balls), 1025f + wsw + 5f, top + 41f, p)

        p.color = Color.BLACK; p.textSize = 28f
        fit(c, nm(tBowl, "TEAM B").uppercase(), 1160f, top + 42f, 92f, p)
        return bmp
    }

    private fun newRecFile(): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val name = "RawCricket_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        return File(dir, name)
    }

    private fun finishRecording() {
        val f = recFile ?: return
        recFile = null
        if (Build.VERSION.SDK_INT < 29) { toast("பதிவு: ${f.absolutePath}"); return }
        toast("பதிவை Gallery-க்கு நகர்த்துகிறது... காத்திருக்கவும்")
        Thread {
            try {
                val v = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RawCricket")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                    ?: throw Exception("insert failed")
                contentResolver.openOutputStream(uri)?.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                }
                v.clear()
                v.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, v, null, null)
                f.delete()
                toast("சேமிக்கப்பட்டது: Gallery → Movies/RawCricket")
            } catch (e: Exception) {
                toast("நகர்த்த முடியவில்லை. கோப்பு: ${f.absolutePath}")
            }
        }.start()
    }

    private fun toggleRec() {
        try {
            if (cam.isStreaming) { toast("Live நடக்கும்போது பதிவு தானாகவே நடக்கிறது"); return }
            if (cam.isRecording) {
                cam.stopRecord()
                recBtn.text = "REC"
                installFilterSoon()
                finishRecording()
                return
            }
            if (!hasPerms()) { toast("Camera/Mic அனுமதி தேவை"); return }
            if (cam.prepareAudio() && cam.prepareVideo(1280, 720, 30, 3000 * 1000, 0)) {
                val f = newRecFile()
                recFile = f
                cam.startRecord(f.absolutePath)
                recBtn.text = "STOP REC"
                installFilterSoon()
            } else toast("Camera/Audio தயாராகவில்லை")
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
    }

    private fun toggleLive() {
        if (cam.isStreaming) {
            cam.stopStream()
            if (cam.isRecording) { cam.stopRecord(); finishRecording() }
            liveBtn.text = "GO LIVE"
            recBtn.text = "REC"
            installFilterSoon()
            return
        }
        if (cam.isRecording) { toast("முதலில் REC-ஐ நிறுத்துங்கள்"); return }
        val target = url.text.toString().trim()
        if (target.isEmpty()) { toast("Stream URL + key உள்ளிடுங்கள்"); return }
        if (!hasPerms()) { toast("Camera/Mic அனுமதி தேவை"); return }
        try {
            if (cam.prepareAudio() && cam.prepareVideo(1280, 720, 30, 2500 * 1000, 0)) {
                val f = newRecFile()
                recFile = f
                try { cam.startRecord(f.absolutePath) } catch (e: Exception) { recFile = null; toast("Recording தொடங்கவில்லை") }
                cam.startStream(target)
                liveBtn.text = "STOP"
                installFilterSoon()
            } else toast("Camera/Audio தயாராகவில்லை")
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
    }

    override fun surfaceCreated(h: SurfaceHolder) {}
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { startCam() }
    override fun surfaceDestroyed(h: SurfaceHolder) {
        try {
            if (cam.isStreaming) cam.stopStream()
            if (cam.isRecording) { cam.stopRecord(); finishRecording() }
            if (cam.isOnPreview) cam.stopPreview()
        } catch (e: Exception) {}
        filterSet = false
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() { toast("Live தொடங்கியது") }
    override fun onConnectionFailed(reason: String) {
        toast("இணைப்பு தோல்வி: $reason")
        runOnUiThread {
            cam.stopStream()
            liveBtn.text = "GO LIVE"
            if (cam.isRecording) recBtn.text = "STOP REC"
        }
    }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() { toast("Disconnected") }
    override fun onAuthError() { toast("Auth error") }
    override fun onAuthSuccess() {}
}s.r}"
            c.drawText(rs, bx + 135f, top + 43f, p)
            val rw = p.measureText(rs)
            p.color = Color.WHITE; p.textSize = 20f
            c.drawText("${s.b}", bx + 135f + rw + 5f, top + 41f, p)
        }

        val bw = st.bowl[bn] ?: BW()
        p.color = Color.WHITE; p.textSize = 26f
        fit(c, bn, 890f, top + 41f, 125f, p)
        p.color = gold; p.textSize = 34f
        val ws = "${bw.w}-${bw.runs}"
        c.drawText(ws, 1025f, top + 43f, p)
        val wsw = p.measureText(ws)
        p.color = Color.WHITE; p.textSize = 20f
        c.drawText(ov(bw.balls), 1025f + wsw + 5f, top + 41f, p)

        p.color = Color.BLACK; p.textSize = 28f
        fit(c, nm(tBowl, "TEAM B").uppercase(), 1160f, top + 42f, 92f, p)
        return bmp
    }

    private fun newRecFile(): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val name = "RawCricket_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        return File(dir, name)
    }

    private fun finishRecording() {
        val f = recFile ?: return
        recFile = null
        if (Build.VERSION.SDK_INT < 29) { toast("பதிவு: ${f.absolutePath}"); return }
        toast("பதிவை Gallery-க்கு நகர்த்துகிறது... காத்திருக்கவும்")
        Thread {
            try {
                val v = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RawCricket")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                    ?: throw Exception("insert failed")
                contentResolver.openOutputStream(uri)?.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                }
                v.clear()
                v.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, v, null, null)
                f.delete()
                toast("சேமிக்கப்பட்டது: Gallery → Movies/RawCricket")
            } catch (e: Exception) {
                toast("நகர்த்த முடியவில்லை. கோப்பு: ${f.absolutePath}")
            }
        }.start()
    }

    private fun toggleRec() {
        try {
            if (cam.isStreaming) { toast("Live நடக்கும்போது பதிவு தானாகவே நடக்கிறது"); return }
            if (cam.isRecording) {
                cam.stopRecord()
                recBtn.text = "REC"
                finishRecording()
                return
            }
            if (!hasPerms()) { toast("Camera/Mic அனுமதி தேவை"); return }
            if (cam.prepareAudio() && cam.prepareVideo(1280, 720, 30, 3000 * 1000, 0)) {
                val f = newRecFile()
                recFile = f
                cam.startRecord(f.absolutePath)
                recBtn.text = "STOP REC"
            } else toast("Camera/Audio தயாராகவில்லை")
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
    }

    private fun toggleLive() {
        if (cam.isStreaming) {
            cam.stopStream()
            if (cam.isRecording) { cam.stopRecord(); finishRecording() }
            liveBtn.text = "GO LIVE"
            recBtn.text = "REC"
            return
        }
        if (cam.isRecording) { toast("முதலில் REC-ஐ நிறுத்துங்கள்"); return }
        val target = url.text.toString().trim()
        if (target.isEmpty()) { toast("Stream URL + key உள்ளிடுங்கள்"); return }
        if (!hasPerms()) { toast("Camera/Mic அனுமதி தேவை"); return }
        try {
            if (cam.prepareAudio() && cam.prepareVideo(1280, 720, 30, 2500 * 1000, 0)) {
                val f = newRecFile()
                recFile = f
                try { cam.startRecord(f.absolutePath) } catch (e: Exception) { recFile = null; toast("Recording தொடங்கவில்லை") }
                cam.startStream(target)
                liveBtn.text = "STOP"
            } else toast("Camera/Audio தயாராகவில்லை")
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
    }

    override fun surfaceCreated(h: SurfaceHolder) {}
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { startCam() }
    override fun surfaceDestroyed(h: SurfaceHolder) {
        try {
            if (cam.isStreaming) cam.stopStream()
            if (cam.isRecording) { cam.stopRecord(); finishRecording() }
            if (cam.isOnPreview) cam.stopPreview()
        } catch (e: Exception) {}
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() { toast("Live தொடங்கியது") }
    override fun onConnectionFailed(reason: String) {
        toast("இணைப்பு தோல்வி: $reason")
        runOnUiThread {
            cam.stopStream()
            liveBtn.text = "GO LIVE"
            if (cam.isRecording) recBtn.text = "STOP REC"
        }
    }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() { toast("Disconnected") }
    override fun onAuthError() { toast("Auth error") }
    override fun onAuthSuccess() {}
}
