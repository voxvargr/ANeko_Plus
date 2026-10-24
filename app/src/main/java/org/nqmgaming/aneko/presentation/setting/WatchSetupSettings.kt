package org.nqmgaming.aneko.presentation.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.system.Os
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nqmgaming.aneko.BuildConfig
import org.nqmgaming.aneko.R
import org.nqmgaming.aneko.presentation.setting.component.PreferenceContainer
import org.nqmgaming.aneko.presentation.setting.component.PreferenceItem
import java.io.File
import java.io.IOException

private const val WATCH_ASSET = "watch/ANeko-watch.apk"
private const val APK_MIME = "application/vnd.android.package-archive"
private const val WATCH_COMMAND = "appops set org.nqmgaming.aneko android:system_alert_window allow"
private const val MAX_WATCH_APK_BYTES = 64L * 1024 * 1024
private val BUGJAEGER_PACKAGES = listOf("eu.sisik.hackendebug", "eu.sisik.hackendebug.full")

/** User-controlled APK export and client launch; no ADB connection or command execution. */
@Composable
fun WatchSetupSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareTitle = stringResource(R.string.watch_setup_share_apk)
    val shellLabel = stringResource(R.string.watch_setup_shell_label)
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var bundled by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        bundled = withContext(Dispatchers.IO) {
            runCatching { context.assets.list("watch")?.contains("ANeko-watch.apk") == true }
                .getOrDefault(false)
        }
    }
    val saveApk = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(APK_MIME)) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val success = watchSetupAttempt {
                withContext(Dispatchers.IO) {
                    val apk = prepareWatchApk(context)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        apk.inputStream().use { it.copyTo(output) }
                    } ?: throw IOException("No destination stream")
                }
            }
            busy = false
            Toast.makeText(context, if (success) R.string.watch_setup_saved else R.string.watch_setup_export_failed,
                Toast.LENGTH_LONG).show()
        }
    }
    PreferenceContainer(title = R.string.watch_setup_title) {
        PreferenceItem(title = stringResource(R.string.watch_setup_open),
            summary = stringResource(R.string.watch_setup_summary), icon = R.drawable.ic_explore,
            onClick = { showGuide = true })
    }
    if (showGuide) AlertDialog(
        onDismissRequest = { if (!busy) showGuide = false },
        title = { Text(stringResource(R.string.watch_setup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.watch_setup_pair))
                Text(stringResource(if (bundled == true) R.string.watch_setup_apk_included
                    else if (bundled == null) R.string.watch_setup_checking else R.string.watch_setup_apk_missing),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = bundled == true && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        busy = true
                        val prepared = watchSetupAttempt { withContext(Dispatchers.IO) { prepareWatchApk(context) } }
                        busy = false
                        if (prepared) saveApk.launch(watchApkName())
                        else Toast.makeText(context, R.string.watch_setup_export_failed, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.watch_setup_save_apk)) }
                TextButton(enabled = bundled == true && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        busy = true
                        val success = watchSetupAttempt {
                            val apk = withContext(Dispatchers.IO) { prepareWatchApk(context) }
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".watch_apk", apk)
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = APK_MIME
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newUri(context.contentResolver, "ANeko watch APK", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, shareTitle))
                        }
                        busy = false
                        if (!success) Toast.makeText(context, R.string.watch_setup_share_failed, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.watch_setup_share_apk)) }
                Text(stringResource(R.string.watch_setup_install))
                Text(stringResource(R.string.watch_setup_shell))
                SelectionContainer { Text(WATCH_COMMAND, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall) }
                TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText(shellLabel, WATCH_COMMAND))
                    Toast.makeText(context, R.string.watch_setup_copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.watch_setup_copy_command)) }
                TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    val launch = BUGJAEGER_PACKAGES.firstNotNullOfOrNull {
                        context.packageManager.getLaunchIntentForPackage(it)
                    }
                    val opened = launch != null && runCatching { context.startActivity(launch) }.isSuccess
                    if (!opened) Toast.makeText(context, R.string.watch_setup_client_missing, Toast.LENGTH_LONG).show()
                }) { Text(stringResource(R.string.watch_setup_open_bugjaeger)) }
                Text(stringResource(if (BuildConfig.FLAVOR == "connected") R.string.watch_setup_finish
                    else R.string.watch_setup_finish_foss))
                Text(stringResource(R.string.watch_setup_shizuku), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { showGuide = false }) {
            Text(stringResource(R.string.watch_setup_close))
        } },
    )
}

/** Dedicated provider authority exposes only the generated watch APK cache directory. */
class WatchApkFileProvider : FileProvider()

private fun watchApkName(): String {
    val version = BuildConfig.VERSION_NAME.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
    return "ANeko-watch-$version-${BuildConfig.BUILD_TYPE}.apk"
}

private suspend fun watchSetupAttempt(action: suspend () -> Unit): Boolean = try {
    action()
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

/** Complete, validate, then atomically replace; a failed copy never becomes the shared APK. */
@Suppress("DEPRECATION")
internal fun prepareWatchApk(context: Context): File {
    val directory = File(context.cacheDir, "watch-apk").apply {
        if (!isDirectory && !mkdirs()) throw IOException("Cannot create APK cache")
    }
    val temporary = File.createTempFile("watch-", ".apk", directory)
    try {
        context.assets.open(WATCH_ASSET).use { input ->
            temporary.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_WATCH_APK_BYTES) throw IOException("APK exceeds size limit")
                    output.write(buffer, 0, read)
                }
                if (total == 0L) throw IOException("Empty APK")
            }
        }
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(temporary.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IOException("Invalid watch APK")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.packageName != context.packageName || archive.longVersionCode != installed.longVersionCode)
            throw IOException("Mismatched watch package or version")
        val expected = installed.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
        val actual = archive.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
        if (expected.isEmpty() || actual != expected) throw IOException("Mismatched watch signer")
        val destination = File(directory, watchApkName())
        Os.rename(temporary.absolutePath, destination.absolutePath)
        // Leave recently shared files available to their readers; prune only obsolete cache entries.
        val oldest = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it != destination && it.lastModified() < oldest }?.forEach { it.delete() }
        return destination
    } finally { temporary.delete() }
}
