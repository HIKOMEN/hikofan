package com.hikofan.mobil

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Uygulama ici guncelleme:
 *  1) GitHub'daki surumlere bakar
 *  2) Mumkunse KUCUK YAMA(lar)i indirip kurulu APK'nin ustune uygular (bsdiff)
 *  3) Olmazsa / hash tutmazsa tam APK'yi indirir
 * Sonuc: kurulmaya hazir, dogrulanmis yeni APK dosyasi.
 */
object Updater {

    private const val API = "https://api.github.com/repos/HIKOMEN/hikofan/releases?per_page=30"

    private class Rel(val num: Int) {
        var patchFrom = -1
        var patchUrl = ""
        var patchSize = 0L
        var shaUrl = ""
        var apkUrl = ""
        var apkSize = 0L
    }

    // ---------------------------------------------------------------- ana giris
    fun prepare(ctx: Context, current: Int, progress: (String, Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates")
        dir.deleteRecursively()
        dir.mkdirs()

        progress("Güncelleme aranıyor…", -1)
        val rels = fetchReleases()
        val latest = rels.maxByOrNull { it.num } ?: throw IllegalStateException("surum bulunamadi")
        if (latest.num <= current) throw IllegalStateException("zaten guncel")

        // Elimizdeki surumden en yeniye giden yama zinciri
        val steps = ArrayList<Rel>()
        var cur = current
        while (cur < latest.num && steps.size < 5) {
            val from = cur
            val r = rels.firstOrNull { it.patchFrom == from && it.num > from } ?: break
            steps.add(r)
            cur = r.num
        }
        val total = steps.sumOf { it.patchSize }
        if (cur == latest.num && steps.isNotEmpty() && total < latest.apkSize * 6 / 10) {
            try {
                return applyPatches(ctx, dir, steps, progress)
            } catch (e: Throwable) {
                // yama olmadi -> tam indirmeye don
                dir.deleteRecursively()
                dir.mkdirs()
            }
        }
        return downloadFull(dir, latest, progress)
    }

    // ---------------------------------------------------------------- yamalar
    private fun applyPatches(ctx: Context, dir: File, steps: List<Rel>, progress: (String, Int) -> Unit): File {
        var base = File(ctx.applicationInfo.sourceDir)
        var baseIsTemp = false
        for ((idx, r) in steps.withIndex()) {
            val label = "Yama " + (idx + 1) + "/" + steps.size
            val patch = File(dir, "patch-" + r.num + ".bin")
            download(r.patchUrl, patch) { done, len -> progress("$label indiriliyor…", pct(done, len)) }

            progress("$label uygulanıyor…", -1)
            val out = File(dir, "app-" + r.num + ".apk")
            BsPatch.patch(base, patch, out)
            patch.delete()

            progress("$label doğrulanıyor…", -1)
            val want = firstToken(httpText(r.shaUrl))
            if (want.isEmpty() || want != sha256(out)) {
                out.delete()
                throw IllegalStateException("hash tutmadi")
            }
            if (baseIsTemp) base.delete()
            base = out
            baseIsTemp = true
        }
        progress("Hazır", 100)
        return base
    }

    // ---------------------------------------------------------------- tam indirme
    private fun downloadFull(dir: File, r: Rel, progress: (String, Int) -> Unit): File {
        val out = File(dir, "app-full.apk")
        download(r.apkUrl, out) { done, len -> progress("Güncelleme indiriliyor…", pct(done, len)) }
        if (r.shaUrl.isNotEmpty()) {
            progress("Doğrulanıyor…", -1)
            val want = try { firstToken(httpText(r.shaUrl)) } catch (_: Exception) { "" }
            if (want.isNotEmpty() && want != sha256(out)) {
                out.delete()
                throw IllegalStateException("indirilen dosya bozuk")
            }
        }
        progress("Hazır", 100)
        return out
    }

    // ---------------------------------------------------------------- GitHub
    private fun fetchReleases(): List<Rel> {
        val arr = JSONArray(httpText(API))
        val out = ArrayList<Rel>()
        val patchRe = Regex("patch-(\\d+)-(\\d+)\\.bin")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue
            val num = o.optString("tag_name", "").filter { it.isDigit() }.toIntOrNull() ?: continue
            val assets = o.optJSONArray("assets") ?: continue
            val r = Rel(num)
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                val size = a.optLong("size", 0L)
                if (name == "HikofanMobil.apk") {
                    r.apkUrl = url
                    r.apkSize = size
                } else if (name == "HikofanMobil.apk.sha256") {
                    r.shaUrl = url
                } else {
                    val m = patchRe.matchEntire(name)
                    if (m != null) {
                        r.patchFrom = m.groupValues[1].toInt()
                        r.patchUrl = url
                        r.patchSize = size
                    }
                }
            }
            if (r.apkUrl.isNotEmpty()) out.add(r)
        }
        return out
    }

    // ---------------------------------------------------------------- yardimcilar
    private fun pct(done: Long, len: Long): Int = if (len > 0) (done * 100 / len).toInt().coerceIn(0, 100) else -1

    private fun firstToken(s: String): String = s.trim().split(Regex("\\s+"))[0].lowercase()

    private fun httpText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "HIKOFAN")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "HIKOFAN")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP " + c.responseCode)
            val len = c.contentLengthLong
            c.inputStream.use { ins ->
                dest.outputStream().use { outs ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        done += n
                        if (done - last > 250_000L) {
                            last = done
                            onProgress(done, len)
                        }
                    }
                    onProgress(done, len)
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
