package com.seeingmeter

import android.content.Context
import org.json.JSONObject

/**
 * =============================================================================
 *  Config  —  the single source of truth for every tunable parameter.
 * =============================================================================
 *
 *  WHY THIS FILE EXISTS
 *  --------------------
 *  Everything you might want to change, calibrate, or add later lives here and
 *  ONLY here. It is deliberately one flat object so that:
 *
 *    1. Any other file can read a value with `Config.windowSec` etc.
 *    2. Every value is automatically written into the JSON session file
 *       (see [toJson]) so each recording is self-describing.
 *    3. The settings dialog (MainActivity) and the on-screen params bar read
 *       from the same place — no value is defined twice.
 *
 *  HOW TO ADD A NEW PARAMETER LATER  (the whole point of v15's structure)
 *  ---------------------------------------------------------------------
 *    a) add a `var myParam = <default>` below, with a short doc comment,
 *    b) add one line to [load] and one line to [save] (copy an existing one),
 *    c) add one line to [toJson] if you want it logged,
 *    d) (optional) add a control to MainActivity.showSettingsDialog().
 *  That is the entire workflow. Nothing else needs to know about it.
 *
 *  UNITS: SI throughout (metres, seconds, radians, Hz) unless the name says
 *  otherwise (e.g. *Deg, *Frac, *Hz, *Ns).
 */
object Config {

    /** App version. Injected at build time from BuildConfig (set by Gradle from
     *  the SEEINGMETER_VERSION env var → GitHub Actions run number). Starts at 1
     *  and auto-increments on every cloud build. Local builds default to "1". */
    val APP_VERSION: String = BuildConfig.APP_VERSION

    // ---- Physics constants (Seykora 1993 single-aperture solar scintillometer) ----

    /** σ²_I = COEFF · k^(-5/6) · sec(z)^(5/6) · H^(-1/6) · λ / α_s² · r0^(-5/3)
     *  COEFF = 8·Q·(0.563/0.423), Q≈1.27 from the Kolmogorov covariance integral.
     *  Treat this as a CALIBRATION knob: the camera+diffuser throughput and the
     *  finite measurement band mean the textbook value will not be exactly right
     *  for your hardware. Cross-calibrate against meteoblue or a reference monitor
     *  and adjust. */
    var coeffSolar = 13.47

    /** Effective wavelength of the camera's luminance response, metres.
     *  The sun is broadband; ~550 nm is a reasonable photopic centre. */
    var lambdaMeasM = 550e-9

    /** Reference wavelength for reporting r0 / seeing, metres (astronomy standard). */
    var lambdaRefM = 500e-9

    /** Turbulent layer height H, metres. The formula's dependence is H^(-1/6)
     *  (extremely weak), so this is a coarse assumption, NOT something measured
     *  from the variance. The spectrum (f_Fresnel) constrains it far better. */
    var turbHeightM = 5000.0

    /** Angular diameter of the sun α_s, radians (~0.53°). Sets the spatial
     *  filtering for solar scintillation (replaces the telescope aperture term). */
    var solarAngularDiamRad = 9.3e-3

    /** Assumed transverse wind speed at the layer, m/s. Used together with
     *  f_Fresnel to back out H. Wind and H are degenerate from a single station,
     *  so this is an assumption — expose it so it can be improved later. */
    var assumedWindMs = 10.0

    // ---- Signal analysis ----

    /** Length of one analysis window → one seeing value, seconds. */
    var windowSec = 0.5

    /** Ring-buffer capacity in samples (rows). 2^18 ≈ 2.4 s at ~108 kHz. */
    var ringSize = 262_144

    /** Welch segment length (samples). Power of two. Sets PSD frequency resolution. */
    var welchSegLen = 4096

    /** Welch segment overlap fraction (0..1). */
    var welchOverlap = 0.5

    /** Variance is integrated over [bandLowHz, bandHighHz].
     *  bandLow removes residual slow drift / DC; bandHigh and the noise-floor
     *  subtraction remove the white measurement-noise plateau. Keeping this band
     *  FIXED is what makes consecutive windows comparable → a stable seeing
     *  time series even when the exposure re-locks between windows. */
    var bandLowHz = 3.0
    var bandHighHz = 4000.0

    // ---- Exposure validation (checked before EVERY frame, see ExposureLogic) ----

    /** Target mean signal as a fraction of full scale (keep well inside linear
     *  range, away from saturation). */
    var exposureTargetFrac = 0.55

    /** Allowed deviation from target before a re-lock is triggered. */
    var exposureTolFrac = 0.12

    /** Any pixel/row above this fraction counts as saturated → force exposure down. */
    var saturationFrac = 0.95

    /** FIXED exposure time in MICROSECONDS. Exposure is held constant (never
     *  auto-varied) so the per-row integration window — and thus the temporal
     *  frequency response — stays identical across the whole session; brightness
     *  is handled by ISO instead. 0 = use the sensor's lowest exposure.
     *  Adjustable in Settings. */
    var exposureUs = 0

    // ---- Camera ----

    /** Physical camera id to use (Samsung: "0" = main wide). User-selectable. */
    var cameraId = "0"

    /** Cap on capture height (rows). More rows = higher row-readout sample rate. */
    var maxRows = 2160

    // ---- Meteoblue ----

    var meteoblueEnabled = true

    /** Meteoblue API key.
     *  ⚠ SECURITY: this ships inside the APK and is in the repo. Anyone with the
     *  APK can extract it, and if your GitHub repo is PUBLIC it is visible there.
     *  Keep the repo PRIVATE, and rotate the key if it leaks. It is deliberately
     *  NOT written into the JSON session files. */
    var meteoblueApiKey = "ksNoguct6UfHiYjy"

    /** When true, the transverse wind used for f_Fresnel / H is taken from the
     *  latest meteoblue fetch (falls back to [assumedWindMs] if unavailable). */
    var meteoblueWindAuto = true

    // -------------------------------------------------------------------------

    private const val PREFS = "seemeter_prefs"

    /** Load persisted values (called once at startup). */
    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        coeffSolar          = p.getFloat("coeffSolar", coeffSolar.toFloat()).toDouble()
        lambdaMeasM         = p.getFloat("lambdaMeasM", lambdaMeasM.toFloat()).toDouble()
        turbHeightM         = p.getFloat("turbHeightM", turbHeightM.toFloat()).toDouble()
        assumedWindMs       = p.getFloat("assumedWindMs", assumedWindMs.toFloat()).toDouble()
        windowSec           = p.getFloat("windowSec", windowSec.toFloat()).toDouble()
        bandLowHz           = p.getFloat("bandLowHz", bandLowHz.toFloat()).toDouble()
        bandHighHz          = p.getFloat("bandHighHz", bandHighHz.toFloat()).toDouble()
        exposureTargetFrac  = p.getFloat("exposureTargetFrac", exposureTargetFrac.toFloat()).toDouble()
        exposureTolFrac     = p.getFloat("exposureTolFrac", exposureTolFrac.toFloat()).toDouble()
        exposureUs          = p.getInt("exposureUs", exposureUs)
        cameraId            = p.getString("cameraId", cameraId) ?: cameraId
        maxRows             = p.getInt("maxRows", maxRows)
        meteoblueEnabled    = p.getBoolean("meteoblueEnabled", meteoblueEnabled)
        meteoblueWindAuto   = p.getBoolean("meteoblueWindAuto", meteoblueWindAuto)
        meteoblueApiKey     = p.getString("meteoblueApiKey", meteoblueApiKey) ?: meteoblueApiKey
    }

    /** Persist current values (called when settings change). */
    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putFloat("coeffSolar", coeffSolar.toFloat())
            putFloat("lambdaMeasM", lambdaMeasM.toFloat())
            putFloat("turbHeightM", turbHeightM.toFloat())
            putFloat("assumedWindMs", assumedWindMs.toFloat())
            putFloat("windowSec", windowSec.toFloat())
            putFloat("bandLowHz", bandLowHz.toFloat())
            putFloat("bandHighHz", bandHighHz.toFloat())
            putFloat("exposureTargetFrac", exposureTargetFrac.toFloat())
            putFloat("exposureTolFrac", exposureTolFrac.toFloat())
            putInt("exposureUs", exposureUs)
            putString("cameraId", cameraId)
            putInt("maxRows", maxRows)
            putBoolean("meteoblueEnabled", meteoblueEnabled)
            putBoolean("meteoblueWindAuto", meteoblueWindAuto)
            putString("meteoblueApiKey", meteoblueApiKey)
            apply()
        }
    }

    /** Serialise all parameters for the JSON session file and the params bar. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("appVersion", APP_VERSION)
        put("coeffSolar", coeffSolar)
        put("lambdaMeasM", lambdaMeasM)
        put("lambdaRefM", lambdaRefM)
        put("turbHeightM", turbHeightM)
        put("solarAngularDiamRad", solarAngularDiamRad)
        put("assumedWindMs", assumedWindMs)
        put("windowSec", windowSec)
        put("ringSize", ringSize)
        put("welchSegLen", welchSegLen)
        put("welchOverlap", welchOverlap)
        put("bandLowHz", bandLowHz)
        put("bandHighHz", bandHighHz)
        put("exposureTargetFrac", exposureTargetFrac)
        put("exposureTolFrac", exposureTolFrac)
        put("saturationFrac", saturationFrac)
        put("exposureUs", exposureUs)
        put("cameraId", cameraId)
        put("maxRows", maxRows)
        put("formula", "sigma2I = COEFF * k^(-5/6) * sec(z)^(5/6) * H^(-1/6) * lambda / alpha_s^2 * r0^(-5/3)")
    }
}
