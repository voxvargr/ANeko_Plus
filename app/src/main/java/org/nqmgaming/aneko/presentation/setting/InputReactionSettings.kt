package org.nqmgaming.aneko.presentation.setting

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.preference.PreferenceManager
import org.nqmgaming.aneko.R
import org.nqmgaming.aneko.core.cursor.CursorPreferences
import org.nqmgaming.aneko.core.media.MediaRegionStore
import org.nqmgaming.aneko.core.scroll.ScrollPreferences
import org.nqmgaming.aneko.presentation.setting.component.PreferenceContainer
import org.nqmgaming.aneko.presentation.setting.component.PreferenceItem

@Composable
fun InputReactionSettings() {
    val context = LocalContext.current
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var cursorEnabled by remember { mutableStateOf(prefs.getBoolean(CursorPreferences.ENABLED, false)) }
    var scrollEnabled by remember { mutableStateOf(prefs.getBoolean(ScrollPreferences.ENABLED, false)) }
    var pendingFeature by rememberSaveable { mutableStateOf<String?>(null) }
    val connected by MediaRegionStore.isDetectionConnected.collectAsStateWithLifecycle()
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
            if (key == CursorPreferences.ENABLED) cursorEnabled = store.getBoolean(key, false)
            if (key == ScrollPreferences.ENABLED) scrollEnabled = store.getBoolean(key, false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun enable(key: String, value: Boolean) {
        if (value) pendingFeature = key else prefs.edit { putBoolean(key, false) }
    }
    PreferenceContainer(title = R.string.input_reactions_title) {
        InputReactionSwitch(stringResource(R.string.cursor_chase_title),
            stringResource(R.string.cursor_chase_summary), cursorEnabled) { enable(CursorPreferences.ENABLED, it) }
        HorizontalDivider()
        InputReactionSwitch(stringResource(R.string.scroll_reactions_title),
            stringResource(R.string.scroll_reactions_summary), scrollEnabled) { enable(ScrollPreferences.ENABLED, it) }
        HorizontalDivider()
        PreferenceItem(
            title = stringResource(R.string.input_reactions_accessibility_title),
            summary = stringResource(if (connected) R.string.input_reactions_connected else R.string.input_reactions_disconnected),
            icon = R.drawable.ic_explore,
            onClick = {
                if (cursorEnabled || scrollEnabled) context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                else Toast.makeText(context, R.string.input_reactions_enable_first, Toast.LENGTH_SHORT).show()
            },
        )
        AccessibilityRestrictedSettingsHint(show = (cursorEnabled || scrollEnabled) && !connected)
    }
    pendingFeature?.let { key ->
        AlertDialog(onDismissRequest = { pendingFeature = null },
            title = { Text(stringResource(if (key == CursorPreferences.ENABLED)
                R.string.cursor_chase_disclosure_title else R.string.scroll_reactions_disclosure_title)) },
            text = { Text(stringResource(if (key == CursorPreferences.ENABLED)
                R.string.cursor_chase_disclosure else R.string.scroll_reactions_disclosure)) },
            confirmButton = { TextButton(onClick = {
                prefs.edit { putBoolean(key, true) }
                pendingFeature = null
            }) { Text(stringResource(R.string.input_reactions_enable)) } },
            dismissButton = { TextButton(onClick = { pendingFeature = null }) {
                Text(stringResource(R.string.cancel))
            } },
        )
    }
}

/** Android may restrict accessibility for sideloaded APKs until the user allows it in App info. */
@Composable
internal fun AccessibilityRestrictedSettingsHint(show: Boolean) {
    if (!show || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.reactions_restricted_settings_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            val opened = runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)))
            }.isSuccess
            if (!opened) Toast.makeText(context, R.string.reactions_app_info_unavailable, Toast.LENGTH_LONG).show()
        }) { Text(stringResource(R.string.reactions_open_app_info)) }
    }
}

@Composable
private fun InputReactionSwitch(title: String, summary: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
}
