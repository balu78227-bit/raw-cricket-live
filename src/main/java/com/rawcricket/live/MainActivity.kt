package com.rawcricket.live

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.extrasources.CameraUvcSource
import com.pedro.library.base.recording.RecordController
import com.pedro.library.rtmp.RtmpStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RtspSource(
    private val ctx: android.content.Context,
    private val rtspUrl: String,
    private val onError: (String) -> Unit
) : VideoSource() {
    private var player: ExoPlayer? = null
    private var running = false
    private var surf: Surface? = null
    private val main = Handler(Looper.getMainLooper())

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean = true

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        running = true
        main.post {
            if (!running) return@post
            try {
                val s = Surface(surfaceTexture)
                surf = s
                val p = ExoPlayer.Builder(ctx).build()
                p.volume = 0f
                p.setVideoSurface(s)
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        onError("RTSP பிழை: " + (error.message ?: error.errorCodeName))
                    }
                })
                val ms = RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(rtspUrl))
                p.setMediaSource(ms)
                p.prepare()
                p.playWhenReady = true
                player = p
            } catch (e: Throwable) {
                onError("RTSP தொடங்க முடியவில்லை: ${e.message}")
            }
        }
    }

    override fun stop() {
        running = false
        main.post {
            try { player?.release() } catch (e: Throwable) {}
            player = null
            try { surf?.release() } catch (e: Throwable) {}
            surf = null
        }
    }

    override fun release() {}
    override fun isRunning(): Boolean = running
    override fun getOrientationConfig(): OrientationForced = OrientationForced.LANDSCAPE
}

class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {

    data class BS(val r: Int = 0, val b: Int = 0)
    data class BW(val balls: Int = 0, val runs: Int = 0, val w: Int = 0)
    data class St(
        val runs: Int = 0, val wk: Int = 0, val legal: Int = 0, val strike: Int = 0,
        val bat: Map<String, BS> = emptyMap(), val bowl: Map<String, BW> = emptyMap(),
        val last: List<String> = emptyList(),
        val outs: Set<String> = emptySet(), val lastBowler: String = "",
        val fh: Boolean = false, val extras: Int = 0,
        val pr: Int = 0, val pb: Int = 0, val fow: List<String> = emptyList(),
        val n1: String = "", val n2: String = "", val bn: String = "",
        val ovBalls: List<String> = emptyList(), val ovRuns: Int = 0, val ovBR: Int = 0,
        val ovList: List<Int> = emptyList(), val hat: Map<String, Int> = emptyMap(),
        val how: Map<String, String> = emptyMap()
    )
    data class Inn(
        val team: String, val runs: Int, val wk: Int, val legal: Int,
        val bat: Map<String, BS>, val outs: Set<String>, val bowl: Map<String, BW>,
        val fow: List<String>, val ovList: List<Int> = emptyList(),
        val how: Map<String, String> = emptyMap()
    )

    private class Prompt(
        val id: Int, val title: String, val items: List<String>,
        val typed: Boolean, val cb: (Int, String?) -> Unit
    )

    private class TkItem(val text: String?, val bmp: Bitmap?)

    private val palette = listOf(
        Triple("Gold", 0xFFFFC107.toInt(), Color.BLACK),
        Triple("Red", 0xFFE53935.toInt(), Color.WHITE),
        Triple("Blue", 0xFF1E88E5.toInt(), Color.WHITE),
        Triple("Green", 0xFF43A047.toInt(), Color.WHITE),
        Triple("Orange", 0xFFFB8C00.toInt(), Color.BLACK),
        Triple("Purple", 0xFF8E24AA.toInt(), Color.WHITE),
        Triple("Teal", 0xFF00897B.toInt(), Color.WHITE),
        Triple("Pink", 0xFFEC407A.toInt(), Color.WHITE),
        Triple("White", 0xFFFFFFFF.toInt(), Color.BLACK),
        Triple("Sky", 0xFF29B6F6.toInt(), Color.BLACK)
    )

    private var st = St()
    private val hist = ArrayList<St>()
    private lateinit var cam: RtmpStream
    private lateinit var glView: SurfaceView
    private lateinit var tBat: EditText
    private lateinit var tBowl: EditText
    private lateinit var b1: EditText
    private lateinit var b2: EditText
    private lateinit var bowler: EditText
    private lateinit var url: EditText
    private lateinit var liveBtn: Button
    private lateinit var recBtn: Button
    private lateinit var ppBtn: Button
    private lateinit var statusTv: TextView
    private var recFile: File? = null
    private var filter = ImageObjectFilterRender()
    private var filterSet = false

    private var srcMode = 0
    private var rtspUrl = ""
    private var prepKey = ""

    private val recListener: RecordController.Listener by lazy {
        java.lang.reflect.Proxy.newProxyInstance(
            RecordController.Listener::class.java.classLoader,
            arrayOf<Class<*>>(RecordController.Listener::class.java)
        ) { _, _, _ -> null } as RecordController.Listener
    }

    private var nameA = ""
    private var nameB = ""
    private var playersA: List<String> = emptyList()
    private var playersB: List<String> = emptyList()
    private var batIsA = true
    private var matchTitle = ""
    private var venue = ""
    private var colA = 0
    private var colB = 2

    private var totalOvers = 10
    private var maxPerBowler = 0
    private var freeHitOn = true
    private var nsOn = true
    private var recQ = 1080
    private var liveQ = 720
    private var splitMin = 30
    private var encW = 1280
    private var encH = 720
    private var innings = 1
    private var target = 0
    private var powerplay = false

    private var inn1: Inn? = null
    private var inn2: Inn? = null
    private var matchOver = false
    private var resultText = ""
    private var cardPage = 0
    private var bigKind = ""
    private var overTask: Runnable? = null
    private var soonEnd = 0L

    private var recStartMs = 0L
    private val marks = ArrayList<String>()
    private val warnAt = HashMap<String, Long>()

    private var wantLive = false
    private var wantRec = false
    private var connected = false
    private var retryCount = 0
    private var resumeLive = false
    private var resumeRec = false
    private var resuming = false
    private var restoring = false
    private var dimOn = false

    // logos, ticker
    private var chLogo: Bitmap? = null
    private val spLogos = ArrayList<Bitmap>()
    private var tkMode = 0
    private var tkChannel = true
    private var sponsorText = ""
    private var tkItems: List<TkItem> = emptyList()
    private var tkStart = System.currentTimeMillis()

    // prompts & remote scorer
    private var prompt: Prompt? = null
    private var promptDialog: AlertDialog? = null
    private var promptSeq = 0
    @Volatile private var lastPoll = 0L
    private var remoteMode = false
    private var pinCode = "1234"
    private var server: java.net.ServerSocket? = null
    private val srvPool = java.util.concurrent.Executors.newCachedThreadPool()

    private val saver = Runnable { saveState() }
    private val retryRunner = Runnable { reconnect() }
    private val splitRunner = Runnable { doSplit() }
    private val bigRunner = Runnable { bigKind = ""; refresh() }
    private val statusRunner = object : Runnable {
        override fun run() {
            try { updateStatus() } catch (e: Exception) {}
            glView.postDelayed(this, 5000)
        }
    }
    private val soonRunner = object : Runnable {
        override fun run() {
            if (bigKind != "SOON") return
            val left = soonEnd - System.currentTimeMillis()
            if (left < -6000L) { bigKind = ""; refresh(); return }
            refresh()
            glView.postDelayed(this, 1000)
        }
    }
    private val tickRunner = object : Runnable {
        override fun run() {
            if (tkMode == 0 || tkItems.isEmpty()) return
            if (cardPage == 0 && bigKind.isEmpty() && animType.isEmpty()) refresh()
            glView.postDelayed(this, if (tkMode == 1) 110L else 1000L)
        }
    }

    private var animType = ""
    private var animWord = ""
    private var animSub = ""
    private var animStart = 0L
    private var animMs = 2200L
    private val animRunner = object : Runnable {
        override fun run() {
            if (animType.isEmpty()) return
            if (System.currentTimeMillis() - animStart >= animMs) {
                animType = ""
                refresh()
                return
            }
            refresh()
            glView.postDelayed(this, if (encW > 1280) 120L else 80L)
        }
    }

    private fun field(h: String, d: String, size: Float = 12f): EditText =
        EditText(this).apply {
            hint = h; setText(d); textSize = size; setSingleLine()
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            doAfterTextChanged { refresh(); saveSoon() }
        }

    private fun lp(w: Float) = LinearLayout.LayoutParams(0, -2, w)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val root = FrameLayout(this)
        glView = SurfaceView(this)
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0x88000000.toInt()) }

        tBat = field("Batting team", "TEAM A")
        tBowl = field("Bowling team", "TEAM B")
        url = field("Stream URL + key", "", 11f)
        url.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
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
        for (e in listOf(b1, b2, bowler)) {
            e.isFocusable = false
            e.isFocusableInTouchMode = false
            e.isCursorVisible = false
        }
        b1.setOnClickListener { pickBatter(0) {} }
        b2.setOnClickListener { pickBatter(1) {} }
        bowler.setOnClickListener { pickBowler() }
        val row2 = LinearLayout(this)
        row2.addView(b1, lp(1f)); row2.addView(b2, lp(1f)); row2.addView(bowler, lp(1f))
        top.addView(row2)

        val row3 = LinearLayout(this)
        fun btn(t: String, a: () -> Unit) {
            row3.addView(Button(this).apply {
                text = t; textSize = 12f; setPadding(0, 0, 0, 0)
                setOnClickListener { a() }
            }, lp(1f))
        }
        for (r in listOf(0, 1, 2, 3, 4, 6)) btn("$r") {
            deliver(true, "$r", batRuns = r, bowlerRuns = r, cross = if (r >= 4) 0 else r)
        }
        btn("Wd") { askWide() }
        btn("Nb") { askNb() }
        btn("B") { askBye(false) }
        btn("LB") { askBye(true) }
        btn("W") { askOut() }
        btn("Undo") { undo() }
        top.addView(row3)

        val row4 = LinearLayout(this)
        fun btn4(t: String, a: () -> Unit) {
            row4.addView(Button(this).apply {
                text = t; textSize = 10f; setPadding(0, 0, 0, 0)
                setOnClickListener { a() }
            }, lp(1f))
        }
        btn4("SOURCE") { askSource() }
        btn4("TOSS") { showToss() }
        btn4("TEAMS") { showTeams() }
        btn4("MATCH") { showMatch() }
        btn4("EDIT") { showEdit() }
        btn4("SWAP") { confirmSwap() }
        btn4("CARD") { cardPage = (cardPage + 1) % 3; refresh() }
        btn4("SAVE") { saveCardImage() }
        btn4("INTRO") { if (bigKind.isNotEmpty()) hideBig() else showBig("INTRO", 10000L) }
        btn4("★ MARK") { mark("MANUAL", true) }
        btn4("NEW") { newMatch() }
        btn4("DIM") { toggleDim() }
        top.addView(row4)

        val row5 = LinearLayout(this)
        fun btn5(t: String, a: () -> Unit) {
            row5.addView(Button(this).apply {
                text = t; textSize = 10f; setPadding(0, 0, 0, 0)
                setOnClickListener { a() }
            }, lp(1f))
        }
        ppBtn = Button(this).apply {
            text = "POWERPLAY"; textSize = 10f; setPadding(0, 0, 0, 0)
            setOnClickListener { togglePowerplay() }
        }
        btn5("OVERTHROW") { askOverthrow() }
        btn5("RETIRE") { askRetire() }
        row5.addView(ppBtn, lp(1f))
        btn5("SOON") { askSoon() }
        btn5("SPONSOR") { showSponsor() }
        btn5("SCORER") { showScorer() }
        top.addView(row5)

        statusTv = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(12, 2, 12, 4)
            text = ""
        }
        top.addView(statusTv)

        root.addView(top, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
        setContentView(root)

        loadTeams()
        restoreState()
        loadLogos()

        cam = RtmpStream(this, this)
        glView.holder.addCallback(this)
        glView.postDelayed(statusRunner, 1000)
        restartTicker()
        if (remoteMode) startServer()

        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 1)
        }
        if (s == null) glView.postDelayed({ askSource() }, 900)
    }

    override fun onPause() {
        super.onPause()
        saveState()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    // ---------- prompts (local dialog + remote mirror) ----------

    private fun remoteActive(): Boolean = remoteMode && SystemClock.elapsedRealtime() - lastPoll < 5000L

    private fun ask(title: String, items: List<String>, typed: Boolean, cb: (Int, String?) -> Unit) {
        try { promptDialog?.dismiss() } catch (e: Exception) {}
        promptDialog = null
        val p = Prompt(++promptSeq, title, items, typed, cb)
        prompt = p
        if (!remoteActive()) showLocal(p)
        else glView.postDelayed({
            if (prompt?.id == p.id && !remoteActive() && promptDialog == null) showLocal(p)
        }, 7000L)
    }

    private fun showLocal(p: Prompt) {
        if (promptDialog != null) return
        val items = (if (p.typed) p.items + "✎ பெயர் தட்டச்சு" else p.items).toTypedArray()
        val d = AlertDialog.Builder(this).setTitle(p.title)
            .setItems(items) { _, i ->
                promptDialog = null
                if (p.typed && i == p.items.size) typeLocal(p) else resolve(p.id, i, null)
            }
            .setOnCancelListener {
                promptDialog = null
                if (prompt?.id == p.id) prompt = null
            }
            .create()
        promptDialog = d
        d.show()
    }

    private fun typeLocal(p: Prompt) {
        val et = EditText(this)
        et.setSingleLine()
        AlertDialog.Builder(this).setTitle(p.title).setView(et)
            .setPositiveButton("OK") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) resolve(p.id, -1, t) else if (prompt?.id == p.id) prompt = null
            }
            .setNegativeButton("Cancel") { _, _ -> if (prompt?.id == p.id) prompt = null }
            .show()
    }

    private fun resolve(id: Int, i: Int, t: String?) {
        val p = prompt ?: return
        if (p.id != id) return
        try { promptDialog?.dismiss() } catch (e: Exception) {}
        promptDialog = null
        prompt = null
        if (i == -2) return
        if (t == null && (i < 0 || i >= p.items.size)) return
        p.cb(i, t)
    }

    private fun choose(title: String, items: List<String>, cb: (Int) -> Unit) {
        ask(title, items, false) { i, _ -> cb(i) }
    }

    private fun pick(title: String, opts: List<String>, onPick: (String) -> Unit) {
        ask(title, opts, true) { i, t ->
            if (t != null) onPick(t)
            else if (i in opts.indices) onPick(opts[i])
        }
    }

    // ---------- camera source selection ----------

    private fun srcName() = when (srcMode) { 1 -> "USB"; 2 -> "RTSP"; else -> "MOBILE" }

    private fun askSource() {
        val items = arrayOf("📱 Mobile கேமரா", "🔌 USB கேமரா / HDMI Capture Card", "📡 RTSP (IP) கேமரா")
        AlertDialog.Builder(this).setTitle("கேமரா மூலம் (SOURCE)")
            .setItems(items) { _, i -> if (i == 2) askRtsp() else applySource(i) }
            .show()
    }

    private fun askRtsp() {
        val et = EditText(this).apply {
            hint = "rtsp://பயனர்:கடவுச்சொல்@192.168.1.10:554/stream1"
            setText(rtspUrl); setSingleLine()
        }
        AlertDialog.Builder(this).setTitle("RTSP முகவரி")
            .setMessage("மொபைலும் கேமராவும் ஒரே Wi-Fi/LAN-ல் இருக்க வேண்டும்.")
            .setView(et)
            .setPositiveButton("இணை") { _, _ ->
                val t = et.text.toString().trim()
                if (t.startsWith("rtsp://")) {
                    rtspUrl = t
                    prefs().edit().putString("rtsp", t).apply()
                    applySource(2)
                } else toast("முகவரி rtsp:// என்று தொடங்க வேண்டும்")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applySource(mode: Int) {
        if (cam.isStreaming || cam.isRecording || wantLive || wantRec) {
            toast("முதலில் Live/REC-ஐ நிறுத்துங்கள்")
            return
        }
        try {
            if (prepKey.isEmpty() && !ensurePrepared(false)) { toast("வீடியோ தயாராகவில்லை"); return }
            val src: VideoSource = when (mode) {
                1 -> CameraUvcSource()
                2 -> RtspSource(this, rtspUrl) { m -> toast(m) }
                else -> Camera2Source(this)
            }
            cam.changeVideoSource(src)
            srcMode = mode
            if (mode == 1) toast("USB கேமராவை இணைத்து, அனுமதி கேட்டால் Allow தேர்வு செய்யுங்கள்")
            else if (mode == 2) toast("RTSP இணைக்கிறது... சில விநாடிகள் ஆகலாம்")
            installFilterSoon()
        } catch (e: Throwable) {
            toast("மூலம் மாற்ற முடியவில்லை: ${e.message}")
            try { cam.changeVideoSource(Camera2Source(this)); srcMode = 0 } catch (e2: Throwable) {}
        }
    }

    // ---------- big cards ----------

    private fun showBig(kind: String, ms: Long) {
        bigKind = kind
        glView.removeCallbacks(bigRunner)
        glView.postDelayed(bigRunner, ms)
        refresh()
    }

    private fun hideBig() {
        bigKind = ""
        glView.removeCallbacks(bigRunner)
        refresh()
    }

    private fun askSoon() {
        if (bigKind == "SOON") { hideBig(); return }
        val e = EditText(this).apply {
            hint = "எத்தனை நிமிடங்களில் தொடக்கம்? (எ.கா. 5)"
            setText("5"); inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this).setTitle("STARTING SOON").setView(e)
            .setPositiveButton("தொடங்கு") { _, _ ->
                val m = e.text.toString().toIntOrNull()?.coerceIn(1, 180) ?: 5
                soonEnd = System.currentTimeMillis() + m * 60_000L
                bigKind = "SOON"
                glView.removeCallbacks(bigRunner)
                glView.removeCallbacks(soonRunner)
                glView.post(soonRunner)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- powerplay, overthrow, retire ----------

    private fun togglePowerplay() {
        powerplay = !powerplay
        ppBtn.text = if (powerplay) "PP ● ON" else "POWERPLAY"
        toast(if (powerplay) "POWERPLAY தொடங்கியது" else "POWERPLAY முடிந்தது")
        refresh()
        saveSoon()
    }

    private fun askOverthrow() {
        if (matchOver) { toast("போட்டி முடிந்தது"); return }
        if (st.legal == 0 && st.runs == 0) { toast("முதலில் பந்துகள் உள்ளிடுங்கள்"); return }
        choose("Overthrow ரன்கள்", listOf("1", "2", "3", "4", "5")) { ri ->
            val n = ri + 1
            choose("Overthrow வகை", listOf("Batter-க்கு (bat-ல் பட்ட பந்து)", "Extra (Bye/Wide)")) { k ->
                if (k == 0) {
                    val n1 = nm(b1, "Batter 1"); val n2 = nm(b2, "Batter 2")
                    choose("யாருக்கு ரன்?", listOf(n1, n2)) { w -> applyOverthrow(n, if (w == 0) n1 else n2) }
                } else applyOverthrow(n, null)
            }
        }
    }

    private fun applyOverthrow(n: Int, batter: String?) {
        val afterOver = st.ovBalls.isEmpty() && st.ovList.isNotEmpty() && st.legal > 0 && st.legal % 6 == 0
        val bn = if (afterOver && st.lastBowler.isNotEmpty()) st.lastBowler else nm(bowler, "Bowler")
        hist.add(st.copy(n1 = nm(b1, "Batter 1"), n2 = nm(b2, "Batter 2"), bn = nm(bowler, "Bowler")))
        if (hist.size > 60) hist.removeAt(0)
        val bat = st.bat.toMutableMap()
        val bw = st.bowl.toMutableMap()
        if (batter != null) {
            val s0 = bat[batter] ?: BS()
            bat[batter] = BS(s0.r + n, s0.b)
            val b0 = bw[bn] ?: BW()
            bw[bn] = BW(b0.balls, b0.runs + n, b0.w)
        }
        val ol = if (afterOver) st.ovList.dropLast(1) + (st.ovList.last() + n) else st.ovList
        st = st.copy(
            runs = st.runs + n, bat = bat, bowl = bw,
            extras = st.extras + (if (batter == null) n else 0),
            pr = st.pr + n,
            ovList = ol,
            ovRuns = if (afterOver) st.ovRuns else st.ovRuns + n,
            ovBR = if (afterOver || batter == null) st.ovBR else st.ovBR + n,
            ovBalls = if (afterOver) st.ovBalls else st.ovBalls + "OT$n",
            last = (st.last + "OT$n").takeLast(6)
        )
        refresh()
        saveSoon()
        toast("Overthrow +$n சேர்க்கப்பட்டது")
        if (innings == 2 && target > 0 && st.runs >= target) finishMatch()
    }

    private fun askRetire() {
        if (matchOver || inningsDone()) { toast("இப்போது முடியாது"); return }
        val n1 = nm(b1, "Batter 1"); val n2 = nm(b2, "Batter 2")
        val items = listOf(n1 + (if (st.strike == 0) "*" else ""), n2 + (if (st.strike == 1) "*" else ""))
        choose("எந்த batter ஓய்வு (Retire)?", items) { slot ->
            choose("வகை", listOf("Retired hurt (திரும்ப வரலாம்)", "Retired out (அவுட் கணக்கு)")) { k ->
                val name = if (slot == 0) n1 else n2
                hist.add(st.copy(n1 = n1, n2 = n2, bn = nm(bowler, "Bowler")))
                if (hist.size > 60) hist.removeAt(0)
                if (k == 0) {
                    st = st.copy(how = st.how + (name to "retired hurt"))
                } else {
                    val newWk = st.wk + 1
                    st = st.copy(
                        wk = newWk, outs = st.outs + name,
                        how = st.how + (name to "retired out"),
                        fow = st.fow + "$newWk-${st.runs} ($name, ${ov(st.legal)})",
                        pr = 0, pb = 0
                    )
                }
                refresh()
                saveSoon()
                if (st.wk >= maxWk()) {
                    if (innings == 2) finishMatch() else toast("Innings முடிந்தது. SWAP அழுத்துங்கள்")
                    return@choose
                }
                pickBatter(slot) {}
            }
        }
    }

    // ---------- logos & ticker ----------

    private fun pickImage(code: Int) {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "image/*"
            startActivityForResult(Intent.createChooser(i, "படம் தேர்வு"), code)
        } catch (e: Exception) {
            toast("Gallery திறக்க முடியவில்லை")
        }
    }

    private fun saveImage(uri: Uri, name: String): Boolean {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var ss = 1
            val mx = maxOf(opts.outWidth, opts.outHeight)
            while (mx / ss > 600) ss *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = ss }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            if (bmp == null) false
            else {
                File(filesDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                true
            }
        } catch (e: Exception) { false }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 901) {
            if (saveImage(uri, "chlogo.png")) { loadLogos(); toast("சேனல் லோகோ சேமிக்கப்பட்டது") }
            else toast("படம் சேமிக்க முடியவில்லை")
        } else if (requestCode == 902) {
            var idx = -1
            for (i in 0 until 6) { if (!File(filesDir, "sp$i.png").exists()) { idx = i; break } }
            if (idx < 0) toast("அதிகபட்சம் 6 லோகோக்கள்")
            else if (saveImage(uri, "sp$idx.png")) { loadLogos(); toast("Sponsor லோகோ சேர்க்கப்பட்டது") }
            else toast("படம் சேமிக்க முடியவில்லை")
        }
        refresh()
    }

    private fun loadLogos() {
        chLogo = null
        try {
            val f = File(filesDir, "chlogo.png")
            if (f.exists()) chLogo = BitmapFactory.decodeFile(f.absolutePath)
            if (chLogo == null) {
                val id = resources.getIdentifier("logo", "drawable", packageName)
                if (id != 0) chLogo = BitmapFactory.decodeResource(resources, id)
            }
        } catch (e: Exception) {}
        spLogos.clear()
        for (i in 0 until 6) {
            try {
                val f = File(filesDir, "sp$i.png")
                if (f.exists()) {
                    val b = BitmapFactory.decodeFile(f.absolutePath)
                    if (b != null) spLogos.add(b)
                }
            } catch (e: Exception) {}
        }
        rebuildTicker()
    }

    private fun rebuildTicker() {
        val l = ArrayList<TkItem>()
        if (tkChannel) {
            val c = chLogo
            if (c != null) l.add(TkItem(null, c))
            l.add(TkItem("RAW CRICKET TN", null))
        }
        for (t in parse(sponsorText)) l.add(TkItem(t, null))
        for (b in spLogos) l.add(TkItem(null, b))
        tkItems = l
    }

    private fun restartTicker() {
        glView.removeCallbacks(tickRunner)
        if (tkMode != 0 && tkItems.isNotEmpty()) {
            tkStart = System.currentTimeMillis()
            glView.post(tickRunner)
        }
    }

    private fun showSponsor() {
        val rg = RadioGroup(this)
        val modes = listOf("OFF - காட்ட வேண்டாம்", "SCROLL - சுருளும்", "ROTATE - மாறி மாறி (குறைந்த வெப்பம்)")
        for ((i, t) in modes.withIndex()) rg.addView(RadioButton(this).apply { text = t; id = 2000 + i })
        rg.check(2000 + tkMode)
        val cbCh = CheckBox(this).apply { text = "சேனல் லோகோ + பெயரையும் சேர்"; isChecked = tkChannel }
        val eT = EditText(this).apply {
            hint = "Sponsor உரைகள் (ஒரு வரிக்கு ஒன்று)"
            setText(sponsorText)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        val bCh = Button(this).apply { text = "சேனல் லோகோ தேர்வு (Gallery)"; setOnClickListener { pickImage(901) } }
        val bSp = Button(this).apply { text = "Sponsor லோகோ சேர் (${spLogos.size}/6)"; setOnClickListener { pickImage(902) } }
        val bClr = Button(this).apply {
            text = "Sponsor லோகோக்களை நீக்கு"
            setOnClickListener {
                for (i in 0 until 6) File(filesDir, "sp$i.png").delete()
                loadLogos()
                toast("நீக்கப்பட்டது")
            }
        }
        val note = TextView(this).apply {
            text = "Live/REC நடக்கும்போது லோகோ தேர்வு செய்யாதீர்கள் (Gallery திறக்கும்போது app பின்னணிக்குப் போகும்)."
            textSize = 12f
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        box.addView(rg); box.addView(cbCh); box.addView(eT)
        box.addView(bCh); box.addView(bSp); box.addView(bClr); box.addView(note)
        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this).setTitle("SPONSOR / சுருளும் பட்டி").setView(sv)
            .setPositiveButton("Save") { _, _ ->
                val sel = rg.checkedRadioButtonId - 2000
                tkMode = if (sel in 0..2) sel else 0
                tkChannel = cbCh.isChecked
                sponsorText = eT.text.toString()
                prefs().edit().putInt("tk", tkMode).putBoolean("tkc", tkChannel).putString("tkt", sponsorText).apply()
                rebuildTicker()
                restartTicker()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- remote scorer (web) ----------

    private fun localIps(): List<String> {
        val l = ArrayList<String>()
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val ad = ni.inetAddresses
                while (ad.hasMoreElements()) {
                    val a = ad.nextElement()
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                        l.add(ni.name + " → http://" + a.hostAddress + ":8080")
                    }
                }
            }
        } catch (e: Exception) {}
        return l
    }

    private fun showScorer() {
        val rg = RadioGroup(this)
        rg.addView(RadioButton(this).apply { text = "இந்த மொபைலிலேயே தட்டச்சு (இயல்பு)"; id = 3000 })
        rg.addView(RadioButton(this).apply { text = "இன்னொரு மொபைலில் ஸ்கோர் (Web)"; id = 3001 })
        rg.check(if (remoteMode) 3001 else 3000)
        val ips = localIps()
        val info = TextView(this).apply {
            text = "PIN: $pinCode\n\n" +
                "1) இந்த மொபைலில் Hotspot இயக்கவும்.\n" +
                "2) ஸ்கோர் மொபைலை அதில் இணைக்கவும்.\n" +
                "3) ஸ்கோர் மொபைலின் Chrome-ல் கீழே உள்ள முகவரியைத் திறக்கவும் (wlan/ap என்று உள்ளது):\n\n" +
                (if (ips.isEmpty()) "(Wi-Fi/Hotspot இணைப்பு இல்லை)" else ips.joinToString("\n"))
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        box.addView(rg); box.addView(info)
        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this).setTitle("SCORER முறை").setView(sv)
            .setPositiveButton("Save") { _, _ ->
                remoteMode = rg.checkedRadioButtonId == 3001
                prefs().edit().putBoolean("rm", remoteMode).apply()
                if (remoteMode) startServer() else stopServer()
                toast(if (remoteMode) "இரண்டாவது மொபைல் முறை இயக்கம்" else "இந்த மொபைல் முறை")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startServer() {
        if (server != null) return
        Thread {
            try {
                val ss = java.net.ServerSocket()
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress(8080))
                server = ss
                while (!ss.isClosed) {
                    val s = ss.accept()
                    srvPool.execute { handleConn(s) }
                }
            } catch (e: Exception) {
                server = null
                runOnUiThread { toast("Scorer server தொடங்கவில்லை (port 8080)") }
            }
        }.start()
    }

    private fun stopServer() {
        try { server?.close() } catch (e: Exception) {}
        server = null
    }

    private fun parseQuery(s: String): Map<String, String> {
        val m = HashMap<String, String>()
        if (s.isEmpty()) return m
        for (kv in s.split("&")) {
            val i = kv.indexOf('=')
            if (i <= 0) continue
            try {
                m[java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8")] =
                    java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            } catch (e: Exception) {}
        }
        return m
    }

    private fun <T> onUi(f: () -> T): T? {
        val box = arrayOfNulls<Any>(1)
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            try { box[0] = f() } catch (e: Throwable) {}
            latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST")
        val r = box[0] as T?
        return r
    }

    private fun handleConn(s: java.net.Socket) {
        try {
            s.soTimeout = 5000
            val br = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream(), "UTF-8"))
            val line = br.readLine() ?: return
            while (true) {
                val h = br.readLine() ?: break
                if (h.isEmpty()) break
            }
            val parts = line.split(" ")
            val target0 = if (parts.size > 1) parts[1] else "/"
            val path = target0.substringBefore("?")
            val q = parseQuery(target0.substringAfter("?", ""))
            var status = "200 OK"
            var type = "application/json; charset=utf-8"
            var body = "{}"
            if (path == "/") {
                type = "text/html; charset=utf-8"
                body = PAGE
            } else if (q["pin"] != pinCode) {
                status = "403 Forbidden"
                body = "{\"err\":\"pin\"}"
            } else if (path == "/state") {
                lastPoll = SystemClock.elapsedRealtime()
                body = onUi { stateJson() } ?: "{}"
            } else if (path == "/cmd") {
                lastPoll = SystemClock.elapsedRealtime()
                runOnUiThread { try { remoteCmd(q) } catch (e: Throwable) {} }
                body = "{\"ok\":1}"
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            val out = s.getOutputStream()
            out.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
        } finally {
            try { s.close() } catch (e: Exception) {}
        }
    }

    private fun stateJson(): String {
        val o = JSONObject()
        val n1 = nm(b1, "Batter 1"); val n2 = nm(b2, "Batter 2"); val bn = nm(bowler, "Bowler")
        val s1 = st.bat[n1] ?: BS()
        val s2 = st.bat[n2] ?: BS()
        val bw = st.bowl[bn] ?: BW()
        o.put("team", nm(tBat, "TEAM A"))
        o.put("runs", st.runs); o.put("wk", st.wk); o.put("ov", ov(st.legal)); o.put("crr", crr())
        o.put("b1", n1 + (if (st.strike == 0) "*" else "") + "  " + s1.r + " (" + s1.b + ")")
        o.put("b2", n2 + (if (st.strike == 1) "*" else "") + "  " + s2.r + " (" + s2.b + ")")
        o.put("bw", bn + "  " + bw.w + "-" + bw.runs + " (" + ov(bw.balls) + ")")
        o.put("balls", JSONArray(st.ovBalls.takeLast(12)))
        o.put("innings", innings); o.put("target", target)
        o.put("need", if (innings == 2 && target > 0) target - st.runs else 0)
        o.put("over", matchOver); o.put("result", resultText)
        o.put("done", inningsDone())
        o.put("fh", st.fh); o.put("pp", powerplay)
        o.put("live", cam.isStreaming || wantLive); o.put("rec", cam.isRecording)
        val p = prompt
        if (p != null) {
            val po = JSONObject()
            po.put("id", p.id); po.put("title", p.title)
            po.put("items", JSONArray(p.items)); po.put("typed", p.typed)
            o.put("prompt", po)
        }
        return o.toString()
    }

    private fun remoteCmd(q: Map<String, String>) {
        when (q["c"]) {
            "run" -> {
                val r = q["v"]?.toIntOrNull() ?: return
                if (r in listOf(0, 1, 2, 3, 4, 6)) deliver(true, "$r", batRuns = r, bowlerRuns = r, cross = if (r >= 4) 0 else r)
            }
            "wd" -> askWide()
            "nb" -> askNb()
            "b" -> askBye(false)
            "lb" -> askBye(true)
            "w" -> askOut()
            "undo" -> undo()
            "swap" -> confirmSwap()
            "toss" -> showToss()
            "card" -> { cardPage = (cardPage + 1) % 3; refresh() }
            "intro" -> { if (bigKind.isNotEmpty()) hideBig() else showBig("INTRO", 10000L) }
            "mark" -> mark("MANUAL", true)
            "b1" -> pickBatter(0) {}
            "b2" -> pickBatter(1) {}
            "bowler" -> pickBowler()
            "ot" -> askOverthrow()
            "ret" -> askRetire()
            "pp" -> togglePowerplay()
            "ans" -> resolve(q["id"]?.toIntOrNull() ?: return, q["i"]?.toIntOrNull() ?: -1, q["t"])
        }
    }

    private val PAGE: String = """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><title>Raw Cricket Scorer</title><style>
body{margin:0;background:#0b1f3a;color:#fff;font-family:sans-serif}
#top{padding:10px 12px;background:#122b52}
#sc{font-size:34px;font-weight:bold;color:#ffc107}
#info{font-size:15px;color:#cfd8dc;margin-top:2px}
#balls{margin-top:6px;min-height:30px}
.bl{display:inline-block;min-width:26px;height:26px;line-height:26px;border-radius:13px;text-align:center;font-size:12px;margin-right:5px;padding:0 3px}
.row{display:flex;flex-wrap:wrap;padding:3px}
button{flex:1 0 21%;margin:3px;padding:15px 2px;font-size:19px;border:0;border-radius:10px;background:#37474f;color:#fff}
button.n{background:#2e7d32}button.f{background:#1e88e5}button.s{background:#f9a825;color:#000}button.w{background:#c62828}button.x{background:#6a1b9a}
button.nm{flex:1 0 28%;font-size:14px;padding:10px 2px;background:#1b3a6b}
button.sm{flex:1 0 20%;font-size:13px;padding:10px 2px;background:#455a64}
#ov{display:none;position:fixed;left:0;top:0;right:0;bottom:0;background:rgba(0,0,0,.93);overflow:auto;padding:12px}
#ov button{display:block;width:calc(100% - 6px);text-align:left;background:#1b3a6b;font-size:18px}
#ov input{width:calc(100% - 24px);font-size:18px;padding:10px;margin:6px 0}
#st{font-size:12px;color:#90a4ae;padding:4px 12px}
</style></head><body>
<div id="top"><div id="sc">-</div><div id="info"></div><div id="balls"></div></div>
<div class="row"><button class="nm" id="b1" onclick="cmd('b1')">-</button><button class="nm" id="b2" onclick="cmd('b2')">-</button><button class="nm" id="bw" onclick="cmd('bowler')">-</button></div>
<div class="row">
<button class="n" onclick="cmd('run',0)">0</button><button class="n" onclick="cmd('run',1)">1</button><button class="n" onclick="cmd('run',2)">2</button><button class="n" onclick="cmd('run',3)">3</button>
<button class="f" onclick="cmd('run',4)">4</button><button class="s" onclick="cmd('run',6)">6</button>
<button class="x" onclick="cmd('wd')">Wd</button><button class="x" onclick="cmd('nb')">Nb</button>
<button class="x" onclick="cmd('b')">B</button><button class="x" onclick="cmd('lb')">LB</button>
<button class="w" onclick="cmd('w')">W</button><button onclick="cmd('undo')">Undo</button>
</div>
<div class="row">
<button class="sm" onclick="cmd('swap')">SWAP</button><button class="sm" onclick="cmd('toss')">TOSS</button><button class="sm" onclick="cmd('card')">CARD</button><button class="sm" onclick="cmd('intro')">INTRO</button>
<button class="sm" onclick="cmd('mark')">MARK</button><button class="sm" onclick="cmd('ot')">OVERTHROW</button><button class="sm" onclick="cmd('ret')">RETIRE</button><button class="sm" onclick="cmd('pp')">POWERPLAY</button>
</div>
<div id="st"></div>
<div id="ov"></div>
<script>
var pin=localStorage.getItem('rcpin')||'';
if(!pin){pin=prompt('PIN (கேமரா மொபைலில் காட்டப்படும்)')||'';localStorage.setItem('rcpin',pin);}
var cur=0,done=0;
function api(q){return fetch('/cmd?pin='+encodeURIComponent(pin)+'&'+q).catch(function(){});}
function cmd(c,v){api('c='+c+(v===undefined?'':'&v='+v));}
function ans(id,i,t){done=id;cur=0;document.getElementById('ov').style.display='none';api('c=ans&id='+id+'&i='+i+(t?'&t='+encodeURIComponent(t):''));}
function el(t,txt,cls,fn){var e=document.createElement(t);if(txt)e.textContent=txt;if(cls)e.className=cls;if(fn)e.onclick=fn;return e;}
function ballColor(l){if(l==='W'||l==='RO')return '#e53935';if(l==='6')return '#ffc107';if(l==='4')return '#1e88e5';if(l==='0')return '#607d8b';if(l.indexOf('Wd')===0||l.indexOf('Nb')===0||l.indexOf('B')===0||l.indexOf('LB')===0||l.indexOf('OT')===0)return '#8e24aa';return '#2e7d32';}
function showPrompt(p){
 var ov=document.getElementById('ov');
 if(!p){ov.style.display='none';cur=0;return;}
 if(p.id===done||cur===p.id)return;
 cur=p.id;ov.innerHTML='';
 ov.appendChild(el('h3',p.title));
 p.items.forEach(function(t,i){ov.appendChild(el('button',t,'',function(){ans(p.id,i);}));});
 if(p.typed){var inp=document.createElement('input');inp.placeholder='பெயர் தட்டச்சு';ov.appendChild(inp);ov.appendChild(el('button','OK','',function(){if(inp.value)ans(p.id,-1,inp.value);}));}
 ov.appendChild(el('button','Cancel','',function(){ans(p.id,-2);}));
 ov.style.display='block';
}
function render(s){
 document.getElementById('sc').textContent=s.team+'  '+s.runs+'-'+s.wk+'  ('+s.ov+')';
 var inf='CRR '+s.crr;
 if(s.innings===2&&s.target>0)inf+='   TARGET '+s.target+'  NEED '+s.need;
 if(s.fh)inf+='   FREE HIT';
 if(s.pp)inf+='   POWERPLAY';
 if(s.done&&!s.over)inf+='   INNINGS COMPLETE';
 if(s.over)inf=s.result;
 document.getElementById('info').textContent=inf;
 var b=document.getElementById('balls');b.innerHTML='';
 s.balls.forEach(function(l){var e=el('span',l,'bl');e.style.background=ballColor(l);b.appendChild(e);});
 document.getElementById('b1').textContent=s.b1;
 document.getElementById('b2').textContent=s.b2;
 document.getElementById('bw').textContent=s.bw;
 document.getElementById('st').textContent=(s.live?'● LIVE   ':'')+(s.rec?'REC   ':'');
 showPrompt(s.prompt);
}
function poll(){
 fetch('/state?pin='+encodeURIComponent(pin)).then(function(r){
  if(r.status===403){document.getElementById('st').textContent='PIN தவறு - பக்கத்தை reload செய்யவும்';localStorage.removeItem('rcpin');pin='';return null;}
  return r.json();
 }).then(function(s){if(s)render(s);}).catch(function(){document.getElementById('st').textContent='இணைப்பு இல்லை';});
}
setInterval(poll,800);poll();
</script></body></html>"""

    // ---------- status, warnings, storage ----------

    private fun freeBytes(): Long = try {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        StatFs(dir.path).availableBytes
    } catch (e: Exception) { -1L }

    private fun fmtGb(b: Long): String = String.format(Locale.US, "%.1fGB", b / 1073741824.0)

    private fun curKbps(): Int =
        if (wantLive || cam.isStreaming) kbpsFor(liveQ, true) else kbpsFor(recQ, false)

    private fun spaceOk(): Boolean {
        val f = freeBytes()
        if (f in 0..500_000_000L) {
            toast("இடம் மிகக் குறைவு (${fmtGb(f)}). பழைய வீடியோக்களை நீக்குங்கள்")
            return false
        }
        return true
    }

    private fun warn(key: String, msg: String, gapMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val last = warnAt[key] ?: -gapMs
        if (now - last >= gapMs) {
            warnAt[key] = now
            toast(msg)
        }
    }

    private fun updateStatus() {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val free = freeBytes()
        val mins = if (free > 0) (free / (curKbps() * 125L + 16000L) / 60).toInt() else 0
        val recTxt = if (cam.isRecording) {
            val s = ((SystemClock.elapsedRealtime() - recStartMs) / 1000).toInt().coerceAtLeast(0)
            String.format(Locale.US, "   ⏺ %02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        } else ""
        val rmTxt = if (remoteMode) (if (remoteActive()) "   📶 REMOTE ●" else "   📶 REMOTE ○") else ""
        statusTv.text = "📷 ${srcName()}   " + String.format(Locale.US, "🔋%d%%%s   🌡%.1f°C   💾%s (~%dh %dm)", pct, if (plugged) "⚡" else "", temp, fmtGb(free), mins / 60, mins % 60) + recTxt + rmTxt

        if (temp >= 44.0) warn("hot2", "வெப்பம் மிக அதிகம் (" + String.format(Locale.US, "%.1f", temp) + "°C)! DIM அழுத்தி, நிழலில் வையுங்கள்; 540p-க்கு மாறுங்கள்", 120_000L)
        else if (temp >= 41.0) warn("hot1", "வெப்பம் அதிகரிக்கிறது (" + String.format(Locale.US, "%.1f", temp) + "°C). DIM அழுத்துங்கள், நிழலில் வையுங்கள்", 300_000L)
        if (pct in 0..15 && !plugged) warn("bat", "Battery $pct% - சார்ஜரை இணையுங்கள்", 180_000L)
        if (cam.isRecording && free in 0..2_000_000_000L) warn("space", "இடம் குறைவு (${fmtGb(free)})! பழைய வீடியோக்களை நீக்குங்கள்", 180_000L)
    }

    // ---------- recording split ----------

    private fun scheduleSplit() {
        glView.removeCallbacks(splitRunner)
        if (splitMin > 0 && cam.isRecording) glView.postDelayed(splitRunner, splitMin * 60_000L)
    }

    private fun doSplit() {
        if (!cam.isRecording) return
        try {
            if (cam.isStreaming || wantLive) {
                cam.stopRecord()
                finishRecording()
                val f = newRecFile()
                recFile = f
                cam.startRecord(f.absolutePath, recListener)
                recStartMs = SystemClock.elapsedRealtime()
                marks.clear()
                toast("பதிவு புதிய கோப்பாகப் பிரிக்கப்பட்டது")
            } else {
                stopRec()
                startRec()
            }
        } catch (e: Throwable) {
            recFile = null
            toast("பதிவைப் பிரிக்க முடியவில்லை: ${e.message}")
        }
        scheduleSplit()
    }

    // ---------- scorecard image ----------

    private fun saveCardImage() {
        if (Build.VERSION.SDK_INT < 29) { toast("இந்த வசதிக்கு Android 10+ தேவை"); return }
        try {
            val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.typeface = Typeface.DEFAULT_BOLD
            drawCardAny(c, p, if (cardPage == 2) 2 else 1)
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Scorecard_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RawCricket")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw Exception("insert failed")
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            toast("படம் சேமிக்கப்பட்டது: Gallery → Pictures/RawCricket")
        } catch (e: Exception) {
            toast("படம் சேமிக்க முடியவில்லை")
        }
    }

    // ---------- persistence ----------

    private fun prefs() = getSharedPreferences("rc", MODE_PRIVATE)

    private fun saveSoon() {
        if (restoring) return
        glView.removeCallbacks(saver)
        glView.postDelayed(saver, 500)
    }

    private fun batJ(m: Map<String, BS>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONArray().put(v.r).put(v.b))
        return o
    }

    private fun bowlJ(m: Map<String, BW>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONArray().put(v.balls).put(v.runs).put(v.w))
        return o
    }

    private fun hatJ(m: Map<String, Int>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        return o
    }

    private fun howJ(m: Map<String, String>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        return o
    }

    private fun batP(o: JSONObject): Map<String, BS> {
        val m = LinkedHashMap<String, BS>()
        val ks = o.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            val a = o.getJSONArray(k)
            m[k] = BS(a.getInt(0), a.getInt(1))
        }
        return m
    }

    private fun bowlP(o: JSONObject): Map<String, BW> {
        val m = LinkedHashMap<String, BW>()
        val ks = o.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            val a = o.getJSONArray(k)
            m[k] = BW(a.getInt(0), a.getInt(1), a.getInt(2))
        }
        return m
    }

    private fun hatP(o: JSONObject?): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        if (o != null) {
            val ks = o.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                m[k] = o.getInt(k)
            }
        }
        return m
    }

    private fun howP(o: JSONObject?): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        if (o != null) {
            val ks = o.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                m[k] = o.getString(k)
            }
        }
        return m
    }

    private fun strL(a: JSONArray): List<String> {
        val l = ArrayList<String>()
        for (i in 0 until a.length()) l.add(a.getString(i))
        return l
    }

    private fun optStrL(o: JSONObject, k: String): List<String> =
        if (o.has(k)) strL(o.getJSONArray(k)) else emptyList()

    private fun optIntL(o: JSONObject, k: String): List<Int> {
        val l = ArrayList<Int>()
        if (o.has(k)) {
            val a = o.getJSONArray(k)
            for (i in 0 until a.length()) l.add(a.getInt(i))
        }
        return l
    }

    private fun stJ(s: St): JSONObject {
        val o = JSONObject()
        o.put("runs", s.runs); o.put("wk", s.wk); o.put("legal", s.legal); o.put("strike", s.strike)
        o.put("bat", batJ(s.bat)); o.put("bowl", bowlJ(s.bowl))
        o.put("last", JSONArray(s.last)); o.put("outs", JSONArray(s.outs.toList()))
        o.put("lb", s.lastBowler); o.put("fh", s.fh); o.put("ex", s.extras)
        o.put("pr", s.pr); o.put("pb", s.pb); o.put("fow", JSONArray(s.fow))
        o.put("n1", s.n1); o.put("n2", s.n2); o.put("bn", s.bn)
        o.put("ob", JSONArray(s.ovBalls)); o.put("or", s.ovRuns); o.put("obr", s.ovBR)
        o.put("ol", JSONArray(s.ovList)); o.put("hat", hatJ(s.hat))
        o.put("how", howJ(s.how))
        return o
    }

    private fun stP(o: JSONObject): St = St(
        o.getInt("runs"), o.getInt("wk"), o.getInt("legal"), o.getInt("strike"),
        batP(o.getJSONObject("bat")), bowlP(o.getJSONObject("bowl")),
        strL(o.getJSONArray("last")), strL(o.getJSONArray("outs")).toSet(), o.getString("lb"),
        o.getBoolean("fh"), o.getInt("ex"), o.getInt("pr"), o.getInt("pb"),
        strL(o.getJSONArray("fow")),
        o.getString("n1"), o.getString("n2"), o.getString("bn"),
        ovBalls = optStrL(o, "ob"), ovRuns = o.optInt("or", 0), ovBR = o.optInt("obr", 0),
        ovList = optIntL(o, "ol"), hat = hatP(o.optJSONObject("hat")),
        how = howP(o.optJSONObject("how"))
    )

    private fun innJ(i: Inn): JSONObject {
        val o = JSONObject()
        o.put("team", i.team); o.put("runs", i.runs); o.put("wk", i.wk); o.put("legal", i.legal)
        o.put("bat", batJ(i.bat)); o.put("outs", JSONArray(i.outs.toList()))
        o.put("bowl", bowlJ(i.bowl)); o.put("fow", JSONArray(i.fow))
        o.put("ol", JSONArray(i.ovList))
        o.put("how", howJ(i.how))
        return o
    }

    private fun innP(o: JSONObject): Inn = Inn(
        o.getString("team"), o.getInt("runs"), o.getInt("wk"), o.getInt("legal"),
        batP(o.getJSONObject("bat")), strL(o.getJSONArray("outs")).toSet(),
        bowlP(o.getJSONObject("bowl")), strL(o.getJSONArray("fow")),
        optIntL(o, "ol"), howP(o.optJSONObject("how"))
    )

    private fun saveState() {
        try {
            val o = JSONObject()
            o.put("st", stJ(st))
            val h = JSONArray()
            for (x in hist.takeLast(60)) h.put(stJ(x))
            o.put("hist", h)
            o.put("innings", innings); o.put("target", target)
            inn1?.let { o.put("inn1", innJ(it)) }
            inn2?.let { o.put("inn2", innJ(it)) }
            o.put("over", matchOver); o.put("result", resultText); o.put("batIsA", batIsA)
            o.put("pp", powerplay)
            o.put("tBat", tBat.text.toString()); o.put("tBowl", tBowl.text.toString())
            o.put("b1", b1.text.toString()); o.put("b2", b2.text.toString())
            o.put("bowler", bowler.text.toString())
            prefs().edit().putString("state", o.toString()).putString("url", url.text.toString()).apply()
        } catch (e: Exception) {}
    }

    private fun restoreState() {
        restoring = true
        try {
            val p = prefs()
            val u = p.getString("url", "") ?: ""
            if (u.isNotEmpty()) url.setText(u)
            val s = p.getString("state", null)
            if (s != null) {
                val o = JSONObject(s)
                st = stP(o.getJSONObject("st"))
                hist.clear()
                val h = o.getJSONArray("hist")
                for (i in 0 until h.length()) hist.add(stP(h.getJSONObject(i)))
                innings = o.getInt("innings"); target = o.getInt("target")
                inn1 = if (o.has("inn1")) innP(o.getJSONObject("inn1")) else null
                inn2 = if (o.has("inn2")) innP(o.getJSONObject("inn2")) else null
                matchOver = o.getBoolean("over"); resultText = o.getString("result")
                batIsA = o.getBoolean("batIsA")
                powerplay = o.optBoolean("pp", false)
                ppBtn.text = if (powerplay) "PP ● ON" else "POWERPLAY"
                tBat.setText(o.getString("tBat")); tBowl.setText(o.getString("tBowl"))
                b1.setText(o.getString("b1")); b2.setText(o.getString("b2"))
                bowler.setText(o.getString("bowler"))
                if (st.legal > 0 || st.runs > 0 || hist.isNotEmpty()) toast("ஸ்கோர் மீட்கப்பட்டது")
            }
        } catch (e: Exception) {
            toast("முந்தைய ஸ்கோர் மீட்க முடியவில்லை")
        }
        restoring = false
    }

    private fun newMatch() {
        AlertDialog.Builder(this).setTitle("புதிய போட்டி?").setMessage("தற்போதைய ஸ்கோர் அழியும்.")
            .setPositiveButton("ஆம்") { _, _ ->
                overTask?.let { glView.removeCallbacks(it) }
                overTask = null
                st = St(); hist.clear()
                innings = 1; target = 0; inn1 = null; inn2 = null
                matchOver = false; resultText = ""; cardPage = 0; animType = ""
                bigKind = ""
                powerplay = false
                ppBtn.text = "POWERPLAY"
                batIsA = true
                tBat.setText(if (nameA.isNotEmpty()) nameA else "TEAM A")
                tBowl.setText(if (nameB.isNotEmpty()) nameB else "TEAM B")
                b1.setText("Batter 1"); b2.setText("Batter 2"); bowler.setText("Bowler")
                refresh()
                saveState()
            }
            .setNegativeButton("இல்லை", null)
            .show()
    }

    private fun toggleDim() {
        dimOn = !dimOn
        val wa = window.attributes
        wa.screenBrightness = if (dimOn) 0.05f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = wa
        toast(if (dimOn) "திரை மங்கலாக்கப்பட்டது" else "திரை சாதாரணம்")
    }

    // ---------- teams, match settings, pickers ----------

    private fun parse(s: String) = s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    private fun batPlayers() = if (batIsA) playersA else playersB
    private fun bowlPlayers() = if (batIsA) playersB else playersA
    private fun maxWk(): Int { val n = batPlayers().size; return if (n >= 2) n - 1 else 10 }
    private fun bowlerCap(): Int =
        if (maxPerBowler > 0) maxPerBowler else Math.ceil(totalOvers / 5.0).toInt().coerceAtLeast(1)
    private fun inningsDone(): Boolean = st.wk >= maxWk() || st.legal >= totalOvers * 6

    private fun pal(i: Int) = palette[i.coerceIn(0, palette.size - 1)]

    private fun loadTeams() {
        val p = prefs()
        nameA = p.getString("nA", "") ?: ""
        nameB = p.getString("nB", "") ?: ""
        playersA = parse(p.getString("pA", "") ?: "")
        playersB = parse(p.getString("pB", "") ?: "")
        totalOvers = p.getInt("ov", 10)
        maxPerBowler = p.getInt("mb", 0)
        freeHitOn = p.getBoolean("fh", true)
        nsOn = p.getBoolean("ns", true)
        splitMin = p.getInt("sp", 30)
        matchTitle = p.getString("mt", "") ?: ""
        venue = p.getString("mv", "") ?: ""
        rtspUrl = p.getString("rtsp", "") ?: ""
        tkMode = p.getInt("tk", 0)
        tkChannel = p.getBoolean("tkc", true)
        sponsorText = p.getString("tkt", "") ?: ""
        remoteMode = p.getBoolean("rm", false)
        val savedPin = p.getString("pin", null)
        if (savedPin != null) pinCode = savedPin
        else {
            pinCode = (1000 + java.util.Random().nextInt(9000)).toString()
            p.edit().putString("pin", pinCode).apply()
        }
        colA = p.getInt("cA", 0)
        colB = p.getInt("cB", 2)
        val qr = p.getInt("qr", 1080)
        recQ = if (qr == 540 || qr == 720 || qr == 1080) qr else 1080
        val ql = p.getInt("ql", 720)
        liveQ = if (ql == 540 || ql == 720) ql else 720
        if (nameA.isNotEmpty()) tBat.setText(nameA)
        if (nameB.isNotEmpty()) tBowl.setText(nameB)
    }

    private fun showMatch() {
        val eT = EditText(this).apply { hint = "போட்டித் தலைப்பு (எ.கா. Raw Cricket League - Match 5)"; setText(matchTitle); setSingleLine() }
        val eV = EditText(this).apply { hint = "இடம் (Venue)"; setText(venue); setSingleLine() }
        val eO = EditText(this).apply { hint = "மொத்த ஓவர்கள்"; setText("$totalOvers"); inputType = InputType.TYPE_CLASS_NUMBER }
        val eB = EditText(this).apply { hint = "ஒரு bowler-க்கு அதிகபட்ச ஓவர் (0 = தானாக)"; setText("$maxPerBowler"); inputType = InputType.TYPE_CLASS_NUMBER }
        val eS = EditText(this).apply { hint = "பதிவைப் பிரிக்கும் இடைவெளி (நிமிடம்; 0 = பிரிக்காதே)"; setText("$splitMin"); inputType = InputType.TYPE_CLASS_NUMBER }
        val cb = CheckBox(this).apply { text = "No-ball-க்குப் பின் Free Hit"; isChecked = freeHitOn }
        val cn = CheckBox(this).apply { text = "ஆடியோ சத்தம் குறைப்பு (Noise suppression)"; isChecked = nsOn }

        val tvR = TextView(this).apply { text = "REC தரம் (மொபைலில் சேமிக்கப்படும் வீடியோ):"; setPadding(0, 16, 0, 0) }
        val rgR = RadioGroup(this)
        val optR = listOf(540 to "540p - வெப்பம் குறைவு", 720 to "720p HD", 1080 to "1080p Full HD")
        for ((q, label) in optR) rgR.addView(RadioButton(this).apply { text = label; id = q })
        rgR.check(recQ)

        val tvL = TextView(this).apply { text = "Live தரம் (இணையத்தில் பார்ப்பது):"; setPadding(0, 16, 0, 0) }
        val rgL = RadioGroup(this)
        val optL = listOf(540 to "540p - குறைந்த data", 720 to "720p HD (பரிந்துரை)")
        for ((q, label) in optL) rgL.addView(RadioButton(this).apply { text = label; id = 1000 + q })
        rgL.check(1000 + liveQ)

        val note = TextView(this).apply {
            text = "குறிப்பு: Live நடக்கும்போது சேமிக்கப்படும் வீடியோவும் Live தரத்திலேயே இருக்கும்."
            textSize = 12f
            setPadding(0, 12, 0, 0)
        }

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        box.addView(eT); box.addView(eV); box.addView(eO); box.addView(eB); box.addView(eS)
        box.addView(cb); box.addView(cn)
        box.addView(tvR); box.addView(rgR); box.addView(tvL); box.addView(rgL); box.addView(note)
        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this).setTitle("போட்டி விதிகள்")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                matchTitle = eT.text.toString().trim()
                venue = eV.text.toString().trim()
                totalOvers = eO.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: totalOvers
                maxPerBowler = eB.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: maxPerBowler
                splitMin = eS.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: splitMin
                freeHitOn = cb.isChecked
                nsOn = cn.isChecked
                val sr = rgR.checkedRadioButtonId
                recQ = if (sr == 540 || sr == 720 || sr == 1080) sr else 1080
                val sl = rgL.checkedRadioButtonId - 1000
                liveQ = if (sl == 540 || sl == 720) sl else 720
                prefs().edit().putInt("ov", totalOvers).putInt("mb", maxPerBowler).putInt("sp", splitMin)
                    .putString("mt", matchTitle).putString("mv", venue)
                    .putBoolean("fh", freeHitOn).putBoolean("ns", nsOn)
                    .putInt("qr", recQ).putInt("ql", liveQ).apply()
                toast("ஆடியோ/தர/பிரிப்பு மாற்றம் அடுத்த REC/Live-ல் செயல்படும்")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTeams() {
        fun et(h: String, v: String, multi: Boolean): EditText = EditText(this).apply {
            hint = h; setText(v)
            if (multi) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 4
                gravity = android.view.Gravity.TOP
            }
        }
        fun sp(sel: Int): Spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, palette.map { it.first })
            setSelection(sel.coerceIn(0, palette.size - 1))
        }
        val eA = et("Team A பெயர்", nameA, false)
        val spA = sp(colA)
        val pA = et("Team A வீரர்கள் (ஒரு வரிக்கு ஒரு பெயர்)", playersA.joinToString("\n"), true)
        val eB = et("Team B பெயர்", nameB, false)
        val spB = sp(colB)
        val pB = et("Team B வீரர்கள் (ஒரு வரிக்கு ஒரு பெயர்)", playersB.joinToString("\n"), true)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        box.addView(eA)
        box.addView(TextView(this).apply { text = "Team A நிறம்:" })
        box.addView(spA); box.addView(pA)
        box.addView(eB)
        box.addView(TextView(this).apply { text = "Team B நிறம்:" })
        box.addView(spB); box.addView(pB)
        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this).setTitle("அணிகள்").setView(sv)
            .setPositiveButton("Save") { _, _ ->
                nameA = eA.text.toString().trim()
                nameB = eB.text.toString().trim()
                playersA = parse(pA.text.toString())
                playersB = parse(pB.text.toString())
                colA = spA.selectedItemPosition
                colB = spB.selectedItemPosition
                prefs().edit()
                    .putString("nA", nameA).putString("nB", nameB)
                    .putString("pA", pA.text.toString()).putString("pB", pB.text.toString())
                    .putInt("cA", colA).putInt("cB", colB)
                    .apply()
                if (nameA.isNotEmpty()) (if (batIsA) tBat else tBowl).setText(nameA)
                if (nameB.isNotEmpty()) (if (batIsA) tBowl else tBat).setText(nameB)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickBatter(slot: Int, after: () -> Unit) {
        val other = nm(if (slot == 0) b2 else b1, "")
        val opts = batPlayers().filter { it !in st.outs && it != other }
        pick(if (slot == 0) "Batter 1" else "Batter 2", opts) { name ->
            (if (slot == 0) b1 else b2).setText(name)
            after()
        }
    }

    private fun pickBowler() {
        val cap = bowlerCap() * 6
        val opts = bowlPlayers().filter { it != st.lastBowler && (st.bowl[it]?.balls ?: 0) < cap }
        pick("Bowler", opts) { name -> bowler.setText(name) }
    }

    private fun askWho(cb: (Int) -> Unit) {
        val n1 = nm(b1, "Batter 1"); val n2 = nm(b2, "Batter 2")
        val items = listOf(n1 + (if (st.strike == 0) "*" else ""), n2 + (if (st.strike == 1) "*" else ""))
        choose("யார் அவுட்?", items) { cb(it) }
    }

    private fun askFielder(cb: (String) -> Unit) {
        val list = bowlPlayers()
        if (list.isEmpty()) { cb(""); return }
        val items = list + "— தவிர் / தெரியாது"
        choose("Fielder (பிடித்தவர் / ரன்-அவுட் செய்தவர்)", items) { i -> cb(if (i < list.size) list[i] else "") }
    }

    private fun howText(type: String, bn: String, f: String): String = when (type) {
        "Bowled" -> "b $bn"
        "LBW" -> "lbw b $bn"
        "Caught" -> if (f.isEmpty()) "c b $bn" else if (f == bn) "c & b $bn" else "c $f b $bn"
        "Stumped" -> if (f.isEmpty()) "st b $bn" else "st $f b $bn"
        "Hit wicket" -> "hit wkt b $bn"
        "Run out" -> if (f.isEmpty()) "run out" else "run out ($f)"
        else -> "out"
    }

    private fun snap() = Inn(
        nm(tBat, "TEAM A"), st.runs, st.wk, st.legal, st.bat, st.outs, st.bowl, st.fow,
        if (st.ovBalls.isNotEmpty()) st.ovList + st.ovRuns else st.ovList, st.how
    )

    private fun showToss() {
        if (innings != 1 || st.legal > 0) { toast("Toss போட்டி தொடக்கத்தில் மட்டுமே"); return }
        val a = nm(tBat, "TEAM A"); val b = nm(tBowl, "TEAM B")
        choose("Toss வென்றது யார்?", listOf(a, b)) { w ->
            choose("தேர்வு", listOf("BAT", "BOWL")) { d ->
                val winner = if (w == 0) a else b
                val batIdx = if (d == 0) w else 1 - w
                if (batIdx == 1) {
                    tBat.setText(b)
                    tBowl.setText(a)
                    batIsA = !batIsA
                }
                startAnim("TOSS", "TOSS", winner.uppercase() + " WON - ELECTED TO " + (if (d == 0) "BAT" else "BOWL"), 4200L)
                pickBatter(0) { pickBatter(1) { pickBowler() } }
            }
        }
    }

    private fun confirmSwap() {
        val msg = if (innings == 1) "Innings மாற்றவா? ஸ்கோர் பூஜ்ஜியமாகும். இலக்கு: ${st.runs + 1}"
        else "புதிய போட்டி தொடங்கவா? ஸ்கோர் பூஜ்ஜியமாகும்."
        choose(msg, listOf("ஆம்", "இல்லை")) { i -> if (i == 0) doSwap() }
    }

    private fun doSwap() {
        overTask?.let { glView.removeCallbacks(it) }
        overTask = null
        var brk = false
        if (innings == 1) {
            inn1 = snap()
            target = st.runs + 1
            innings = 2
            brk = true
        } else {
            inn1 = null; inn2 = null; matchOver = false; resultText = ""
            target = 0; innings = 1
        }
        cardPage = 0
        animType = ""
        powerplay = false
        ppBtn.text = "POWERPLAY"
        val a = tBat.text.toString()
        tBat.setText(tBowl.text.toString())
        tBowl.setText(a)
        batIsA = !batIsA
        st = St()
        hist.clear()
        b1.setText("Batter 1"); b2.setText("Batter 2"); bowler.setText("Bowler")
        if (brk) showBig("BREAK", 14000L) else hideBig()
        refresh()
        saveSoon()
        pickBatter(0) { pickBatter(1) { pickBowler() } }
    }

    private fun undo() {
        if (hist.isEmpty()) return
        overTask?.let { glView.removeCallbacks(it) }
        overTask = null
        val s = hist.removeAt(hist.size - 1)
        st = s
        animType = ""
        if (matchOver) { matchOver = false; inn2 = null; resultText = ""; cardPage = 0 }
        if (s.n1.isNotEmpty()) b1.setText(s.n1)
        if (s.n2.isNotEmpty()) b2.setText(s.n2)
        if (s.bn.isNotEmpty()) bowler.setText(s.bn)
        refresh()
        saveSoon()
    }

    // ---------- manual edit & rename ----------

    private fun num(e: EditText, d: Int): Int = e.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: d

    private fun parseOv(s: String, d: Int): Int {
        val t = s.trim()
        if (t.isEmpty()) return d
        val parts = t.split(".")
        val o = parts[0].toIntOrNull() ?: return d
        val b = if (parts.size > 1) (parts[1].toIntOrNull() ?: 0).coerceIn(0, 5) else 0
        return o.coerceAtLeast(0) * 6 + b
    }

    private fun showEdit() {
        if (matchOver) { toast("போட்டி முடிந்த நிலையில் Undo பயன்படுத்துங்கள்"); return }
        val n1 = nm(b1, "Batter 1"); val n2 = nm(b2, "Batter 2"); val bn = nm(bowler, "Bowler")
        fun et(h: String, v: String, dec: Boolean = false): EditText = EditText(this).apply {
            hint = h; setText(v); setSingleLine()
            inputType = if (dec) (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) else InputType.TYPE_CLASS_NUMBER
        }
        fun lbl
