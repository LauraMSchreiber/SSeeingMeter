package com.seeingmeter

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executors

data class CameraInfo(val id:String,val facing:String,val focalLengthMm:Float?,
    val apertureFNumber:Float?,val maxYuv:Size,val manualSupported:Boolean) {
    val label:String get()=buildString{append(id).append(" · ").append(facing)
        apertureFNumber?.let{append(" · f/").append("%.1f".format(it))}
        focalLengthMm?.let{append(" · ").append("%.1f".format(it)).append("mm")}
        append(" · ").append("${maxYuv.width}x${maxYuv.height}")
        if(!manualSupported)append(" · NO-MANUAL")}
}

object CameraEnumerator {
    fun list(ctx:Context):List<CameraInfo> {
        val cm=ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager;val out=ArrayList<CameraInfo>()
        for(id in cm.cameraIdList){val c=cm.getCameraCharacteristics(id)
            val facing=when(c.get(CameraCharacteristics.LENS_FACING)){
                CameraCharacteristics.LENS_FACING_FRONT->"front";CameraCharacteristics.LENS_FACING_BACK->"back";else->"ext"}
            val focal=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val aperture=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
            val map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val maxYuv=map?.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull{it.height}?:Size(640,480)
            val caps=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?:IntArray(0)
            val manual=caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            out.add(CameraInfo(id,facing,focal,aperture,maxYuv,manual))};return out
    }
}

class CameraController(private val ctx:Context,private val analyzer:SeeingAnalyzer,private val listener:Listener) {
    data class FrameInfo(val meanCounts:Double,val peakCounts:Double,val fullScale:Double,
        val satRows:Int,val totalRows:Int,val setExposureNs:Long,val setIso:Int,
        val actualExposureNs:Long,val actualIso:Int,val aeOff:Boolean,val sampleRateHz:Double,val relocked:Boolean)
    interface Listener{fun onFrameStats(f:FrameInfo);fun onError(msg:String)}

    private val cm=ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device:CameraDevice?=null;private var session:CameraCaptureSession?=null
    private var reader:ImageReader?=null;private var thread:HandlerThread?=null;private var handler:Handler?=null
    private val executor=Executors.newSingleThreadExecutor()
    private var exposureNs=5_000_000L;private var iso=100
    private var expRange=100_000L..50_000_000L;private var isoRange=50..3200
    private var rows=1080;private var manualSupported=false;private var minFrameDurationNs=0L
    @Volatile private var actualExpNs=0L;@Volatile private var actualIso=0;@Volatile private var aeOff=false

    @Throws(SecurityException::class)
    fun start(previewSurface:Surface?){
        thread=HandlerThread("cam").also{it.start()};handler=Handler(thread!!.looper)
        val id=Config.cameraId;val c=cm.getCameraCharacteristics(id)
        c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let{expRange=it.lower..it.upper}
        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let{isoRange=it.lower..it.upper}
        exposureNs=exposureNs.coerceIn(expRange.first,expRange.last);iso=iso.coerceIn(isoRange.first,isoRange.last)
        val caps=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?:IntArray(0)
        manualSupported=caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        if(!manualSupported)listener.onError("Camera $id has NO manual exposure — AE cannot be locked. Pick a non-NO-MANUAL camera in Settings.")
        val map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val size=map.getOutputSizes(ImageFormat.YUV_420_888).filter{it.height<=Config.maxRows}.maxByOrNull{it.height}
            ?:map.getOutputSizes(ImageFormat.YUV_420_888).first()
        rows=size.height;minFrameDurationNs=try{map.getOutputMinFrameDuration(ImageFormat.YUV_420_888,size)}catch(_:Exception){0L}
        reader=ImageReader.newInstance(size.width,size.height,ImageFormat.YUV_420_888,3).apply{setOnImageAvailableListener({r->onImage(r)},handler)}
        cm.openCamera(id,object:CameraDevice.StateCallback(){
            override fun onOpened(d:CameraDevice){device=d;configure(d,previewSurface)}
            override fun onDisconnected(d:CameraDevice){d.close();device=null}
            override fun onError(d:CameraDevice,e:Int){d.close();device=null;listener.onError("camera error $e")}
        },handler)
    }

    private fun configure(d:CameraDevice,previewSurface:Surface?){
        val targets=ArrayList<Surface>();targets.add(reader!!.surface);previewSurface?.let{targets.add(it)}
        val cb=object:CameraCaptureSession.StateCallback(){
            override fun onConfigured(s:CameraCaptureSession){session=s;startRepeating(previewSurface)}
            override fun onConfigureFailed(s:CameraCaptureSession){listener.onError("session configure failed")}
        }
        val outputs=targets.map{OutputConfiguration(it)}
        d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,outputs,executor,cb))
    }

    private fun buildRequest(previewSurface:Surface?):CaptureRequest{
        val template=if(manualSupported)CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_PREVIEW
        val b=device!!.createCaptureRequest(template);b.addTarget(reader!!.surface);previewSurface?.let{b.addTarget(it)}
        b.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE,CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_LOCK,true)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,exposureNs);b.set(CaptureRequest.SENSOR_SENSITIVITY,iso)
        if(minFrameDurationNs>0)b.set(CaptureRequest.SENSOR_FRAME_DURATION,minFrameDurationNs)
        b.set(CaptureRequest.CONTROL_AWB_MODE,CameraMetadata.CONTROL_AWB_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AF_MODE,CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE,0f);return b.build()
    }

    private var previewRef:Surface?=null
    private fun startRepeating(previewSurface:Surface?){previewRef=previewSurface;applyRequest()}
    private fun applyRequest(){try{session?.setRepeatingRequest(buildRequest(previewRef),captureCallback,handler)}
        catch(e:Exception){listener.onError("exposure request rejected: ${e.message}")}}

    private val captureCallback=object:CameraCaptureSession.CaptureCallback(){
        override fun onCaptureCompleted(s:CameraCaptureSession,req:CaptureRequest,result:TotalCaptureResult){
            val skewNs=result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)
            if(skewNs!=null&&rows>1){val lt=skewNs.toDouble()/(rows-1);if(lt>0)analyzer.sampleRateHz=1e9/lt}
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let{actualExpNs=it}
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let{actualIso=it}
            aeOff=result.get(CaptureResult.CONTROL_AE_MODE)==CameraMetadata.CONTROL_AE_MODE_OFF
        }
    }

    private fun onImage(r:ImageReader){
        val img=r.acquireLatestImage()?:return
        try{val plane=img.planes[0];val buf=plane.buffer;val rowStride=plane.rowStride
            val pixelStride=plane.pixelStride;val w=img.width;val h=img.height;val full=255.0
            val rowMeans=DoubleArray(h);var frameSum=0.0;var peak=0.0;var satRows=0
            val sample=maxOf(1,w/256)
            for(y in 0 until h){var s=0.0;var rowPeak=0.0;var cnt=0;var x=0;val base=y*rowStride
                while(x<w){val v=(buf.get(base+x*pixelStride).toInt()and 0xFF).toDouble()
                    s+=v;if(v>rowPeak)rowPeak=v;cnt++;x+=sample}
                val m=if(cnt>0)s/cnt else 0.0;rowMeans[y]=m;frameSum+=m
                if(rowPeak>peak)peak=rowPeak;if(rowPeak>=Config.saturationFrac*full)satRows++}
            val frameMean=frameSum/h;val meanFrac=frameMean/full;val peakFrac=peak/full
            analyzer.ingestRows(rowMeans)
            val decision=ExposureLogic.evaluate(meanFrac,peakFrac,exposureNs,iso,expRange,isoRange)
            var relocked=false
            if(decision.action!=ExposureLogic.Action.OK){exposureNs=decision.newExposureNs;iso=decision.newIso;applyRequest();relocked=true}
            listener.onFrameStats(FrameInfo(frameMean,peak,full,satRows,h,exposureNs,iso,actualExpNs,actualIso,aeOff,analyzer.sampleRateHz,relocked))
        }finally{img.close()}
    }

    fun stop(){try{session?.stopRepeating()}catch(_:Exception){};session?.close();session=null
        device?.close();device=null;reader?.close();reader=null;thread?.quitSafely();thread=null;handler=null}
}