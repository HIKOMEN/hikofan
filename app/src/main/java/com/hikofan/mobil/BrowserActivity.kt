package com.hikofan.mobil

import android.app.Dialog
import android.app.DownloadManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * HIKOFAN Tarayici: siteyi gezersin, sayfa video yuklerken/oynatirken
 * bulunan video adresleri toplanir ve altta "Indir" butonu cikar.
 *  - Duz dosyalar (mp4, webm...) -> Android indirme yoneticisi (arka planda + bildirim)
 *  - Akislar (m3u8, mpd)         -> ana ekrandaki yt-dlp motoruna devredilir
 */
class BrowserActivity : AppCompatActivity() {

    private val cBg = Color.parseColor("#05070d")
    private val cCard = Color.parseColor("#0e1424")
    private val cCard2 = Color.parseColor("#090d18")
    private val cBorder = Color.parseColor("#1c2a4a")
    private val cAccent = Color.parseColor("#3b82f6")
    private val cAccentD = Color.parseColor("#2563eb")
    private val cAccentL = Color.parseColor("#93c5fd")
    private val cText = Color.parseColor("#eef3ff")
    private val cMuted = Color.parseColor("#8f9bb8")

    // size: -2 henuz bakilmadi, -3 bakiliyor, -1 bilinmiyor
    private class Found(val url: String, val kind: String, val referer: String, val page: String) {
        @Volatile var size: Long = -2L
    }

    private val found = LinkedHashMap<String, Found>()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var currentPage = ""
    @Volatile private var currentTitle = ""

    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var bar: ProgressBar
    private lateinit var foundBtn: TextView
    private lateinit var fullscreenHolder: FrameLayout
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private val scanRunnable = object : Runnable {
        override fun run() {
            scanDom()
            handler.postDelayed(this, 2500)
        }
    }

    // ------------------------------------------------------------ yardimcilar
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, left: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); leftMargin = dp(left) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun iconBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        setTextColor(cAccentL)
        gravity = Gravity.CENTER
        background = rounded(cCard, 20, cBorder)
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------ yasam dongusu
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustom()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
        val start = intent.getStringExtra("url")
        if (!start.isNullOrBlank()) go(start) else showStartPage()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        handler.postDelayed(scanRunnable, 2500)
    }

    override fun onPause() {
        handler.removeCallbacks(scanRunnable)
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------ arayuz
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(cBg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- ust cubuk
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        top.addView(iconBtn("✕") { finish() }, lp(dp(40), dp(40)))
        urlBox = EditText(this).apply {
            hint = "Site adresi yaz ya da ara"
            setHintTextColor(Color.parseColor("#566383"))
            setTextColor(cText)
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(cCard, 20, cBorder)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setSelectAllOnFocus(true)
        }
        urlBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                go(urlBox.text.toString())
                hideKeyboard()
                true
            } else false
        }
        top.addView(urlBox, lp(0, WRAP_CONTENT, 1f, left = 8))
        top.addView(iconBtn("⟳") { web.reload() }, lp(dp(40), dp(40), left = 8))
        col.addView(top, lp(MATCH_PARENT, WRAP_CONTENT))

        // ---- yukleme cubugu
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(cAccent)
            progressBackgroundTintList = ColorStateList.valueOf(cBorder)
            visibility = View.INVISIBLE
        }
        col.addView(bar, lp(MATCH_PARENT, dp(3)))

        // ---- web gorunumu
        web = WebView(this)
        web.setBackgroundColor(cBg)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString.replace("; wv", "")   // normal Chrome gibi gorunsun
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                currentTitle = title ?: ""
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                showCustom(view, callback)
            }

            override fun onHideCustomView() {
                hideCustom()
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                // intent://, market:// gibi garip adresleri engelle, sadece web adreslerini ac
                return !(u.startsWith("http://") || u.startsWith("https://") || u == "about:blank")
            }

            // Sadece GOZLEMLER: sayfanin hicbir istegine mudahale etmez (null doner)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                val kind = classify(u)
                if (kind != null) {
                    val ref = request.requestHeaders?.get("Referer") ?: currentPage
                    handler.post { addFound(u, kind, ref) }
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                currentPage = url
                currentTitle = ""
                if (url.startsWith("http")) urlBox.setText(url) else urlBox.setText("")
                found.clear()
                updateFound(false)
            }

            override fun onPageFinished(view: WebView, url: String) {
                scanDom()
            }
        }

        // Sayfa dogrudan bir dosya sunarsa (indirme baglantisi)
        web.setDownloadListener { url, _, _, mime, _ ->
            val k = classify(url)
            if (k != null || mime.startsWith("video/") || mime.startsWith("audio/")) {
                addFound(url, k ?: "VİDEO", currentPage)
                toast("Video bulundu – alttaki butona dokun")
            } else {
                toast("Bu dosya türü desteklenmiyor")
            }
        }

        col.addView(web, lp(MATCH_PARENT, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---- yuzen "Indir" butonu
        foundBtn = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cAccent, cAccentD)).apply {
                cornerRadius = dp(26).toFloat()
            }
            setPadding(dp(22), dp(14), dp(22), dp(14))
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        foundBtn.setOnClickListener { showSheet() }
        root.addView(foundBtn, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(28)
        })

        // ---- tam ekran video katmani
        fullscreenHolder = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(fullscreenHolder, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        setContentView(root)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBox.windowToken, 0)
        urlBox.clearFocus()
    }

    private fun showStartPage() {
        val html = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<style>body{background:#05070d;color:#eef3ff;font-family:sans-serif;text-align:center;padding:60px 28px}" +
            "h1{color:#3b82f6;font-size:26px}p{color:#8f9bb8;line-height:1.5}" +
            ".box{background:#0e1424;border:1px solid #1c2a4a;border-radius:16px;padding:6px 18px;margin-top:26px;text-align:left}" +
            "</style></head><body><h1>HIKOFAN Tarayıcı</h1>" +
            "<p>Yukarıya bir site adresi yaz ya da ara.<br>Sayfada video yüklenirken veya oynarken altta <b>İndir</b> butonu çıkar.</p>" +
            "<div class='box'><p>İpucu: videoyu bir kez oynat, buton o zaman çıkar.<br><br>" +
            "Sadece indirme hakkın olan içerikleri indir.</p></div></body></html>"
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun go(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(" ") || !t.contains(".") -> "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
            else -> "https://$t"
        }
        web.loadUrl(url)
    }

    // ------------------------------------------------------------ tam ekran video
    @Suppress("DEPRECATION")
    private fun showCustom(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        customView = v
        customCb = cb
        fullscreenHolder.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        fullscreenHolder.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    @Suppress("DEPRECATION")
    private fun hideCustom() {
        val v = customView ?: return
        fullscreenHolder.removeView(v)
        fullscreenHolder.visibility = View.GONE
        customCb?.onCustomViewHidden()
        customView = null
        customCb = null
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    // ------------------------------------------------------------ video tespiti
    private fun classify(url: String): String? {
        val path = url.lowercase(Locale.US).substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".m3u8") -> "HLS"
            path.endsWith(".mpd") -> "DASH"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "MP4"
            path.endsWith(".webm") -> "WEBM"
            path.endsWith(".mkv") -> "MKV"
            path.endsWith(".mov") -> "MOV"
            path.endsWith(".mp3") -> "MP3"
            path.endsWith(".m4a") -> "M4A"
            else -> null
        }
    }

    private fun addFound(url: String, kind: String, referer: String) {
        if (!url.startsWith("http")) return
        if (found.containsKey(url) || found.size >= 40) return
        found[url] = Found(url, kind, referer, currentTitle)
        updateFound(true)
    }

    private fun updateFound(pulse: Boolean) {
        if (found.isEmpty()) {
            foundBtn.visibility = View.GONE
            return
        }
        foundBtn.text = if (found.size == 1) "↓  Video bulundu – İndir" else "↓  " + found.size + " video bulundu – İndir"
        foundBtn.visibility = View.VISIBLE
        if (pulse) {
            foundBtn.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).withEndAction {
                foundBtn.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
        }
    }

    // Sayfadaki <video>/<audio>/<source> etiketlerine bakar
    private fun scanDom() {
        val js = "(function(){var r=[];document.querySelectorAll('video,audio,source').forEach(function(e){" +
            "var s=e.currentSrc||e.src;if(s&&s.indexOf('http')===0)r.push(s);});return JSON.stringify(r);})()"
        web.evaluateJavascript(js) { v ->
            try {
                val t = JSONTokener(v).nextValue()
                if (t is String) {
                    val arr = JSONArray(t)
                    for (i in 0 until arr.length()) {
                        val u = arr.getString(i)
                        addFound(u, classify(u) ?: "VİDEO", currentPage)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------ ad / boyut
    private fun fileSeg(f: Found): String = try {
        URLDecoder.decode(Uri.parse(f.url).lastPathSegment ?: "", "UTF-8")
    } catch (_: Exception) {
        ""
    }

    private fun displayName(f: Found): String {
        val seg = fileSeg(f)
        val stem = seg.substringBeforeLast('.')
        val generic = seg.isBlank() || stem.length < 5 ||
            stem.lowercase(Locale.US) in setOf("video", "index", "master", "playlist", "stream", "manifest", "chunklist")
        val stream = f.kind == "HLS" || f.kind == "DASH"
        return if ((generic || stream) && f.page.isNotBlank()) f.page else if (seg.isBlank()) "video" else seg
    }

    private fun fmtSize(b: Long): String = when {
        b >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", b / 1e9)
        b >= 1_000_000L -> String.format(Locale.US, "%.1f MB", b / 1e6)
        else -> String.format(Locale.US, "%d KB", b / 1000)
    }

    // Dosya boyutunu ogrenir (Range: bytes=0-0 ile, dosyayi indirmeden). -1 = bilinmiyor
    private fun probeSize(f: Found, ua: String, cookie: String): Long {
        return try {
            val c = URL(f.url).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.setRequestProperty("Range", "bytes=0-0")
            c.setRequestProperty("User-Agent", ua)
            if (f.referer.isNotBlank()) c.setRequestProperty("Referer", f.referer)
            if (cookie.isNotBlank()) c.setRequestProperty("Cookie", cookie)
            c.connect()
            val cr = c.getHeaderField("Content-Range")
            val total = cr?.substringAfter('/')?.toLongOrNull()
            val len = c.contentLengthLong
            c.disconnect()
            total ?: (if (len > 1) len else -1L)
        } catch (_: Exception) {
            -1L
        }
    }

    // ------------------------------------------------------------ bulunanlar listesi
    private fun showSheet() {
        if (found.isEmpty()) return
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cCard)
                val r = dp(24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(18), dp(20), dp(18), dp(20))
        }
        box.addView(TextView(this).apply {
            text = "Bulunan videolar"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            text = "İndirmek istediğine dokun. Reklam ve küçük dosyalar da çıkabilir, boyuta bak."
            textSize = 12f; setTextColor(cMuted)
        }, lp(WRAP_CONTENT, WRAP_CONTENT, top = 2))

        val items = found.values.toList().asReversed()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ua = web.settings.userAgentString
        items.forEach { f -> list.addView(row(f, d, ua), lp(MATCH_PARENT, WRAP_CONTENT, top = 8)) }
        val sv = ScrollView(this)
        sv.addView(list)
        box.addView(sv, lp(MATCH_PARENT, if (items.size > 4) dp(4 * 72) else WRAP_CONTENT, top = 6))

        val close = TextView(this).apply {
            text = "Kapat"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cMuted)
            gravity = Gravity.CENTER
            background = rounded(cCard2, 14, cBorder)
        }
        close.setOnClickListener { d.dismiss() }
        box.addView(close, lp(MATCH_PARENT, dp(46), top = 14))

        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        d.show()
    }

    private fun row(f: Found, d: Dialog, ua: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cCard2, 14, cBorder)
            setPadding(dp(12), dp(10), dp(10), dp(10))
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = displayName(f); textSize = 14f; setTextColor(cText); typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        val meta = TextView(this).apply {
            textSize = 12f; setTextColor(cMuted); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(meta, lp(MATCH_PARENT, WRAP_CONTENT, top = 2))
        row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val btn = TextView(this).apply {
            text = "İndir"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(cAccent, 18)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        btn.setOnClickListener {
            d.dismiss()
            download(f)
        }
        row.addView(btn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })

        val host = try { Uri.parse(f.url).host ?: "" } catch (_: Exception) { "" }
        val isStream = f.kind == "HLS" || f.kind == "DASH"
        fun setMeta() {
            val sz = when {
                isStream -> "akış"
                f.size < -1L -> "boyut…"
                f.size > 0L -> fmtSize(f.size)
                else -> "boyut bilinmiyor"
            }
            meta.text = f.kind + "  •  " + sz + "  •  " + host
        }
        setMeta()
        if (!isStream && f.size == -2L) {
            f.size = -3L
            val cookie = CookieManager.getInstance().getCookie(f.url) ?: ""
            Thread {
                val s = probeSize(f, ua, cookie)
                f.size = s
                runOnUiThread { setMeta() }
            }.start()
        }
        return row
    }

    // ------------------------------------------------------------ indirme
    private fun extFor(kind: String): String = when (kind) {
        "WEBM" -> "webm"
        "MKV" -> "mkv"
        "MOV" -> "mov"
        "MP3" -> "mp3"
        "M4A" -> "m4a"
        else -> "mp4"
    }

    private fun download(f: Found) {
        val ua = web.settings.userAgentString
        val cookie = CookieManager.getInstance().getCookie(f.url) ?: ""
        val ref = if (f.referer.isNotBlank()) f.referer else currentPage

        // Akislar (m3u8/mpd): yt-dlp motoru birlestirir -> ana ekrana devret
        if (f.kind == "HLS" || f.kind == "DASH") {
            val i = Intent(this, MainActivity::class.java).apply {
                action = "com.hikofan.mobil.DOWNLOAD"
                putExtra("url", f.url)
                putExtra("referer", ref)
                putExtra("ua", ua)
                putExtra("cookie", cookie)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(i)
            finish()
            return
        }

        // Duz dosyalar: Android indirme yoneticisi (arka planda iner, bildirim cikar)
        try {
            val ext = extFor(f.kind)
            var name = displayName(f).replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(90)
            if (name.isBlank()) name = "video"
            if (!name.lowercase(Locale.US).endsWith(".$ext")) name = "$name.$ext"

            val req = DownloadManager.Request(Uri.parse(f.url))
            if (ref.isNotBlank()) req.addRequestHeader("Referer", ref)
            req.addRequestHeader("User-Agent", ua)
            if (cookie.isNotBlank()) req.addRequestHeader("Cookie", cookie)
            req.setTitle(name)
            req.setDescription("HIKOFAN")
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "HIKOFAN/" + name)
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            toast("İndirme başladı – bildirimden takip edebilirsin")
        } catch (e: Exception) {
            toast("İndirme başlatılamadı")
        }
    }
}
