package com.seeingmeter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

class SessionStats {
    var n=0L;private set;var mean=0.0;private set;private var m2=0.0
    var min=Double.POSITIVE_INFINITY;private set;var max=Double.NEGATIVE_INFINITY;private set
    fun add(x:Double){if(x.isNaN())return;n++;val d=x-mean;mean+=d/n;m2+=d*(x-mean);if(x<min)min=x;if(x>max)max=x}
    val sd:Double get()=if(n>1)sqrt(m2/(n-1)) else 0.0
    fun toJson():JSONObject=JSONObject().apply{put("n",n);put("mean",mean);put("sd",sd)
        put("min",if(min.isFinite())min else JSONObject.NULL);put("max",if(max.isFinite())max else JSONObject.NULL)}
}

class DataLogger(private val ctx:Context) {
    private val startedMs=System.currentTimeMillis()
    private val samples=JSONArray();private val meteoblueSnaps=JSONArray();private val hourly=JSONArray()
    private val seeingStats=SessionStats();private val r0Stats=SessionStats()
    private var gps:JSONObject?=null;private var pressureHpa:Double?=null;private var instrument:JSONObject?=null
    private var fileUri:Uri?=null;private var fallbackFile:File?=null
    private val tsName=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date(startedMs))
    val filename="SeeingMeter-${Config.APP_VERSION}_$tsName.json"
    private var lastSaveMs=0L

    fun open():String {
        try{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){
            val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,filename)
                put(MediaStore.MediaColumns.MIME_TYPE,"application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/seeing")
                put(MediaStore.MediaColumns.IS_PENDING,1)}
            fileUri=ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
        }}catch(_:Exception){fileUri=null}
        if(fileUri==null){val dir=File(ctx.getExternalFilesDir(null),"seeing").apply{mkdirs()};fallbackFile=File(dir,filename)}
        return fileUri?.toString()?:fallbackFile?.absolutePath?:"(no file)"
    }
    fun setGps(lat:Double?,lon:Double?,accM:Float?,altM:Double?){gps=JSONObject().apply{put("lat",lat?:JSONObject.NULL);put("lon",lon?:JSONObject.NULL);put("accM",accM?:JSONObject.NULL);put("altM",altM?:JSONObject.NULL)}}
    fun setPressureHpa(p:Double?){pressureHpa=p}
    fun setInstrument(model:String,androidVer:String,cameraLabel:String){instrument=JSONObject().apply{put("device",model);put("android",androidVer);put("camera",cameraLabel)}}
    fun addMeteoblue(json:JSONObject){meteoblueSnaps.put(json)}
    fun addSample(r:SeeingResult,exposureNs:Long,iso:Int,meanFrac:Double){
        samples.put(JSONObject().apply{put("t",r.timeMs);put("r0",nz(r.r0M));put("seeing",nz(r.seeingArcsec))
            put("sigma2I",nz(r.sigma2I));put("fChar",nz(r.fCharHz));put("fFresnel",nz(r.fFresnelHz))
            put("hFit",nz(r.hFitM));put("noiseFloor",nz(r.noiseFloor));put("quality",r.quality)
            put("expNs",exposureNs);put("iso",iso);put("meanFrac",meanFrac)})
        seeingStats.add(r.seeingArcsec);r0Stats.add(r.r0M)
        val now=System.currentTimeMillis();if(now-lastSaveMs>5_000){saveNow(false);lastSaveMs=now}
    }
    private fun nz(d:Double):Any=if(d.isFinite())d else JSONObject.NULL
    private fun saveNow(finalize:Boolean){
        val payload=buildJson(finalize).toString(2).toByteArray()
        try{fileUri?.let{uri->ctx.contentResolver.openOutputStream(uri,"wt")?.use{it.write(payload)}
            if(finalize&&Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){
                val v=ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)}
                ctx.contentResolver.update(uri,v,null,null)}}
            ?:fallbackFile?.let{f->FileOutputStream(f).use{it.write(payload)}}}catch(_:Exception){}
    }
    fun finalize():String{saveNow(true);return fileUri?.toString()?:fallbackFile?.absolutePath?:"(no file)"}
    private fun buildJson(finalize:Boolean):JSONObject=JSONObject().apply{
        put("session",JSONObject().apply{put("appVersion",Config.APP_VERSION);put("startedMs",startedMs)
            put("endedMs",if(finalize)System.currentTimeMillis() else JSONObject.NULL)
            put("durationSec",(System.currentTimeMillis()-startedMs)/1000.0)})
        put("gps",gps?:JSONObject.NULL);put("pressureHpa",pressureHpa?:JSONObject.NULL)
        put("instrument",instrument?:JSONObject.NULL);put("parameters",Config.toJson())
        put("meteoblue",meteoblueSnaps);put("samples",samples);put("hourly",hourly)
        put("overall",JSONObject().apply{put("seeingArcsec",seeingStats.toJson());put("r0M",r0Stats.toJson())})}
}

object MeteoblueClient {
    private val INDEX_ARCSEC=mapOf(1 to 0.6,2 to 0.8,3 to 1.1,4 to 1.5,5 to 2.0,6 to 2.7,7 to 3.6,8 to 5.0)
    fun indexToArcsec(i:Int):Double=INDEX_ARCSEC[i.coerceIn(1,8)]?:Double.NaN
    fun fetch(apiKey:String,latDeg:Double,lonDeg:Double):JSONObject?{
        if(apiKey.isBlank())return null
        val url=URL("https://my.meteoblue.com/packages/seeing-3h?apikey=$apiKey&lat=$latDeg&lon=$lonDeg&format=json")
        return try{val con=(url.openConnection()as HttpURLConnection).apply{connectTimeout=8000;readTimeout=8000;requestMethod="GET"}
            val txt=con.inputStream.bufferedReader().use{it.readText()};val root=JSONObject(txt)
            val arcsecDirect=firstNumber(root){it.contains("seeing")&&it.contains("arcsec")}
            val index=(firstNumber(root){it.contains("seeing")&&it.contains("index")}
                ?:firstNumber(root){it=="seeing1"||it=="seeing"})?.toInt()
            val arcsec=arcsecDirect?:index?.let{indexToArcsec(it)}?:Double.NaN
            val windMs=firstNumber(root){it.contains("jetstream")}
                ?:firstNumber(root){it.contains("wind")&&it.contains("speed")}
                ?:firstNumber(root){it.contains("wind")}?:Double.NaN
            JSONObject().apply{put("fetchedMs",System.currentTimeMillis());put("indexNow",index?:-1)
                put("arcsecNow",if(arcsec.isFinite())arcsec else JSONObject.NULL)
                put("windMs",if(windMs.isFinite())windMs else JSONObject.NULL);put("raw",root)}
        }catch(_:Exception){null}
    }
    private fun firstNumber(root:JSONObject,match:(String)->Boolean):Double?{
        val stack=ArrayDeque<JSONObject>();stack.add(root)
        while(stack.isNotEmpty()){val obj=stack.removeFirst();val keys=obj.keys()
            while(keys.hasNext()){val key=keys.next();val v=obj.get(key)
                if(match(key.lowercase()))numericFrom(v)?.let{return it}
                when(v){is JSONObject->stack.add(v);is JSONArray->for(i in 0 until v.length())(v.opt(i)as?JSONObject)?.let{stack.add(it)}}}}
        return null
    }
    private fun numericFrom(v:Any?):Double?=when(v){is Number->v.toDouble();is String->v.toDoubleOrNull()
        is JSONArray->{var r:Double?=null;for(i in 0 until v.length()){val e=v.opt(i);val n=(e as?Number)?.toDouble()?:(e as?String)?.toDoubleOrNull();if(n!=null){r=n;break}};r};else->null}
}

object FlatStore {
    fun save(ctx:Context,cameraId:String,profile:DoubleArray){
        try{File(ctx.filesDir,"flat_$cameraId.csv").writeText(profile.joinToString(","))}catch(_:Exception){}}
    fun load(ctx:Context,cameraId:String):DoubleArray?=try{
        val f=File(ctx.filesDir,"flat_$cameraId.csv")
        if(!f.exists())null else f.readText().split(",").mapNotNull{it.trim().toDoubleOrNull()}.toDoubleArray().takeIf{it.isNotEmpty()}
    }catch(_:Exception){null}
}