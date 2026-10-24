package org.nqmgaming.aneko.core.companion

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nqmgaming.aneko.companion.CompanionSession
import timber.log.Timber

/** The connected flavor supplies transport; the FOSS flavor stays entirely local. */
class PhoneCompanionController(private val context: Context, host: CompanionSession.Host,
    private val onDisplayChanged: (Boolean) -> Unit = {}) {
    private val session = CompanionSession(context,true,host)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var snapshotJob: Job? = null
    private var presenceJob: Job? = null
    private var lastPresence: Boolean? = null
    private var started = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if (key?.startsWith("motion.") == true) publishSettings()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reconcilePresence(forceAsleep = intent.action == Intent.ACTION_SCREEN_OFF)
        }
    }

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(context,receiver,IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        },ContextCompat.RECEIVER_NOT_EXPORTED)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        session.start()
        reconcilePresence()
        presenceJob = scope.launch {
            // Keyguard can finish dismissal after SCREEN_ON/USER_PRESENT is delivered.
            // Recheck while this user-started overlay runs so one early/missed broadcast
            // cannot leave the transport believing an unlocked phone is still asleep.
            while (started) {
                delay(1_000)
                reconcilePresence()
            }
        }
        applyMode()
        publishSettings()
    }

    private fun reconcilePresence(forceAsleep: Boolean = false) {
        if (!started) return
        val interactive = !forceAsleep && isPhoneInteractive(context)
        if (interactive == lastPresence) return
        lastPresence = interactive
        onDisplayChanged(interactive)
        session.updatePresence(interactive)
    }

    fun applyMode() {
        session.setMode(context.getSharedPreferences("companion_sync",Context.MODE_PRIVATE)
            .getString("mode","phone") ?: "phone")
    }

    private fun publishSettings() {
        if (!started || !CompanionSession.isSupported()) return
        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            delay(750)
            try {
                val snapshot = withContext(Dispatchers.IO) { WearCompanionSnapshot.create(context,prefs) }
                session.publishSettings(snapshot.settings,snapshot.skinPack)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { Timber.w(error,"Unable to prepare companion settings for watch") }
        }
    }

    fun close() {
        presenceJob?.cancel()
        presenceJob = null
        if (started) {
            context.unregisterReceiver(receiver)
            prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
        started = false
        lastPresence = null
        scope.cancel()
        session.close()
    }

    companion object {
        fun isPhoneInteractive(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java).isInteractive &&
                !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }
}
