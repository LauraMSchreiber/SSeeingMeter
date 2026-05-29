package com.seeingmeter

import android.content.Context
import org.json.JSONObject

object Config {

    val APP_VERSION: String = BuildConfig.APP_VERSION

    var coeffSolar = 13.47
    var lambdaMeasM = 550e-9
    var lambdaRefM = 500e-9
    var turbHeightM = 5000.0
    var solarAngularDiamRad = 9.3e-3
    var assumedWindMs = 10.0
    var windowSec = 0.5
    var ringSize = 262_144
    var welchSegLen = 4096
    var welchOverlap = 0.5
    var bandLowHz = 3.0
    var bandHighHz = 4000.0
    var exposureTargetFrac = 0.55
    var exposureTolFrac = 0.12
    var saturationFrac = 0.95
    var cameraId = "0"
    var maxRows = 2160
    var meteoblueEnabled = true
    var meteoblueApiKey = "ksNoguct6UfHiYjy"
    var meteoblueWindAuto = true

    private const val PREFS = "seemeter_prefs"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        coeffSolar         = p.getFloat("coeffSolar", coeffSolar.toFloat()).toDouble()
        lambdaMeasM        = p.getFloat("lambdaMeasM", lambdaMeasM.toFloat()).toDouble()
        turbHeightM        = p.getFloat("turbHeightM", turbHeightM.toFloat()).toDouble()
        assumedWindMs      = p.getFloat("assumedWindMs", assumedWindMs.toFloat()).toDouble()
        windowSec          = p.getFloat("windowSec", windowSec.toFloat()).toDouble()
        bandLowHz          = p.getFloat("bandLowHz", bandLowHz.toFloat()).toDouble()
        bandHighHz         = p.getFloat("bandHighHz", bandHighHz.toFloat()).toDouble()
        exposureTargetFrac = p.getFloat("exposureTargetFrac", exposureTargetFrac.toFloat()).toDouble()
        exposureTolFrac    = p.getFloat("exposureTolFrac", exposureTolFrac.toFloat()).toDouble()
        cameraId           = p.getString("cameraId", cameraId) ?: cameraId
        maxRows            = p.getInt("maxRows", maxRows)
        meteoblueEnabled   = p.getBoolean("meteoblueEnabled", meteoblueEnabled)
        meteoblueWindAuto  = p.getBoolean("meteoblueWindAuto", meteoblueWindAuto)
        meteoblueApiKey    = p.getString("meteoblueApiKey", meteoblueApiKey) ?: meteoblueApiKey
    }

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
            putString("cameraId", cameraId)
            putInt("maxRows", maxRows)
            putBoolean("meteoblueEnabled", meteoblueEnabled)
            putBoolean("meteoblueWindAuto", meteoblueWindAuto)
            putString("meteoblueApiKey", meteoblueApiKey)
            apply()
        }
    }

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
        put("cameraId", cameraId)
        put("maxRows", maxRows)
        put("formula", "sigma2I = COEFF * k^(-5/6) * sec(z)^(5/6) * H^(-1/6) * lambda / alpha_s^2 * r0^(-5/3)")
    }
}