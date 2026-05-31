package com.seeingmeter

import kotlin.math.*

/**
 * =============================================================================
 *  SeeingEngine.kt  —  all the pure, device-independent maths in one place.
 * =============================================================================
 *
 *  Nothing in this file touches Android, the camera, or the UI, which means
 *  every piece can be reasoned about (and unit-tested) on its own. The chain is:
 *
 *      rows of pixel intensity  ──▶ RingBuffer
 *      RingBuffer (one window)  ──▶ normalise (gain-independent) ──▶ Welch PSD
 *      PSD  ──▶ subtract noise floor ──▶ integrate band ──▶ σ²_I
 *      σ²_I + sec(z)  ──▶ Physics.r0FromVariance ──▶ r0 ──▶ seeing (arcsec)
 *
 *  THE KEY IDEA (your requested aspect ii — "continuous seeing over time"):
 *  Within one window the exposure is LOCKED, so we normalise the whole window by
 *  its OWN mean: x = I/mean − 1. That is dimensionless and gain-independent, so
 *  when the exposure re-locks between windows the absolute level changes but the
 *  observable does NOT jump. Combine that with a FIXED integration band and
 *  noise-floor subtraction and every 0.5 s window yields a directly comparable
 *  seeing value → a smooth time series.
 *
 *  (Contrast with v14, which divided each *frame* by its own mean. That is a
 *  high-pass at the frame rate ~50 Hz and silently discarded the low-frequency
 *  scintillation, biasing σ²_I in a wind-dependent way.)
 */

// =============================================================================
//  RingBuffer — fixed-size circular store of the row-intensity time series.
// =============================================================================
class RingBuffer(private val capacity: Int) {
    private val buf = DoubleArray(capacity)
    private var head = 0          // index of next write
    private var count = 0         // number of valid samples (<= capacity)

    val size: Int get() = count

    @Synchronized fun add(v: Double) {
        buf[head] = v
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized fun addAll(values: DoubleArray) { for (v in values) add(v) }

    /** Most-recent [n] samples in chronological order (oldest → newest). */
    @Synchronized fun lastN(n: Int): DoubleArray {
        val k = min(n, count)
        val out = DoubleArray(k)
        var idx = (head - k + capacity) % capacity
        for (i in 0 until k) { out[i] = buf[idx]; idx = (idx + 1) % capacity }
        return out
    }

    @Synchronized fun clear() { head = 0; count = 0 }
}

// =============================================================================
//  Fft — iterative radix-2 Cooley–Tukey + Welch one-sided PSD.
// =============================================================================
object Fft {

    /** In-place complex FFT. re/im length MUST be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n <= 1) return
        // bit-reversal permutation
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

    /** Welch one-sided PSD. Returns (freqs[Hz], psd) with psd in units of (signal²/Hz). */
    fun welchPsd(x: DoubleArray, fs: Double, segLenReq: Int, overlap: Double):
            Pair<DoubleArray, DoubleArray> {
        if (x.size < 8 || fs <= 0) return Pair(DoubleArray(0), DoubleArray(0))
        val segLen = floorPow2(min(segLenReq, x.size))
        val step = max(1, (segLen * (1.0 - overlap)).toInt())
        val half = segLen / 2

        // Hann window + its power (for correct PSD scaling).
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
                if (k != 0 && k != half) p *= 2.0   // one-sided: fold negative freqs
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

// =============================================================================
//  SolarGeometry — solar zenith angle from lat/lon/UTC (so sec(z) is real).
//  Compact NOAA algorithm, accurate to ~0.01°, good enough for airmass.
// =============================================================================
object SolarGeometry {

    /** Solar zenith angle in degrees. */
    fun zenithDeg(latDeg: Double, lonDeg: Double, epochMs: Long): Double {
        val jd = epochMs / 86_400_000.0 + 2440587.5          // Julian Date
        val n = jd - 2451545.0                                // days since J2000
        val L = (280.460 + 0.9856474 * n).mod(360.0)         // mean longitude
        val g = Math.toRadians((357.528 + 0.9856003 * n).mod(360.0)) // mean anomaly
        val lambda = Math.toRadians(L + 1.915 * sin(g) + 0.020 * sin(2 * g)) // ecliptic long.
        val eps = Math.toRadians(23.439 - 0.0000004 * n)     // obliquity
        val decl = asin(sin(eps) * sin(lambda))              // declination
        // Greenwich mean sidereal time → local hour angle
        val gmst = (18.697374558 + 24.06570982441908 * n).mod(24.0)
        val lst = (gmst * 15.0 + lonDeg).mod(360.0)
        val ra = Math.toDegrees(atan2(cos(eps) * sin(lambda), cos(lambda))).mod(360.0)
        val ha = Math.toRadians((lst - ra).mod(360.0))
        val lat = Math.toRadians(latDeg)
        val cosZ = sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(ha)
        return Math.toDegrees(acos(cosZ.coerceIn(-1.0, 1.0)))
    }

    /** sec(z), clamped so a low/below-horizon sun can't blow up the inversion. */
    fun secZ(zenithDeg: Double): Double {
        val z = zenithDeg.coerceIn(0.0, 80.0)   // airmass model breaks down near horizon
        return 1.0 / cos(Math.toRadians(z))
    }
}

// =============================================================================
//  Physics — the Seykora inversion and seeing conversions.
// =============================================================================
object Physics {

    /**
     * Invert  σ²_I = COEFF · k^(-5/6) · sec(z)^(5/6) · H^(-1/6) · λ / α_s² · r0^(-5/3)
     * for r0 (metres).
     *
     *   Let A = COEFF · k^(-5/6) · sec(z)^(5/6) · H^(-1/6) · λ / α_s²
     *   then σ²_I = A · r0^(-5/3)  ⇒  r0 = (A / σ²_I)^(3/5)
     */
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

    /** Atmospheric seeing FWHM in arcseconds: ε = 0.98 · λ / r0. */
    fun seeingArcsec(r0M: Double, lambdaM: Double = Config.lambdaMeasM): Double {
        if (r0M.isNaN() || r0M <= 0) return Double.NaN
        val rad = 0.98 * lambdaM / r0M
        return Math.toDegrees(rad) * 3600.0
    }

    /** Fresnel frequency f_F = v⊥ / √(λH) — the scintillation PSD knee. */
    fun fresnelFreqHz(windMs: Double, heightM: Double, lambdaM: Double): Double =
        windMs / sqrt(lambdaM * heightM)

    /** Back out layer height from an observed Fresnel knee (degenerate with wind). */
    fun heightFromFresnel(fFresnelHz: Double, windMs: Double, lambdaM: Double): Double {
        if (fFresnelHz <= 0) return Double.NaN
        val s = windMs / fFresnelHz          // = √(λH)
        return s * s / lambdaM
    }
}

// =============================================================================
//  ExposureLogic — runs before EVERY frame (your requested aspect ii, part 1).
//  Pure decision function: given the last frame's level, say whether the
//  exposure is still good, and if not, what to change it to.
// =============================================================================
object ExposureLogic {

    enum class Action { OK, ADJUST, SATURATED }

    data class Decision(
        val action: Action,
        val newExposureNs: Long,
        val newIso: Int,
        val reason: String
    )

    /**
     * @param meanFrac  last frame mean as a fraction of full scale (0..1)
     * @param maxFrac   last frame peak as a fraction of full scale (0..1)
     */
    /**
     * Auto-exposure decision, evaluated once per frame.
     *
     * STRATEGY (why it looks the way it does):
     *  - We regulate the PEAK signal (brightest row) into a comfortable band,
     *    high enough for SNR but clear of clipping. Controlling the peak — not the
     *    mean — is what guarantees we never saturate.
     *  - ISO is the primary knob. We estimate the ISO that *would* hit the target
     *    (signal is ~linear in ISO: neededIso = iso · target/peak) and then move
     *    only [STEP] = 30 % of the way there. This under-relaxation is the cure for
     *    the old 24⇄3184 oscillation: each step approaches the target and never
     *    overshoots, converging in a handful of frames.
     *  - Exposure is the LAST resort. It only changes when ISO is already pinned at
     *    a rail (max and still too dark, or min and still clipping) — i.e. only
     *    when ISO alone cannot bring the peak into band.
     *
     * @param meanFrac  frame mean / full-scale (unused by the controller; kept for
     *                  logging and possible future use)
     * @param maxFrac   frame PEAK / full-scale — the quantity we regulate (0..1)
     */
    fun evaluate(
        meanFrac: Double,
        maxFrac: Double,
        curExposureNs: Long,
        curIso: Int,
        expRangeNs: LongRange,
        isoRange: IntRange
    ): Decision {
        val target = Config.exposureTargetFrac   // desired PEAK fraction (~0.55)
        val tol = Config.exposureTolFrac          // half-band; 0.55 ± 0.12 → ~0.43–0.67
        val isoMin = isoRange.first
        val isoMax = isoRange.last
        val STEP = 0.30                           // fraction of the needed change to apply

        val peak = maxFrac.coerceIn(1.0 / 255, 1.0)

        // Peak already in band → change nothing (deadband; no jitter).
        if (abs(peak - target) <= tol)
            return Decision(Action.OK, curExposureNs, curIso, "lock")

        // 1) ISO: 30 % step toward the ISO that would reach the target.
        val neededIso = curIso * (target / peak)
        val newIso = (curIso + STEP * (neededIso - curIso)).roundToInt()
            .coerceIn(isoMin, isoMax)

        // 2) Exposure: only if ISO is railed and still can't reach the band.
        var newExp = curExposureNs
        val railedDark   = peak < target - tol && newIso >= isoMax   // maxed ISO, still dim
        val railedBright = peak > target + tol && newIso <= isoMin   // min ISO, still clipping
        if (railedDark || railedBright) {
            val neededExp = curExposureNs * (target / peak)
            newExp = (curExposureNs + STEP * (neededExp - curExposureNs)).toLong()
                .coerceIn(expRangeNs.first, expRangeNs.last)
        }

        val action = if (peak >= Config.saturationFrac) Action.SATURATED else Action.ADJUST
        return Decision(action, newExp, newIso, "iso=$newIso exp=${newExp / 1000}us")
    }
}

// =============================================================================
//  SeeingAnalyzer — turns buffered rows into one SeeingResult per window.
// =============================================================================

/** One computed seeing point (also the row written to the JSON sample list). */
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

    /** Effective row-readout sample rate (Hz), updated from the camera each frame. */
    @Volatile var sampleRateHz: Double = 108_000.0

    /** Last PSD for the spectrum view (freqs, psd). */
    @Volatile var lastPsd: Pair<DoubleArray, DoubleArray>? = null

    // -------- Flat-field (your requested aspect #2) --------------------------
    // A per-row sensitivity profile (paper texture + vignetting), normalised to
    // mean 1. When set, each incoming row is divided by it before buffering, so
    // the fixed row-to-row pattern (which otherwise repeats every frame and adds
    // a comb to the PSD) is removed. Capture it once, pointed at the uniform
    // diffuser, with the same exposure you'll measure at.
    @Volatile var flatProfile: DoubleArray? = null    // normalised, mean = 1
    @Volatile var flatJustFinished = false            // UI polls this to persist
    private var flatCapturing = false
    private var flatAccum: DoubleArray? = null
    private var flatCount = 0
    private var flatTarget = 0

    /** Begin averaging [frames] frames into a new flat profile. */
    fun startFlatCapture(frames: Int) {
        flatTarget = frames; flatCount = 0; flatAccum = null
        flatJustFinished = false; flatCapturing = true
    }
    val isCapturingFlat: Boolean get() = flatCapturing

    /** Feed one frame's per-row mean intensities. */
    fun ingestRows(rowMeans: DoubleArray) {
        // (a) flat-capture mode: accumulate, don't analyse
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
        // (b) normal mode: apply flat if we have one, then buffer
        val fp = flatProfile
        if (fp != null && fp.size == rowMeans.size)
            ring.addAll(DoubleArray(rowMeans.size) { rowMeans[it] / fp[it] })
        else
            ring.addAll(rowMeans)
    }

    fun reset() { ring.clear(); lastPsd = null }

    /** Drop the buffered samples (keeps PSD + flat). Called on every ISO/exposure
     *  change so a variance window never straddles a gain change → no systematic
     *  error from mixed-gain data. compute() then returns null until a fresh full
     *  window of constant-gain samples has accumulated. */
    fun discardWindow() { ring.clear() }

    /**
     * Compute one seeing value from the most recent window.
     * Returns null if there is not yet enough data.
     */
    fun compute(secZ: Double, nowMs: Long): SeeingResult? {
        val need = (Config.windowSec * sampleRateHz).toInt()
        if (ring.size < min(need, Config.welchSegLen * 2)) return null

        val raw = ring.lastN(need)

        // --- gain-independent normalisation: x = I/mean − 1 (window mean, NOT frame) ---
        var mean = 0.0; for (v in raw) mean += v; mean /= raw.size
        if (mean <= 0) return null
        val x = DoubleArray(raw.size) { raw[it] / mean - 1.0 }

        // --- Welch PSD ---
        val (freqs, psd) = Fft.welchPsd(x, sampleRateHz, Config.welchSegLen, Config.welchOverlap)
        if (freqs.isEmpty()) return null
        lastPsd = Pair(freqs, psd)
        val df = if (freqs.size > 1) freqs[1] - freqs[0] else 0.0

        // --- noise floor = median PSD in the top 20% of the band (assumed white) ---
        val floorStart = (freqs.size * 0.8).toInt().coerceIn(1, freqs.size - 1)
        val tail = psd.copyOfRange(floorStart, psd.size).sortedArray()
        val noiseFloor = if (tail.isNotEmpty()) tail[tail.size / 2] else 0.0

        // --- band-limited, noise-subtracted variance σ²_I ---
        var sigma2I = 0.0
        var peakP = -1.0; var fChar = 0.0
        for (i in freqs.indices) {
            val f = freqs[i]
            if (f < Config.bandLowHz || f > Config.bandHighHz) continue
            val p = (psd[i] - noiseFloor).coerceAtLeast(0.0)
            sigma2I += p * df
            if (p > peakP) { peakP = p; fChar = f }   // characteristic (peak) frequency
        }
        if (sigma2I <= 0) return null

        // --- invert to r0 / seeing ---
        val r0 = Physics.r0FromVariance(sigma2I, secZ)
        val seeing = Physics.seeingArcsec(r0)

        // --- spectrum-derived height (using assumed wind); f_Fresnel ≈ f_char here ---
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
