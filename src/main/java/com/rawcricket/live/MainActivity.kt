package com.rawcricket.live

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
        val n1: String = "", val n2: String = "", val bn: String = ""
    )
    data class Inn(
        val team: String, val runs: Int, val wk: Int, val legal: Int,
        val bat: Map<String, BS>, val outs: Set<String>, val bowl: Map<String, BW>
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

    private var nameA = ""
    private var nameB = ""
    private var playersA: List<String> = emptyList()
    private var playersB: List<String> = emptyList()
    private var batIsA = true

    private var totalOvers = 10
    private var maxPerBowler = 0
    private var freeHitOn = true
    private var innings = 1
    private var target = 0

    private var inn1: Inn? = null
    private var inn2: Inn? = null
    private var matchOver = false
    private var resultText = ""
    private var cardPage = 0

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
        for (e in listOf(b1, b2, bowler)) {
            e.isFocusable = false
            e.isFocusableInTouchMode = false
            e.isCursorVisible = false
        }
        b1.setOnClickListener { pickBatter(0) {} }
        b2.setOnClickListener { pickBatter(1) {} }
        bowler.setOnClickListener { pickBowler() }
        val teamsBtn = Button(this).apply { text = "TEAMS"; textSize = 12f; setOnClickListener { showTeams() } }
        val matchBtn = Button(this).apply { text = "MATCH"; textSize = 12f; setOnClickListener { showMatch() } }
        val swapBtn = Button(this).apply { text = "SWAP"; textSize = 12f; setOnClickListener { confirmSwap() } }
        val cardBtn = Button(this).apply {
            text = "CARD"; textSize = 12f
            setOnClickListener { cardPage = (cardPage + 1) % 3; refresh() }
        }
        val row2 = LinearLayout(this)
        row2.addView(b1, lp(1f)); row2.addView(b2, lp(1f)); row2.addView(bowler, lp(1f))
        row2.addView(teamsBtn, LinearLayout.LayoutParams(-2, -2))
        row2.addView(matchBtn, LinearLayout.LayoutParams(-2, -2))
        row2.addView(swapBtn, LinearLayout.LayoutParams(-2, -2))
        row2.addView(cardBtn, LinearLayout.LayoutParams(-2, -2))
        top.addView(row2)

        val row3 = LinearLayout(this)
        fun btn(t: String, a: () -> Unit) {
            row3.addView(Button(this).apply { text = t; textSize = 12f; setPadding(0, 0, 0, 0); setOnClickListener { a() } }, lp(1f))
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

        root.addView(top, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
        setContentView(root)

        loadTeams()

        cam = RtmpCamera2(glView, this)
        glView.holder.addCallback(this)

        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 1)
        }
    }

    // ---------- teams, match settings, pickers ----------

    private fun prefs() = getSharedPreferences("rc", MODE_PRIVATE)
    private fun parse(s: String) = s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    private fun batPlayers() = if (batIsA) playersA else playersB
    private fun bowlPlayers() = if (batIsA) playersB else playersA
    private fun maxWk(): Int { val n = batPlayers().size; return if (n >= 2) n - 1 else 10 }
    private fun bowlerCap(): Int =
        if (maxPerBowler > 0) maxPerBowler else Math.ceil(totalOvers / 5.0).toInt().coerceAtLeast(1)

    private fun loadTeams() {
        val p = prefs()
        nameA = p.getString("nA", "") ?: ""
        nameB = p.getString("nB", "") ?: ""
        playersA = parse(p.getString("pA", "") ?: "")
        playersB = parse(p.getString("pB", "") ?: "")
        totalOvers = p.getInt("ov", 10)
        maxPerBowler = p.getInt("mb", 0)
        freeHitOn = p.getBoolean("fh", true)
        if (nameA.isNotEmpty()) tBat.setText(nameA)
        if (nameB.isNotEmpty()) tBowl.setText(nameB)
    }

    private fun showMatch() {
        val eO = EditText(this).apply { hint = "மொத்த ஓவர்கள்"; setText("$totalOvers"); inputType = InputType.TYPE_CLASS_NUMBER }
        val eB = EditText(this).apply { hint = "ஒரு bowler-க்கு அதிகபட்ச ஓவர் (0 = தானாக)"; setText("$maxPerBowler"); inputType = InputType.TYPE_CLASS_NUMBER }
        val cb = CheckBox(this).apply { text = "No-ball-க்குப் பின் Free Hit"; isChecked = freeHitOn }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        box.addView(eO); box.addView(eB); box.addView(cb)
        AlertDialog.Builder(this).setTitle("போட்டி விதிகள்")
            .setMessage("தானாக = மொத்த ஓவர் ÷ 5 (மேல்நோக்கி)")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                totalOvers = eO.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: totalOvers
                maxPerBowler = eB.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: maxPerBowler
                freeHitOn = cb.isChecked
                prefs().edit().putInt("ov", totalOvers).putInt("mb", maxPerBowler).putBoolean("fh", freeHitOn).apply()
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
        val eA = et("Team A பெயர்", nameA, false)
        val pA = et("Team A வீரர்கள் (ஒரு வரிக்கு ஒரு பெயர்)", playersA.joinToString("\n"), true)
        val eB = et("Team B பெயர்", nameB, false)
        val pB = et("Team B வீரர்கள் (ஒரு வரிக்கு ஒரு பெயர்)", playersB.joinToString("\n"), true)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        box.addView(eA); box.addView(pA); box.addView(eB); box.addView(pB)
        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this).setTitle("அணிகள்").setView(sv)
            .setPositiveButton("Save") { _, _ ->
                nameA = eA.text.toString().trim()
                nameB = eB.text.toString().trim()
                playersA = parse(pA.text.toString())
                playersB = parse(pB.text.toString())
                prefs().edit()
                    .putString("nA", nameA).putString("nB", nameB)
                    .putString("pA", pA.text.toString()).putString("pB", pB.text.toString())
                    .apply()
                if (nameA.isNotEmpty()) (if (batIsA) tBat else tBowl).setText(nameA)
                if (nameB.isNotEmpty()) (if (batIsA) tBowl else tBat).setText(nameB)
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

    private fun snap() = Inn(nm(tBat, "TEAM A"), st.runs, st.wk, st.legal, st.bat, st.outs, st.bowl)

    private fun confirmSwap() {
        val msg = if (innings == 1) "ஸ்கோர் பூஜ்ஜியமாகும். இலக்கு: ${st.runs + 1}" else "புதிய போட்டி தொடங்கும். ஸ்கோர் பூஜ்ஜியமாகும்."
        AlertDialog.Builder(this).setTitle("Innings மாற்றவா?").setMessage(msg)
            .setPositiveButton("ஆம்") { _, _ ->
                if (innings == 1) {
                    inn1 = snap()
                    target = st.runs + 1
                    innings = 2
                } else {
                    inn1 = null; inn2 = null; matchOver = false; resultText = ""
                    target = 0; innings = 1
                }
                cardPage = 0
                val a = tBat.text.toString()
                tBat.setText(tBowl.text.toString())
                tBowl.setText(a)
                batIsA = !batIsA
                st = St()
                hist.clear()
                b1.setText("Batter 1"); b2.setText("Batter 2"); bowler.setText("Bowler")
                refresh()
                pickBatter(0) { pickBatter(1) { pickBowler() } }
            }
            .setNegativeButton("இல்லை", null)
            .show()
    }

    private fun undo() {
        if (hist.isEmpty()) return
        val s = hist.removeAt(hist.size - 1)
        st = s
        if (matchOver) { matchOver = false; inn2 = null; resultText = ""; cardPage = 0 }
        if (s.n1.isNotEmpty()) b1.setText(s.n1)
        if (s.n2.isNotEmpty()) b2.setText(s.n2)
        if (s.bn.isNotEmpty()) bowler.setText(s.bn)
        refresh()
    }

    // ---------- match result ----------

    private fun finishMatch() {
        val a = inn1 ?: return
        val b = snap()
        inn2 = b
        matchOver = true
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
        refresh()
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
                i == 5 -> askWho { s -> deliver(false, "Wd+RO", extra = 1, bowlerRuns = 1, faced = false, wk = "Run out", outSlot = s) }
                else -> deliver(false, "Wd+St", extra = 1, bowlerRuns = 1, faced = false, wk = "Stumped", outSlot = st.strike, credit = true)
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
                else -> askWho { s -> deliver(false, "Nb+RO", extra = 1, bowlerRuns = 1, wk = "Run out", outSlot = s, setFH = freeHitOn) }
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
                deliver(true, "W", wk = t, outSlot = st.strike, credit = true)
            } else {
                askWho { slot ->
                    choose("ஓடிய ரன்கள்", listOf("0", "1", "2", "3")) { r ->
                        deliver(true, if (t == "Run out") "RO" else "W", batRuns = r, bowlerRuns = r, cross = r, wk = t, outSlot = slot)
                    }
                }
            }
        }
    }

    private fun nm(e: EditText, d: String) = e.text.toString().trim().ifEmpty { d }

    private fun deliver(
        legal: Boolean, label: String,
        batRuns: Int = 0, extra: Int = 0, bowlerRuns: Int = 0, cross: Int = 0,
        faced: Boolean = true, wk: String? = null, outSlot: Int = 0,
        credit: Boolean = false, setFH: Boolean = false
    ) {
        if (matchOver) { toast("போட்டி முடிந்தது. தவறு என்றால் Undo அழுத்துங்கள்"); return }
        cardPage = 0
        val n1 = nm(b1, "Batter 1"); val n2 = nm(b2, "Batter 2"); val bn = nm(bowler, "Bowler")
        val sn = if (st.strike == 0) n1 else n2
        val bat = st.bat.toMutableMap(); val bw = st.bowl.toMutableMap()
        val sb = bat[sn] ?: BS(); val bb = bw[bn] ?: BW()
        if (faced || batRuns > 0) bat[sn] = BS(sb.r + batRuns, sb.b + (if (faced) 1 else 0))
        bw[bn] = BW(bb.balls + (if (legal) 1 else 0), bb.runs + bowlerRuns, bb.w + (if (wk != null && credit) 1 else 0))
        val newLegal = st.legal + (if (legal) 1 else 0)
        var strike = st.strike
        if (cross % 2 == 1) strike = 1 - strike
        val overEnded = legal && newLegal % 6 == 0
        if (overEnded) strike = 1 - strike
        val outs = if (wk != null) st.outs + (if (outSlot == 0) n1 else n2) else st.outs
        val fh = if (setFH) true else if (legal) false else st.fh
        hist.add(st.copy(n1 = n1, n2 = n2, bn = bn))
        st = St(
            st.runs + batRuns + extra, st.wk + (if (wk != null) 1 else 0), newLegal, strike,
            bat, bw, (st.last + label).takeLast(6), outs, if (overEnded) bn else st.lastBowler,
            fh, st.extras + extra
        )
        refresh()

        val chased = innings == 2 && target > 0 && st.runs >= target
        val allOut = st.wk >= maxWk()
        val oversDone = st.legal >= totalOvers * 6
        if (chased) { finishMatch(); return }
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

    // ---------- overlay ----------

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
        p.textSize = 21f
        for ((n, s) in inn.bat.entries.take(11)) {
            p.color = Color.WHITE; p.textAlign = Paint.Align.LEFT
            fit(c, n + (if (n in inn.outs) "" else "*"), x, y, w - 130f, p)
            p.color = gold; p.textAlign = Paint.Align.RIGHT
            c.drawText("${s.r} (${s.b})", x + w, y, p)
            y += 25f
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
    }

    private fun drawMvp(c: Canvas, p: Paint, list: List<Inn>) {
        class PS(var r: Int = 0, var b: Int = 0, var w: Int = 0, var rc: Int = 0, var bl: Int = 0)
        val gold = 0xFFFFC107.toInt()
        val m = mutableMapOf<String, PS>()
        for (inn in list) {
            for ((n, s) in inn.bat) { val q = m.getOrPut(n) { PS() }; q.r += s.r; q.b += s.b }
            for ((n, b) in inn.bowl) { val q = m.getOrPut(n) { PS() }; q.w += b.w; q.rc += b.runs; q.bl += b.balls }
        }
        val rows = m.entries.sortedByDescending { it.value.r + 20 * it.value.w }.take(9)
        p.textAlign = Paint.Align.LEFT
        if (rows.isEmpty()) {
            p.color = Color.WHITE; p.textSize = 30f
            c.drawText("இன்னும் தரவு இல்லை", 30f, 160f, p)
            return
        }
        val first = rows[0]
        p.color = gold; p.textSize = 34f
        fit(c, "MVP - ${first.key.uppercase()}  (${first.value.r + 20 * first.value.w} pts)", 30f, 130f, 1220f, p)

        p.textSize = 20f; p.color = Color.LTGRAY
        c.drawText("#", 30f, 178f, p)
        c.drawText("PLAYER", 80f, 178f, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText("BAT", 800f, 178f, p)
        c.drawText("BOWL", 1030f, 178f, p)
        c.drawText("POINTS", 1250f, 178f, p)

        var y = 225f
        for ((i, e) in rows.withIndex()) {
            val q = e.value
            if (i % 2 == 0) {
                p.color = 0x22FFFFFF
                c.drawRect(30f, y - 33f, 1250f, y + 12f, p)
            }
            p.textSize = 28f
            p.textAlign = Paint.Align.LEFT
            p.color = if (i == 0) gold else Color.WHITE
            c.drawText("${i + 1}", 30f, y, p)
            fit(c, e.key, 80f, y, 450f, p)
            p.textAlign = Paint.Align.RIGHT
            c.drawText(if (q.b > 0 || q.r > 0) "${q.r} (${q.b})" else "-", 800f, y, p)
            c.drawText(if (q.bl > 0) "${q.w}-${q.rc} (${ov(q.bl)})" else "-", 1030f, y, p)
            p.color = gold
            c.drawText("${q.r + 20 * q.w}", 1250f, y, p)
            y += 48f
        }
        p.textAlign = Paint.Align.LEFT
        p.textSize = 18f; p.color = Color.LTGRAY
        c.drawText("Points = Runs + 20 x Wickets", 30f, 700f, p)
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
        if (cardPage == 1) {
            for (i in list.indices) drawInnings(c, p, list[i], if (i == 0) 30f else 660f, 590f, 100f)
        } else {
            drawMvp(c, p, list)
        }
    }

    private fun drawOverlay(): Bitmap {
        val w = 1280; val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.DEFAULT_BOLD
        if (cardPage != 0) {
            drawCard(c, p)
            return bmp
        }
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

        if (st.fh) {
            p.textSize = 28f
            val tw = p.measureText("FREE HIT")
            p.color = gold; c.drawRoundRect(1260f - tw - 36f, 30f, 1260f, 74f, 10f, 10f, p)
            p.color = Color.BLACK; c.drawText("FREE HIT", 1260f - tw - 18f, 62f, p)
        }

        val n1 = nm(b1, "Batter 1"); val n2 = nm(b2, "Batter 2"); val bn = nm(bowler, "Bowler")
        val top = 640f; val bot = 704f

        val lb = st.last.joinToString(" ")
        if (lb.isNotEmpty()) {
            p.textSize = 24f
            val tw = p.measureText(lb)
            p.color = navy; c.drawRoundRect(20f, top - 44f, 20f + tw + 24f, top - 8f, 8f, 8f, p)
            p.color = Color.WHITE; c.drawText(lb, 32f, top - 17f, p)
        }

        if (innings == 2 && target > 0) {
            val need = target - st.runs
            val left = totalOvers * 6 - st.legal
            val t = if (need <= 0) "TARGET $target - REACHED" else "TARGET $target  Need $need off $left"
            p.textSize = 24f
            val tw = p.measureText(t)
            p.color = navy; c.drawRoundRect(1260f - tw - 24f, top - 44f, 1260f, top - 8f, 8f, 8f, p)
            p.color = gold; c.drawText(t, 1260f - tw - 12f, top - 17f, p)
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

    // ---------- recording & live ----------

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
        val target2 = url.text.toString().trim()
        if (target2.isEmpty()) { toast("Stream URL + key உள்ளிடுங்கள்"); return }
        if (!hasPerms()) { toast("Camera/Mic அனுமதி தேவை"); return }
        try {
            if (cam.prepareAudio() && cam.prepareVideo(1280, 720, 30, 2500 * 1000, 0)) {
                val f = newRecFile()
                recFile = f
                try { cam.startRecord(f.absolutePath) } catch (e: Exception) { recFile = null; toast("Recording தொடங்கவில்லை") }
                cam.startStream(target2)
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
}
