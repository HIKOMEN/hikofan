package com.hikofan.mobil

import android.Manifest
import android.app.Dialog
import android.content.ClipData
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // ---- tema ----
    private val cBg = Color.parseColor("#05070d")
    private val cBgTop = Color.parseColor("#0b1a3a")
    private val cCard = Color.parseColor("#0e1424")
    private val cCard2 = Color.parseColor("#090d18")
    private val cBorder = Color.parseColor("#1c2a4a")
    private val cAccent = Color.parseColor("#3b82f6")
    private val cAccentD = Color.parseColor("#2563eb")
    private val cAccentL = Color.parseColor("#93c5fd")
    private val cText = Color.parseColor("#eef3ff")
    private val cMuted = Color.parseColor("#8f9bb8")
    private val cOk = Color.parseColor("#4ade80")
    private val cWarn = Color.parseColor("#fbbf24")
    private val cErr = Color.parseColor("#f87171")

    private val worker = Executors.newSingleThreadExecutor()
    private val infoWorker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val pid = "hikofan_dl"
    private val infoPid = "hikofan_info"
    private val prefs by lazy { getSharedPreferences("hikofan", MODE_PRIVATE) }

    // Sabit indirme linki: her zaman EN SON surumu direkt indirir (GitHub Releases)
    private val apkUrl = "https://github.com/HIKOMEN/hikofan/releases/latest/download/HikofanMobil.apk"
    private val latestApi = "https://api.github.com/repos/HIKOMEN/hikofan/releases/latest"

    @Volatile private var ready = false
    @Volatile private var busy = false
    @Volatile private var cancelled = false
    @Volatile private var infoToken = 0

    private var lastInfoUrl: String? = null
    private var audioMode = false
    private var maxRes = 0                       // 0 = en yuksek
    private var lastSavedUri: Uri? = null
    private var lastSavedMime = "video/mp4"

    // Tarayicidan gelen akis (m3u8) icin: o sitenin istedigi basliklar
    private var hdrUrl: String? = null
    private var hdrReferer = ""
    private var hdrUa = ""
    private var hdrCookie = ""

    // arayuz parcalari
    private lateinit var urlBox: EditText
    private lateinit var pasteBtn: TextView
    private lateinit var engineTv: TextView
    private lateinit var updateBanner: TextView
    private lateinit var hero: LinearLayout
    private lateinit var previewCard: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var thumbSpinner: ProgressBar
    private lateinit var durBadge: TextView
    private lateinit var platBadge: TextView
    private lateinit var titleTv: TextView
    private lateinit var metaTv: TextView
    private lateinit var optCard: LinearLayout
    private lateinit var segVideo: TextView
    private lateinit var segAudio: TextView
    private lateinit var chipsRow: LinearLayout
    private val chips = mutableListOf<Pair<Int, TextView>>()
    private lateinit var compatRow: LinearLayout
    private lateinit var compat: SwitchCompat
    private lateinit var btnDownload: Button
    private lateinit var progressCard: LinearLayout
    private lateinit var pctTv: TextView
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var btnCancel: Button
    private lateinit var resultRow: LinearLayout
    private lateinit var histTitle: TextView
    private lateinit var histBox: LinearLayout

    private val infoRunnable = Runnable { onUrlChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        handleIntent(intent)
        initEngine()
        checkUpdate { n ->
            if (n != null && n > currentCode()) {
                updateBanner.text = "⬆  Yeni sürüm var (v$n) – dokun ve güncelle"
                updateBanner.visibility = View.VISIBLE
                showUpdateDialog(n)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Baska uygulamadan "Paylas" ile link gelirse kutuya yaz
    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!t.isNullOrBlank()) urlBox.setText(extractUrl(t) ?: t)
        }
        // Uygulama ici tarayicidan "Indir"e basilinca
        if (i?.action == "com.hikofan.mobil.DOWNLOAD") {
            val u = i.getStringExtra("url")
            if (!u.isNullOrBlank()) {
                hdrUrl = extractUrl(u) ?: u
                hdrReferer = i.getStringExtra("referer") ?: ""
                hdrUa = i.getStringExtra("ua") ?: ""
                hdrCookie = i.getStringExtra("cookie") ?: ""
                lastInfoUrl = null
                urlBox.setText(u)
            }
        }
    }

    // ------------------------------------------------------------ arayuz yardimcilari
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun mkButton(label: String, color: Int) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color, 14)
        stateListAnimator = null
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, left: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); leftMargin = dp(left) }

    private fun mkSeg(label: String) = TextView(this).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(cMuted); typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
    }

    // ------------------------------------------------------------ arayuz
    private fun buildUi() {
        val root = ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cBgTop, cBg, cBg))
            isFillViewport = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(40), dp(18), dp(28))
        }
        root.addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- ust cubuk: logo + motor durumu
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "HIKO"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        })
        top.addView(TextView(this).apply {
            text = "FAN"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cAccent)
        })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        engineTv = TextView(this).apply {
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            background = rounded(cCard, 20, cBorder)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        top.addView(engineTv)
        val gear = TextView(this).apply {
            text = "⚙"; textSize = 20f; setTextColor(cAccentL); gravity = Gravity.CENTER
            background = rounded(cCard, 20, cBorder)
        }
        gear.setOnClickListener { showSettings() }
        val globe = TextView(this).apply {
            text = "🌐"; textSize = 18f; gravity = Gravity.CENTER
            background = rounded(cCard, 20, cBorder)
        }
        globe.setOnClickListener { openBrowser() }
        top.addView(globe, lp(dp(40), dp(40), left = 10))
        top.addView(gear, lp(dp(40), dp(40), left = 10))
        col.addView(top, lp(MATCH_PARENT, WRAP_CONTENT))

        updateBanner = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(cAccentD, 14)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            visibility = View.GONE
        }
        updateBanner.setOnClickListener { openUrl(apkUrl) }
        col.addView(updateBanner, lp(MATCH_PARENT, WRAP_CONTENT, top = 16))

        // ---- arama cubugu gibi link alani
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cCard, 28, cBorder)
            setPadding(dp(18), dp(4), dp(8), dp(4))
        }
        urlBox = EditText(this).apply {
            hint = "Video linkini buraya yapıştır"
            setHintTextColor(Color.parseColor("#566383"))
            setTextColor(cText); textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = null
            setPadding(0, dp(12), dp(8), dp(12))
        }
        urlBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(infoRunnable)
                handler.postDelayed(infoRunnable, 600)
                updateLayoutState()
            }
        })
        inputRow.addView(urlBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pasteBtn = TextView(this).apply {
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(cAccent, 22)
            setPadding(dp(16), 0, dp(16), 0)
        }
        pasteBtn.setOnClickListener {
            if (urlBox.text.isNotEmpty()) {
                urlBox.setText("")
            } else {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                if (t.isNullOrBlank()) toast("Panoda link yok") else urlBox.setText(extractUrl(t) ?: t)
            }
        }
        inputRow.addView(pasteBtn, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)))
        col.addView(inputRow, lp(MATCH_PARENT, WRAP_CONTENT, top = 22))

        // ---- bos durum karti
        hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(cCard2, 20, cBorder)
            setPadding(dp(20), dp(34), dp(20), dp(34))
        }
        hero.addView(TextView(this).apply {
            text = "↓"; textSize = 34f; setTextColor(cAccent); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#0f1f45"), 32)
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        hero.addView(TextView(this).apply {
            text = "Bir link yapıştır"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cText)
            gravity = Gravity.CENTER
        }, lp(WRAP_CONTENT, WRAP_CONTENT, top = 16))
        hero.addView(TextView(this).apply {
            text = "YouTube • Instagram • X • TikTok • Facebook\nve 1000'den fazla site"
            textSize = 13f; setTextColor(cMuted); gravity = Gravity.CENTER
        }, lp(WRAP_CONTENT, WRAP_CONTENT, top = 6))
        val heroBrowse = TextView(this).apply {
            text = "🌐  Linkin yok mu? Siteyi tarayıcıda aç"
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cAccentL)
            gravity = Gravity.CENTER
            background = rounded(cCard, 22, cBorder)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        heroBrowse.setOnClickListener { openBrowser() }
        hero.addView(heroBrowse, lp(WRAP_CONTENT, WRAP_CONTENT, top = 22))
        col.addView(hero, lp(MATCH_PARENT, WRAP_CONTENT, top = 18))

        // ---- onizleme karti
        previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 20, cBorder)
            setPadding(dp(10), dp(10), dp(10), dp(14))
            visibility = View.GONE
        }
        val thumbW = resources.displayMetrics.widthPixels - dp(18 * 2) - dp(10 * 2)
        val thumbFrame = FrameLayout(this).apply {
            background = rounded(cCard2, 14)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                }
            }
            clipToOutline = true
        }
        thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumbFrame.addView(thumb, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        thumbSpinner = ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(cAccentL) }
        thumbFrame.addView(thumbSpinner, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        durBadge = TextView(this).apply {
            textSize = 12f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.parseColor("#cc000000"), 8)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            visibility = View.GONE
        }
        thumbFrame.addView(durBadge, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        platBadge = TextView(this).apply {
            textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = rounded(cAccentD, 8)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            visibility = View.GONE
        }
        thumbFrame.addView(platBadge, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        previewCard.addView(thumbFrame, LinearLayout.LayoutParams(MATCH_PARENT, thumbW * 9 / 16))

        titleTv = TextView(this).apply {
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cText)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        previewCard.addView(titleTv, lp(MATCH_PARENT, WRAP_CONTENT, top = 12).apply { leftMargin = dp(4); rightMargin = dp(4) })
        metaTv = TextView(this).apply { textSize = 13f; setTextColor(cMuted); maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        previewCard.addView(metaTv, lp(MATCH_PARENT, WRAP_CONTENT, top = 4).apply { leftMargin = dp(4); rightMargin = dp(4) })
        col.addView(previewCard, lp(MATCH_PARENT, WRAP_CONTENT, top = 16))

        // ---- ayarlar karti
        optCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 20, cBorder)
            setPadding(dp(14), dp(14), dp(14), dp(10))
            visibility = View.GONE
        }
        optCard.addView(label("FORMAT"))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(cCard2, 14, cBorder)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        segVideo = mkSeg("Video  MP4")
        segAudio = mkSeg("Ses  MP3")
        segVideo.setOnClickListener { selectMode(false) }
        segAudio.setOnClickListener { selectMode(true) }
        seg.addView(segVideo, lp(0, dp(42), 1f))
        seg.addView(segAudio, lp(0, dp(42), 1f))
        optCard.addView(seg, lp(MATCH_PARENT, WRAP_CONTENT, top = 8))

        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chipsRow.addView(label("KALİTE"), lp(WRAP_CONTENT, WRAP_CONTENT, top = 14))
        val chipLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val opts = listOf(0 to "Maks", 1080 to "1080p", 720 to "720p", 480 to "480p")
        opts.forEachIndexed { idx, (res, name) ->
            val tv = TextView(this).apply {
                text = name; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            }
            tv.setOnClickListener { selectRes(res) }
            chips.add(res to tv)
            chipLine.addView(tv, lp(0, dp(38), 1f, left = if (idx == 0) 0 else 8))
        }
        chipsRow.addView(chipLine, lp(MATCH_PARENT, WRAP_CONTENT, top = 8))
        optCard.addView(chipsRow, lp(MATCH_PARENT, WRAP_CONTENT))

        compatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val compatTxt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        compatTxt.addView(TextView(this).apply {
            text = "Uyumlu mod"; textSize = 14f; setTextColor(cText); typeface = Typeface.DEFAULT_BOLD
        })
        compatTxt.addView(TextView(this).apply {
            text = "H.264, en fazla 1080p – her telefonda açılır"; textSize = 12f; setTextColor(cMuted)
        })
        compatRow.addView(compatTxt, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        compat = SwitchCompat(this).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(cAccentL, cMuted))
            trackTintList = ColorStateList(states, intArrayOf(cAccentD, cBorder))
        }
        compatRow.addView(compat, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        optCard.addView(compatRow, lp(MATCH_PARENT, WRAP_CONTENT, top = 14))
        col.addView(optCard, lp(MATCH_PARENT, WRAP_CONTENT, top = 12))

        // ---- ana indirme butonu
        btnDownload = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cAccent, cAccentD)).apply {
                cornerRadius = dp(18).toFloat()
            }
        }
        btnDownload.setOnClickListener { start() }
        col.addView(btnDownload, lp(MATCH_PARENT, dp(60), top = 14))

        // ---- ilerleme karti
        progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 20, cBorder)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
        }
        pctTv = TextView(this).apply { textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); text = "" }
        progressCard.addView(pctTv, lp(WRAP_CONTENT, WRAP_CONTENT))
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(cAccent)
            progressBackgroundTintList = ColorStateList.valueOf(cBorder)
        }
        progressCard.addView(bar, lp(MATCH_PARENT, dp(8), top = 6))
        status = TextView(this).apply { textSize = 14f; setTextColor(cMuted) }
        progressCard.addView(status, lp(MATCH_PARENT, WRAP_CONTENT, top = 10))

        btnCancel = mkButton("İptal", Color.parseColor("#3a1220")).apply { visibility = View.GONE; textSize = 14f }
        btnCancel.setOnClickListener {
            cancelled = true
            Thread { try { YoutubeDL.getInstance().destroyProcessById(pid) } catch (_: Exception) {} }.start()
        }
        progressCard.addView(btnCancel, lp(MATCH_PARENT, dp(42), top = 12))

        resultRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        val btnOpen = mkButton("Aç", cAccent)
        val btnShare = mkButton("Paylaş", Color.parseColor("#16295a"))
        btnOpen.setOnClickListener { lastSavedUri?.let { openUri(it, lastSavedMime) } }
        btnShare.setOnClickListener { shareSaved() }
        resultRow.addView(btnOpen, lp(0, dp(44), 1f))
        resultRow.addView(btnShare, lp(0, dp(44), 1f, left = 10))
        progressCard.addView(resultRow, lp(MATCH_PARENT, WRAP_CONTENT, top = 12))
        col.addView(progressCard, lp(MATCH_PARENT, WRAP_CONTENT, top = 14))

        // ---- son indirilenler
        histTitle = label("SON İNDİRİLENLER").apply { visibility = View.GONE }
        col.addView(histTitle, lp(WRAP_CONTENT, WRAP_CONTENT, top = 26))
        histBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(histBox, lp(MATCH_PARENT, WRAP_CONTENT, top = 4))

        setContentView(root)
        selectMode(false)
        selectRes(0)
        setEngine("Başlatılıyor…", cWarn)
        updateLayoutState()
        renderHistory()
    }

    private fun onUi(block: () -> Unit) = runOnUiThread { block() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun setEngine(text: String, color: Int) {
        engineTv.text = "●  $text"
        engineTv.setTextColor(color)
    }

    private fun setStatus(s: String, color: Int = cMuted) {
        status.text = s
        status.setTextColor(color)
    }

    private fun styleSeg(v: TextView, on: Boolean) {
        v.background = if (on) rounded(cAccent, 11) else null
        v.setTextColor(if (on) Color.WHITE else cMuted)
    }

    private fun selectMode(audio: Boolean) {
        audioMode = audio
        styleSeg(segVideo, !audio)
        styleSeg(segAudio, audio)
        val vis = if (audio) View.GONE else View.VISIBLE
        chipsRow.visibility = vis
        compatRow.visibility = vis
        refreshDownloadButton()
    }

    private fun selectRes(res: Int) {
        maxRes = res
        chips.forEach { (r, tv) ->
            val on = r == res
            tv.background = if (on) rounded(cAccent, 10) else rounded(cCard2, 10, cBorder)
            tv.setTextColor(if (on) Color.WHITE else cMuted)
        }
        refreshDownloadButton()
    }

    // Videoda olmayan (daha yuksek) kalite secenekleri gizlenir
    private fun updateChipsForHeight(h: Int) {
        chips.forEach { (r, tv) ->
            if (r != 0) tv.visibility = if (h > 0 && r >= h) View.GONE else View.VISIBLE
        }
        val cur = chips.firstOrNull { it.first == maxRes }
        if (cur != null && cur.second.visibility == View.GONE) selectRes(0)
    }

    // Link var mi yok mu'ya gore ekrani duzenler
    private fun updateLayoutState() {
        val has = extractUrl(urlBox.text.toString()) != null
        hero.visibility = if (has) View.GONE else View.VISIBLE
        optCard.visibility = if (has) View.VISIBLE else View.GONE
        pasteBtn.text = if (urlBox.text.isNotEmpty()) "Temizle" else "Yapıştır"
        refreshDownloadButton()
    }

    private fun refreshDownloadButton() {
        if (!::btnDownload.isInitialized) return
        val hasUrl = extractUrl(urlBox.text.toString()) != null
        val ok = hasUrl && ready && !busy
        btnDownload.isEnabled = ok
        btnDownload.alpha = if (ok) 1f else 0.4f
        btnDownload.text = when {
            !ready -> "Motor hazırlanıyor…"
            busy -> "İndiriliyor…"
            !hasUrl -> "Önce bir link yapıştır"
            audioMode -> "↓  MP3 olarak indir"
            maxRes > 0 -> "↓  MP4 indir  ·  en fazla ${maxRes}p"
            else -> "↓  MP4 indir  ·  en yüksek kalite"
        }
    }

    private fun setIdle(idle: Boolean) {
        busy = !idle
        btnCancel.visibility = if (idle) View.GONE else View.VISIBLE
        refreshDownloadButton()
    }

    // ------------------------------------------------------------ onizleme (video bilgisi)
    private data class Info(
        val title: String, val uploader: String, val platform: String,
        val duration: Double, val maxH: Int, val bmp: Bitmap?
    )

    private fun onUrlChanged() {
        val url = extractUrl(urlBox.text.toString())
        if (url == null) {
            infoToken++
            lastInfoUrl = null
            previewCard.visibility = View.GONE
            updateChipsForHeight(0)
            return
        }
        if (!ready) return                       // motor hazir olunca tekrar denenir
        if (url == lastInfoUrl) return
        fetchInfo(url)
    }

    private fun fetchInfo(url: String) {
        val token = ++infoToken
        lastInfoUrl = url
        previewCard.visibility = View.VISIBLE
        thumb.setImageDrawable(null)
        thumbSpinner.visibility = View.VISIBLE
        durBadge.visibility = View.GONE
        platBadge.visibility = View.GONE
        titleTv.text = "Video bilgisi alınıyor…"
        metaTv.text = ""

        val myPid = infoPid + "_" + token
        val prevPid = infoPid + "_" + (token - 1)
        Thread { try { YoutubeDL.getInstance().destroyProcessById(prevPid) } catch (_: Exception) {} }.start()
        infoWorker.execute {
            try {
                val r = YoutubeDLRequest(url)
                applyHeaders(r, url)
                r.addOption("--dump-single-json")
                r.addOption("--no-playlist")
                r.addOption("--no-warnings")
                r.addOption("--ignore-no-formats-error")
                r.addOption("--socket-timeout", "15")
                val resp = YoutubeDL.getInstance().execute(r, myPid) { _, _, _ -> }
                val raw = resp.out
                val js = raw.lines().lastOrNull { it.trim().startsWith("{") } ?: raw
                val o = JSONObject(js)

                var thumbUrl = o.optString("thumbnail", "")
                if (thumbUrl.isBlank() || thumbUrl == "null") {
                    val arr = o.optJSONArray("thumbnails")
                    if (arr != null && arr.length() > 0) thumbUrl = arr.getJSONObject(arr.length() - 1).optString("url", "")
                }
                var maxH = 0
                val fm = o.optJSONArray("formats")
                if (fm != null) {
                    for (i in 0 until fm.length()) {
                        val f = fm.optJSONObject(i) ?: continue
                        if (f.optString("vcodec", "none") == "none") continue
                        val h = f.optInt("height", 0)
                        if (h > maxH) maxH = h
                    }
                }
                val uploader = o.optString("uploader", "").ifBlank { o.optString("channel", "") }
                val bmp = if (thumbUrl.isNotBlank() && thumbUrl != "null") loadBitmap(thumbUrl) else null
                val info = Info(
                    title = o.optString("title", "").ifBlank { "Başlıksız video" },
                    uploader = if (uploader == "null") "" else uploader,
                    platform = o.optString("extractor_key", ""),
                    duration = o.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it },
                    maxH = maxH,
                    bmp = bmp
                )
                onUi { if (token == infoToken) showPreview(info) }
            } catch (e: Exception) {
                val why = errLine(e)
                onUi {
                    if (token == infoToken) {
                        thumbSpinner.visibility = View.GONE
                        titleTv.text = "Önizleme alınamadı"
                        metaTv.text = "$why\n\nYine de aşağıdaki butonla indirmeyi deneyebilirsin."
                    }
                }
            }
        }
    }

    private fun loadBitmap(u: String): Bitmap? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        c.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    private fun showPreview(i: Info) {
        thumbSpinner.visibility = View.GONE
        if (i.bmp != null) thumb.setImageBitmap(i.bmp)
        if (i.duration > 0) {
            durBadge.text = fmtDuration(i.duration)
            durBadge.visibility = View.VISIBLE
        } else durBadge.visibility = View.GONE
        if (i.platform.isNotBlank()) {
            platBadge.text = i.platform
            platBadge.visibility = View.VISIBLE
        }
        titleTv.text = i.title
        val parts = mutableListOf<String>()
        if (i.uploader.isNotBlank()) parts.add(i.uploader)
        if (i.maxH > 0) parts.add("en fazla ${i.maxH}p")
        metaTv.text = parts.joinToString("  •  ")
        updateChipsForHeight(i.maxH)
    }

    private fun fmtDuration(sec: Double): String {
        val s = sec.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, ss)
        else String.format(Locale.US, "%d:%02d", m, ss)
    }

    // ------------------------------------------------------------ motor (acilista otomatik guncellenir)
    private fun initEngine() {
        worker.execute {
            var ok = false
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                ok = true
            } catch (e: Exception) {
                val m = e.message
                onUi { setEngine("Hata", cErr); progressCard.visibility = View.VISIBLE; setStatus("Başlatma hatası: $m", cErr) }
            }
            if (ok) {
                onUi { setEngine("Güncelleniyor…", cWarn) }
                var updated = true
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                } catch (_: Exception) {
                    updated = false          // internet yoksa / hata olursa eski surumle devam
                }
                ready = true
                onUi {
                    setEngine(if (updated) "Hazır" else "Hazır (güncellenemedi)", if (updated) cOk else cWarn)
                    refreshDownloadButton()
                    onUrlChanged()
                }
            }
        }
    }

    // ------------------------------------------------------------ ayarlar / guncelleme / paylasma
    @Suppress("DEPRECATION")
    private fun currentCode(): Int = try {
        val p = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else p.versionCode
    } catch (_: Exception) {
        0
    }

    private fun currentName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    // GitHub'daki en son surum numarasini ogrenir (null = ogrenilemedi)
    private fun checkUpdate(done: (Int?) -> Unit) {
        Thread {
            var n: Int? = null
            try {
                val c = URL(latestApi).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "HIKOFAN")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val tag = JSONObject(body).optString("tag_name", "")
                n = tag.filter { it.isDigit() }.toIntOrNull()
            } catch (_: Exception) {
            }
            onUi { done(n) }
        }.start()
    }

    // Acilista yeni surum varsa ortada cikan pencere: Guncelle / Simdi degil
    private fun showUpdateDialog(n: Int) {
        if (isFinishing) return
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 24, cBorder)
            setPadding(dp(22), dp(24), dp(22), dp(20))
        }
        box.addView(TextView(this).apply {
            text = "⬆"; textSize = 32f; setTextColor(cAccent); gravity = Gravity.CENTER
        }, lp(MATCH_PARENT, WRAP_CONTENT))
        box.addView(TextView(this).apply {
            text = "Yeni sürüm var"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }, lp(MATCH_PARENT, WRAP_CONTENT, top = 6))
        box.addView(TextView(this).apply {
            text = "HIKOFAN v$n yayınlandı. Güncelle'ye dokun, inen dosyayı aç ve Yükle'ye bas."
            textSize = 14f; setTextColor(cMuted); gravity = Gravity.CENTER
        }, lp(MATCH_PARENT, WRAP_CONTENT, top = 8))
        val bUpd = mkButton("Güncelle", cAccent)
        bUpd.setOnClickListener { d.dismiss(); openUrl(apkUrl) }
        val bLater = mkButton("Şimdi değil", cCard2).apply {
            background = rounded(cCard2, 14, cBorder); setTextColor(cMuted)
        }
        bLater.setOnClickListener { d.dismiss() }
        box.addView(bUpd, lp(MATCH_PARENT, dp(48), top = 20))
        box.addView(bLater, lp(MATCH_PARENT, dp(46), top = 8))
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(resources.displayMetrics.widthPixels - dp(40), WRAP_CONTENT)
        }
        d.show()
    }

    private fun openUrl(u: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (_: Exception) {
            toast("Tarayıcı açılamadı")
        }
    }

    private fun shareMessage() =
        "HIKOFAN – video indirme uygulaması 📲\n\n" +
            "1) Aşağıdaki linke dokun, dosya telefonuna insin.\n" +
            "2) İnen dosyaya dokunup \"Yükle\" de (telefon izin isterse \"İzin ver\" de).\n\n" +
            apkUrl

    private fun showSettings() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cCard)
                val r = dp(24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        box.addView(TextView(this).apply {
            text = "Ayarlar"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            text = "Sürüm " + currentName(); textSize = 13f; setTextColor(cMuted)
        }, lp(WRAP_CONTENT, WRAP_CONTENT, top = 2))

        // --- guncelleme
        box.addView(label("GÜNCELLEME"), lp(WRAP_CONTENT, WRAP_CONTENT, top = 22))
        val res = TextView(this).apply { textSize = 14f; setTextColor(cMuted) }
        val btnGet = mkButton("↓  Yeni sürümü indir", cAccent).apply { visibility = View.GONE }
        btnGet.setOnClickListener { openUrl(apkUrl) }
        val btnCheck = mkButton("Güncellemeyi kontrol et", Color.parseColor("#16295a"))
        btnCheck.setOnClickListener {
            res.text = "Kontrol ediliyor…"; res.setTextColor(cMuted)
            checkUpdate { n ->
                if (n == null) {
                    res.text = "Kontrol edilemedi. İnternetini kontrol et."; res.setTextColor(cErr)
                } else if (n > currentCode()) {
                    res.text = "Yeni sürüm var (v$n)"; res.setTextColor(cWarn)
                    btnGet.visibility = View.VISIBLE
                } else {
                    res.text = "Uygulama güncel ✓"; res.setTextColor(cOk)
                }
            }
        }
        box.addView(btnCheck, lp(MATCH_PARENT, dp(46), top = 8))
        box.addView(res, lp(MATCH_PARENT, WRAP_CONTENT, top = 8))
        box.addView(btnGet, lp(MATCH_PARENT, dp(46), top = 8))

        // --- arkadaslara gonder
        box.addView(label("UYGULAMAYI ARKADAŞLARINA GÖNDER"), lp(WRAP_CONTENT, WRAP_CONTENT, top = 24))
        box.addView(TextView(this).apply {
            text = apkUrl; textSize = 12f; setTextColor(cAccentL); setTextIsSelectable(true)
            background = rounded(cCard2, 12, cBorder)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, lp(MATCH_PARENT, WRAP_CONTENT, top = 8))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bCopy = mkButton("Linki kopyala", Color.parseColor("#16295a"))
        val bShare = mkButton("Paylaş (WhatsApp…)", cAccent)
        bCopy.setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("HIKOFAN", apkUrl))
            toast("Link kopyalandı")
        }
        bShare.setOnClickListener {
            try {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareMessage())
                }
                startActivity(Intent.createChooser(i, "Gönder"))
            } catch (_: Exception) {
                toast("Paylaşılamadı")
            }
        }
        row.addView(bCopy, lp(0, dp(46), 1f))
        row.addView(bShare, lp(0, dp(46), 1f, left = 10))
        box.addView(row, lp(MATCH_PARENT, WRAP_CONTENT, top = 10))
        box.addView(TextView(this).apply {
            text = "Linke dokunan kişinin telefonuna uygulama direkt iner."
            textSize = 12f; setTextColor(cMuted)
        }, lp(MATCH_PARENT, WRAP_CONTENT, top = 8))

        val close = mkButton("Kapat", cCard2).apply { background = rounded(cCard2, 14, cBorder) }
        close.setOnClickListener { d.dismiss() }
        box.addView(close, lp(MATCH_PARENT, dp(46), top = 22))

        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        d.show()
    }

    private fun openBrowser() {
        startActivity(Intent(this, BrowserActivity::class.java))
    }

    // Tarayicidan gelen adres icin referer/cerez/user-agent bilgisini yt-dlp'ye verir
    private fun applyHeaders(r: YoutubeDLRequest, url: String) {
        if (url != hdrUrl) return
        if (hdrReferer.isNotBlank()) r.addOption("--referer", hdrReferer)
        if (hdrUa.isNotBlank()) r.addOption("--user-agent", hdrUa)
        if (hdrCookie.isNotBlank()) r.addOption("--add-header", "Cookie:" + hdrCookie)
    }

    private fun extractUrl(t: String): String? =
        Regex("https?://\\S+").find(t)?.value?.trimEnd(')', ']', '.', ',')

    private fun errLine(e: Exception): String {
        val lines = (e.message ?: e.toString()).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val l = lines.firstOrNull { it.startsWith("ERROR") } ?: lines.lastOrNull() ?: "bilinmeyen hata"
        return if (l.length > 220) l.take(220) + "…" else l
    }

    private fun start() {
        val url = extractUrl(urlBox.text.toString())
        if (url == null) { toast("Önce geçerli bir link yapıştır"); return }
        if (!ready) { toast("Motor hazırlanıyor, birkaç saniye bekle"); return }
        if (busy) return
        cancelled = false
        val audio = audioMode
        val useCompat = compat.isChecked
        val wantRes = maxRes

        setIdle(false)
        progressCard.visibility = View.VISIBLE
        resultRow.visibility = View.GONE
        bar.progress = 0
        pctTv.text = "%0"
        setStatus("Başlıyor…", cMuted)

        worker.execute {
            val tmp = File(getExternalFilesDir(null) ?: cacheDir, "tmp")
            tmp.deleteRecursively(); tmp.mkdirs()
            try {
                val r = YoutubeDLRequest(url)
                applyHeaders(r, url)
                r.addOption("--no-playlist")
                r.addOption("--windows-filenames")
                r.addOption("--no-mtime")
                r.addOption("-o", tmp.absolutePath + "/%(title).100B.%(ext)s")
                if (audio) {
                    r.addOption("-f", "bestaudio/best")
                    r.addOption("-x")
                    r.addOption("--audio-format", "mp3")
                    r.addOption("--audio-quality", "320K")
                    r.addOption("--embed-metadata")
                } else {
                    // Secilen kaliteye kadar en yuksek cozunurluk -> en yuksek fps -> H.264 tercihi.
                    // Uyumlu modda 1080p tavan.
                    val cap = when {
                        useCompat && wantRes == 0 -> 1080
                        useCompat -> minOf(wantRes, 1080)
                        else -> wantRes
                    }
                    r.addOption("-f", "bv*+ba/b")
                    r.addOption("-S", (if (cap > 0) "res:$cap" else "res") + ",fps,vcodec:h264,acodec:aac")
                    r.addOption("--merge-output-format", "mp4")
                }

                YoutubeDL.getInstance().execute(r, pid) { p, eta, line ->
                    val pr = p.toInt().coerceIn(0, 100)
                    val txt = when {
                        line.contains("[Merger]") -> "Video ve ses birleştiriliyor…"
                        line.contains("[ExtractAudio]") -> "MP3'e çevriliyor…"
                        pr < 100 -> "İndiriliyor" + (if (eta > 0) "  •  kalan " + eta + " sn" else "")
                        else -> "İşleniyor…"
                    }
                    onUi { bar.progress = pr; pctTv.text = "%" + pr; setStatus(txt, cMuted) }
                }

                val exts = setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus")
                val out = tmp.listFiles()?.filter { it.isFile && it.extension.lowercase() in exts }
                    ?.maxByOrNull { it.lastModified() }
                if (out == null) {
                    onUi { setStatus("Dosya bulunamadı, tekrar dene.", cErr) }
                } else {
                    onUi { setStatus("Kaydediliyor…", cMuted) }
                    val (name, uri, mime) = saveToDownloads(out)
                    lastSavedUri = uri
                    lastSavedMime = mime
                    if (uri != null) addHistory(name, uri, mime)
                    onUi {
                        bar.progress = 100
                        pctTv.text = "✓ Bitti"
                        setStatus("İndirilenler/HIKOFAN/" + name, cOk)
                        resultRow.visibility = if (uri != null) View.VISIBLE else View.GONE
                        renderHistory()
                    }
                }
            } catch (e: Exception) {
                val m = if (cancelled) "İptal edildi." else "Hata: " + errLine(e)
                onUi { pctTv.text = ""; setStatus(m, if (cancelled) cMuted else cErr) }
            } finally {
                tmp.deleteRecursively()
                onUi { setIdle(true) }
            }
        }
    }

    // ------------------------------------------------------------ gecmis
    private fun addHistory(name: String, uri: Uri, mime: String) {
        val arr = JSONArray()
        arr.put(JSONObject().put("n", name).put("u", uri.toString()).put("m", mime))
        val old = try { JSONArray(prefs.getString("hist", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until minOf(old.length(), 5)) arr.put(old.get(i))
        prefs.edit().putString("hist", arr.toString()).apply()
    }

    private fun renderHistory() {
        histBox.removeAllViews()
        val arr = try { JSONArray(prefs.getString("hist", "[]")) } catch (_: Exception) { JSONArray() }
        histTitle.visibility = if (arr.length() == 0) View.GONE else View.VISIBLE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val mime = o.optString("m", "video/mp4")
            val u = o.optString("u", "")
            val row = TextView(this).apply {
                text = (if (mime.startsWith("audio")) "♪   " else "▶   ") + o.optString("n", "")
                textSize = 14f; setTextColor(cText)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                background = rounded(cCard, 14, cBorder)
                setPadding(dp(14), dp(13), dp(14), dp(13))
            }
            row.setOnClickListener { openUri(Uri.parse(u), mime) }
            histBox.addView(row, lp(MATCH_PARENT, WRAP_CONTENT, top = 8))
        }
    }

    private fun openUri(uri: Uri, mime: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            toast("Dosya açılamadı (silinmiş olabilir)")
        }
    }

    private fun shareSaved() {
        val uri = lastSavedUri ?: return
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = lastSavedMime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Paylaş"))
        } catch (_: Exception) {
            toast("Paylaşılamadı")
        }
    }

    // Dosyayi telefonun Indirilenler/HIKOFAN klasorune kopyalar. (ad, uri, mime) dondurur.
    private fun saveToDownloads(f: File): Triple<String, Uri?, String> {
        val mime = when (f.extension.lowercase()) {
            "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; else -> "application/octet-stream"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/HIKOFAN")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("Kayıt oluşturulamadı")
            contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, cv, null, null)
            return Triple(f.name, uri, mime)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HIKOFAN")
            dir.mkdirs()
            val dest = File(dir, f.name)
            f.copyTo(dest, true)
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), arrayOf(mime), null)
            return Triple(f.name, null, mime)   // eski Android: Aç/Paylaş gösterilmez
        }
    }
}
