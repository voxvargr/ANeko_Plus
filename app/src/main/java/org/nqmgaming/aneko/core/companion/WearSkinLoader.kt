package org.nqmgaming.aneko.core.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Xml
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.nqmgaming.aneko.core.motion.MotionDrawable
import org.nqmgaming.aneko.core.motion.MotionParams
import org.nqmgaming.aneko.core.pet.CodexPetContract
import org.nqmgaming.aneko.core.pet.CodexPetPackage
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File

/** Loads only bounded Wear export frames, without decoding the phone's complete skin. */
internal object WearSkinLoader {
    val states = listOf("stop", "wait", "awake", "moveUp", "moveUpRight", "moveRight",
        "moveDownRight", "moveDown", "moveDownLeft", "moveLeft", "moveUpLeft")
    private const val MAX_XML_BYTES = 256 * 1024L
    private const val MAX_MANIFEST_BYTES = 64 * 1024L
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024L
    private const val MAX_SOURCE_PIXELS = 64 * 1024 * 1024L
    private const val MAX_DECODED_BYTES = 32 * 1024 * 1024L
    private const val MAX_REFERENCES = 8_192

    class LoadedSkin(val params: MotionParams, var displayName: String) : Closeable {
        private val bitmaps = mutableListOf<Bitmap>()
        private var decodedBytes = 0L

        fun own(bitmap: Bitmap): Bitmap {
            val bytes = bitmap.allocationByteCount.toLong()
            if (decodedBytes + bytes > MAX_DECODED_BYTES) {
                bitmap.recycle()
                throw IllegalArgumentException("Skin exceeds Wear decoded-image budget")
            }
            decodedBytes += bytes
            bitmaps += bitmap
            return bitmap
        }

        fun checkAllocation(width: Int, height: Int) {
            require(width > 0 && height > 0 && decodedBytes + width.toLong() * height * 4 <= MAX_DECODED_BYTES) {
                "Skin exceeds Wear decoded-image budget"
            }
        }

        override fun close() {
            bitmaps.asReversed().forEach { if (!it.isRecycled) it.recycle() }
            bitmaps.clear()
            decodedBytes = 0
        }
    }

    suspend fun load(context: Context, folder: File, frameLimit: Int): LoadedSkin {
        currentCoroutineContext().ensureActive()
        val root = folder.canonicalFile
        require(root.isDirectory) { "Skin folder missing" }
        val manifest = confined(root, "pet.json")
        val loaded = LoadedSkin(MotionParams(), root.name)
        try {
            if (manifest.isFile) {
                require(manifest.length() <= MAX_MANIFEST_BYTES) { "Pet manifest is too large" }
                val source = CodexPetPackage.fromManifest(manifest, validateTransparency = false)
                val atlasFile = confined(root, source.atlasFile.relativeTo(root).path)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val cellWidth = CodexPetContract.CELL_WIDTH / 2
                val cellHeight = CodexPetContract.CELL_HEIGHT / 2
                loaded.checkAllocation(CodexPetContract.ATLAS_WIDTH / 2,
                    CodexPetContract.expectedHeight(source.version) / 2)
                val atlas = loaded.own(requireNotNull(BitmapFactory.decodeFile(atlasFile.path, options)))
                require(atlas.width == cellWidth * CodexPetContract.COLUMNS &&
                    atlas.height == cellHeight * (if (source.version == 2) CodexPetContract.V2_ROWS else CodexPetContract.STANDARD_ROWS))
                val cells = mutableMapOf<Pair<Int, Int>, Bitmap>()
                fun cell(row: Int, column: Int): Bitmap = cells.getOrPut(row to column) {
                    loaded.checkAllocation(cellWidth, cellHeight)
                    loaded.own(Bitmap.createBitmap(atlas, column * cellWidth, row * cellHeight, cellWidth, cellHeight))
                }
                suspend fun addRow(name: String, row: CodexPetContract.AnimationRow) {
                    val motion = MotionDrawable()
                    row.durationsMs.take(frameLimit.coerceIn(1, 64)).forEachIndexed { column, duration ->
                        currentCoroutineContext().ensureActive()
                        motion.addFrame(BitmapDrawable(context.resources, cell(row.row, column)), duration)
                    }
                    loaded.params.motions[name] = MotionParams.Motion(name = name, items = motion)
                }
                addRow("stop", CodexPetContract.idle)
                addRow("wait", CodexPetContract.waiting)
                addRow("awake", CodexPetContract.jumping)
                addRow("moveRight", CodexPetContract.runningRight)
                addRow("moveLeft", CodexPetContract.runningLeft)
                for ((name, degrees) in listOf("Up" to 0, "UpRight" to 45, "DownRight" to 135,
                    "Down" to 180, "DownLeft" to 225, "UpLeft" to 315)) {
                    currentCoroutineContext().ensureActive()
                    if (source.version == 2) {
                        val position = CodexPetContract.lookCell(degrees)
                        loaded.params.motions["move$name"] = MotionParams.Motion(name = "move$name", items = MotionDrawable().apply {
                            addFrame(BitmapDrawable(context.resources, cell(position.row, position.column)), 750)
                        })
                    } else addRow("move$name", when {
                        name.endsWith("Right") -> CodexPetContract.runningRight
                        name.endsWith("Left") -> CodexPetContract.runningLeft
                        else -> CodexPetContract.running
                    })
                }
                // Name is already bounded and parsed by CodexPetPackage.
                loaded.displayName = source.manifest.displayName
                return loaded
            }
            loadLegacy(context, root, loaded, frameLimit.coerceIn(1, 64))
            return loaded
        } catch (error: Throwable) {
            loaded.close()
            throw error
        }
    }

    private data class Frame(val name: String, val duration: Int)

    private suspend fun loadLegacy(context: Context, root: File, loaded: LoadedSkin, frameLimit: Int) {
        val xml = confined(root, "skin.xml")
        require(xml.isFile && xml.length() <= MAX_XML_BYTES) { "Skin XML missing or too large" }
        val definitions = linkedMapOf<String, MutableList<Frame>>()
        var initial = "stop"
        var awake = "awake"
        var movePrefix = "move"
        var wait = "wait"
        var current: String? = null
        var references = 0
        var nodes = 0
        xml.inputStream().buffered().use { stream ->
            val parser = Xml.newPullParser().apply { setInput(stream, null) }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                currentCoroutineContext().ensureActive()
                require(++nodes <= MAX_REFERENCES * 4 && parser.depth <= 12) { "Skin XML is too complex" }
                require(event != XmlPullParser.DOCDECL && event != XmlPullParser.ENTITY_REF) { "XML entities are unsupported" }
                if (event == XmlPullParser.START_TAG) when (parser.name) {
                    "motion-params" -> {
                        initial = parser.getAttributeValue(null, "initialState") ?: initial
                        awake = parser.getAttributeValue(null, "awakeState") ?: awake
                        movePrefix = parser.getAttributeValue(null, "moveStatePrefix") ?: movePrefix
                    }
                    "motion" -> {
                        require(current == null && definitions.size < 256) { "Invalid motion nesting" }
                        current = requireNotNull(parser.getAttributeValue(null, "state"))
                        require(current!!.length <= 128) { "Motion state name too long" }
                        definitions[current!!] = mutableListOf()
                        if (current == initial) wait = parser.getAttributeValue(null, "nextState") ?: wait
                    }
                    "item" -> {
                        require(++references <= MAX_REFERENCES) { "Too many skin frame references" }
                        val name = parser.getAttributeValue(null, "drawable").orEmpty().trim()
                        require(name.length <= 512) { "Frame name too long" }
                        val duration = parser.getAttributeValue(null, "duration")?.toIntOrNull()?.coerceIn(40, 10_000) ?: 100
                        current?.let { state ->
                            // Flatten each nested repeat group once, exactly like exportFrames.
                            if (definitions.getValue(state).size < frameLimit && name.isNotEmpty()) {
                                definitions.getValue(state).add(Frame(name, duration))
                            }
                        }
                    }
                    "repeat-item" -> Unit
                    else -> throw IllegalArgumentException("Unsupported skin XML element")
                }
                if (event == XmlPullParser.END_TAG && parser.name == "motion") current = null
                event = parser.nextToken()
            }
        }
        val cache = mutableMapOf<File, Bitmap>()
        for (state in states) {
            val sourceState = when (state) {
                "stop" -> initial
                "awake" -> awake
                "wait" -> wait
                else -> movePrefix + state.removePrefix("move")
            }
            val frames = definitions[sourceState] ?: continue
            val motion = MotionDrawable()
            for (frame in frames) {
                currentCoroutineContext().ensureActive()
                val file = listOf("png", "webp", "jpg", "jpeg").asSequence()
                    .map { confined(root, "${frame.name}.$it") }.firstOrNull { it.isFile } ?: continue
                val bitmap = cache[file] ?: decodeFrame(file, loaded).also { cache[file] = it }
                motion.addFrame(BitmapDrawable(context.resources, bitmap), frame.duration)
            }
            if (motion.exportFrames(1).isNotEmpty()) {
                loaded.params.motions[state] = MotionParams.Motion(name = state, items = motion)
            }
        }
        require(loaded.params.hasState("stop")) { "Skin has no exportable idle frames" }
    }

    private fun decodeFrame(file: File, loaded: LoadedSkin): Bitmap {
        require(file.length() <= MAX_IMAGE_BYTES) { "Skin frame is too large" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth in 1..16_384 && bounds.outHeight in 1..16_384 &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS) { "Skin frame dimensions exceed export limit" }
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, 256)
        loaded.checkAllocation((bounds.outWidth + sample - 1) / sample, (bounds.outHeight + sample - 1) / sample)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "Cannot decode skin frame" }
        if (maxOf(bitmap.width, bitmap.height) <= 256) return loaded.own(bitmap)
        return try {
            val scale = 256f / maxOf(bitmap.width, bitmap.height)
            loaded.own(Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true))
        } finally { bitmap.recycle() }
    }

    internal fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        require(width > 0 && height > 0 && maxSide > 0)
        var sample = 1
        while ((maxOf(width, height).toLong() + sample - 1) / sample > maxSide) {
            if (sample > Int.MAX_VALUE / 2) return Int.MAX_VALUE
            sample *= 2
        }
        return sample
    }

    internal fun confined(root: File, relative: String): File {
        val canonicalRoot = root.canonicalFile
        val result = File(canonicalRoot, relative).canonicalFile
        require(result.path.startsWith(canonicalRoot.path + File.separator)) { "Skin file escapes package" }
        return result
    }
}
