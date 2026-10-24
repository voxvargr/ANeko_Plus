package org.nqmgaming.aneko.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.view.animation.LinearInterpolator
import androidx.annotation.NonNull
import androidx.core.content.edit
import org.nqmgaming.aneko.R
import org.nqmgaming.aneko.companion.CompanionSession
import org.nqmgaming.aneko.core.companion.PhoneCompanionController
import org.nqmgaming.aneko.core.cursor.CursorPreferences
import org.nqmgaming.aneko.core.cursor.CursorTarget
import org.nqmgaming.aneko.core.cursor.CursorTargetStore
import org.nqmgaming.aneko.core.media.MediaAreaSelector
import org.nqmgaming.aneko.core.media.MediaGeometry
import org.nqmgaming.aneko.core.media.MediaFadePolicy
import org.nqmgaming.aneko.core.media.MediaPoint
import org.nqmgaming.aneko.core.media.MediaRect
import org.nqmgaming.aneko.core.media.MediaReaction
import org.nqmgaming.aneko.core.media.MediaReactionController
import org.nqmgaming.aneko.core.media.MediaReactionKind
import org.nqmgaming.aneko.core.media.MediaReactionPreferences
import org.nqmgaming.aneko.core.media.MediaRegion
import org.nqmgaming.aneko.core.media.MediaRegionStore
import org.nqmgaming.aneko.core.motion.CodexPetMotionParams
import org.nqmgaming.aneko.core.motion.CursorChaseGeometry
import org.nqmgaming.aneko.core.motion.DragDirectionTracker
import org.nqmgaming.aneko.core.motion.MotionConfigParser
import org.nqmgaming.aneko.core.motion.MotionDrawable
import org.nqmgaming.aneko.core.motion.MotionDirectionResolver
import org.nqmgaming.aneko.core.motion.MotionParams
import org.nqmgaming.aneko.core.scroll.ScrollFlingController
import org.nqmgaming.aneko.core.scroll.ScrollFlingStep
import org.nqmgaming.aneko.core.scroll.ScrollImpulseStore
import org.nqmgaming.aneko.core.scroll.ScrollPreferences
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Overlay and detector coordinates are physical left/top pixels, regardless of UI language.
@SuppressLint("RtlHardcoded")
class AnimationService : Service() {

    companion object {
        const val ACTION_START = "org.nqmgaming.aneko.action.START"
        const val ACTION_STOP = "org.nqmgaming.aneko.action.STOP"
        const val ACTION_TOGGLE = "org.nqmgaming.aneko.action.TOGGLE"
        const val ACTION_SELECT_MEDIA = "org.nqmgaming.aneko.action.SELECT_MEDIA"
        const val ACTION_APPLY_COMPANION = "org.nqmgaming.aneko.action.APPLY_COMPANION"
        const val PREF_KEY_ENABLE = "motion.enable"
        const val PREF_KEY_VISIBLE = "motion.visible"
        const val PREF_KEY_TRANSPARENCY = "motion.transparency"
        const val PREF_KEY_FOCUS = "motion.focus"
        const val PREF_KEY_SIZE = "motion.size"
        const val PREF_KEY_SPEED = "motion.speed"
        const val PREF_KEY_BEHAVIOUR = "motion.behaviour"
        const val PREF_KEY_SKIN_COMPONENT = "motion.skin"
        const val PREF_KEY_NEKO_COUNT = "motion.neko_count"
        const val PREF_KEY_NOTIFICATION_ENABLE = "notification.enable"
        const val PREF_KEY_BOTTOM_OFFSET = "motion.bottom_offset"
        const val PREF_KEY_QUIET_MODE = "motion.quiet_mode"
        const val AVOID_KEYBOARD = 33
        private const val MSG_ANIMATE = 1
        private const val ANIMATION_INTERVAL = 125L
        private const val SKINS_DIR_NAME = "skins"
    }

    private enum class Behaviour { Closer, Further, Whimsical }

    // ================== Per-neko instance ==================
    private inner class NekoInstance(
        val skinPackageName: String,
        val motionState: MotionState,
        val imageView: ImageView,
        var imageParams: WindowManager.LayoutParams,
        val mediaController: MediaReactionController = MediaReactionController(),
        var cursorControlled: Boolean = false,
        val scrollController: ScrollFlingController = ScrollFlingController(resources.displayMetrics.density),
        var scrollControlled: Boolean = false,
    ) {
        var attentionOffsetY = 0f
        var mediaFaded = false
    }

    private data class DragUpdate(
        val positionChanged: Boolean,
        val animationChanged: Boolean,
    )

    private var isStarted = false
    private var isForeground = false
    private lateinit var prefs: SharedPreferences
    private var prefListener: PreferenceChangeListener? = null
    private val handler = Handler(Looper.getMainLooper(), ::onHandleMessage)
    private val nekos = mutableListOf<NekoInstance>()
    private val random = java.util.Random()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaJob: Job? = null
    private var cursorJob: Job? = null
    private var scrollJob: Job? = null
    private var mediaRegions = emptyList<MediaRegion>()
    private lateinit var mediaSelector: MediaAreaSelector
    private lateinit var phoneCompanion: PhoneCompanionController
    private var companionVisible = !CompanionSession.isSupported()
    private var handoffAnimator: ValueAnimator? = null
    private var handoffGeneration = 0
    private var pendingDeparture: Runnable? = null
    private var phoneInteractive = true
    private var attentionAnimator: ValueAnimator? = null
    private val attentionTick = Runnable {
        if (canSeekAttention()) playAttention()
        scheduleAttention()
    }

    private var touchView: View? = null

    override fun onCreate() {
        super.onCreate()
        isStarted = false
        prefs = getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)
        mediaSelector = MediaAreaSelector(this)
        phoneInteractive = PhoneCompanionController.isPhoneInteractive(this)
        phoneCompanion = PhoneCompanionController(this,object : CompanionSession.Host {
            override fun isReady() = isStarted && Settings.canDrawOverlays(this@AnimationService)
            override fun depart(onGone: Runnable) { departCompanions(onGone) }
            override fun arrive() { arriveCompanions() }
            override fun hide() { hideCompanions() }
            override fun onSettings(json: String, skinPack: File?) { /* Phone is the settings authority. */ }
        }) { interactive ->
            phoneInteractive = interactive
            if (!interactive) stopScrollMotion(stopTravel = false)
            if (!interactive) cancelAttention() else scheduleAttention()
            if (!interactive && pendingDeparture != null) {
                val callback = pendingDeparture
                hideCompanions()
                callback?.run()
            } else {
                nekos.forEach { neko ->
                    refreshMediaOpacity(neko)
                    neko.imageView.visibility = if (interactive && companionVisible) View.VISIBLE else View.GONE
                    if (interactive && companionVisible) updateDrawable(neko) else neko.motionState.currentDrawable().stop()
                }
                if (interactive) requestAnimate() else handler.removeMessages(MSG_ANIMATE)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Local shell diagnostics for overlay geometry/effects; no skin names or screen content. */
    protected override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.println("started=$isStarted visible=$companionVisible interactive=$phoneInteractive " +
            "fullscreenFadeEnabled=${isFullscreenFadeEnabled()}")
        nekos.forEachIndexed { index, neko ->
            val base = neko.motionState.alpha / 255f
            writer.println("pet[$index] mediaFaded=${neko.mediaFaded} baseOpacity=$base " +
                "viewAlpha=${neko.imageView.alpha} effectiveOpacity=${base * neko.imageView.alpha}")
            val center = neko.motionState.center()
            writer.println("petMotion[$index] center=${center.x},${center.y} " +
                "cursorControlled=${neko.cursorControlled} scrollControlled=${neko.scrollControlled} " +
                "scrollActive=${neko.scrollController.isActive} dragging=${neko.motionState.isDragging()}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restore the running companion after process death without replaying UI commands.
        val action = if (flags and START_FLAG_REDELIVERY != 0) ACTION_START else intent?.action
        if (action == ACTION_STOP) {
            isStarted = false
            stopAnimation()
            setForegroundNotification(false)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Timber.w("Stopping companion service because overlay permission is unavailable")
            stopFailedStart()
            return START_NOT_STICKY
        }
        if (action == ACTION_TOGGLE) toggleAnimation()

        when {
            !isStarted && (action == null || ACTION_START == action ||
                ACTION_SELECT_MEDIA == action || ACTION_APPLY_COMPANION == action ||
                ACTION_TOGGLE == action) -> {
                if (!checkPrefEnable() || !setForegroundNotification(true)) {
                    stopFailedStart()
                    return START_NOT_STICKY
                }
                try {
                    // Foreground promotion must succeed before any overlay or live link is created.
                    if (!startAnimation()) {
                        stopFailedStart()
                        return START_NOT_STICKY
                    }
                    isStarted = true
                    phoneCompanion.start()
                } catch (error: SecurityException) {
                    Timber.w(error, "Companion startup lost a required permission")
                    stopFailedStart()
                    return START_NOT_STICKY
                } catch (error: WindowManager.BadTokenException) {
                    Timber.w(error, "Companion overlay could not be attached")
                    stopFailedStart()
                    return START_NOT_STICKY
                } catch (error: IllegalStateException) {
                    Timber.w(error, "Companion startup is not allowed in the current state")
                    stopFailedStart()
                    return START_NOT_STICKY
                }
            }
        }
        if (action == ACTION_SELECT_MEDIA && isStarted && hasMediaFeatureEnabled()) {
            mediaSelector.showLauncher()
        }
        if (action == ACTION_APPLY_COMPANION && isStarted) phoneCompanion.applyMode()
        if (!isStarted) stopSelfResult(startId)
        return if (isStarted) START_REDELIVER_INTENT else START_NOT_STICKY
    }

    override fun onDestroy() {
        isStarted = false
        phoneCompanion.close()
        handoffGeneration++
        handoffAnimator?.cancel()
        stopAnimation()
        stopForegroundSafely()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        stopScrollMotion()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val display: Display = wm.defaultDisplay
        val size = Point().also { display.getSize(it) }
        val offsetPct = if (prefs.getBoolean(PREF_KEY_BOTTOM_OFFSET, false)) AVOID_KEYBOARD else 0
        nekos.forEach { it.motionState.setDisplaySize(size.x, size.y, offsetPct) }
        MediaRegionStore.setManual(null)
        nekos.forEach { it.mediaController.reset() }
        requestAnimate()
    }

    private fun startAnimation(): Boolean {
        if (!checkPrefEnable()) return false

        prefListener = PreferenceChangeListener().also {
            prefs.registerOnSharedPreferenceChangeListener(it)
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // Shared touch overlay (1x1 pixel)
        touchView = View(this).apply {
            setOnTouchListener(TouchListener())
        }
        val touchParams = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        wm.addView(touchView, touchParams)

        syncNekosFromPreferences()
        mediaJob = serviceScope.launch {
            MediaRegionStore.regions.collect { regions ->
                mediaRegions = regions
                refreshMediaOpacity()
                if (hasMediaFeatureEnabled()) requestAnimate()
            }
        }
        cursorJob = serviceScope.launch {
            CursorTargetStore.targets.collect {
                if (activeCursorTarget() != null) cancelAttention()
                else {
                    stopCursorChase()
                    scheduleAttention()
                }
                if (isCursorChaseEnabled()) requestAnimate()
            }
        }
        scrollJob = serviceScope.launch {
            ScrollImpulseStore.events.collect { impulse ->
                if (impulse == null) {
                    stopScrollMotion()
                    scheduleAttention()
                    requestAnimate()
                    return@collect
                }
                if (!canReactToScroll()) return@collect
                val origin = IntArray(2).also { touchView?.getLocationOnScreen(it) }
                val local = impulse.copy(bounds = impulse.bounds.let {
                    MediaRect(it.left - origin[0], it.top - origin[1], it.right - origin[0], it.bottom - origin[1])
                })
                val viewport = mediaViewport()
                var accepted = false
                nekos.forEach { neko ->
                    if (!neko.motionState.isDragging() && neko.scrollController.onScroll(local,
                        neko.motionState.center(), neko.imageParams.width / 2f, neko.imageParams.height / 2f,
                        viewport, SystemClock.uptimeMillis())) accepted = true
                }
                if (accepted) {
                    cancelAttention()
                    requestAnimate()
                }
            }
        }
        return true
    }

    private fun stopAnimation() {
        cancelAttention()
        mediaJob?.cancel()
        mediaJob = null
        cursorJob?.cancel()
        cursorJob = null
        scrollJob?.cancel()
        scrollJob = null
        stopScrollMotion(stopTravel = false)
        mediaSelector.dismiss()
        prefListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        prefListener = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        touchView?.let { runCatching { wm.removeView(it) } }
        touchView = null

        nekos.forEach { neko -> removeNekoView(wm, neko) }
        nekos.clear()

        handler.removeMessages(MSG_ANIMATE)
        MediaRegionStore.setManual(null)
    }

    private fun toggleAnimation() {
        val visible = prefs.getBoolean(PREF_KEY_VISIBLE, true)
        prefs.edit { putBoolean(PREF_KEY_VISIBLE, !visible) }
    }

    private fun stopFailedStart() {
        isStarted = false
        stopAnimation()
        stopForegroundSafely()
        stopSelf()
    }

    private fun stopForegroundSafely() {
        if (!isForeground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
    }

    private fun setForegroundNotification(start: Boolean): Boolean = try {
        if (!start) stopForegroundSafely()
        val intent = PendingIntent.getService(
            this, 0,
            Intent(this, AnimationService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE
        )

        val channel = NotificationChannel(
            getString(R.string.app_name),
            getString(R.string.aneko_notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notification_chanel_description)
        }
        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
            ?.createNotificationChannel(channel)

        val builder = Notification.Builder(this, getString(R.string.app_name))
            .setContentIntent(intent)
            .setSmallIcon(if (start) R.drawable.right2 else R.drawable.sleep2)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (start) R.string.notification_enabled else R.string.notification_disabled))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)

        val notify = builder.build()
        if (start) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notify, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notify)
            }
            isForeground = true
        } else if (prefs.getBoolean(PREF_KEY_ENABLE, true)) {
            (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.notify(1, notify)
        }
        true
    } catch (error: SecurityException) {
        Timber.w(error, "Companion foreground notification lacks a required permission")
        false
    } catch (error: IllegalStateException) {
        // Includes ForegroundServiceStartNotAllowedException on Android 12 and later.
        Timber.w(error, "Companion foreground promotion is not allowed from the current state")
        false
    }

    // ================== Load Motion ==================

    /**
     * Get skin path for a specific slot, falling back to global skin pref.
     */
    private fun getSkinForSlot(index: Int): String {
        val slotSkin = prefs.getString("motion.skin.$index", null)
        if (!slotSkin.isNullOrBlank()) return slotSkin
        return prefs.getString(PREF_KEY_SKIN_COMPONENT, "org.nqmgaming.aneko")
            ?: "org.nqmgaming.aneko"
    }

    private fun desiredSkinPackages(): List<String> {
        val nekoCount = prefs.getString(PREF_KEY_NEKO_COUNT, "1")
            ?.toFloatOrNull()?.toInt()?.coerceIn(1, 6) ?: 1
        return List(nekoCount, ::getSkinForSlot)
    }

    private fun syncNekosFromPreferences() {
        if (handoffAnimator != null) {
            handler.postDelayed({ if (isStarted) syncNekosFromPreferences() },750)
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val reconciliation = reconcilePetInstances(
            existing = nekos,
            desiredKeys = desiredSkinPackages(),
            keyOf = NekoInstance::skinPackageName,
            create = ::createNekoInstance,
        )
        reconciliation.removed.forEach { neko -> removeNekoView(wm, neko) }
        nekos.clear()
        nekos.addAll(reconciliation.instances)
        if (!isQuietModeEnabled() || isMediaReactionsEnabled()) requestAnimate()
    }

    private fun createNekoInstance(skinPkg: String): NekoInstance? {
        val ms = loadMotionState(skinPkg) ?: return null
        ms.setOverlaySize(getSizeForSkin(skinPkg), getSizeForSkin(skinPkg))
        refreshMotionSpeedFor(ms, skinPkg)

        val alphaStr = prefs.getString("motion.transparency.$skinPkg", null)
            ?: prefs.getString(PREF_KEY_TRANSPARENCY, "0.0") ?: "0.0"
        val opacity = 1f - (alphaStr.toFloatOrNull() ?: 0f)
        ms.alpha = (opacity * 0xff).roundToInt()

        val iv = ImageView(this).apply {
            visibility = if (companionVisible && phoneInteractive) View.VISIBLE else View.GONE
            setOnClickListener {
                if (!isQuietModeEnabled()) moveToRandomTarget(ms)
            }
        }
        val focusFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val unfocusFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val isTouchable = prefs.getBoolean(PREF_KEY_FOCUS, false) || isQuietModeEnabled() || isMediaReactionsEnabled() ||
            isCursorChaseEnabled() || isScrollReactionsEnabled()
        val params = WindowManager.LayoutParams(
            getSizeForSkin(skinPkg),
            getSizeForSkin(skinPkg),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (isTouchable) focusFlag else unfocusFlag,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(iv, params)

        return NekoInstance(skinPkg, ms, iv, params).also { neko ->
            iv.setOnTouchListener(PetDragTouchListener(neko))
            updateDrawable(neko)
            updatePosition(neko)
        }
    }

    private fun removeNekoView(wm: WindowManager, neko: NekoInstance) {
        try {
            wm.removeView(neko.imageView)
        } catch (_: Exception) {
        }
    }

    /** Creates a new MotionState with a random spawn position. */
    private fun loadMotionState(skinPath: String): MotionState? {
        if (skinPath.isBlank()) return null
        return try {
            val params = getMotionParamsInternal(skinPath)
            val ms = MotionState().apply { setParams(params) }
            initMotionState(ms)
            ms
        } catch (e: Exception) {
            Timber.e(e)
            null
        }
    }

    /**
     * Đọc từ INTERNAL: files/skins/<folder>/skin.xml (+ ảnh)
     * Giá trị prefs "motion.skin" có thể là "<folder>/skin.xml" hoặc "<folder>"
     */
    @NonNull
    @Throws(PackageManager.NameNotFoundException::class)
    private fun getMotionParamsInternal(skinPath: String): MotionParams {
        // Root: {filesDir}/skins
        val skinsRoot = File(filesDir, SKINS_DIR_NAME).apply { if (!exists()) mkdirs() }

        var folder = ""
        var xmlFile = "skin.xml"

        if (skinPath.isNotBlank()) {
            val parts = skinPath.split('/')
            if (parts.size >= 2) {
                folder = parts.dropLast(1).joinToString(File.separator)
                xmlFile = parts.last()
            } else {
                if (skinPath.lowercase().endsWith(".xml")) {
                    xmlFile = parts[0]
                    folder = ""
                } else {
                    folder = parts[0]
                    xmlFile = "skin.xml"
                }
            }
        }

        val dir = if (folder.isBlank()) skinsRoot else File(skinsRoot, folder)
        val codexManifest = File(dir, "pet.json")
        if (codexManifest.isFile) {
            return CodexPetMotionParams(resources, codexManifest)
        }
        val skinXml = File(dir, xmlFile).let { f ->
            if (!f.exists()) {
                dir.listFiles { _, name -> name.lowercase().endsWith(".xml") }?.firstOrNull()
                    ?: throw IllegalArgumentException("skin.xml not found in ${dir.absolutePath}")
            } else f
        }

        return MotionConfigParser.parseFromFile(this, skinXml, dir)
    }

    /**
     * Initialize a MotionState with random edge spawn + global prefs.
     */
    private fun initMotionState(ms: MotionState) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d: Display = wm.defaultDisplay
        val size = Point().also { d.getSize(it) }
        val dw = size.x
        val dh = size.y
        val offsetPct = if (prefs.getBoolean(PREF_KEY_BOTTOM_OFFSET, false)) AVOID_KEYBOARD else 0

        ms.setDisplaySize(dw, dh, offsetPct)
        val effectiveDh = ms.effectiveDisplayHeight

        val (cx, cy) = when (random.nextInt(4)) {
            0 -> 0 to random.nextInt(effectiveDh)
            1 -> dw to random.nextInt(effectiveDh)
            2 -> random.nextInt(dw) to 0
            else -> random.nextInt(dw) to effectiveDh
        }

        // Transparency is set later per-skin in startAnimation
        val alphaStr = prefs.getString(PREF_KEY_TRANSPARENCY, "0.0") ?: "0.0"
        val opacity = 1f - (alphaStr.toFloatOrNull() ?: 0f)
        ms.alpha = (opacity * 0xff).roundToInt()

        ms.setBehaviour(
            Behaviour.valueOf(
                prefs.getString(PREF_KEY_BEHAVIOUR, ms.behaviour.toString())!!
            )
        )

        ms.setCurrentPosition(cx.toFloat(), cy.toFloat())
        // Give each neko a unique random target so they spread out
        ms.setTargetPositionDirect(
            random.nextInt(dw).toFloat(),
            random.nextInt(effectiveDh).toFloat()
        )
        ms.setQuietMode(prefs.getBoolean(PREF_KEY_QUIET_MODE, false))
        refreshMotionSize()
    }

    /**
     * Get the size for a specific skin, falling back to global size.
     */
    private fun getSizeForSkin(skinPkg: String): Int {
        val perSkin = prefs.getString("motion.size.$skinPkg", null)
        if (!perSkin.isNullOrBlank()) {
            return perSkin.toFloatOrNull()?.toInt() ?: 80
        }
        return prefs.getString(PREF_KEY_SIZE, "80")?.toFloatOrNull()?.toInt() ?: 80
    }

    // ================== UI refresh ==================

    private fun refreshMotionSize() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        nekos.forEach { neko ->
            val v = getSizeForSkin(neko.skinPackageName)
            neko.imageParams.width = v
            neko.imageParams.height = v
            neko.motionState.setOverlaySize(v, v)
            neko.mediaController.reset()
            try {
                wm.updateViewLayout(neko.imageView, neko.imageParams)
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshMotionSpeed() {
        nekos.forEach { neko ->
            val speedFactor = getSpeedForSkin(neko.skinPackageName)
            neko.motionState.setSpeedFactor(if (speedFactor > 0f) speedFactor else 1.0f)
        }
    }

    private fun refreshMotionSpeedFor(ms: MotionState, skinPkg: String) {
        val speedFactor = getSpeedForSkin(skinPkg)
        ms.setSpeedFactor(if (speedFactor > 0f) speedFactor else 1.0f)
    }

    private fun getSpeedForSkin(skinPkg: String): Float {
        val perSkin = prefs.getString("motion.speed.$skinPkg", null)
        if (!perSkin.isNullOrBlank()) {
            return perSkin.toFloatOrNull() ?: 1.0f
        }
        return prefs.getString(PREF_KEY_SPEED, "1.0")?.toFloatOrNull() ?: 1.0f
    }

    private fun refreshMotionTransparency() {
        nekos.forEach { neko ->
            val alphaStr = prefs.getString("motion.transparency.${neko.skinPackageName}", null)
                ?: prefs.getString(PREF_KEY_TRANSPARENCY, "0.0") ?: "0.0"
            val opacity = 1f - (alphaStr.toFloatOrNull() ?: 0f)
            neko.motionState.alpha = (opacity * 0xff).roundToInt()
            neko.imageView.drawable?.alpha = neko.motionState.alpha
            refreshMediaOpacity(neko)
        }
    }

    private fun isQuietModeEnabled(): Boolean =
        prefs.getBoolean(PREF_KEY_QUIET_MODE, false)

    private fun isMediaReactionsEnabled(): Boolean =
        prefs.getBoolean(MediaReactionPreferences.ENABLED, false)

    private fun isFullscreenFadeEnabled(): Boolean =
        prefs.getBoolean(MediaReactionPreferences.FULLSCREEN_FADE_ENABLED, false)

    private fun hasMediaFeatureEnabled(): Boolean = isMediaReactionsEnabled() || isFullscreenFadeEnabled()

    private fun refreshMediaOpacity() {
        val viewport = mediaViewport()
        val regions = localMediaRegions(viewport)
        nekos.forEach { refreshMediaOpacity(it, viewport, regions) }
    }

    /** Temporary view opacity never changes saved appearance or the watch's settings snapshot. */
    private fun refreshMediaOpacity(
        neko: NekoInstance,
        viewport: MediaRect = mediaViewport(),
        regions: List<MediaRegion> = localMediaRegions(viewport),
    ) {
        val fade = phoneInteractive && MediaFadePolicy.shouldFade(isFullscreenFadeEnabled(),
            neko.motionState.center(), neko.imageParams.width / 2f, neko.imageParams.height / 2f,
            regions, viewport, 8f * resources.displayMetrics.density)
        val base = (neko.motionState.alpha / 255f).coerceIn(0f, 1f)
        val cap = runCatching {
            prefs.getFloat(MediaReactionPreferences.FULLSCREEN_FADE_OPACITY, 0.10f)
        }.getOrDefault(0.10f)
        val effective = MediaFadePolicy.capAlpha(base, fade, cap)
        neko.imageView.alpha = if (base > 0f) (effective / base).coerceIn(0f, 1f) else 1f
        neko.mediaFaded = fade
    }

    private fun isCursorChaseEnabled(): Boolean = prefs.getBoolean(CursorPreferences.ENABLED, false)

    private fun isScrollReactionsEnabled(): Boolean = prefs.getBoolean(ScrollPreferences.ENABLED, false)

    private fun canReactToScroll(): Boolean = isScrollReactionsEnabled() &&
        !isQuietModeEnabled() && companionVisible && phoneInteractive && handoffAnimator == null

    private fun stopScrollMotion(stopTravel: Boolean = true) {
        nekos.forEach { neko ->
            neko.scrollController.cancel()
            neko.motionState.setScrollStep(null)
            if (neko.scrollControlled && stopTravel && handoffAnimator == null && !neko.motionState.isDragging()) {
                neko.motionState.forceStop()
                if (companionVisible && phoneInteractive) updateDrawable(neko)
                else neko.motionState.currentDrawable().stop()
            }
            neko.scrollControlled = false
        }
    }

    private fun activeCursorTarget(): CursorTarget? {
        if (!isCursorChaseEnabled() || isQuietModeEnabled() || !companionVisible ||
            !phoneInteractive || handoffAnimator != null) return null
        return CursorTargetStore.targets.value?.takeIf {
            val age = SystemClock.uptimeMillis() - it.observedAtUptimeMs
            age >= 0 && age < CursorTargetStore.MAX_AGE_MS
        }
    }

    private fun stopCursorChase() {
        nekos.filter { it.cursorControlled }.forEach { neko ->
            neko.cursorControlled = false
            if (handoffAnimator == null) {
                neko.motionState.setMediaReaction(null, false)
                if (!neko.motionState.isDragging()) {
                    neko.motionState.forceStop()
                    if (companionVisible && phoneInteractive) updateDrawable(neko)
                    else neko.motionState.currentDrawable().stop()
                }
            }
        }
    }

    private fun moveToRandomTarget(ms: MotionState) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val displaySize = Point().also { wm.defaultDisplay.getSize(it) }
        val dw = displaySize.x
        val effectiveDh = ms.effectiveDisplayHeight
        val (x, y) = if (random.nextFloat() < 0.4f) {
            when (random.nextInt(4)) {
                0 -> 0f to random.nextInt(effectiveDh).toFloat()
                1 -> dw.toFloat() to random.nextInt(effectiveDh).toFloat()
                2 -> random.nextInt(dw).toFloat() to 0f
                else -> random.nextInt(dw).toFloat() to effectiveDh.toFloat()
            }
        } else {
            random.nextInt(dw).toFloat() to random.nextInt(effectiveDh).toFloat()
        }
        ms.setTargetPosition(x, y)
        requestAnimate()
    }

    private fun refreshPetTouchability() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val touchableFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val passthroughFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val isTouchable = prefs.getBoolean(PREF_KEY_FOCUS, false) || isQuietModeEnabled() || isMediaReactionsEnabled() ||
            isCursorChaseEnabled() || isScrollReactionsEnabled()
        nekos.forEach { neko ->
            neko.imageParams.flags = if (isTouchable) touchableFlag else passthroughFlags
            try {
                wm.updateViewLayout(neko.imageView, neko.imageParams)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update pet touchability")
            }
        }
    }

    private fun updateQuietMode(enabled: Boolean) {
        nekos.forEach { neko ->
            neko.motionState.setQuietMode(enabled)
            if (!enabled) moveToRandomTarget(neko.motionState)
            updateDrawable(neko)
            updatePosition(neko)
        }
        refreshPetTouchability()
        if (!enabled || hasMediaFeatureEnabled()) requestAnimate()
    }

    private fun requestAnimate() {
        if (!companionVisible || !phoneInteractive || handoffAnimator != null) return
        if (!handler.hasMessages(MSG_ANIMATE)) {
            handler.sendEmptyMessage(MSG_ANIMATE)
        }
    }

    private fun updateDrawable(neko: NekoInstance) {
        val drawable = neko.motionState.currentDrawable()
        drawable.alpha = neko.motionState.alpha
        neko.imageView.setImageDrawable(drawable)
        refreshMediaOpacity(neko)
        drawable.stop()
        drawable.start()
    }

    private fun updatePosition(neko: NekoInstance) {
        try {
            val pos = neko.motionState.position()
            neko.imageParams.x = pos.x
            neko.imageParams.y = pos.y + neko.attentionOffsetY.roundToInt()
            (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(
                neko.imageView,
                neko.imageParams
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to update position")
        }
    }

    private fun updateToNext(neko: NekoInstance) {
        val ms = neko.motionState
        if (ms.isMediaControlled()) {
            updateDrawable(neko)
            requestAnimate()
            return
        }
        if (ms.checkWall() || ms.updateMovingState() || ms.changeToNextState()) {
            updateDrawable(neko)
            updatePosition(neko)
            requestAnimate()
        }
    }

    private fun onHandleMessage(msg: Message): Boolean {
        if (msg.what == MSG_ANIMATE) {
            handler.removeMessages(MSG_ANIMATE)
            if (!companionVisible || !phoneInteractive || handoffAnimator != null) return true
            var anyActive = false
            val viewport = mediaViewport()
            val observedRegions = localMediaRegions(viewport)
            val regions = if (isMediaReactionsEnabled()) observedRegions.filter {
                it.escapeAllowed && !it.fullscreen
            } else emptyList()
            val cursor = activeCursorTarget()
            val origin = IntArray(2).also { touchView?.getLocationOnScreen(it) }
            nekos.forEach { neko ->
                val ms = neko.motionState
                refreshMediaOpacity(neko, viewport, observedRegions)
                val mediaReaction = if (isMediaReactionsEnabled()) neko.mediaController.update(
                    center = ms.center(), halfWidth = neko.imageParams.width / 2f,
                    halfHeight = neko.imageParams.height / 2f, regions = regions,
                    viewport = viewport, margin = 8 * resources.displayMetrics.density,
                    allowApproach = !isQuietModeEnabled(), dragging = ms.isDragging(),
                ) else { neko.mediaController.reset(); null }
                val blocked = !ms.isDragging() && regions.isNotEmpty() && mediaReaction == null &&
                    MediaGeometry.overlaps(ms.center(), neko.imageParams.width / 2f, neko.imageParams.height / 2f, regions)
                val scroll = if (canReactToScroll() && !ms.isDragging() && !blocked &&
                    mediaReaction?.kind != MediaReactionKind.ESCAPE) {
                    neko.scrollController.step(ms.center(), neko.imageParams.width / 2f,
                        neko.imageParams.height / 2f, viewport, SystemClock.uptimeMillis())?.takeIf { step ->
                        val safe = !MediaGeometry.overlaps(step.target, neko.imageParams.width / 2f,
                            neko.imageParams.height / 2f, regions) && CursorChaseGeometry.pathAvoidsMedia(ms.center(),
                            step.target, neko.imageParams.width / 2f, neko.imageParams.height / 2f, regions)
                        if (!safe) neko.scrollController.cancel()
                        safe
                    }
                } else { neko.scrollController.cancel(); null }
                val cursorReaction = if (cursor != null && scroll == null && !ms.isDragging() && !blocked &&
                    mediaReaction?.kind != MediaReactionKind.ESCAPE) {
                    val caret = MediaPoint(cursor.caretX - origin[0], cursor.caretY - origin[1])
                    val field = cursor.fieldBounds.let { MediaRect(it.left - origin[0], it.top - origin[1],
                        it.right - origin[0], it.bottom - origin[1]) }
                    CursorChaseGeometry.target(ms.center(), caret, field, neko.imageParams.width / 2f,
                        neko.imageParams.height / 2f, viewport, 16 * resources.displayMetrics.density, regions)?.let { point ->
                        MediaReaction(if (hypot(point.x - ms.center().x, point.y - ms.center().y) <= 1f)
                            MediaReactionKind.WATCH else MediaReactionKind.APPROACH, point, caret)
                    }
                } else null
                val reaction = if (mediaReaction?.kind == MediaReactionKind.ESCAPE) mediaReaction
                    else if (scroll != null) null else cursorReaction ?: mediaReaction
                neko.cursorControlled = cursorReaction != null
                neko.scrollControlled = scroll != null
                ms.setScrollStep(scroll)
                ms.setMediaReaction(reaction, blocked)
                neko.motionState.updateState()
                if (neko.motionState.isStateChanged() || neko.motionState.isPositionMoved()) {
                    if (neko.motionState.isStateChanged()) updateDrawable(neko)
                    updatePosition(neko)
                    anyActive = true
                }
            }
            if (anyActive || (hasMediaFeatureEnabled() && observedRegions.isNotEmpty()) || cursor != null ||
                nekos.any { it.scrollController.isActive || it.scrollControlled }) {
                handler.sendEmptyMessageDelayed(MSG_ANIMATE, ANIMATION_INTERVAL)
            }
            return true
        }
        return false
    }

    /** The tiny top/start overlay gives the same screen origin as the pet windows. */
    private fun mediaViewport(): MediaRect {
        val frame = Rect()
        touchView?.getWindowVisibleDisplayFrame(frame)
        val origin = IntArray(2)
        touchView?.getLocationOnScreen(origin)
        val size = Point().also { (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getSize(it) }
        val width = if (frame.width() > 0) frame.right - origin[0] else size.x
        val height = if (frame.height() > 0) frame.bottom - origin[1] else size.y
        val usableHeight = if (prefs.getBoolean(PREF_KEY_BOTTOM_OFFSET, false))
            min(height, size.y * (100 - AVOID_KEYBOARD) / 100) else height
        return MediaRect(0f, 0f, width.coerceAtLeast(1).toFloat(), usableHeight.coerceAtLeast(1).toFloat())
    }

    private fun hideCompanions() {
        cancelAttention()
        stopScrollMotion(stopTravel = false)
        handoffGeneration++
        handoffAnimator?.cancel()
        handoffAnimator = null
        pendingDeparture = null
        companionVisible = false
        handler.removeMessages(MSG_ANIMATE)
        nekos.forEach { neko ->
            neko.cursorControlled = false
            neko.imageView.visibility = View.GONE
            neko.motionState.currentDrawable().stop()
            neko.imageParams.flags = neko.imageParams.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
        }
    }

    private fun departCompanions(onGone: Runnable) {
        cancelAttention()
        stopScrollMotion(stopTravel = false)
        if (!companionVisible || !phoneInteractive || nekos.isEmpty()) {
            hideCompanions()
            onGone.run()
            return
        }
        handoffGeneration++
        handoffAnimator?.cancel()
        val generation = handoffGeneration
        pendingDeparture = onGone
        mediaSelector.dismiss()
        handler.removeMessages(MSG_ANIMATE)
        val viewport = mediaViewport()
        val starts = nekos.map { it.motionState.center() }
        val targets = nekos.mapIndexed { index, neko ->
            val point = starts[index]
            val half = neko.imageParams.width / 2f
            listOf(MediaPoint(-half-8,point.y),MediaPoint(viewport.right+half+8,point.y),
                MediaPoint(point.x,-half-8),MediaPoint(point.x,viewport.bottom+half+8))
                .minBy { hypot(it.x-point.x,it.y-point.y) }
        }
        nekos.forEachIndexed { index, neko ->
            neko.cursorControlled = false
            neko.imageParams.flags = neko.imageParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            neko.motionState.prepareTravel(targets[index].x-starts[index].x,targets[index].y-starts[index].y)
            updateDrawable(neko)
        }
        val animator = ValueAnimator.ofFloat(0f,1f).apply {
            duration = 650
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                nekos.forEachIndexed { index, neko ->
                    if (index < starts.size) {
                        neko.motionState.setCurrentPosition(starts[index].x+(targets[index].x-starts[index].x)*progress,
                            starts[index].y+(targets[index].y-starts[index].y)*progress)
                        updatePosition(neko)
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != handoffGeneration) return
                    hideCompanions()
                    onGone.run()
                }
            })
        }
        handoffAnimator = animator
        animator.start()
    }

    private fun arriveCompanions() {
        stopScrollMotion(stopTravel = false)
        handoffGeneration++
        handoffAnimator?.cancel()
        handoffAnimator = null
        pendingDeparture = null
        companionVisible = true
        val viewport = mediaViewport()
        nekos.forEachIndexed { index, neko ->
            neko.cursorControlled = false
            neko.mediaController.reset()
            val half = neko.imageParams.width/2f
            val y = (viewport.height * (index+1)/(nekos.size+1)).coerceIn(half, maxOf(half,viewport.bottom-half))
            neko.motionState.setCurrentPosition(half+8*resources.displayMetrics.density,y)
            neko.motionState.forceStop()
            neko.imageParams.flags = neko.imageParams.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
            neko.imageView.visibility = if (phoneInteractive) View.VISIBLE else View.GONE
            updateDrawable(neko)
            updatePosition(neko)
        }
        refreshPetTouchability()
        requestAnimate()
        scheduleAttention()
    }

    private fun canSeekAttention(): Boolean = companionVisible && phoneInteractive &&
        handoffAnimator == null && attentionAnimator == null && !isQuietModeEnabled() &&
        activeCursorTarget() == null &&
        nekos.none { it.scrollController.isActive } &&
        prefs.getBoolean("motion.attention.enabled",false) && nekos.isNotEmpty() &&
        nekos.none { it.motionState.isDragging() }

    private fun scheduleAttention() {
        handler.removeCallbacks(attentionTick)
        if (companionVisible && phoneInteractive && prefs.getBoolean("motion.attention.enabled",false)) {
            handler.postDelayed(attentionTick, (8*60_000L)+random.nextInt(7*60_000))
        }
    }

    private fun cancelAttention() {
        handler.removeCallbacks(attentionTick)
        attentionAnimator?.cancel()
        attentionAnimator = null
        nekos.forEach { neko ->
            neko.attentionOffsetY = 0f
            neko.imageView.scaleY = 1f
            if (neko.imageView.isAttachedToWindow) updatePosition(neko)
        }
    }

    private fun playAttention() {
        val neko = nekos.random()
        val hop = random.nextBoolean()
        var cancelled = false
        val animator = ValueAnimator.ofFloat(0f,1f).apply {
            duration = 480
            interpolator = LinearInterpolator()
            addUpdateListener {
                val pulse = sin((it.animatedValue as Float)*PI).toFloat()
                if (hop) neko.attentionOffsetY = -pulse*12*resources.displayMetrics.density
                else neko.imageView.scaleY = 1f-pulse*0.12f
                updatePosition(neko)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    attentionAnimator = null
                    neko.attentionOffsetY = 0f
                    neko.imageView.scaleY = 1f
                    if (!cancelled && canSeekAttention() && prefs.getBoolean("motion.attention.haptics",false) &&
                        Settings.System.getInt(contentResolver,Settings.System.HAPTIC_FEEDBACK_ENABLED,1) != 0) {
                        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                        if (vibrator.hasVibrator()) vibrator.vibrate(if (Build.VERSION.SDK_INT >= 29)
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                        else VibrationEffect.createOneShot(10,VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
            })
        }
        attentionAnimator = animator
        animator.start()
    }

    private fun localMediaRegions(viewport: MediaRect): List<MediaRegion> {
        if (!hasMediaFeatureEnabled()) return emptyList()
        val origin = IntArray(2)
        touchView?.getLocationOnScreen(origin)
        return mediaRegions.mapNotNull { region ->
            val bounds = MediaRect(region.bounds.left-origin[0], region.bounds.top-origin[1],
                region.bounds.right-origin[0], region.bounds.bottom-origin[1])
            bounds.intersection(viewport)?.let { region.copy(bounds = it) }
        }
    }

    private fun checkPrefEnable(): Boolean {
        val enable = prefs.getBoolean(PREF_KEY_ENABLE, false)
        val visible = prefs.getBoolean(PREF_KEY_VISIBLE, false)
        if (!enable || !visible) {
            if (isStarted) {
                isStarted = false
                stopAnimation()
                setForegroundNotification(false)
                stopSelf()
            }
            return false
        }
        return true
    }

    // ================== Listeners ==================

    private inner class PreferenceChangeListener :
        SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            when {
                key == PREF_KEY_ENABLE || key == PREF_KEY_VISIBLE -> checkPrefEnable()
                key == PREF_KEY_SIZE || (key != null && key.startsWith("motion.size.")) -> refreshMotionSize()
                key == PREF_KEY_SPEED || (key != null && key.startsWith("motion.speed.")) -> refreshMotionSpeed()
                key == PREF_KEY_TRANSPARENCY || (key != null && key.startsWith("motion.transparency.")) -> {
                    refreshMotionTransparency()
                }

                key == PREF_KEY_QUIET_MODE -> {
                    cancelAttention()
                    stopCursorChase()
                    stopScrollMotion()
                    updateQuietMode(prefs.getBoolean(PREF_KEY_QUIET_MODE, false))
                    scheduleAttention()
                }

                key?.startsWith("motion.attention.") == true -> {
                    cancelAttention()
                    scheduleAttention()
                }

                key == MediaReactionPreferences.ENABLED -> {
                    nekos.forEach { it.mediaController.reset() }
                    if (!hasMediaFeatureEnabled()) {
                        mediaSelector.dismiss()
                        MediaRegionStore.clearAll()
                    }
                    refreshMediaOpacity()
                    refreshPetTouchability()
                    requestAnimate()
                }

                key == MediaReactionPreferences.FULLSCREEN_FADE_ENABLED ||
                    key == MediaReactionPreferences.FULLSCREEN_FADE_OPACITY -> {
                    if (!hasMediaFeatureEnabled()) {
                        mediaSelector.dismiss()
                        MediaRegionStore.clearAll()
                    }
                    refreshMediaOpacity()
                    requestAnimate()
                }

                key == CursorPreferences.ENABLED -> {
                    stopCursorChase()
                    if (activeCursorTarget() != null) cancelAttention() else scheduleAttention()
                    refreshPetTouchability()
                    requestAnimate()
                }

                key == ScrollPreferences.ENABLED -> {
                    stopScrollMotion()
                    scheduleAttention()
                    refreshPetTouchability()
                    requestAnimate()
                }

                key == PREF_KEY_FOCUS -> {
                    refreshPetTouchability()
                }

                key == PREF_KEY_NEKO_COUNT || key == PREF_KEY_SKIN_COMPONENT ||
                    (key != null && key.startsWith("motion.skin.")) -> {
                    if (isStarted) syncNekosFromPreferences()
                }

                key == PREF_KEY_BOTTOM_OFFSET -> {
                    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                    val display: Display = wm.defaultDisplay
                    val size = Point().also { display.getSize(it) }
                    val offsetPct =
                        if (prefs.getBoolean(PREF_KEY_BOTTOM_OFFSET, false)) AVOID_KEYBOARD else 0
                    nekos.forEach { it.motionState.setDisplaySize(size.x, size.y, offsetPct) }
                }

                else -> {
                    // Skin or other change — reload all nekos
                    if (isStarted) {
                        stopAnimation()
                        startAnimation()
                    }
                }
            }
        }
    }

    private inner class TouchListener : View.OnTouchListener {
        override fun onTouch(v: View?, ev: MotionEvent): Boolean {
            if (nekos.isEmpty()) return false
            if (isQuietModeEnabled()) return false
            if (ev.action == MotionEvent.ACTION_UP) {
                v?.performClick()
                return true
            }
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                nekos.forEach { neko ->
                    moveToRandomTarget(neko.motionState)
                }
                return true
            } else if (ev.action == MotionEvent.ACTION_CANCEL) {
                nekos.forEach { neko ->
                    neko.motionState.forceStop()
                    updateDrawable(neko)
                    updatePosition(neko)
                }
                return true
            }
            return false
        }
    }

    private inner class PetDragTouchListener(
        private val neko: NekoInstance,
    ) : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(this@AnimationService).scaledTouchSlop
        private val activationThreshold = (touchSlop / 2f).coerceAtLeast(2f)
        private val directionChangeThreshold = touchSlop.toFloat()
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragged = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (handoffAnimator != null || !companionVisible) return true
            if (!isQuietModeEnabled() && !isMediaReactionsEnabled() && !isCursorChaseEnabled() && !isScrollReactionsEnabled()) return false
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelAttention()
                    neko.cursorControlled = false
                    neko.scrollController.cancel()
                    neko.scrollControlled = false
                    neko.motionState.setScrollStep(null)
                    neko.mediaController.reset()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragged = false
                    if (neko.motionState.beginDrag(event.rawX, event.rawY)) {
                        updateDrawable(neko)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragged) {
                        dragged = hypot(event.rawX - downRawX, event.rawY - downRawY) >= touchSlop
                    }
                    val update = neko.motionState.dragTo(
                        rawX = event.rawX,
                        rawY = event.rawY,
                        activationThreshold = activationThreshold,
                        directionChangeThreshold = directionChangeThreshold,
                    )
                    if (update.animationChanged) updateDrawable(neko)
                    if (update.positionChanged) updatePosition(neko)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    neko.motionState.endDrag()
                    updateDrawable(neko)
                    updatePosition(neko)
                    if (!dragged) {
                        view.performClick()
                    }
                    requestAnimate()
                    scheduleAttention()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    neko.motionState.endDrag()
                    updateDrawable(neko)
                    requestAnimate()
                    true
                }

                else -> true
            }
        }
    }

    private inner class MotionEndListener : MotionDrawable.OnMotionEndListener {
        override fun onMotionEnd(drawable: MotionDrawable) {
            if (!isStarted) return
            val neko = nekos.find { it.motionState.currentDrawable() === drawable } ?: return
            if (neko.motionState.isDragAnimationLocked()) {
                updateDrawable(neko)
                return
            }
            updateToNext(neko)
        }
    }

    // ================== MotionState ==================

    private inner class MotionState {
        private var curX = 0f
        private var curY = 0f
        private var targetX = 0f
        private var targetY = 0f
        private var vx = 0f // px/s
        private var vy = 0f // px/s

        private var displayWidth = 1
        private var displayHeight = 1

        private lateinit var params: MotionParams
        var alpha: Int = 0xff

        var behaviour: Behaviour = Behaviour.Whimsical
            private set
        private var curBehaviourIdx = 0
        private var lastBehaviourChanged = 0L

        private var curState: String? = null
        private var movingState = false
        private var stateChanged = false
        private var positionMoved = false
        private var speedFactor = 1.0f
        private var quietMode = false
        private var dragging = false
        private var overlayWidth = 80
        private var overlayHeight = 80
        private var mediaReaction: MediaReaction? = null
        private var scrollStep: ScrollFlingStep? = null
        private var mediaBlocked = false
        private var mediaWasControlled = false
        private var dragAnimationLocked = false
        private var dragStartRawX = 0f
        private var dragStartRawY = 0f
        private var dragStartX = 0f
        private var dragStartY = 0f
        private val dragDirectionTracker = DragDirectionTracker()

        private val onMotionEnd = MotionEndListener()

        fun setSpeedFactor(factor: Float) {
            speedFactor = if (factor <= 0f) {
                Timber.w("Attempt to set non-positive speed: %f, fallback 1.0", factor)
                1.0f
            } else factor
        }

        fun updateState() {
            stateChanged = false
            positionMoved = false

            if (dragging) return
            val reaction = mediaReaction
            if (reaction != null) {
                mediaWasControlled = true
                if (reaction.kind == MediaReactionKind.WATCH) {
                    targetX = curX; targetY = curY
                    vx = 0f; vy = 0f
                    movingState = false
                    val direction = MotionDirectionResolver.resolve(reaction.lookAt.x-curX, reaction.lookAt.y-curY)
                    val watchState = direction?.let { params.getMoveState(it).replaceFirst(params.moveStatePrefix, "watch") }
                    changeState(if (watchState != null && params.hasState(watchState)) watchState else params.initialState)
                } else {
                    val dx = reaction.target.x-curX
                    val dy = reaction.target.y-curY
                    val distance = hypot(dx,dy)
                    val speed = maxOf(params.maxVelocity * speedFactor, 80 * resources.displayMetrics.density) *
                        if (reaction.kind == MediaReactionKind.ESCAPE) 2f else 1f
                    val step = min(distance, speed * ANIMATION_INTERVAL / 1000f)
                    if (distance > 0.01f) {
                        curX += dx/distance*step
                        curY += dy/distance*step
                        positionMoved = true
                        changeToMovingState(dx,dy)
                    }
                    targetX = curX; targetY = curY
                    vx = 0f; vy = 0f
                }
                return
            }
            scrollStep?.let { step ->
                mediaWasControlled = true
                positionMoved = curX != step.target.x || curY != step.target.y
                curX = step.target.x
                curY = step.target.y
                targetX = curX; targetY = curY
                vx = 0f; vy = 0f
                changeToMovingState(step.velocity.x, step.velocity.y)
                return
            }
            if (mediaWasControlled || mediaBlocked) {
                stopAtCurrentPosition()
                mediaWasControlled = false
            }
            if (quietMode || mediaBlocked) return

            val dx = targetX - curX
            val dy = targetY - curY
            val len = hypot(dx, dy)

            if (len <= params.proximityDistance) {
                if (movingState) {
                    vx = 0f; vy = 0f
                    changeState(params.initialState)
                }
                return
            }

            if (!movingState) {
                val nstate = params.awakeState
                if (params.hasState(nstate)) changeState(nstate)
                return
            }

            val interval = ANIMATION_INTERVAL / 1000f
            val baseAcc = params.acceleration
            val baseMaxV = params.maxVelocity
            val decelDist = params.decelerationDistance

            val acc = baseAcc * speedFactor
            val maxV = baseMaxV * speedFactor

            if (len > 0) {
                vx += (acc * interval * dx / len)
                vy += (acc * interval * dy / len)
            }

            val curSpeed = hypot(vx, vy)
            val dynMax = maxV * min((len + 1.0f) / (decelDist + 1.0f), 1.0f)

            if (curSpeed > dynMax) {
                if (dynMax <= 0 && curSpeed > 0) {
                    vx = 0f; vy = 0f
                } else if (curSpeed > 0) {
                    val ratio = dynMax / curSpeed
                    vx *= ratio; vy *= ratio
                }
            }

            curX += vx * interval
            curY += vy * interval

            // When hitting the effective bottom boundary, stop and enter idle
            if (curY > effectiveDisplayHeight.toFloat()) {
                curY = effectiveDisplayHeight.toFloat()
                vy = 0f
                targetY = curY  // redirect so neko stops moving
            }

            positionMoved = true

            changeToMovingState(vx, vy)
        }

        fun checkWall(): Boolean {
            if (!params.needCheckWall(curState!!)) return false

            val drawable = currentDrawable()
            val dw2 = drawable.intrinsicWidth / 2f
            val dh2 = drawable.intrinsicHeight / 2f

            val dir = when {
                curX >= 0 && curX < dw2 -> MotionParams.WallDirection.LEFT
                curX <= displayWidth && curX > displayWidth - dw2 -> MotionParams.WallDirection.RIGHT
                curY >= 0 && curY < dh2 -> MotionParams.WallDirection.UP
                curY <= effectiveDisplayHeight && curY > effectiveDisplayHeight - dh2 -> MotionParams.WallDirection.DOWN
                else -> return false
            }

            val nstate = params.getWallState(dir)
            if (!params.hasState(nstate)) return false
            changeState(nstate)
            return true
        }

        fun updateMovingState(): Boolean {
            if (!params.needCheckMove(curState!!)) return false
            val len = hypot(targetX - curX, targetY - curY)
            if (len <= params.proximityDistance) return false
            changeToMovingState(targetX - curX, targetY - curY)
            return true
        }

        fun setParams(p: MotionParams) {
            val nstate = p.initialState
            require(p.hasState(nstate)) { "Initial State does not exist" }
            params = p
            changeState(nstate)
            movingState = false
        }

        private fun changeState(state: String) {
            if (state == curState) return
            curState = state
            stateChanged = true
            movingState = false
            currentDrawable().setOnMotionEndListener(onMotionEnd)
        }

        fun changeToNextState(): Boolean {
            val next = params.getNextState(curState ?: return false) ?: return false
            changeState(next)
            return true
        }

        private fun changeToMovingState(dx: Float, dy: Float): Boolean {
            val direction = MotionDirectionResolver.resolve(dx, dy) ?: return false
            return changeToMovingState(direction)
        }

        private fun changeToMovingState(direction: MotionParams.MoveDirection): Boolean {
            val nstate = params.getMoveState(direction)
            if (!params.hasState(nstate)) return false
            val previousState = curState
            changeState(nstate)
            movingState = true
            return previousState != curState
        }

        private fun changeToDraggingState(direction: MotionParams.MoveDirection): Boolean {
            val dragState = when (direction) {
                MotionParams.MoveDirection.LEFT,
                MotionParams.MoveDirection.UP_LEFT,
                MotionParams.MoveDirection.DOWN_LEFT -> "dragLeft"

                MotionParams.MoveDirection.RIGHT,
                MotionParams.MoveDirection.UP_RIGHT,
                MotionParams.MoveDirection.DOWN_RIGHT -> "dragRight"

                MotionParams.MoveDirection.UP,
                MotionParams.MoveDirection.DOWN -> "dragVertical"
            }
            if (params.hasState(dragState)) {
                val previousState = curState
                changeState(dragState)
                movingState = true
                return previousState != curState
            }
            return changeToMovingState(direction)
        }

        var effectiveDisplayHeight: Int = 1
            private set
        private var bottomOffsetPct: Int = 0

        fun setDisplaySize(w: Int, h: Int, offsetPct: Int = bottomOffsetPct) {
            displayWidth = w
            displayHeight = h
            bottomOffsetPct = offsetPct.coerceIn(0, 50)
            effectiveDisplayHeight = (h * (100 - bottomOffsetPct) / 100).coerceAtLeast(1)
        }

        fun setBehaviour(b: Behaviour) {
            behaviour = b
            lastBehaviourChanged = 0
            curBehaviourIdx = Behaviour.valueOf(b.toString()).ordinal
        }

        fun setCurrentPosition(x: Float, y: Float) {
            curX = x; curY = y
        }

        fun setOverlaySize(width: Int, height: Int) {
            overlayWidth = width.coerceAtLeast(1)
            overlayHeight = height.coerceAtLeast(1)
        }

        fun center() = MediaPoint(curX, curY)
        fun prepareTravel(dx: Float, dy: Float) {
            dragging = false
            dragAnimationLocked = false
            mediaReaction = null
            scrollStep = null
            mediaBlocked = false
            changeToMovingState(dx,dy)
        }
        fun isDragging() = dragging
        fun isMediaControlled() = mediaReaction != null || mediaBlocked || scrollStep != null
        fun setScrollStep(step: ScrollFlingStep?) { scrollStep = step }
        fun setMediaReaction(reaction: MediaReaction?, blocked: Boolean) {
            mediaReaction = reaction
            mediaBlocked = blocked
        }

        fun setQuietMode(enabled: Boolean) {
            quietMode = enabled
            dragAnimationLocked = false
            if (enabled) {
                dragging = false
                stopAtCurrentPosition()
            }
        }

        fun beginDrag(rawX: Float, rawY: Float): Boolean {
            dragging = true
            dragAnimationLocked = true
            dragStartRawX = rawX
            dragStartRawY = rawY
            dragStartX = curX
            dragStartY = curY
            dragDirectionTracker.reset(rawX, rawY)
            vx = 0f
            vy = 0f
            val previousState = curState
            val initialDragState = when {
                params.hasState("dragVertical") -> "dragVertical"
                params.hasState(params.getMoveState(MotionParams.MoveDirection.RIGHT)) ->
                    params.getMoveState(MotionParams.MoveDirection.RIGHT)
                else -> null
            }
            if (initialDragState != null) {
                changeState(initialDragState)
                movingState = true
            }
            return previousState != curState
        }

        fun dragTo(
            rawX: Float,
            rawY: Float,
            activationThreshold: Float,
            directionChangeThreshold: Float,
        ): DragUpdate {
            if (!dragging) return DragUpdate(false, false)

            val nextX = (dragStartX + rawX - dragStartRawX)
                .coerceIn(0f, displayWidth.toFloat())
            val nextY = (dragStartY + rawY - dragStartRawY)
                .coerceIn(0f, effectiveDisplayHeight.toFloat())
            val positionChanged = nextX != curX || nextY != curY
            curX = nextX
            curY = nextY
            targetX = curX
            targetY = curY
            vx = 0f
            vy = 0f
            positionMoved = positionChanged

            val direction = dragDirectionTracker.update(
                rawX = rawX,
                rawY = rawY,
                activationThreshold = activationThreshold,
                directionChangeThreshold = directionChangeThreshold,
            )
            val animationChanged = direction?.let(::changeToDraggingState) ?: false
            return DragUpdate(positionChanged, animationChanged)
        }

        fun endDrag() {
            if (!dragAnimationLocked) return
            dragging = false
            dragAnimationLocked = false
            stopAtCurrentPosition()
        }

        fun cancelDragWithoutIdle() {
            if (!dragging) return
            dragging = false
            setTargetPositionDirect(curX, curY)
            vx = 0f
            vy = 0f
        }

        fun isDragAnimationLocked(): Boolean = dragAnimationLocked

        fun setTargetPosition(x: Float, y: Float) {
            // Giữ nguyên hành vi cũ (ICS_OR_LATER = true -> whimsical)
            curBehaviourIdx = Behaviour.entries.size - 1
            when (Behaviour.entries[curBehaviourIdx]) {
                Behaviour.Closer -> setTargetPositionDirect(x, y)
                Behaviour.Further -> {
                    var dx = displayWidth / 2f - x
                    var dy = effectiveDisplayHeight / 2f - y
                    if (dx == 0f && dy == 0f) {
                        val ang = random.nextFloat() * (PI.toFloat()) * 2
                        dx = cos(ang); dy = sin(ang)
                    }
                    if (dx < 0) {
                        dx = -dx; dy = -dy
                    }
                    val e: PointF = run {
                        val e1: PointF
                        val e2: PointF
                        if (dy > dx * effectiveDisplayHeight / displayWidth || dy < -dx * effectiveDisplayHeight / displayWidth) {
                            val dxdy = dx / dy
                            e1 = PointF((displayWidth - effectiveDisplayHeight * dxdy) / 2f, 0f)
                            e2 = PointF(
                                (displayWidth + effectiveDisplayHeight * dxdy) / 2f,
                                effectiveDisplayHeight.toFloat()
                            )
                        } else {
                            val dydx = dy / dx
                            e1 = PointF(0f, (effectiveDisplayHeight - displayWidth * dydx) / 2f)
                            e2 = PointF(
                                displayWidth.toFloat(),
                                (effectiveDisplayHeight + displayWidth * dydx) / 2f
                            )
                        }
                        val d1 = hypot(e1.x - x, e1.y - y).toDouble()
                        val d2 = hypot(e2.x - x, e2.y - y).toDouble()
                        if (d1 > d2) e1 else e2
                    }
                    val r = 0.9f + random.nextFloat() * 0.1f
                    setTargetPositionDirect(e.x * r + x * (1 - r), e.y * r + y * (1 - r))
                }

                Behaviour.Whimsical -> {
                    val minWH2 = min(displayWidth, displayHeight) / 2f
                    val r = random.nextFloat() * minWH2 + minWH2
                    val a = random.nextFloat() * 360f
                    var nx = curX + r * cos(Math.toRadians(a.toDouble())).toFloat()
                    var ny = curY + r * sin(Math.toRadians(a.toDouble())).toFloat()

                    if (random.nextFloat() < 0.15f) nx =
                        if (random.nextBoolean()) 0f else displayWidth.toFloat()
                    if (random.nextFloat() < 0.15f) ny =
                        if (random.nextBoolean()) 0f else effectiveDisplayHeight.toFloat()

                    if (random.nextFloat() < 0.15f) {
                        nx = nx.coerceIn(0f, displayWidth.toFloat())
                        ny = ny.coerceIn(0f, effectiveDisplayHeight.toFloat())
                    } else {
                        nx =
                            if (nx < 0) -nx else if (nx >= displayWidth) displayWidth * 2 - nx - 1 else nx
                        ny =
                            if (ny < 0) -ny else if (ny >= effectiveDisplayHeight) effectiveDisplayHeight * 2 - ny - 1 else ny
                    }
                    setTargetPositionDirect(nx, ny)
                }
            }
        }

        fun setTargetPositionDirect(x: Float, y: Float) {
            targetX = x
            targetY = y.coerceAtMost(effectiveDisplayHeight.toFloat())
        }

        fun forceStop() {
            stopAtCurrentPosition()
        }

        private fun stopAtCurrentPosition() {
            setTargetPositionDirect(curX, curY)
            vx = 0f
            vy = 0f
            movingState = false
            changeState(params.initialState)
        }

        fun isStateChanged(): Boolean = stateChanged
        fun isPositionMoved(): Boolean = positionMoved

        fun currentDrawable(): MotionDrawable {
            return params.getDrawable(curState!!)!!
        }

        fun position(): Point {
            return Point(
                (curX - overlayWidth / 2f).toInt(),
                (curY - overlayHeight / 2f).toInt()
            )
        }
    }

}
