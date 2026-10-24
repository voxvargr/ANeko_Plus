package org.nqmgaming.aneko.core.companion

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Rasterizes only installed selected skins; bounded output travels to the paired watch. */
object WearCompanionSnapshot {
    data class Snapshot(val settings: String, val skinPack: File)
    private val exportMutex = Mutex()

    suspend fun create(context: Context, prefs: SharedPreferences): Snapshot = exportMutex.withLock {
        createLocked(context, prefs)
    }

    private suspend fun createLocked(context: Context, prefs: SharedPreferences): Snapshot {
        currentCoroutineContext().ensureActive()
        val count = prefs.getString("motion.neko_count", "1")?.toFloatOrNull()?.toInt()?.coerceIn(1,6) ?: 1
        val settings = JSONObject().put("version",1)
            .put("sizeScale", number(prefs,"motion.size",80f)/80f)
            .put("speed",number(prefs,"motion.speed",1f))
            .put("opacity",1f-number(prefs,"motion.transparency",0f))
            .put("quiet",prefs.getBoolean("motion.quiet_mode",false)).put("count",count)
            .put("attention",prefs.getBoolean("motion.attention.enabled",false))
            .put("haptics",prefs.getBoolean("motion.attention.haptics",false))
        val skins = JSONArray()
        val pets = JSONArray()
        val root = File(context.filesDir,"skins").canonicalFile
        val outbox = File(context.cacheDir,"watch-outbox").apply {
            check(isDirectory || mkdirs()) { "Cannot create watch outbox" }
        }
        cleanOutbox(outbox)
        val temp = File.createTempFile("watch-pets-", ".zip", outbox)
        var extractedBytes = 0L
        var completed = false
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                repeat(count) { index ->
                    currentCoroutineContext().ensureActive()
                    val id = prefs.getString("motion.skin.$index",null)
                        ?: prefs.getString("motion.skin","org.nqmgaming.aneko") ?: "org.nqmgaming.aneko"
                    val folder = File(root,id.removeSuffix("/skin.xml")).canonicalFile
                    require(folder.path.startsWith(root.path+File.separator)) { "Invalid skin folder" }
                    val states = WearSkinLoader.states
                    val frameLimit = (500/count/states.size).coerceIn(1,64)
                    WearSkinLoader.load(context,folder,frameLimit).use { loaded ->
                    val params = loaded.params
                    val displayName = loaded.displayName
                    skins.put(JSONObject().put("package",id).put("name",displayName)
                        .put("sizeScale",number(prefs,"motion.size.$id",number(prefs,"motion.size",80f))/80f)
                        .put("speed",number(prefs,"motion.speed.$id",number(prefs,"motion.speed",1f)))
                        .put("opacity",1f-number(prefs,"motion.transparency.$id",number(prefs,"motion.transparency",0f))))
                    val motions = JSONObject()
                    states.forEach { state ->
                        val frames = JSONArray()
                        params.getDrawable(state)?.exportFrames(frameLimit)?.forEachIndexed { frameIndex, (drawable,duration) ->
                            currentCoroutineContext().ensureActive()
                            val sourceWidth = drawable.intrinsicWidth.coerceAtLeast(1)
                            val sourceHeight = drawable.intrinsicHeight.coerceAtLeast(1)
                            val scale = minOf(1f,256f/maxOf(sourceWidth,sourceHeight))
                            val bitmap = Bitmap.createBitmap((sourceWidth*scale).roundToInt().coerceAtLeast(1),
                                (sourceHeight*scale).roundToInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                            val bytes = try {
                                drawable.setBounds(0,0,bitmap.width,bitmap.height)
                                drawable.draw(Canvas(bitmap))
                                ByteArrayOutputStream().use { output ->
                                    bitmap.compress(Bitmap.CompressFormat.PNG,100,output)
                                    output.toByteArray()
                                }
                            } finally { bitmap.recycle() }
                            extractedBytes += bytes.size
                            require(extractedBytes <= 8*1024*1024) { "Selected skins exceed watch transfer limit" }
                            val path = "frames/p$index/$state-$frameIndex.png"
                            zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
                            frames.put(JSONObject().put("file",path).put("durationMs",duration))
                        }
                        if (frames.length() > 0) motions.put(state,frames)
                    }
                    pets.put(JSONObject().put("id",id).put("name",displayName).put("motions",motions))
                    }
                }
                val manifest = JSONObject().put("version",1).put("pets",pets).toString().toByteArray()
                require(extractedBytes+manifest.size <= 8*1024*1024)
                zip.putNextEntry(ZipEntry("frames.json")); zip.write(manifest); zip.closeEntry()
            }
            currentCoroutineContext().ensureActive()
            settings.put("skins",skins)
            completed = true
            return Snapshot(settings.toString(),temp)
        } finally { if (!completed) temp.delete() }
    }

    private fun cleanOutbox(outbox: File) {
        val packs = outbox.listFiles { file ->
            file.isFile && file.name.startsWith("watch-pets-") && file.name.endsWith(".zip")
        }.orEmpty()
        val latest = packs.maxByOrNull { it.lastModified() }
        val cutoff = System.currentTimeMillis() - 10 * 60_000L
        packs.filter { it != latest && it.lastModified() < cutoff }.forEach { it.delete() }
    }

    private fun number(prefs: SharedPreferences,key:String,fallback:Float):Float =
        prefs.getString(key,null)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: fallback
}
