package com.mediatech.camera

import android.graphics.Bitmap
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** שיפור קלאסי (לא AI): איזון רמות, הבהרת תמונות כהות וחידוד עדין. עובד בלי מודל. */
object ClassicEnhancer {

    fun enhance(srcIn: Bitmap): Bitmap {
        var src = srcIn
        val cap = 3072
        val longSide = max(src.width, src.height)
        if (longSide > cap) {
            val f = cap.toFloat() / longSide
            src = Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
        }
        val w = src.width; val h = src.height; val n = w * h
        val px = IntArray(n); src.getPixels(px, 0, w, 0, 0, w, h)

        val hist = Array(3) { IntArray(256) }
        for (p in px) {
            hist[0][(p shr 16) and 255]++; hist[1][(p shr 8) and 255]++; hist[2][p and 255]++
        }
        fun pct(hh: IntArray, frac: Double): Int {
            var c = 0L; val t = (n * frac).toLong()
            for (i in 0..255) { c += hh[i]; if (c >= t) return i }
            return 255
        }
        val lo = min(pct(hist[0], 0.004), min(pct(hist[1], 0.004), pct(hist[2], 0.004)))
        val hi = max(pct(hist[0], 0.996), max(pct(hist[1], 0.996), pct(hist[2], 0.996)))
        var lum = 0.0
        for (i in 0..255) lum += i * (hist[0][i] * 0.299 + hist[1][i] * 0.587 + hist[2][i] * 0.114)
        val mean = (lum / n).coerceIn(1.0, 254.0)
        val gamma = if (mean >= 118.0) 1.0 else (ln(0.46) / ln(mean / 255.0)).coerceIn(0.55, 1.0)

        val range = max(1, hi - lo).toDouble()
        val lut = IntArray(256) { v ->
            val x = ((v - lo) / range).coerceIn(0.0, 1.0)
            (x.pow(gamma) * 255.0).roundToInt().coerceIn(0, 255)
        }
        for (i in 0 until n) {
            val p = px[i]
            px[i] = (0xFF shl 24) or (lut[(p shr 16) and 255] shl 16) or
                (lut[(p shr 8) and 255] shl 8) or lut[p and 255]
        }

        // חידוד עדין: unsharp mask עם טשטוש 3x3
        val out = px.copyOf()
        val amount = 0.6f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var sr = 0; var sg = 0; var sb = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val q = px[(y + dy) * w + x + dx]
                    sr += (q shr 16) and 255; sg += (q shr 8) and 255; sb += q and 255
                }
                val c = px[y * w + x]
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                val nr = (r + amount * (r - sr / 9f)).roundToInt().coerceIn(0, 255)
                val ng = (g + amount * (g - sg / 9f)).roundToInt().coerceIn(0, 255)
                val nb = (b + amount * (b - sb / 9f)).roundToInt().coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        res.setPixels(out, 0, w, 0, 0, w, h)
        return res
    }
}
