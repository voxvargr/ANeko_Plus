package org.nqmgaming.aneko.presentation.setting

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.nqmgaming.aneko.R
import org.nqmgaming.aneko.companion.CompanionSession
import org.nqmgaming.aneko.core.service.AnimationService
import org.nqmgaming.aneko.presentation.setting.component.PreferenceContainer
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.preference.PreferenceManager
import org.nqmgaming.aneko.presentation.setting.component.SwitchPreferenceItem

@Composable
fun WearCompanionSettings() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("companion_sync", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(prefs.getString("mode","phone") ?: "phone") }
    var status by remember { mutableStateOf(prefs.getString("status","") ?: "") }
    var owner by remember { mutableStateOf(prefs.getString("owner","phone") ?: "phone") }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
            if (key == "mode") mode = prefs.getString("mode","phone") ?: "phone"
            if (key == "status") status = prefs.getString("status","") ?: ""
            if (key == "owner") owner = prefs.getString("owner","phone") ?: "phone"
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    PreferenceContainer(title = R.string.wear_companion_title) {
        if (!CompanionSession.isSupported()) {
            Text(stringResource(R.string.wear_companion_foss), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.wear_companion_summary), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(when (owner) {
                "phone" -> R.string.wear_location_phone
                "watch" -> R.string.wear_location_watch
                "both" -> R.string.wear_location_both
                else -> R.string.wear_location_pending
            }), style = MaterialTheme.typography.bodyMedium)
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            listOf("auto" to R.string.wear_mode_auto, "phone" to R.string.wear_mode_phone,
                "watch" to R.string.wear_mode_watch, "both" to R.string.wear_mode_both).forEach { (value,label) ->
                Row(Modifier.fillMaxWidth().selectable(selected = mode == value, role = Role.RadioButton,
                    onClick = {
                        mode = value
                        prefs.edit { putString("mode",value) }
                        val animationPrefs = context.getSharedPreferences(context.packageName+"_preferences",Context.MODE_PRIVATE)
                        if (animationPrefs.getBoolean(AnimationService.PREF_KEY_ENABLE,false) &&
                            animationPrefs.getBoolean(AnimationService.PREF_KEY_VISIBLE,false)) {
                            ContextCompat.startForegroundService(context, Intent(context,AnimationService::class.java)
                                .setAction(AnimationService.ACTION_APPLY_COMPANION))
                        } else Toast.makeText(context,R.string.media_reactions_start_pets_first,Toast.LENGTH_SHORT).show()
                    }), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start) {
                    RadioButton(selected = mode == value, onClick = null)
                    Text(stringResource(label))
                }
            }
        }
    }
    WatchSetupSettings()
    PreferenceContainer(title = R.string.attention_title) {
        val petPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        SwitchPreferenceItem(title = stringResource(R.string.attention_enabled),
            summary = stringResource(R.string.attention_summary), icon = R.drawable.ic_heart,
            key = "motion.attention.enabled", defaultValue = false, prefs = petPrefs)
        SwitchPreferenceItem(title = stringResource(R.string.attention_haptics),
            summary = stringResource(R.string.attention_haptics_summary), icon = R.drawable.ic_heart,
            key = "motion.attention.haptics", defaultValue = false, prefs = petPrefs)
    }
}
