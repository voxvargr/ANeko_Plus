package org.nqmgaming.aneko

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.nqmgaming.aneko.presentation.ANekoActivity
import org.nqmgaming.aneko.presentation.setting.SettingsScreen
import org.nqmgaming.aneko.presentation.setting.prepareWatchApk
import org.nqmgaming.aneko.presentation.ui.theme.ANekoTheme
import java.io.InputStream
import java.security.MessageDigest

/** Exercises the production activity without changing onboarding or saved user settings. */
@RunWith(AndroidJUnit4::class)
class StartupSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ANekoActivity>()

    @Test
    fun activityLaunchesAndComposesWithoutCrashing() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.onRoot().assertExists()
        assertEquals(Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
        compose.activityRule.scenario.onActivity { activity ->
            assertFalse(activity.isFinishing)
            assertFalse(activity.isDestroyed)
        }
    }
}

/** Eagerly composes every settings row, including the media icon that previously crashed. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mediaSettingsAndClearActionRenderWithoutResourceCrashes() {
        compose.setContent {
            ANekoTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SettingsScreen()
                }
            }
        }
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val title = resources.getString(R.string.media_reactions_title)
        val clearAction = resources.getString(R.string.media_reactions_clear_title)
        compose.waitForIdle()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(clearAction).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun watchSetupDialogShowsActualBundledStateAndShellCommandWithoutExternalActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resources = context.resources
        val hasBundledApk = context.assets.list("watch")?.contains("ANeko-watch.apk") == true
        if (hasBundledApk) context.assets.open("watch/ANeko-watch.apk").use {
            assertTrue("Bundled watch APK must be nonempty", it.read() >= 0)
        }
        // Distribution acceptance can require the optional asset explicitly; ordinary
        // connected/FOSS test builds also exercise the truthful missing-asset branch.
        InstrumentationRegistry.getArguments().getString("expectBundledWatchApk")?.let {
            assertEquals("Watch APK bundling differs from the requested acceptance build", it.toBoolean(), hasBundledApk)
        }
        compose.setContent {
            ANekoTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SettingsScreen()
                }
            }
        }
        compose.onNodeWithText(resources.getString(R.string.watch_setup_open))
            .performScrollTo().assertIsDisplayed().performClick()
        val bundleStatus = resources.getString(if (hasBundledApk) R.string.watch_setup_apk_included
            else R.string.watch_setup_apk_missing)
        compose.waitUntil(timeoutMillis = 5_000L) {
            compose.onAllNodesWithText(bundleStatus).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(bundleStatus).performScrollTo().assertIsDisplayed()
        for (action in listOf(R.string.watch_setup_save_apk, R.string.watch_setup_share_apk)) {
            val button = compose.onNodeWithText(resources.getString(action))
                .performScrollTo().assertIsDisplayed()
            if (hasBundledApk) button.assertIsEnabled() else button.assertIsNotEnabled()
        }
        val command = "appops set org.nqmgaming.aneko android:system_alert_window allow"
        compose.onNodeWithText(command).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.watch_setup_copy_command))
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(resources.getString(R.string.watch_setup_open_bugjaeger))
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(resources.getString(R.string.watch_setup_close)).performClick()
        compose.onNodeWithText(command).assertDoesNotExist()
    }

    @Test
    fun bundledWatchApkPreparesAndReadsThroughPrivateProviderWithIdenticalSha256() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val asset = "watch/ANeko-watch.apk"
        val hasBundledApk = context.assets.list("watch")?.contains("ANeko-watch.apk") == true
        val required = InstrumentationRegistry.getArguments().getString("expectBundledWatchApk").toBoolean()
        if (required) assertTrue("Acceptance build must include the matching watch APK", hasBundledApk)
        assumeTrue("Watch APK bundling is optional for phone-only builds", hasBundledApk)

        // Calls the production copy and PackageManager version/signer verification.
        val prepared = prepareWatchApk(context)
        assertTrue("Prepared APK must be nonempty", prepared.length() > 0L)
        assertEquals(java.io.File(context.cacheDir, "watch-apk").canonicalFile, prepared.parentFile?.canonicalFile)
        val authority = context.packageName + ".watch_apk"
        val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))
        assertFalse("Watch APK provider must remain private", provider.exported)
        assertTrue("Sharing requires per-URI grants", provider.grantUriPermissions)
        val uri = FileProvider.getUriForFile(context, authority, prepared)
        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        val expected = context.assets.open(asset).use(::sha256)
        // Read-only mode exercises the real provider without launching or granting another app access.
        val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
        val actual = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(::sha256)
        assertArrayEquals("Shared watch APK bytes must exactly match the bundled asset", expected, actual)
    }

    private fun sha256(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return digest.digest()
            digest.update(buffer, 0, read)
        }
    }
}
