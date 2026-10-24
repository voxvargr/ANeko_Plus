package org.nqmgaming.aneko.presentation.setting

import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.preference.PreferenceManager
import org.nqmgaming.aneko.R
import org.nqmgaming.aneko.core.media.MediaReactionPreferences
import org.nqmgaming.aneko.core.media.MediaRegionStore
import org.nqmgaming.aneko.core.service.AnimationService
import org.nqmgaming.aneko.core.util.extension.getStringResource
import org.nqmgaming.aneko.core.util.extension.openUrl
import org.nqmgaming.aneko.presentation.setting.component.PreferenceContainer
import org.nqmgaming.aneko.presentation.setting.component.PreferenceItem
import org.nqmgaming.aneko.presentation.setting.component.SliderPreferenceItem
import org.nqmgaming.aneko.presentation.setting.component.SwitchPreferenceItem
import org.nqmgaming.aneko.presentation.ui.theme.ANekoTheme

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    Column(modifier = modifier.padding(16.dp)) {
        PreferenceContainer(
            title = R.string.motion_settings_default_title
        ) {
            SwitchPreferenceItem(
                title = stringResource(R.string.motion_quiet_mode_title),
                summary = stringResource(R.string.motion_quiet_mode_summary),
                icon = R.drawable.sleep2,
                key = AnimationService.PREF_KEY_QUIET_MODE,
                defaultValue = false,
                prefs = prefs,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            )
            SliderPreferenceItem(
                title = stringResource(R.string.motion_transparency_title),
                summary = stringResource(R.string.motion_transparency_summary),
                icon = R.drawable.ic_shape_exclude,
                entries = context.run { resources.getStringArray(R.array.pref_motion_transparency_entries) },
                entryValues = context.run { resources.getStringArray(R.array.pref_motion_transparency_entry_values) },
                key = AnimationService.PREF_KEY_TRANSPARENCY,
                defaultValue = "0.0",
                prefs = prefs
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(
                    alpha = 0.5f
                ),
            )
            SwitchPreferenceItem(
                title = stringResource(R.string.motion_focus_title),
                summary = stringResource(R.string.motion_focus_summary),
                icon = R.drawable.ic_shape_exclude,
                key = AnimationService.PREF_KEY_FOCUS,
                defaultValue = false,
                prefs = prefs
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(
                    alpha = 0.5f
                ),
            )
            SliderPreferenceItem(
                title = stringResource(R.string.motion_speed_title),
                summary = stringResource(R.string.motion_speed_summary),
                icon = R.drawable.ic_speed,
                entries = context.run { resources.getStringArray(R.array.pref_motion_speed_entries) },
                entryValues = context.run { resources.getStringArray(R.array.pref_motion_speed_entry_values) },
                key = AnimationService.PREF_KEY_SPEED,
                defaultValue = "1.0",
                prefs = prefs
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(
                    alpha = 0.5f
                ),
            )
            SliderPreferenceItem(
                title = stringResource(R.string.motion_size_title),
                summary = stringResource(R.string.motion_size_summary),
                icon = R.drawable.ic_slide_size,
                entries = context.run { resources.getStringArray(R.array.pref_motion_size) },
                entryValues = context.run { resources.getStringArray(R.array.pref_motion_size_entry_values) },
                key = AnimationService.PREF_KEY_SIZE,
                defaultValue = "80",
                prefs = prefs
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(
                    alpha = 0.5f
                ),
            )
            SwitchPreferenceItem(
                title = stringResource(R.string.motion_bottom_offset_title),
                summary = stringResource(R.string.motion_bottom_offset_summary),
                icon = R.drawable.ic_keyboard,
                key = AnimationService.PREF_KEY_BOTTOM_OFFSET,
                defaultValue = false,
                prefs = prefs
            )
        }

        Spacer(Modifier.height(16.dp))

        MediaReactionSettings()
        Spacer(Modifier.height(16.dp))
        InputReactionSettings()

        Spacer(Modifier.height(16.dp))

        WearCompanionSettings()

        Spacer(Modifier.height(16.dp))

        PreferenceContainer(
            title = R.string.community_title
        ) {
            PreferenceItem(
                title = stringResource(R.string.github_contribute_title),
                summary = stringResource(R.string.github_contribute_summary),
                icon = R.drawable.ic_github_mark,
                onClick = {
                    context.openUrl(context.getStringResource(R.string.github_uri))
                }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            )

            PreferenceItem(
                title = stringResource(R.string.website_title),
                summary = stringResource(R.string.website_summary),
                icon = R.drawable.ic_language,
                onClick = {
                    context.openUrl(context.getStringResource(R.string.aneko_url))
                }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            )

            PreferenceItem(
                title = stringResource(R.string.rate_app_title),
                summary = stringResource(R.string.rate_app_summary),
                icon = R.drawable.ic_star,
                onClick = {
                    context.openUrl(context.getStringResource(R.string.rate_app_url))
                }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            )

            PreferenceItem(
                title = stringResource(R.string.sponsor_title),
                summary = stringResource(R.string.sponsor_summary),
                icon = R.drawable.ic_heart,
                onClick = {
                    context.openUrl(context.getStringResource(R.string.sponsor_url))
                }
            )

        }
    }
}

@Composable
private fun MediaReactionSettings() {
    val context = LocalContext.current
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var enabled by remember(prefs) {
        mutableStateOf(prefs.getBoolean(MediaReactionPreferences.ENABLED, false))
    }
    var showDisclosure by rememberSaveable { mutableStateOf(false) }
    var fadeEnabled by remember(prefs) {
        mutableStateOf(prefs.getBoolean(MediaReactionPreferences.FULLSCREEN_FADE_ENABLED, false))
    }
    var fadeOpacity by remember(prefs) {
        mutableStateOf(prefs.getFloat(MediaReactionPreferences.FULLSCREEN_FADE_OPACITY, 0.10f))
    }
    var showFadeDisclosure by rememberSaveable { mutableStateOf(false) }
    var showFadeOpacity by rememberSaveable { mutableStateOf(false) }
    val detectionConnected by MediaRegionStore.isDetectionConnected.collectAsStateWithLifecycle()

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
            if (key == MediaReactionPreferences.ENABLED) {
                enabled = sharedPrefs.getBoolean(MediaReactionPreferences.ENABLED, false)
            }
            if (key == MediaReactionPreferences.FULLSCREEN_FADE_ENABLED) {
                fadeEnabled = sharedPrefs.getBoolean(MediaReactionPreferences.FULLSCREEN_FADE_ENABLED, false)
            }
            if (key == MediaReactionPreferences.FULLSCREEN_FADE_OPACITY) {
                fadeOpacity = sharedPrefs.getFloat(MediaReactionPreferences.FULLSCREEN_FADE_OPACITY, 0.10f)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun requestEnabled(value: Boolean) {
        if (value) showDisclosure = true
        else prefs.edit { putBoolean(MediaReactionPreferences.ENABLED, false) }
    }

    fun showMessage(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    fun startMediaSelection() {
        if (!enabled && !fadeEnabled) {
            showMessage(R.string.media_reactions_enable_first)
            return
        }
        if (!prefs.getBoolean(AnimationService.PREF_KEY_ENABLE, false) ||
            !prefs.getBoolean(AnimationService.PREF_KEY_VISIBLE, true)
        ) {
            showMessage(R.string.media_reactions_start_pets_first)
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            showMessage(R.string.overlay_permission_title)
            return
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, AnimationService::class.java).setAction(AnimationService.ACTION_SELECT_MEDIA),
        )
    }

    PreferenceContainer(title = R.string.media_reactions_title) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = enabled,
                    role = Role.Switch,
                    onValueChange = ::requestEnabled,
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.media_reactions_enabled_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.media_reactions_enabled_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = enabled, onCheckedChange = null)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = fadeEnabled,
                role = Role.Switch,
                onValueChange = { value ->
                    if (value) showFadeDisclosure = true
                    else prefs.edit { putBoolean(MediaReactionPreferences.FULLSCREEN_FADE_ENABLED, false) }
                },
            ).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.media_fullscreen_fade_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.media_fullscreen_fade_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = fadeEnabled, onCheckedChange = null)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        PreferenceItem(
            title = stringResource(R.string.media_fullscreen_fade_amount_title),
            summary = stringResource(if (fadeOpacity == 0.20f) R.string.media_fullscreen_fade_80
                else R.string.media_fullscreen_fade_90),
            icon = R.drawable.ic_shape_exclude,
            onClick = { showFadeOpacity = true },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        PreferenceItem(
            title = stringResource(R.string.media_reactions_accessibility_title),
            summary = stringResource(
                if (detectionConnected) R.string.media_reactions_accessibility_connected
                else R.string.media_reactions_accessibility_disconnected,
            ),
            icon = R.drawable.ic_explore,
            onClick = {
                if (!enabled && !fadeEnabled) showMessage(R.string.media_reactions_enable_first)
                else context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
        )
        AccessibilityRestrictedSettingsHint(show = (enabled || fadeEnabled) && !detectionConnected)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        PreferenceItem(
            title = stringResource(R.string.media_reactions_manual_title),
            summary = stringResource(R.string.media_reactions_manual_summary),
            icon = R.drawable.ic_shape_exclude,
            onClick = ::startMediaSelection,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        PreferenceItem(
            title = stringResource(R.string.media_reactions_clear_title),
            summary = stringResource(R.string.media_reactions_clear_summary),
            icon = R.drawable.ic_shape_exclude,
            onClick = { MediaRegionStore.setManual(null) },
        )
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text(stringResource(R.string.media_reactions_disclosure_title)) },
            text = {
                Text(
                    text = stringResource(R.string.media_reactions_disclosure_message),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit { putBoolean(MediaReactionPreferences.ENABLED, true) }
                    showDisclosure = false
                }) {
                    Text(stringResource(R.string.media_reactions_disclosure_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showFadeDisclosure) {
        AlertDialog(
            onDismissRequest = { showFadeDisclosure = false },
            title = { Text(stringResource(R.string.media_fullscreen_fade_disclosure_title)) },
            text = {
                Text(stringResource(R.string.media_fullscreen_fade_disclosure),
                    modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = {
                prefs.edit { putBoolean(MediaReactionPreferences.FULLSCREEN_FADE_ENABLED, true) }
                showFadeDisclosure = false
            }) { Text(stringResource(R.string.media_fullscreen_fade_enable)) } },
            dismissButton = { TextButton(onClick = { showFadeDisclosure = false }) {
                Text(stringResource(R.string.cancel))
            } },
        )
    }

    if (showFadeOpacity) {
        AlertDialog(
            onDismissRequest = { showFadeOpacity = false },
            title = { Text(stringResource(R.string.media_fullscreen_fade_amount_title)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    listOf(0.20f to R.string.media_fullscreen_fade_80,
                        0.10f to R.string.media_fullscreen_fade_90).forEach { (value, label) ->
                        val selected = if (value == 0.20f) fadeOpacity == 0.20f else fadeOpacity != 0.20f
                        Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton,
                            onClick = {
                                prefs.edit { putFloat(MediaReactionPreferences.FULLSCREEN_FADE_OPACITY, value) }
                                showFadeOpacity = false
                            }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = null)
                            Text(stringResource(label))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFadeOpacity = false }) {
                Text(stringResource(R.string.cancel))
            } },
        )
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun SettingScreenPreview() {
    ANekoTheme(
        dynamicColor = false
    ) {
        SettingsScreen()
    }
}
