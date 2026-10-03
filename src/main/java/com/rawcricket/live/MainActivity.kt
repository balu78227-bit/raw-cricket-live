package com.rawcricket.live

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Environment
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {

    data class Ball(val runs: Int, val legal: Boolean, val wkt: Boolean, val label: String)

    private val balls = ArrayList<Ball>()
    private lateinit var cam: RtmpCamera2
    private lateinit var glView: OpenGlView
    private lateinit var team: EditText
    private lateinit var url: EditText
    private lateinit var liveBtn: Button
    private val filter = ImageObjectFilterRender()
    private var filterSet = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val root = FrameLayout(this)
        glView = OpenGlView(this)
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0x66000000) }
        val row1 = LinearLayout(this)
        team = EditText(this).apply { hint = "Batting team"; setText("TEAM A"); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 14f }
        url = EditText(this).apply { hint = "Stream URL + key"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 12f; setSingleLine() }
        liveBtn = Button(this).apply { text = "GO LIVE"; setOnClickListener { toggleLive() } }
        row1.addView(team, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(url, LinearLayout.LayoutParams(0, -2, 2f))
        row1.addView(liveBtn, LinearLayout.LayoutParams(-2, -2))
        top.addView(row1)

        val row2 = LinearLayout(this)
        fun btn(t: String, a: () -> Unit) {
            row2.addView(Button(this).apply { text = t; textSize = 12f; setPadding(0, 0, 0, 0); setOnClickListener { a() } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        for (r in listOf(0, 1, 2, 3, 4, 6)) btn("$r") { add(Ball(r, true, false, "$r")) }
        btn("Wd") { add(Ball(1, false, false, "Wd")) }
        btn("Nb") { add(Ball(1, false, false, "Nb")) }
        btn("W") { add(Ball(0, true, true, "W")) }
        btn("Undo") { if (balls.isNotEmpty()) { balls.removeAt(balls.size - 1); refresh() } }
        top.addView(row2)
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

    private fun startCam() {
        if (!hasPerms()) return
        try {
            if (!cam.isOnPreview) cam.startPreview()
            if (!filterSet) {
                filter.setScale(100f, 12.5f)
                filter.setPosition(TranslateTo.BOTTOM)
                cam.getGlInterface().setFilter(filter)
                filter.setImage(drawBar())
                filterSet = true
            }
        } catch (e: Exception) {
            toast("Camera error: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startCam()
    }

    private fun add(b: Ball) { balls.add(b); refresh() }
    private fun refresh() { if (filterSet) filter.setImage(drawBar()) }

    private fun drawBar(): Bitmap {
        val w = 1280; val h = 90
        val runs = balls.sumOf { it.runs }
        val wk = balls.count { it.wkt }
        val legal = balls.count { it.legal }
        val overs = "${legal / 6}.${legal % 6}"
        val last = balls.takeLast(6).joinToString(" ") { it.label }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xE60B1F3A.toInt(); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.color = 0xFFFFC107.toInt(); c.drawRect(0f, 0f, 340f, h.toFloat(), p)
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = Color.BLACK; p.textSize = 34f
        c.drawText("RAW CRICKET TN", 16f, 58f, p)
        p.color = Color.WHITE; p.textSize = 44f
        c.drawText("${team.text.toString().uppercase()}  $runs/$wk", 360f, 62f, p)
        p.textSize = 36f; p.color = 0xFFFFC107.toInt()
        c.drawText("($overs)", 840f, 60f, p)
        p.textSize = 30f; p.color = Color.WHITE
        c.drawText(last, 980f, 58f, p)
        return bmp
    }

    private fun toggleLive() {
        if (cam.isStreaming) {
            cam.stopStream()
            if (cam.isRecording) cam.stopRecord()
            liveBtn.text = "GO LIVE"
            return
        }
        val target = url.text.toString().trim()
        if (target.isEmpty()) { toast("Stream URL + key உள்ளிடுங்கள்"); return }
        if (!hasPerms()) { toast("Camera/Mic அனுமதி தேவை"); return }
        try {
            if (cam.prepareAudio() && cam.prepareVideo(1280, 720, 30, 2500 * 1000, 0)) {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                try { cam.startRecord(File(dir, name).absolutePath) } catch (e: Exception) { toast("Recording தொடங்கவில்லை") }
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
            if (cam.isStreaming) { cam.stopStream(); if (cam.isRecording) cam.stopRecord() }
            if (cam.isOnPreview) cam.stopPreview()
        } catch (e: Exception) {}
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() { toast("Live தொடங்கியது") }
    override fun onConnectionFailed(reason: String) {
        toast("இணைப்பு தோல்வி: $reason")
        runOnUiThread { cam.stopStream(); liveBtn.text = "GO LIVE" }
    }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() { toast("Disconnected") }
    override fun onAuthError() { toast("Auth error") }
    override fun onAuthSuccess() {}
}
