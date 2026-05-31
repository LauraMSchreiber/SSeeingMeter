package com.seeingmeter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * =============================================================================
 *  MainActivity  —  all UI in code (no XML), wiring of every module.
 * =============================================================================
 *
 *  Layout (top → bottom, inside a vertical ScrollView):
 *    - camera preview (TextureView, fixed height)
 *    - tvInfo / tvSignal / tvMeta (status strip)
 *    - results panel (r0, seeing, σ_I, f_c/f_F/H, session stats)
 *    - tvParams (all parameters + JSON path)
 *    - TimeSeriesView (scrolling 2-min r0 history)
 *    - SpectrumView (log-log PSD with f_Fresnel / f_char overlays)
 *    - buttons: ▶ Measure / 💾 Save / Reset / ⚙
 *
 *  All visible numbers come from one place: the latest SeeingResult from
 *  the analyzer + the latest frame stats from the camera. Adding a new
 *  parameter to Config automatically shows up in the params bar.
 */
class MainActivity : Activity(), CameraController.Listener, SensorEventListener, LocationListener {

    // --- modules ---
    private val analyzer = SeeingAnalyzer()
    private lateinit var camera: CameraController
    private lateinit var logger: DataLogger

    // --- UI ---
    private lateinit var preview: TextureView
    private lateinit var tvInfo: TextView
    private lateinit var tvSignal: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvResults: TextView
    private lateinit var tvParams: TextView
    private lateinit var btnMeasure: Button
    private lateinit var btnSave: Button
    private lateinit var btnReset: Button
    private lateinit var btnFlat: Button
    private lateinit var btnSettings: Button
    private lateinit var tsView: TimeSeriesView
    private lateinit var specView: SpectrumView

    // --- runtime state ---
    private val handler = Handler(Looper.getMainLooper())
    private var measuring = false
    private var lastFrame: CameraController.FrameInfo? = null
    private var lastResult: SeeingResult? = null
    private val historyR0 = ArrayDeque<Pair<Long, Double>>()  // 2 min trailing
    private var lat: Double? = null; private var lon: Double? = null
    private var accM: Float? = null; private var altM: Double? = null
    private var pressureHpa: Double? = null
    private var sensorMgr: SensorManager? = null
    private var locMgr: LocationManager? = null
    private var lastMeteoblueHour = -1

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.load(this)
        analyzer.flatProfile = FlatStore.load(this, Config.cameraId)   // restore flat if any
        logger = DataLogger(this)
        buildUi()
        ensurePermissions()

        sensorMgr = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorMgr?.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorMgr?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        camera = CameraController(this, analyzer, this)
        handler.post(uiTick)
    }

    override fun onDestroy() {
        super.onDestroy(); stopMeasuring()
        sensorMgr?.unregisterListener(this)
        locMgr?.removeUpdates(this)
    }

    // ---------------------------------------------------------------- UI build

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun mkTv(small: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 14f)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        setTextColor(Color.WHITE)
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        scroll.addView(col)

        preview = TextureView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(180))
        }
        col.addView(preview)

        tvInfo = mkTv();    col.addView(tvInfo)
        tvSignal = mkTv();  col.addView(tvSignal)
        tvMeta = mkTv();    col.addView(tvMeta)
        tvResults = mkTv().apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        col.addView(tvResults)
        tvParams = mkTv(true); col.addView(tvParams)

        tsView = TimeSeriesView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(110))
        }; col.addView(tsView)
        specView = SpectrumView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(140))
        }; col.addView(specView)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnMeasure = Button(this).apply { text = "▶ Measure" }
        btnSave    = Button(this).apply { text = "💾 Save" }
        btnReset   = Button(this).apply { text = "Reset" }
        btnFlat    = Button(this).apply { text = "▦ Flat" }
        btnSettings= Button(this).apply { text = "⚙" }
        for (b in arrayOf(btnMeasure, btnSave, btnReset, btnFlat, btnSettings)) {
            b.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            btnRow.addView(b)
        }
        col.addView(btnRow)
        setContentView(scroll)

        btnMeasure.setOnClickListener { if (measuring) stopMeasuring() else startMeasuring() }
        btnSave.setOnClickListener {
            val path = logger.finalize()
            Toast.makeText(this, "saved: $path", Toast.LENGTH_LONG).show()
        }
        btnReset.setOnClickListener { resetSession() }
        btnFlat.setOnClickListener { captureFlat() }
        btnSettings.setOnClickListener { showSettingsDialog() }
    }

    // ---------------------------------------------------------------- measuring

    private fun startMeasuring() {
        if (!hasPerm(Manifest.permission.CAMERA)) { ensurePermissions(); return }
        logger = DataLogger(this).also {
            it.setInstrument(Build.MODEL, Build.VERSION.RELEASE, "cam=${Config.cameraId}")
            val path = it.open()
            tvParams.text = "JSON: $path"
        }
        try { camera.start(if (preview.isAvailable) Surface(preview.surfaceTexture) else null) }
        catch (e: SecurityException) { Toast.makeText(this, "camera permission denied", Toast.LENGTH_LONG).show(); return }
        measuring = true
        btnMeasure.text = "⏸ Stop"
        startLocation()
        maybeFetchMeteoblue()
    }

    private fun stopMeasuring() {
        if (!measuring) return
        camera.stop(); measuring = false; btnMeasure.text = "▶ Measure"
    }

    private fun resetSession() {
        stopMeasuring()
        analyzer.reset(); historyR0.clear(); lastResult = null
        tsView.invalidate(); specView.invalidate()
    }

    /** Capture a flat field: point the diffused lens at uniform sky/sun, then tap.
     *  Averages ~30 frames into a per-row sensitivity profile and persists it. */
    private fun captureFlat() {
        if (!measuring) {
            Toast.makeText(this, "Start measuring first, aim at the uniform diffuser, then tap Flat", Toast.LENGTH_LONG).show()
            return
        }
        analyzer.startFlatCapture(30)
        Toast.makeText(this, "Capturing flat field… hold steady", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- UI tick

    /** 500 ms loop: compute one seeing value, push to UI + logger + views. */
    private val uiTick = object : Runnable {
        override fun run() {
            if (measuring) {
                // flat-field capture just completed → persist for this camera
                if (analyzer.flatJustFinished) {
                    analyzer.flatJustFinished = false
                    analyzer.flatProfile?.let {
                        FlatStore.save(this@MainActivity, Config.cameraId, it)
                        Toast.makeText(this@MainActivity, "Flat field captured (${it.size} rows)", Toast.LENGTH_SHORT).show()
                    }
                }
                val nowMs = System.currentTimeMillis()
                val secZ = if (lat != null && lon != null)
                    SolarGeometry.secZ(SolarGeometry.zenithDeg(lat!!, lon!!, nowMs))
                else 1.0
                val r = analyzer.compute(secZ, nowMs)
                if (r != null) {
                    lastResult = r
                    historyR0.addLast(r.timeMs to r.r0M)
                    val cutoff = nowMs - 120_000
                    while (historyR0.isNotEmpty() && historyR0.first().first < cutoff)
                        historyR0.removeFirst()
                    val lf = lastFrame
                    logger.addSample(r, lf?.setExposureNs ?: 0L, lf?.setIso ?: 0,
                        if (lf != null && lf.fullScale > 0) lf.meanCounts / lf.fullScale else 0.0)
                    tsView.points = historyR0.toList()
                    tsView.invalidate()
                    analyzer.lastPsd?.let { (f, p) -> specView.set(f, p, r.fCharHz,
                        Physics.fresnelFreqHz(Config.assumedWindMs, Config.turbHeightM, Config.lambdaMeasM)) }
                }
                refreshTexts()
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (hour != lastMeteoblueHour) { lastMeteoblueHour = hour; maybeFetchMeteoblue() }
            }
            handler.postDelayed(this, (Config.windowSec * 1000).toLong())
        }
    }

    private fun refreshTexts() {
        val f = lastFrame
        if (f == null) { tvInfo.text = "SeeingMeter-${Config.APP_VERSION}  (no frames yet)"; return }

        val fs = f.fullScale
        // Show what the SENSOR actually did (ground truth), and what we asked for.
        val actMs = f.actualExposureNs / 1e6
        val setMs = f.setExposureNs / 1e6
        val expStr = if (f.actualExposureNs > 0)
            "Exp=${"%.2f".format(actMs)}ms(set ${"%.2f".format(setMs)})" else "Exp(set)=${"%.2f".format(setMs)}ms"
        val isoStr = if (f.actualIso > 0) "ISO=${f.actualIso}(set ${f.setIso})" else "ISO(set)=${f.setIso}"
        val aeStr = if (f.aeOff) "AE:OFF" else "AE:ON⚠"   // ON ⇒ camera is still auto-exposing!
        tvInfo.text = "SeeingMeter-${Config.APP_VERSION}  $expStr  $isoStr  $aeStr" +
                "  ~${"%.0f".format(f.sampleRateHz / 1000.0)}kHz  cam=${Config.cameraId}  rows=${f.totalRows}"

        // PEAK + MEAN in RAW COUNTS so over/underflow is obvious.
        val peakC = f.peakCounts.toInt(); val meanC = f.meanCounts.toInt(); val fsC = fs.toInt()
        val flag = when {
            f.peakCounts >= fs - 1 -> "  🔴 OVERFLOW (clipping)"
            f.satRows > 0          -> "  🟠 ${f.satRows} sat rows"
            f.meanCounts < fs*0.05 -> "  🔵 underflow (too dark)"
            else                   -> "  ✓"
        }
        tvSignal.text = "peak=$peakC/$fsC (${"%.0f".format(f.peakCounts/fs*100)}%)" +
                "   mean=$meanC/$fsC (${"%.0f".format(f.meanCounts/fs*100)}%)$flag"

        val gpsStr = if (lat != null) "GPS ${"%.4f".format(lat!!)},${"%.4f".format(lon!!)} ±${accM?.toInt() ?: 0}m" else "GPS —"
        val pStr = pressureHpa?.let { "  ·  ${"%.1f".format(it)} hPa" } ?: ""
        tvMeta.text = "$gpsStr$pStr"

        val r = lastResult
        if (r != null) {
            tvResults.text = "r0=${"%.2f".format(r.r0M * 100)} cm   seeing=${"%.2f".format(r.seeingArcsec)}\"" +
                    "  σ_I=${"%.4f".format(r.sigma2I)}   f_c=${"%.1f".format(r.fCharHz)} Hz" +
                    "  H=${(r.hFitM / 1000).let { "%.1f".format(it) }} km   ${r.quality}"
        } else tvResults.text = "settling exposure — waiting for stable ISO…"

        tvParams.text = "Params: " + Config.toJson().toString()
    }

    // ---------------------------------------------------------------- callbacks

    override fun onFrameStats(f: CameraController.FrameInfo) { lastFrame = f }
    override fun onError(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_PRESSURE) {
            pressureHpa = e.values[0].toDouble(); logger.setPressureHpa(pressureHpa)
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onLocationChanged(loc: Location) {
        lat = loc.latitude; lon = loc.longitude
        accM = loc.accuracy; altM = if (loc.hasAltitude()) loc.altitude else null
        logger.setGps(lat, lon, accM, altM)
    }

    // ---------------------------------------------------------------- helpers

    private fun ensurePermissions() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { !hasPerm(it) }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }
    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) return
        locMgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locMgr?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 0f, this)
        } catch (_: SecurityException) {}
    }

    private fun maybeFetchMeteoblue() {
        if (!Config.meteoblueEnabled || Config.meteoblueApiKey.isBlank()) return
        val la = lat ?: return; val lo = lon ?: return
        Thread {
            MeteoblueClient.fetch(Config.meteoblueApiKey, la, lo)?.let { snap ->
                runOnUiThread {
                    logger.addMeteoblue(snap)
                    // (#3) use meteoblue wind for f_Fresnel / H if available
                    if (Config.meteoblueWindAuto) {
                        val w = snap.optDouble("windMs", Double.NaN)
                        if (w.isFinite() && w > 0) Config.assumedWindMs = w
                    }
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- settings

    /** In-code settings dialog (no XML — avoids the v14 ScrollView clipping bug).
     *  Picks: camera, exposure target, band edges, meteoblue key, wind, height. */
    private fun showSettingsDialog() {
        val cams = CameraEnumerator.list(this)
        val labels = cams.map { it.label }.toTypedArray()
        val curIdx = cams.indexOfFirst { it.id == Config.cameraId }.coerceAtLeast(0)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        fun row(label: String, view: View) {
            box.addView(TextView(this).apply { text = label; setPadding(0, dp(8), 0, 0) })
            box.addView(view)
        }
        val camSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(curIdx)
        }
        row("Camera (your requested aspect iii)", camSpin)

        fun numEdit(default: String): EditText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(default)
        }
        val eTarget = numEdit(Config.exposureTargetFrac.toString())
        val eExpUs  = numEdit(Config.exposureUs.toString())
        val eTol    = numEdit(Config.exposureTolFrac.toString())
        val eBandLo = numEdit(Config.bandLowHz.toString())
        val eBandHi = numEdit(Config.bandHighHz.toString())
        val eWind   = numEdit(Config.assumedWindMs.toString())
        val eH      = numEdit(Config.turbHeightM.toString())
        val eCoeff  = numEdit(Config.coeffSolar.toString())
        val eKey    = EditText(this).apply { setText(Config.meteoblueApiKey); hint = "meteoblue API key" }

        row("Exposure µs (0 = lowest)", eExpUs)
        row("Exposure target (0..1)", eTarget)
        row("Exposure tolerance (±)", eTol)
        row("Band low (Hz)",  eBandLo)
        row("Band high (Hz)", eBandHi)
        row("Assumed wind (m/s)", eWind)
        row("Turbulence height (m)", eH)
        row("COEFF (calibration)", eCoeff)
        row("Meteoblue API key", eKey)

        AlertDialog.Builder(this)
            .setTitle("Settings — v${Config.APP_VERSION}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("OK") { _, _ ->
                Config.cameraId = cams[camSpin.selectedItemPosition].id
                Config.exposureUs = eExpUs.text.toString().toIntOrNull() ?: Config.exposureUs
                Config.exposureTargetFrac = eTarget.text.toString().toDoubleOrNull() ?: Config.exposureTargetFrac
                Config.exposureTolFrac    = eTol.text.toString().toDoubleOrNull()    ?: Config.exposureTolFrac
                Config.bandLowHz          = eBandLo.text.toString().toDoubleOrNull() ?: Config.bandLowHz
                Config.bandHighHz         = eBandHi.text.toString().toDoubleOrNull() ?: Config.bandHighHz
                Config.assumedWindMs      = eWind.text.toString().toDoubleOrNull()   ?: Config.assumedWindMs
                Config.turbHeightM        = eH.text.toString().toDoubleOrNull()      ?: Config.turbHeightM
                Config.coeffSolar         = eCoeff.text.toString().toDoubleOrNull()  ?: Config.coeffSolar
                Config.meteoblueApiKey    = eKey.text.toString()
                Config.save(this)
                analyzer.flatProfile = FlatStore.load(this, Config.cameraId)  // flat is per-camera
                if (measuring) { stopMeasuring(); startMeasuring() }   // re-open with new camera
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    //  Custom views — kept inside MainActivity to honour "few large files".
    // =========================================================================

    class TimeSeriesView(c: Context) : View(c) {
        var points: List<Pair<Long, Double>> = emptyList()
        private val paintLine = Paint().apply { color = Color.YELLOW; strokeWidth = 3f; isAntiAlias = true }
        private val paintAxis = Paint().apply { color = Color.DKGRAY; strokeWidth = 1f }
        private val paintText = Paint().apply { color = Color.LTGRAY; textSize = 24f }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawLine(0f, h - 1, w, h - 1, paintAxis)
            canvas.drawText("r0 history (2 min) cm", 8f, 24f, paintText)
            if (points.isEmpty()) return
            val t0 = points.first().first; val t1 = points.last().first
            val span = max(1L, t1 - t0).toFloat()
            val ymax = (points.maxOf { it.second } * 100).coerceAtLeast(20.0).toFloat()
            var prevX = -1f; var prevY = 0f
            for ((t, r0) in points) {
                val x = ((t - t0) / span) * w
                val y = h - ((r0 * 100).toFloat() / ymax) * (h - 30f)
                if (prevX >= 0) canvas.drawLine(prevX, prevY, x, y, paintLine)
                prevX = x; prevY = y
            }
        }
    }

    class SpectrumView(c: Context) : View(c) {
        private var freqs: DoubleArray = DoubleArray(0)
        private var psd: DoubleArray = DoubleArray(0)
        private var fChar = 0.0; private var fFresnel = 0.0
        private val pPsd = Paint().apply { color = Color.CYAN; strokeWidth = 2f; isAntiAlias = true }
        private val pChar = Paint().apply { color = Color.MAGENTA; strokeWidth = 2f }
        private val pFres = Paint().apply { color = Color.GREEN;   strokeWidth = 2f }
        private val pTxt  = Paint().apply { color = Color.LTGRAY; textSize = 22f }
        fun set(f: DoubleArray, p: DoubleArray, fc: Double, fF: Double) {
            freqs = f; psd = p; fChar = fc; fFresnel = fF; invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawText("PSD (log-log)  magenta=f_char  green=f_F", 8f, 22f, pTxt)
            if (freqs.size < 2 || psd.size < 2) return
            val fLo = max(1.0, freqs[1]); val fHi = freqs.last()
            val pLo = (psd.filter { it > 0 }.minOrNull() ?: 1e-12).coerceAtLeast(1e-15)
            val pHi = psd.maxOrNull() ?: 1.0
            fun mapX(f: Double) = ((log10(f) - log10(fLo)) / (log10(fHi) - log10(fLo)) * w).toFloat()
            fun mapY(p: Double): Float {
                val pp = p.coerceAtLeast(pLo)
                return (h - ((log10(pp) - log10(pLo)) / (log10(pHi) - log10(pLo))) * (h - 30f)).toFloat()
            }
            var prevX = -1f; var prevY = 0f
            for (i in 1 until freqs.size) {
                if (freqs[i] < fLo) continue
                val x = mapX(freqs[i]); val y = mapY(psd[i])
                if (prevX >= 0) canvas.drawLine(prevX, prevY, x, y, pPsd)
                prevX = x; prevY = y
            }
            if (fChar > 0) canvas.drawLine(mapX(fChar), 30f, mapX(fChar), h, pChar)
            if (fFresnel > 0) canvas.drawLine(mapX(fFresnel), 30f, mapX(fFresnel), h, pFres)
        }
    }
}
