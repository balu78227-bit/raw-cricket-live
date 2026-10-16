package com.rawcricket.live

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
    private lateinit var statusTv: TextView
    private var recFile: File? = null
    private var filter = ImageObjectFilterRender()
    private var filterSet = false

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

    private var inn1: Inn? = null
    private var inn2: Inn? = null
    private var matchOver = false
    private var resultText = ""
    private var cardPage = 0
    private var bigKind = ""
    private var overTask: Runnable? = null

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
            doAfterTextChanged { refresh(); saveSoon() }
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
                text = t; textSize = 11f; setPadding(0, 0, 0, 0)
                setOnClickListener { a() }
            }, lp(1f))
        }
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

        cam = RtmpCamera2(glView, this)
        glView.holder.addCallback(this)
        glView.postDelayed(statusRunner, 1000)

        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 1)
        }
    }

    override fun onPause() {
        super.onPause()
        saveState()
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
        statusTv.text = String.format(Locale.US, "🔋%d%%%s   🌡%.1f°C   💾%s (~%dh %dm)", pct, if (plugged) "⚡" else "", temp, fmtGb(free), mins / 60, mins % 60) + recTxt

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
                cam.startRecord(f.absolutePath)
                recStartMs = SystemClock.elapsedRealtime()
                marks.clear()
                toast("பதிவு புதிய கோப்பாகப் பிரிக்கப்பட்டது")
            } else {
                stopRec()
                startRec()
            }
        } catch (e: Exception) {
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

    private fun typeName(title: String, onPick: (String) -> Unit) {
        val et = EditText(this)
        et.setSingleLine()
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) onPick(t)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pick(title: String, opts: List<String>, onPick: (String) -> Unit) {
        val items = (opts + "✎ பெயர் தட்டச்சு").toTypedArray()
        AlertDialog.Builder(this).setTitle(title)
            .setItems(items) { _, i ->
                if (i < opts.size) onPick(opts[i]) else typeName(title, onPick)
            }
            .show()
    }

    private fun choose(title: String, items: List<String>, cb: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setItems(items.toTypedArray()) { _, i -> cb(i) }
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
        val msg = if (innings == 1) "ஸ்கோர் பூஜ்ஜியமாகும். இலக்கு: ${st.runs + 1}" else "புதிய போட்டி தொடங்கும். ஸ்கோர் பூஜ்ஜியமாகும்."
        AlertDialog.Builder(this).setTitle("Innings மாற்றவா?").setMessage(msg)
            .setPositiveButton("ஆம்") { _, _ ->
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
            .setNegativeButton("இல்லை", null)
            .show()
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
        fun lbl(t: String): TextView = TextView(this).apply { text = t; setPadding(0, 18, 0, 0); setTypeface(null, Typeface.BOLD) }

        val s1 = st.bat[n1] ?: BS()
        val s2 = st.bat[n2] ?: BS()
        val bwS = st.bowl[bn] ?: BW()
        val eRuns = et("Runs", "${st.runs}")
        val eWk = et("Wickets", "${st.wk}")
        val eOv = et("Overs (எ.கா. 3.4)", ov(st.legal), true)
        val e1r = et("$n1 ரன்", "${s1.r}")
        val e1b = et("$n1 பந்து", "${s1.b}")
        val e2r = et("$n2 ரன்", "${s2.r}")
        val e2b = et("$n2 பந்து", "${s2.b}")
        val ebw = et("$bn விக்கெட்", "${bwS.w}")
        val ebr = et("$bn ரன்", "${bwS.runs}")
        val ebo = et("$bn ஓவர் (எ.கா. 2.3)", ov(bwS.balls), true)

        val names = LinkedHashSet<String>()
        names.addAll(st.bat.keys); names.addAll(st.bowl.keys)
        names.addAll(playersA); names.addAll(playersB)
        names.add(n1); names.add(n2); names.add(bn)
        val nameList = names.filter { it.isNotEmpty() }
        val spN = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, nameList)
        }
        val eNew = EditText(this).apply { hint = "புதிய பெயர் (காலியாக விட்டால் மாற்றம் இல்லை)"; setSingleLine() }

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        box.addView(lbl("அணி ஸ்கோர்"))
        box.addView(eRuns); box.addView(eWk); box.addView(eOv)
        box.addView(lbl("தற்போதைய batter-கள்"))
        box.addView(e1r); box.addView(e1b); box.addView(e2r); box.addView(e2b)
        box.addView(lbl("தற்போதைய bowler"))
        box.addView(ebw); box.addView(ebr); box.addView(ebo)
        box.addView(lbl("வீரர் பெயர் மாற்றம்"))
        box.addView(spN); box.addView(eNew)
        val sv = ScrollView(this)
        sv.addView(box)

        AlertDialog.Builder(this).setTitle("திருத்தம் (EDIT)").setView(sv)
            .setPositiveButton("Save") { _, _ ->
                hist.add(st.copy(n1 = n1, n2 = n2, bn = bn))
                if (hist.size > 60) hist.removeAt(0)
                val bat = st.bat.toMutableMap()
                bat[n1] = BS(num(e1r, s1.r), num(e1b, s1.b))
                bat[n2] = BS(num(e2r, s2.r), num(e2b, s2.b))
                val bwm = st.bowl.toMutableMap()
                bwm[bn] = BW(parseOv(ebo.text.toString(), bwS.balls), num(ebr, bwS.runs), num(ebw, bwS.w))
                val newRuns = num(eRuns, st.runs)
                val newWk = num(eWk, st.wk)
                val newLegal = parseOv(eOv.text.toString(), st.legal)
                val reset = newRuns != st.runs || newLegal != st.legal
                st = st.copy(
                    runs = newRuns, wk = newWk, legal = newLegal, bat = bat, bowl = bwm,
                    ovBalls = if (reset) emptyList() else st.ovBalls,
                    ovRuns = if (reset) 0 else st.ovRuns,
                    ovBR = if (reset) 0 else st.ovBR,
                    pr = if (reset) 0 else st.pr,
                    pb = if (reset) 0 else st.pb
                )
                val newName = eNew.text.toString().trim()
                val oldName = if (nameList.isNotEmpty()) nameList[spN.selectedItemPosition] else ""
                if (newName.isNotEmpty() && oldName.isNotEmpty() && newName != oldName) renamePlayer(oldName, newName)
                refresh()
                saveSoon()
                toast("திருத்தம் சேமிக்கப்பட்டது")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun <V> mv(m: Map<String, V>, old: String, new: String): Map<String, V> {
        val r = LinkedHashMap<String, V>()
        for ((k, v) in m) r[if (k == old) new else k] = v
        return r
    }

    private fun rep(s: String, old: String, new: String): String {
        val re = Regex("(?<![\\p{L}\\p{M}\\p{N}])" + Regex.escape(old) + "(?![\\p{L}\\p{M}\\p{N}])")
        return re.replace(s, Regex.escapeReplacement(new))
    }

    private fun renameSt(s: St, old: String, new: String): St = s.copy(
        bat = mv(s.bat, old, new), bowl = mv(s.bowl, old, new), hat = mv(s.hat, old, new),
        how = mv(s.how, old, new).mapValues { rep(it.value, old, new) },
        outs = s.outs.map { if (it == old) new else it }.toSet(),
        lastBowler = if (s.lastBowler == old) new else s.lastBowler,
        fow = s.fow.map { rep(it, old, new) },
        n1 = if (s.n1 == old) new else s.n1,
        n2 = if (s.n2 == old) new else s.n2,
        bn = if (s.bn == old) new else s.bn
    )

    private fun renamePlayer(old: String, new: String) {
        st = renameSt(st, old, new)
        for (i in hist.indices) hist[i] = renameSt(hist[i], old, new)
        inn1 = inn1?.let { a ->
            a.copy(
                bat = mv(a.bat, old, new), bowl = mv(a.bowl, old, new),
                how = mv(a.how, old, new).mapValues { rep(it.value, old, new) },
                outs = a.outs.map { if (it == old) new else it }.toSet(),
                fow = a.fow.map { rep(it, old, new) }
            )
        }
        playersA = playersA.map { if (it == old) new else it }
        playersB = playersB.map { if (it == old) new else it }
        prefs().edit()
            .putString("pA", playersA.joinToString("\n"))
            .putString("pB", playersB.joinToString("\n"))
            .apply()
        if (b1.text.toString().trim() == old) b1.setText(new)
        if (b2.text.toString().trim() == old) b2.setText(new)
        if (bowler.text.toString().trim() == old) bowler.setText(new)
    }

    // ---------- highlight markers ----------

    private fun mark(label: String, manual: Boolean = false) {
        if (!cam.isRecording) {
            if (manual) toast("பதிவு (REC/Live) நடக்கும்போது மட்டுமே குறிக்க முடியும்")
            return
        }
        val sec = ((SystemClock.elapsedRealtime() - recStartMs) / 1000).toInt().coerceAtLeast(0)
        val ts = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
        marks.add("$ts  $label  [${nm(tBat, "TEAM A")} ${st.runs}-${st.wk} (${ov(st.legal)})]")
        if (manual) toast("★ $ts")
    }

    // ---------- match result ----------

    private fun finishMatch() {
        val a = inn1 ?: return
        val b = snap()
        inn2 = b
        matchOver = true
        overTask?.let { glView.removeCallbacks(it) }
        overTask = null
        resultText = when {
            b.runs > a.runs -> {
                val wl = (maxWk() - b.wk).coerceAtLeast(1)
                "${b.team.uppercase()} WON BY $wl ${if (wl == 1) "WICKET" else "WICKETS"}"
            }
            b.runs == a.runs -> "MATCH TIED"
            else -> {
                val d = a.runs - b.runs
                "${a.team.uppercase()} WON BY $d ${if (d == 1) "RUN" else "RUNS"}"
            }
        }
        cardPage = 1
        animType = ""
        refresh()
        saveSoon()
        toast(resultText)
    }

    private fun cards(): List<Inn> {
        val l = ArrayList<Inn>()
        if (innings == 1) {
            l.add(snap())
        } else {
            inn1?.let { l.add(it) }
            l.add(inn2 ?: snap())
        }
        return l
    }

    // ---------- scoring (MCC laws based) ----------

    private fun askWide() {
        val items = listOf("Wd", "Wd+1", "Wd+2", "Wd+3", "Wd+4", "Wd + Run out", "Wd + Stumped")
        choose("Wide", items) { i ->
            when {
                i <= 4 -> deliver(false, items[i], extra = 1 + i, bowlerRuns = 1 + i, cross = if (i == 4) 0 else i, faced = false)
                i == 5 -> askWho { s ->
                    askFielder { f ->
                        deliver(false, "Wd+RO", extra = 1, bowlerRuns = 1, faced = false, wk = "Run out", outSlot = s, how = howText("Run out", "", f))
                    }
                }
                else -> askFielder { f ->
                    deliver(false, "Wd+St", extra = 1, bowlerRuns = 1, faced = false, wk = "Stumped", outSlot = st.strike, credit = true, how = howText("Stumped", nm(bowler, "Bowler"), f))
                }
            }
        }
    }

    private fun askNb() {
        val items = listOf("Nb", "Nb+1", "Nb+2", "Nb+3", "Nb+4", "Nb+6", "Nb+Bye 1", "Nb+Bye 2", "Nb+Bye 4", "Nb + Run out")
        val bats = listOf(0, 1, 2, 3, 4, 6)
        choose("No-ball", items) { i ->
            when {
                i < 6 -> {
                    val r = bats[i]
                    deliver(false, items[i], batRuns = r, extra = 1, bowlerRuns = 1 + r, cross = if (r >= 4) 0 else r, setFH = freeHitOn)
                }
                i < 9 -> {
                    val n = listOf(1, 2, 4)[i - 6]
                    deliver(false, "Nb+B$n", extra = 1 + n, bowlerRuns = 1, cross = if (n == 4) 0 else n, setFH = freeHitOn)
                }
                else -> askWho { s ->
                    askFielder { f ->
                        deliver(false, "Nb+RO", extra = 1, bowlerRuns = 1, wk = "Run out", outSlot = s, setFH = freeHitOn, how = howText("Run out", "", f))
                    }
                }
            }
        }
    }

    private fun askBye(leg: Boolean) {
        val items = listOf("1", "2", "3", "4")
        choose(if (leg) "Leg byes" else "Byes", items) { i ->
            val n = i + 1
            deliver(true, (if (leg) "LB" else "B") + n, extra = n, cross = if (n == 4) 0 else n)
        }
    }

    private fun askOut() {
        val types = if (st.fh) listOf("Run out", "Obstructing/Handled/Timed out")
        else listOf("Bowled", "Caught", "LBW", "Stumped", "Hit wicket", "Run out", "Obstructing/Handled/Timed out")
        choose("Dismissal", types) { i ->
            val t = types[i]
            if (t in listOf("Bowled", "Caught", "LBW", "Stumped", "Hit wicket")) {
                val go = { f: String ->
                    deliver(true, "W", wk = t, outSlot = st.strike, credit = true, how = howText(t, nm(bowler, "Bowler"), f))
                }
                if (t == "Caught" || t == "Stumped") askFielder(go) else go("")
            } else {
                askWho { slot ->
                    choose("ஓடிய ரன்கள்", listOf("0", "1", "2", "3")) { r ->
                        val go = { f: String ->
                            deliver(true, if (t == "Run out") "RO" else "W", batRuns = r, bowlerRuns = r, cross = r, wk = t, outSlot = slot, how = howText(t, nm(bowler, "Bowler"), f))
                        }
                        if (t == "Run out") askFielder(go) else go("")
                    }
                }
            }
        }
    }

    private fun nm(e: EditText, d: String) = e.text.toString().trim().ifEmpty { d }

    private fun crossed(old: Int, new: Int, step: Int): Int {
        val a = old / step
        val b = new / step
        return if (b > a) b * step else 0
    }

    private fun queueOverCard(overNo: Int, balls: List<String>, runs: Int, maiden: Boolean, bnName: String) {
        overTask?.let { glView.removeCallbacks(it) }
        val remain = if (animType.isNotEmpty()) (animMs - (System.currentTimeMillis() - animStart)).coerceAtLeast(0L) + 200L else 0L
        val word = if (maiden) "MAIDEN OVER" else "END OF OVER $overNo"
        val sub = if (maiden) bnName.uppercase() + "  -  OVER $overNo"
        else balls.joinToString("  ") + "   =  $runs RUN" + (if (runs == 1) "" else "S")
        val t = Runnable { startAnim(if (maiden) "MAIDEN" else "OVER", word, sub, if (maiden) 3500L else 4500L) }
        overTask = t
        glView.postDelayed(t, remain)
    }

    private fun deliver(
        legal: Boolean, label: String,
        batRuns: Int = 0, extra: Int = 0, bowlerRuns: Int = 0, cross: Int = 0,
        faced: Boolean = true, wk: String? = null, outSlot: Int = 0,
        credit: Boolean = false, setFH: Boolean = false, how: String? = null
    ) {
        if (matchOver) { toast("போட்டி முடிந்தது. தவறு என்றால் Undo அழுத்துங்கள்"); return }
        cardPage = 0
        if (bigKind.isNotEmpty()) hideBig()
        val n1 = nm(b1, "Batter 1"); val n2 = nm(b2, "Batter 2"); val bn = nm(bowler, "Bowler")
        val sn = if (st.strike == 0) n1 else n2
        val oldRuns = st.runs
        val bat = st.bat.toMutableMap(); val bw = st.bowl.toMutableMap()
        val sb = bat[sn] ?: BS(); val bb = bw[bn] ?: BW()
        if (faced || batRuns > 0) bat[sn] = BS(sb.r + batRuns, sb.b + (if (faced) 1 else 0))
        bw[bn] = BW(bb.balls + (if (legal) 1 else 0), bb.runs + bowlerRuns, bb.w + (if (wk != null && credit) 1 else 0))
        val newLegal = st.legal + (if (legal) 1 else 0)
        val newRuns = st.runs + batRuns + extra
        val newWk = st.wk + (if (wk != null) 1 else 0)
        var strike = st.strike
        if (cross % 2 == 1) strike = 1 - strike
        val overEnded = legal && newLegal % 6 == 0
        if (overEnded) strike = 1 - strike
        val outName = if (outSlot == 0) n1 else n2
        val outs = if (wk != null) st.outs + outName else st.outs
        val newHow = if (wk != null) st.how + (outName to (how ?: howText(wk, bn, ""))) else st.how
        val fh = if (setFH) true else if (legal) false else st.fh
        var pr = st.pr + batRuns + extra
        var pb = st.pb + (if (legal) 1 else 0)
        var fow = st.fow
        if (wk != null) {
            fow = fow + "$newWk-$newRuns ($outName, ${ov(newLegal)})"
            pr = 0
            pb = 0
        }

        var oBalls = st.ovBalls + label
        var oRuns = st.ovRuns + batRuns + extra
        var oBR = st.ovBR + bowlerRuns
        var oList = st.ovList
        var hat = st.hat
        var hatNow = 0
        if (wk != null && credit) {
            hatNow = (hat[bn] ?: 0) + 1
            hat = hat + (bn to hatNow)
        } else if (legal && (hat[bn] ?: 0) != 0) {
            hat = hat + (bn to 0)
        }
        var sumBalls: List<String> = emptyList()
        var sumRuns = 0
        var maiden = false
        if (overEnded) {
            sumBalls = oBalls
            sumRuns = oRuns
            maiden = oBR == 0
            oList = oList + oRuns
            oBalls = emptyList()
            oRuns = 0
            oBR = 0
        }

        hist.add(st.copy(n1 = n1, n2 = n2, bn = bn))
        if (hist.size > 60) hist.removeAt(0)
        st = st.copy(
            runs = newRuns, wk = newWk, legal = newLegal, strike = strike,
            bat = bat, bowl = bw, last = (st.last + label).takeLast(6), outs = outs,
            lastBowler = if (overEnded) bn else st.lastBowler,
            fh = fh, extras = st.extras + extra, pr = pr, pb = pb, fow = fow,
            n1 = "", n2 = "", bn = "",
            ovBalls = oBalls, ovRuns = oRuns, ovBR = oBR, ovList = oList, hat = hat,
            how = newHow
        )
        refresh()
        saveSoon()

        val bNew = bat[sn]?.r ?: 0
        val bm = if (batRuns > 0) crossed(sb.r, bNew, 50) else 0
        val tm = crossed(oldRuns, newRuns, 50)
        if (wk != null) {
            if (hatNow == 3) {
                startAnim("HAT", "HAT-TRICK!", bn.uppercase() + "  -  3 WICKETS IN 3 BALLS", 3800L)
                mark("HAT-TRICK - $bn")
            } else {
                startAnim("WICKET", "WICKET!", outName.uppercase() + "  -  " + wk.uppercase())
                mark("WICKET - $outName")
            }
        } else if (bm >= 50) {
            if (bm == 50) {
                startAnim("FIFTY", "FIFTY", sn.uppercase() + "  50 (" + bNewBalls(bat, sn) + ")")
                mark("FIFTY - $sn")
            } else {
                startAnim("CENTURY", if (bm == 100) "CENTURY" else "$bm", sn.uppercase() + "  $bm (" + bNewBalls(bat, sn) + ")")
                mark("$bm - $sn")
            }
        } else if (batRuns == 6) {
            startAnim("SIX", "SIX", sn.uppercase())
            mark("SIX - $sn")
        } else if (batRuns == 4) {
            startAnim("FOUR", "FOUR", sn.uppercase())
            mark("FOUR - $sn")
        } else if (tm >= 50) {
            startAnim("TEAM", "TEAM $tm", nm(tBat, "TEAM A").uppercase() + "  $tm UP")
        }

        val chased = innings == 2 && target > 0 && st.runs >= target
        val allOut = st.wk >= maxWk()
        val oversDone = st.legal >= totalOvers * 6
        if (chased) { finishMatch(); return }
        if (overEnded) queueOverCard(newLegal / 6, sumBalls, sumRuns, maiden, bn)
        if (allOut || oversDone) {
            if (innings == 2) finishMatch() else toast("Innings முடிந்தது. SWAP அழுத்துங்கள்")
            return
        }
        if (wk != null) {
            pickBatter(outSlot) { if (overEnded) pickBowler() }
        } else if (overEnded) {
            pickBowler()
        }
    }

    private fun bNewBalls(bat: Map<String, BS>, n: String): Int = bat[n]?.b ?: 0

    // ---------- camera ----------

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

    // ---------- overlay & animation ----------

    private fun refresh() {
        if (filterSet) {
            try { filter.setImage(drawOverlay()) } catch (e: Exception) {}
        }
    }

    private fun startAnim(type: String, word: String, sub: String, ms: Long = 2200L) {
        animType = type
        animWord = word
        animSub = sub
        animMs = ms
        animStart = System.currentTimeMillis()
        glView.removeCallbacks(animRunner)
        glView.post(animRunner)
    }

    private fun clamp01(x: Float) = if (x < 0f) 0f else if (x > 1f) 1f else x

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val t = x - 1f
        return 1f + c3 * t * t * t + c1 * t * t
    }

    private fun drawAnim(c: Canvas) {
        val t = (System.currentTimeMillis() - animStart).toFloat()
        val dur = animMs.toFloat()
        val f = clamp01(t / dur)
        val inP = clamp01(t / 450f)
        val outP = clamp01((t - (dur - 450f)) / 450f)
        val a = 1f - outP
        val accent = when (animType) {
            "FOUR" -> 0xFF29B6F6.toInt()
            "SIX" -> 0xFFFFC107.toInt()
            "FIFTY" -> 0xFF66BB6A.toInt()
            "CENTURY" -> 0xFFFF7043.toInt()
            "TEAM" -> 0xFF26C6DA.toInt()
            "TOSS" -> 0xFFFFC107.toInt()
            "HAT" -> 0xFFFFD54F.toInt()
            "MAIDEN" -> 0xFF66BB6A.toInt()
            "OVER" -> 0xFF26C6DA.toInt()
            else -> 0xFFE53935.toInt()
        }
        val bg = if (animType == "WICKET" || animType == "HAT") 0xFF4A0D0D.toInt() else 0xFF0B1F3A.toInt()
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        q.typeface = Typeface.DEFAULT_BOLD
        val cx = 640f
        val cy = 290f
        val grow = easeOutBack(inP)

        q.style = Paint.Style.FILL
        q.color = accent; q.alpha = (45 * a).toInt()
        c.drawRect(0f, 0f, 1280f, 720f, q)

        val rays = when (animType) {
            "SIX" -> 28
            "CENTURY" -> 32
            "HAT" -> 36
            "FIFTY" -> 24
            "MAIDEN" -> 20
            "FOUR" -> 18
            "TOSS" -> 16
            "OVER" -> 10
            else -> 12
        }
        q.style = Paint.Style.STROKE
        q.strokeWidth = 10f
        q.strokeCap = Paint.Cap.ROUND
        q.color = accent; q.alpha = (200 * a).toInt()
        for (i in 0 until rays) {
            val ang = Math.PI * 2 * i / rays + f * 0.6
            val r1 = 150f + 120f * grow
            val r2 = r1 + 120f + 260f * inP
            c.drawLine(
                cx + (Math.cos(ang) * r1).toFloat(), cy + (Math.sin(ang) * r1).toFloat(),
                cx + (Math.cos(ang) * r2).toFloat(), cy + (Math.sin(ang) * r2).toFloat(), q
            )
        }

        q.style = Paint.Style.FILL
        val hh = 120f * grow.coerceAtMost(1.05f)
        q.color = bg; q.alpha = (235 * a).toInt()
        c.drawRect(0f, cy - hh, 1280f, cy + hh, q)
        q.color = accent; q.alpha = (255 * a).toInt()
        c.drawRect(0f, cy - hh - 6f, 1280f, cy - hh, q)
        c.drawRect(0f, cy + hh, 1280f, cy + hh + 6f, q)

        q.textAlign = Paint.Align.CENTER
        q.textSize = 140f * grow.coerceAtLeast(0.1f)
        while (q.measureText(animWord) > 1150f && q.textSize > 40f) q.textSize -= 6f
        q.style = Paint.Style.STROKE
        q.strokeWidth = 8f
        q.color = accent; q.alpha = (255 * a).toInt()
        c.drawText(animWord, cx, cy + 35f, q)
        q.style = Paint.Style.FILL
        q.color = Color.WHITE; q.alpha = (255 * a).toInt()
        c.drawText(animWord, cx, cy + 35f, q)

        var s = animSub
        q.textSize = 34f
        while (s.length > 1 && q.measureText(s) > 1100f) s = s.dropLast(1)
        q.color = accent; q.alpha = (255 * a * clamp01((inP - 0.5f) * 2f)).toInt()
        c.drawText(s, cx, cy + 98f, q)
    }

    private fun ov(b: Int) = "${b / 6}.${b % 6}"

    private fun crr(): String =
        if (st.legal == 0) "0.00" else String.format(Locale.US, "%.2f", st.runs * 6.0 / st.legal)

    private fun rrr(need: Int, left: Int): String =
        String.format(Locale.US, "%.2f", need * 6.0 / left)

    private fun fit(c: Canvas, t: String, x: Float, y: Float, maxW: Float, p: Paint) {
        var s = t
        while (s.length > 1 && p.measureText(s) > maxW) s = s.dropLast(1)
        c.drawText(s, x, y, p)
    }

    private fun fitStr(p: Paint, t: String, maxW: Float): String {
        var s = t
        while (s.length > 1 && p.measureText(s) > maxW) s = s.dropLast(1)
        return s
    }

    private fun pill(c: Canvas, p: Paint, text: String, x: Float, y1: Float, fg: Int): Float {
        p.textAlign = Paint.Align.LEFT
        p.textSize = 24f
        val tw = p.measureText(text)
        p.color = 0xF00B1F3A.toInt()
        c.drawRoundRect(x, y1, x + tw + 24f, y1 + 36f, 8f, 8f, p)
        p.color = fg
        c.drawText(text, x + 12f, y1 + 27f, p)
        return x + tw + 24f + 10f
    }

    private fun ballColor(l: String): Int = when {
        l == "W" || l == "RO" || l.endsWith("+RO") || l.endsWith("+St") -> 0xFFE53935.toInt()
        l == "6" -> 0xFFFFC107.toInt()
        l == "4" -> 0xFF1E88E5.toInt()
        l == "0" -> 0xFF607D8B.toInt()
        l.startsWith("Wd") || l.startsWith("Nb") || l.startsWith("B") || l.startsWith("LB") -> 0xFF8E24AA.toInt()
        else -> 0xFF2E7D32.toInt()
    }

    private fun drawInnings(c: Canvas, p: Paint, inn: Inn, x: Float, w: Float, y0: Float) {
        val gold = 0xFFFFC107.toInt()
        var y = y0 + 30f
        p.textAlign = Paint.Align.LEFT
        p.color = gold; p.textSize = 30f
        fit(c, inn.team.uppercase(), x, y, w - 190f, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText("${inn.runs}-${inn.wk} (${ov(inn.legal)})", x + w, y, p)
        p.textAlign = Paint.Align.LEFT
        y += 30f
        p.textSize = 18f; p.color = Color.LTGRAY
        c.drawText("BATTING", x, y, p)
        y += 24f
        val entries = inn.bat.entries.take(11)
        val two = entries.size <= 7
        for ((n, s) in entries) {
            val isOut = n in inn.outs
            p.textAlign = Paint.Align.LEFT
            p.textSize = 21f; p.color = Color.WHITE
            fit(c, n + (if (isOut) "" else "*"), x, y, if (two) w - 130f else 150f, p)
            val hw = inn.how[n] ?: (if (isOut) "out" else "not out")
            p.textSize = 15f; p.color = Color.LTGRAY
            if (two) fit(c, hw, x, y + 17f, w - 130f, p)
            else fit(c, hw, x + 158f, y, w - 130f - 158f, p)
            p.textSize = 21f; p.color = gold; p.textAlign = Paint.Align.RIGHT
            c.drawText("${s.r} (${s.b})", x + w, y, p)
            y += if (two) 38f else 25f
        }
        p.textAlign = Paint.Align.LEFT
        y += 12f
        p.textSize = 18f; p.color = Color.LTGRAY
        c.drawText("BOWLING", x, y, p)
        y += 24f
        p.textSize = 21f
        for ((n, b) in inn.bowl.entries.filter { it.value.balls > 0 || it.value.runs > 0 }.take(6)) {
            p.color = Color.WHITE; p.textAlign = Paint.Align.LEFT
            fit(c, n, x, y, w - 190f, p)
            p.color = gold; p.textAlign = Paint.Align.RIGHT
            c.drawText("${b.w}-${b.runs} (${ov(b.balls)})", x + w, y, p)
            y += 25f
        }
        p.textAlign = Paint.Align.LEFT
        if (inn.fow.isNotEmpty()) {
            p.textSize = 16f; p.color = gold
            c.drawText("FALL OF WICKETS", x, 648f, p)
            p.textSize = 17f; p.color = Color.LTGRAY
            val lines = ArrayList<String>()
            var cur = ""
            for (tok in inn.fow) {
                val nxt = if (cur.isEmpty()) tok else "$cur   $tok"
                if (p.measureText(nxt) > w && cur.isNotEmpty()) { lines.add(cur); cur = tok } else cur = nxt
            }
            if (cur.isNotEmpty()) lines.add(cur)
            var ly = 668f
            for (ln in lines.take(3)) {
                c.drawText(ln, x, ly, p)
                ly += 21f
            }
        }
    }

    private fun drawCard(c: Canvas, p: Paint) {
        val gold = 0xFFFFC107.toInt()
        p.color = 0xFA0B1F3A.toInt(); c.drawRect(0f, 0f, 1280f, 720f, p)
        p.color = gold; c.drawRect(0f, 0f, 1280f, 8f, p)
        p.textAlign = Paint.Align.LEFT
        p.textSize = 26f; p.color = gold
        c.drawText("RAW CRICKET TN", 30f, 52f, p)
        val head = if (matchOver) resultText else "MATCH SCORECARD"
        p.textSize = 38f; p.color = Color.WHITE
        p.textAlign = Paint.Align.RIGHT
        c.drawText(head, 1250f, 56f, p)
        p.textAlign = Paint.Align.LEFT
        p.color = gold; c.drawRect(30f, 78f, 1250f, 81f, p)
        val list = cards()
        for (i in list.indices) drawInnings(c, p, list[i], if (i == 0) 30f else 660f, 590f, 100f)
    }

    private fun drawGraph(c: Canvas, p: Paint) {
        val gold = 0xFFFFC107.toInt()
        val series = intArrayOf(gold, 0xFF29B6F6.toInt())
        val list = cards()
        p.color = 0xFA0B1F3A.toInt(); c.drawRect(0f, 0f, 1280f, 720f, p)
        p.color = gold; c.drawRect(0f, 0f, 1280f, 8f, p)
        p.textAlign = Paint.Align.LEFT
        p.textSize = 26f; p.color = gold
        c.drawText("RAW CRICKET TN", 30f, 52f, p)
        p.textSize = 36f; p.color = Color.WHITE
        p.textAlign = Paint.Align.RIGHT
        c.drawText(if (matchOver) resultText else "RUNS PER OVER", 1250f, 56f, p)
        p.textAlign = Paint.Align.LEFT

        var n = totalOvers
        var mx = 6
        for (inn in list) {
            n = maxOf(n, inn.ovList.size)
            for (v in inn.ovList) mx = maxOf(mx, v)
        }
        val step = Math.ceil(mx / 4.0).toInt().coerceAtLeast(1)
        val top = 120f
        val bot = 600f
        val left = 90f
        val right = 1240f

        p.strokeWidth = 1.5f
        for (k in 0..4) {
            val y = bot - (bot - top) * k / 4f
            p.color = 0x33FFFFFF
            c.drawLine(left, y, right, y, p)
            p.color = Color.LTGRAY; p.textSize = 18f; p.textAlign = Paint.Align.RIGHT
            c.drawText("${step * k}", left - 10f, y + 6f, p)
        }

        val gw = (right - left) / n
        val bwid = (gw * 0.8f) / list.size.coerceAtLeast(1)
        for ((si, inn) in list.withIndex()) {
            for ((oi, v) in inn.ovList.withIndex()) {
                val x0 = left + gw * oi + gw * 0.1f + bwid * si
                val h = (bot - top) * v / (step * 4f)
                p.color = series[si % 2]
                c.drawRect(x0, bot - h, x0 + bwid - 2f, bot, p)
                if (bwid >= 18f) {
                    p.color = Color.WHITE; p.textSize = 16f; p.textAlign = Paint.Align.CENTER
                    c.drawText("$v", x0 + (bwid - 2f) / 2f, bot - h - 6f, p)
                }
            }
        }

        p.textAlign = Paint.Align.CENTER; p.color = Color.LTGRAY; p.textSize = 18f
        val every = if (n > 20) 5 else if (n > 12) 2 else 1
        for (oi in 0 until n) {
            if ((oi + 1) % every == 0 || oi == 0) c.drawText("${oi + 1}", left + gw * oi + gw / 2f, bot + 28f, p)
        }

        var lx = 90f
        p.textAlign = Paint.Align.LEFT; p.textSize = 22f
        for ((si, inn) in list.withIndex()) {
            p.color = series[si % 2]
            c.drawRect(lx, 650f, lx + 22f, 672f, p)
            p.color = Color.WHITE
            val t = "${inn.team.uppercase()}  ${inn.runs}-${inn.wk}"
            c.drawText(t, lx + 32f, 669f, p)
            lx += 32f + p.measureText(t) + 50f
        }
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawCardAny(c: Canvas, p: Paint, page: Int) {
        if (page == 2) drawGraph(c, p) else drawCard(c, p)
    }

    private fun drawIntro(c: Canvas, p: Paint) {
        val gold = 0xFFFFC107.toInt()
        val navy = 0xF00B1F3A.toInt()
        val ta = (if (batIsA) tBat else tBowl).text.toString().trim().ifEmpty { "TEAM A" }
        val tb = (if (batIsA) tBowl else tBat).text.toString().trim().ifEmpty { "TEAM B" }
        val ca = pal(colA)
        val cb = pal(colB)
        p.color = navy; c.drawRect(0f, 0f, 1280f, 720f, p)
        p.color = gold; c.drawRect(0f, 0f, 1280f, 8f, p); c.drawRect(0f, 712f, 1280f, 720f, p)
        p.textAlign = Paint.Align.CENTER
        p.textSize = 28f; p.color = gold
        c.drawText("RAW CRICKET TN", 640f, 70f, p)
        p.textSize = 58f; p.color = Color.WHITE
        c.drawText(fitStr(p, matchTitle.ifEmpty { "CRICKET MATCH" }.uppercase(), 1150f), 640f, 150f, p)

        p.color = ca.second; c.drawRect(60f, 220f, 610f, 470f, p)
        p.color = cb.second; c.drawRect(670f, 220f, 1220f, 470f, p)
        p.textSize = 54f
        p.color = ca.third
        c.drawText(fitStr(p, ta.uppercase(), 520f), 335f, 365f, p)
        p.color = cb.third
        c.drawText(fitStr(p, tb.uppercase(), 520f), 945f, 365f, p)

        p.color = navy; c.drawCircle(640f, 345f, 52f, p)
        p.color = gold; p.textSize = 40f
        c.drawText("VS", 640f, 360f, p)

        val date = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date()).uppercase()
        p.textSize = 32f; p.color = Color.LTGRAY
        val vl = if (venue.isNotEmpty()) venue.uppercase() + "   |   " + date else date
        c.drawText(fitStr(p, vl, 1150f), 640f, 560f, p)
        p.color = gold
        c.drawText("$totalOvers OVERS MATCH", 640f, 620f, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawBreak(c: Canvas, p: Paint) {
        val gold = 0xFFFFC107.toInt()
        p.color = 0xF00B1F3A.toInt(); c.drawRect(0f, 0f, 1280f, 720f, p)
        p.color = gold; c.drawRect(0f, 0f, 1280f, 8f, p); c.drawRect(0f, 712f, 1280f, 720f, p)
        p.textAlign = Paint.Align.CENTER
        p.textSize = 26f; p.color = gold
        c.drawText("RAW CRICKET TN", 640f, 56f, p)
        p.textSize = 66f; p.color = Color.WHITE
        c.drawText("INNINGS BREAK", 640f, 140f, p)
        val a = inn1
        if (a != null) {
            p.textSize = 44f; p.color = gold
            c.drawText(fitStr(p, a.team.uppercase(), 1100f), 640f, 235f, p)
            p.textSize = 120f; p.color = Color.WHITE
            c.drawText("${a.runs}/${a.wk}", 640f, 355f, p)
            p.textSize = 30f; p.color = Color.LTGRAY
            c.drawText("(${ov(a.legal)} OVERS)", 640f, 400f, p)
        }
        p.textSize = 54f; p.color = gold
        c.drawText("TARGET $target", 640f, 495f, p)
        p.textSize = 30f; p.color = Color.WHITE
        val chase = nm(tBat, "TEAM B").uppercase()
        c.drawText(fitStr(p, "$chase NEED $target RUNS IN $totalOvers OVERS", 1150f), 640f, 555f, p)
        p.color = gold
        c.drawText("REQUIRED RATE  " + rrr(target, totalOvers * 6), 640f, 605f, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawOverlay(): Bitmap {
        val bw0 = encW
        val bh0 = encH
        val bmp = Bitmap.createBitmap(bw0, bh0, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val scl = bw0 / 1280f
        c.scale(scl, scl)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.DEFAULT_BOLD
        if (cardPage != 0) {
            drawCardAny(c, p, cardPage)
            return bmp
        }
        if (bigKind.isNotEmpty()) {
            if (bigKind == "INTRO") drawIntro(c, p) else drawBreak(c, p)
            return bmp
        }
        val navy = 0xF00B1F3A.toInt()
        val gold = 0xFFFFC107.toInt()
        val bc = pal(if (batIsA) colA else colB)
        val oc = pal(if (batIsA) colB else colA)

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
        if (matchTitle.isNotEmpty()) pill(c, p, matchTitle.take(36).uppercase(), 24f, 84f, Color.WHITE)

        if (st.fh) {
            p.textSize = 28f
            val tw = p.measureText("FREE HIT")
            p.color = gold; c.drawRoundRect(1260f - tw - 36f, 30f, 1260f, 74f, 10f, 10f, p)
            p.color = Color.BLACK; c.drawText("FREE HIT", 1260f - tw - 18f, 62f, p)
        }

        val n1 = nm(b1, "Batter 1"); val n2 = nm(b2, "Batter 2"); val bn = nm(bowler, "Bowler")
        val top = 640f; val bot = 704f

        var px = 20f
        val lbl = st.last
        if (lbl.isNotEmpty()) {
            p.textAlign = Paint.Align.CENTER
            for ((i, l) in lbl.withIndex()) {
                val ccx = 40f + i * 46f
                val ccy = top - 26f
                p.color = navy; c.drawCircle(ccx, ccy, 23f, p)
                p.color = ballColor(l); c.drawCircle(ccx, ccy, 20f, p)
                p.color = if (l == "6") Color.BLACK else Color.WHITE
                p.textSize = 20f
                while (p.measureText(l) > 34f && p.textSize > 10f) p.textSize -= 1f
                c.drawText(l, ccx, ccy + p.textSize * 0.35f, p)
            }
            p.textAlign = Paint.Align.LEFT
            px = 20f + lbl.size * 46f + 6f
        }
        pill(c, p, "CRR " + crr(), px, top - 44f, gold)
        if (st.pb > 0) pill(c, p, "P'SHIP ${st.pr} (${st.pb})", 20f, top - 90f, Color.WHITE)

        if (innings == 2 && target > 0) {
            val need = target - st.runs
            val left = totalOvers * 6 - st.legal
            val t = if (need <= 0) "TARGET $target - REACHED"
            else if (left > 0) "TARGET $target  NEED $need OFF $left  RRR ${rrr(need, left)}"
            else "TARGET $target  NEED $need"
            p.textSize = 24f
            val tw = p.measureText(t)
            pill(c, p, t, 1260f - tw - 24f, top - 44f, gold)
        }

        p.color = navy; c.drawRect(20f, top, 1260f, bot, p)
        p.color = bc.second; c.drawRect(20f, top, 390f, bot, p)
        p.color = oc.second; c.drawRect(1150f, top, 1260f, bot, p)

        p.color = bc.third; p.textSize = 30f
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

        p.color = oc.third; p.textSize = 28f
        fit(c, nm(tBowl, "TEAM B").uppercase(), 1160f, top + 42f, 92f, p)

        if (animType.isNotEmpty()) drawAnim(c)
        return bmp
    }

    // ---------- recording & live ----------

    private fun newRecFile(): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val name = "RawCricket_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        return File(dir, name)
    }

    private fun finishRecording() {
        val f = recFile ?: return
        recFile = null
        val ms = ArrayList(marks)
        marks.clear()
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
            if (ms.isNotEmpty()) {
                try {
                    val tv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, f.nameWithoutExtension + "_highlights.txt")
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/RawCricket")
                    }
                    val turi = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, tv)
                        ?: throw Exception("insert failed")
                    contentResolver.openOutputStream(turi)?.use { it.write(ms.joinToString("\n").toByteArray()) }
                    toast("★ ${ms.size} highlight குறிப்புகள்: Download/RawCricket")
                } catch (e: Exception) {
                    toast("Highlight கோப்பு சேமிக்க முடியவில்லை")
                }
            }
        }.start()
    }

    private fun fixUrl(raw: String): String {
        var s = raw.trim().replace(" ", "")
        if (s.isEmpty()) return s
        if (!s.contains("://")) return "rtmp://a.rtmp.youtube.com/live2/$s"
        val m = Regex("^(rtmps?://[^/]+/live2)([^/?].*)$").find(s)
        if (m != null) s = m.groupValues[1] + "/" + m.groupValues[2]
        return s
    }

    private fun dims(q: Int): Pair<Int, Int> = when (q) {
        540 -> Pair(960, 540)
        1080 -> Pair(1920, 1080)
        else -> Pair(1280, 720)
    }

    private fun kbpsFor(q: Int, live: Boolean): Int = when (q) {
        540 -> if (live) 1800 else 2200
        1080 -> if (live) 5000 else 6000
        else -> if (live) 2500 else 3000
    }

    private fun prep(live: Boolean): Boolean {
        val q = if (live) liveQ else recQ
        val d = dims(q)
        encW = d.first
        encH = d.second
        val audioOk = if (nsOn) cam.prepareAudio(128 * 1024, 44100, false, true, true) else cam.prepareAudio()
        return audioOk && cam.prepareVideo(encW, encH, 30, kbpsFor(q, live) * 1000, 0)
    }

    private fun toggleRec() {
        if (cam.isStreaming || wantLive) { toast("Live நடக்கும்போது பதிவு தானாகவே நடக்கிறது (Live தரத்தில்)"); return }
        if (cam.isRecording) stopRec() else startRec()
    }

    private fun startRec() {
        try {
            if (cam.isStreaming || cam.isRecording) return
            if (!hasPerms()) { toast("Camera/Mic அனுமதி தேவை"); return }
            if (!spaceOk()) return
            if (prep(false)) {
                val f = newRecFile()
                recFile = f
                cam.startRecord(f.absolutePath)
                recStartMs = SystemClock.elapsedRealtime()
                marks.clear()
                wantRec = true
                recBtn.text = "STOP REC"
                installFilterSoon()
                scheduleSplit()
            } else toast("இந்தத் தரம் ஆதரிக்கப்படவில்லை அல்லது கேமரா தயாராகவில்லை")
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
    }

    private fun stopRec() {
        try {
            wantRec = false
            glView.removeCallbacks(splitRunner)
            if (cam.isRecording) { cam.stopRecord(); finishRecording() }
            recBtn.text = "REC"
            installFilterSoon()
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
    }

    private fun toggleLive() {
        if (cam.isStreaming || wantLive) stopLive() else startLive()
    }

    private fun startLive() {
        if (cam.isStreaming) return
        if (cam.isRecording) { toast("முதலில் REC-ஐ நிறுத்துங்கள்"); return }
        val raw = url.text.toString()
        val target2 = fixUrl(raw)
        if (target2.isEmpty()) { toast("Stream URL + key உள்ளிடுங்கள்"); return }
        if (target2 != raw.trim()) url.setText(target2)
        if (!hasPerms()) { toast("Camera/Mic அனுமதி தேவை"); return }
        if (!spaceOk()) return
        try {
            if (prep(true)) {
                val f = newRecFile()
                recFile = f
                try {
                    cam.startRecord(f.absolutePath)
                    recStartMs = SystemClock.elapsedRealtime()
                    marks.clear()
                    scheduleSplit()
                } catch (e: Exception) { recFile = null; toast("Recording தொடங்கவில்லை") }
                wantRec = false
                wantLive = true
                connected = false
                retryCount = 0
                cam.startStream(target2)
                liveBtn.text = "STOP"
                installFilterSoon()
            } else toast("இந்தத் தரம் ஆதரிக்கப்படவில்லை அல்லது கேமரா தயாராகவில்லை")
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
    }

    private fun stopLive() {
        wantLive = false
        connected = false
        glView.removeCallbacks(retryRunner)
        glView.removeCallbacks(splitRunner)
        try {
            if (cam.isStreaming) cam.stopStream()
            if (cam.isRecording) { cam.stopRecord(); finishRecording() }
        } catch (e: Exception) {}
        liveBtn.text = "GO LIVE"
        recBtn.text = "REC"
        installFilterSoon()
    }

    private fun scheduleRetry() {
        if (!wantLive) return
        if (retryCount >= 40) {
            toast("இணைப்பு மீளவில்லை. GO LIVE-ஐ மீண்டும் அழுத்துங்கள்")
            wantLive = false
            liveBtn.text = "GO LIVE"
            return
        }
        retryCount++
        glView.removeCallbacks(retryRunner)
        glView.postDelayed(retryRunner, 4000)
    }

    private fun reconnect() {
        if (!wantLive || cam.isStreaming) return
        val t = fixUrl(url.text.toString())
        try {
            if (!cam.isRecording) {
                if (!prep(true)) { scheduleRetry(); return }
            }
            cam.startStream(t)
        } catch (e: Exception) {
            scheduleRetry()
        }
    }

    private fun resumeNow() {
        resuming = false
        val l = resumeLive
        val r = resumeRec
        resumeLive = false
        resumeRec = false
        if (!hasPerms()) return
        if (l) { toast("Live மீண்டும் தொடங்குகிறது (பகுதி 2)"); startLive() }
        else if (r) { toast("பதிவு மீண்டும் தொடங்குகிறது (பகுதி 2)"); startRec() }
    }

    override fun surfaceCreated(h: SurfaceHolder) {}

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
        startCam()
        if ((resumeLive || resumeRec) && !resuming) {
            resuming = true
            glView.postDelayed({ resumeNow() }, 1500)
        }
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        resumeLive = wantLive
        resumeRec = wantRec && !wantLive
        glView.removeCallbacks(retryRunner)
        glView.removeCallbacks(splitRunner)
        try {
            if (cam.isStreaming) cam.stopStream()
            if (cam.isRecording) { cam.stopRecord(); finishRecording() }
            if (cam.isOnPreview) cam.stopPreview()
        } catch (e: Exception) {}
        liveBtn.text = "GO LIVE"
        recBtn.text = "REC"
        filterSet = false
        saveState()
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        connected = true
        retryCount = 0
        toast("Live இணைந்தது")
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            try { cam.stopStream() } catch (e: Exception) {}
            if (wantLive && connected) {
                toast("இணைப்பு துண்டிப்பு - மீண்டும் இணைக்கிறது...")
                scheduleRetry()
            } else {
                wantLive = false
                toast("இணைப்பு தோல்வி: $reason")
                liveBtn.text = "GO LIVE"
                if (cam.isRecording) recBtn.text = "STOP REC"
            }
        }
    }

    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {}
    override fun onAuthError() { toast("Auth error") }
    override fun onAuthSuccess() {}
}
