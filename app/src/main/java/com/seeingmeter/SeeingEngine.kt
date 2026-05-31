package com.seeingmeter

import kotlin.math.*

class RingBuffer(private val capacity: Int) {
    private val buf = DoubleArray(capacity)
    private var head = 0
    private var count = 0

    val size: Int get() = count

    @Synchronized fun add(v: Double) {
        buf[head] = v
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized fun addAll(values: DoubleArray) { for (v in values) add(v) }

    @Synchronized fun lastN(n: Int): DoubleArray {
        val k = min(n, count)
        val out = DoubleArray(k)
        var idx = (head - k + capacity) % capacity
        for (i in 0 until k) { out[i] = buf[idx]; idx = (idx + 1) % capacity }
        return out
    }

    @Synchronized fun clear() { head = 0; count = 0 }
}

object Fft {

    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n <= 1) return
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang); val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0; var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k];           val aIm = im[i + k]
                    val bRe = re[i + k + len / 2]; val bIm = im[i + k + len / 2]
                    val tRe = bRe * curRe - bIm * curIm
                    val tIm = bRe * curIm + bIm * curRe
                    re[i + k] = aRe + tRe;           im[i + k] = aIm + tIm
                    re[i + k + len / 2] = aRe - tRe;  im[i + k + len / 2] = aIm - tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe; curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun floorPow2(x: Int): Int { var p = 1; while (p * 2 <= x) p *= 2; return p }

    fun welchPsd(x: DoubleArray, fs: Double, segLenReq: Int, overlap: Double):
            Pair<DoubleArray, DoubleArray> {
        if (x.size < 8 || fs <= 0) return Pair(DoubleArray(0), DoubleArray(0))
        val segLen = floorPow2(min(segLenReq, x.size))
        val step = max(1, (segLen * (1.0 - overlap)).toInt())
        val half = segLen / 2

        val w = DoubleArray(segLen) { 0.5 - 0.5 * cos(2.0 * PI * it / (segLen - 1)) }
        val wPow = w.sumOf { it * it }
        val scale = 1.0 / (fs * wPow)

        val acc = DoubleArray(half + 1)
        var segs = 0
        var start = 0
        while (start + segLen <= x.size) {
            val re = DoubleArray(segLen); val im = DoubleArray(segLen)
            var mean = 0.0
            for (i in 0 until segLen) mean += x[start + i]
            mean /= segLen
            for (i in 0 until segLen) re[i] = (x[start + i] - mean) * w[i]
            fft(re, im)
            for (k in 0..half) {
                var p = (re[k] * re[k] + im[k] * im[k]) * scale
                if (k != 0 && k != half) p *= 2.0
                acc[k] += p
            }
            segs++; start += step
        }
        if (segs == 0) return Pair(DoubleArray(0), DoubleArray(0))
        val psd = DoubleArray(half + 1) { acc[it] / segs }
        val df = fs / segLen
        val freqs = DoubleArray(half + 1) { it * df }
        return Pair(freqs, psd)
    }
}

object SolarGeometry {

    fun zenithDeg(latDeg: Double, lonDeg: Double, epochMs: Long): Double {
        val jd = epochMs / 86_400_000.0 + 2440587.5
        val n = jd - 2451545.0
        val L = (280.460 + 0.9856474 * n).mod(360.0)
        val g = Math.toRadians((357.528 + 0.9856003 * n).mod(360.0))
        val lambda = Math.toRadians(L + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val decl = asin(sin(eps) * sin(lambda))
        val gmst = (18.697374558 + 24.06570982441908 * n).mod(24.0)
        val lst = (gmst * 15.0 + lonDeg).mod(360.0)
        val ra = Math.toDegrees(atan2(cos(eps) * sin(lambda), cos(lambda))).mod(360.0)
        val ha = Math.toRadians((lst - ra).mod(360.0))
        val lat = Math.toRadians(latDeg)
        val cosZ = sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(ha)
        return Math.toDegrees(acos(cosZ.coerceIn(-1.0, 1.0)))
    }

    fun secZ(zenithDeg: Double): Double {
        val z = zenithDeg.coerceIn(0.0, 80.0)
        return 1.0 / cos(Math.toRadians(z))
    }
}

object Physics {

    fun r0FromVariance(sigma2I: Double, secZ: Double): Double {
        if (sigma2I <= 0) return Double.NaN
        val lambda = Config.lambdaMeasM
        val k = 2.0 * PI / lambda
        val a = Config.coeffSolar
            .times(k.pow(-5.0 / 6.0))
            .times(secZ.pow(5.0 / 6.0))
            .times(Config.turbHeightM.pow(-1.0 / 6.0))
            .times(lambda)
            .div(Config.solarAngularDiamRad.pow(2.0))
        return (a / sigma2I).pow(3.0 / 5.0)
    }

    fun seeingArcsec(r0M: Double, lambdaM: Double = Config.lambdaMeasM): Double {
        if (r0M.isNaN() || r0M <= 0) return Double.NaN
        val rad = 0.98 * lambdaM / r0M
        return Math.toDegrees(rad) * 3600.0
    }

    fun fresnelFreqHz(windMs: Double, heightM: Double, lambdaM: Double): Double =
        windMs / sqrt(lambdaM * heightM)

    fun heightFromFresnel(fFresnelHz: Double, windMs: Double, lambdaM: Double): Double {
        if (fFresnelHz <= 0) return Double.NaN
        val s = windMs / fFresnelHz
        return s * s / lambdaM
    }
}

object ExposureLogic {

    enum class Action { OK, ADJUST, SATURATED }

    data class Decision(
        val action: Action,
        val newExposureNs: Long,
        val newIso: Int,
        val reason: String
    )

    fun evaluate(
        meanFrac: Double,
        maxFrac: Double,
        curExposureNs: Long,
        curIso: Int,
        expRangeNs: LongRange,
        isoRange: IntRange
    ): Decision {
        val target = Config.exposureTargetFrac
        val tol = Config.exposureTolFrac
        val saturated = maxFrac >= Config.saturationFrac
        val isoMin = isoRange.first
        val isoMax = isoRange.last

        // Exposure is FIXED (set once at camera start). Only ISO is adjusted, so
        // the temporal response never changes and exposure no longer jumps around.
        if (!saturated && abs(meanFrac - target) <= tol)
            return Decision(Action.OK, curExposureNs, curIso, "lock")

        val ratio = if (saturated) 0.5
                    else (target / meanFrac.coerceAtLeast(1.0 / 255)).coerceIn(0.5, 2.0)
        val newIso = (curIso * ratio).roundToInt().coerceIn(isoMin, isoMax)

        val action = if (saturated) Action.SATURATED else Action.ADJUST
        return Decision(action, curExposureNs, newIso, "iso=$newIso (exp fixed)")
    }
}

data class SeeingResult(
    val timeMs: Long,
    val r0M: Double,
    val seeingArcsec: Double,
    val sigma2I: Double,
    val fCharHz: Double,
    val fFresnelHz: Double,
    val hFitM: Double,
    val noiseFloor: Double,
    val quality: String
)

class SeeingAnalyzer {

    private val ring = RingBuffer(Config.ringSize)

    @Volatile var sampleRateHz: Double = 108_000.0
    @Volatile var lastPsd: Pair<DoubleArray, DoubleArray>? = null

    @Volatile var flatProfile: DoubleArray? = null
    @Volatile var flatJustFinished = false
    private var flatCapturing = false
    private var flatAccum: DoubleArray? = null
    private var flatCount = 0
    private var flatTarget = 0

    fun startFlatCapture(frames: Int) {
        flatTarget = frames; flatCount = 0; flatAccum = null
        flatJustFinished = false; flatCapturing = true
    }
    val isCapturingFlat: Boolean get() = flatCapturing

    fun ingestRows(rowMeans: DoubleArray) {
        if (flatCapturing) {
            val acc = flatAccum ?: DoubleArray(rowMeans.size).also { flatAccum = it }
            if (acc.size == rowMeans.size) {
                for (i in rowMeans.indices) acc[i] += rowMeans[i]; flatCount++
            }
            if (flatCount >= flatTarget) {
                val n = flatCount.coerceAtLeast(1)
                val prof = DoubleArray(acc.size) { acc[it] / n }
                var m = 0.0; for (v in prof) m += v; m /= prof.size
                if (m > 0) for (i in prof.indices) prof[i] = (prof[i] / m).let { if (it <= 0) 1.0 else it }
                flatProfile = prof; flatCapturing = false; flatJustFinished = true
            }
            return
        }
        val fp = flatProfile
        if (fp != null && fp.size == rowMeans.size)
            ring.addAll(DoubleArray(rowMeans.size) { rowMeans[it] / fp[it] })
        else
            ring.addAll(rowMeans)
    }

    fun reset() { ring.clear(); lastPsd = null }

    fun compute(secZ: Double, nowMs: Long): SeeingResult? {
        val need = (Config.windowSec * sampleRateHz).toInt()
        if (ring.size < min(need, Config.welchSegLen * 2)) return null

        val raw = ring.lastN(need)

        var mean = 0.0; for (v in raw) mean += v; mean /= raw.size
        if (mean <= 0) return null
        val x = DoubleArray(raw.size) { raw[it] / mean - 1.0 }

        val (freqs, psd) = Fft.welchPsd(x, sampleRateHz, Config.welchSegLen, Config.welchOverlap)
        if (freqs.isEmpty()) return null
        lastPsd = Pair(freqs, psd)
        val df = if (freqs.size > 1) freqs[1] - freqs[0] else 0.0

        val floorStart = (freqs.size * 0.8).toInt().coerceIn(1, freqs.size - 1)
        val tail = psd.copyOfRange(floorStart, psd.size).sortedArray()
        val noiseFloor = if (tail.isNotEmpty()) tail[tail.size / 2] else 0.0

        var sigma2I = 0.0
        var peakP = -1.0; var fChar = 0.0
        for (i in freqs.indices) {
            val f = freqs[i]
            if (f < Config.bandLowHz || f > Config.bandHighHz) continue
            val p = (psd[i] - noiseFloor).coerceAtLeast(0.0)
            sigma2I += p * df
            if (p > peakP) { peakP = p; fChar = f }
        }
        if (sigma2I <= 0) return null

        val r0 = Physics.r0FromVariance(sigma2I, secZ)
        val seeing = Physics.seeingArcsec(r0)

        val fFresnel = fChar
        val hFit = Physics.heightFromFresnel(fFresnel, Config.assumedWindMs, Config.lambdaMeasM)

        val quality = when {
            r0.isNaN() || seeing.isNaN() -> "no fit"
            seeing < 1.0 -> "excellent"
            seeing < 2.0 -> "good"
            seeing < 4.0 -> "fair"
            else -> "poor"
        }
        return SeeingResult(nowMs, r0, seeing, sigma2I, fChar, fFresnel, hFit, noiseFloor, quality)
    }
}
