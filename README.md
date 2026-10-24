# ANeko Plus

An open-source Android screen companion with custom skins and an optional Wear OS companion. Based on [ANeko Reborn](https://github.com/pass-with-high-score/ANeko).

## Features

- Multiple pets and custom skins, including Codex pet ZIPs and PNG/WebP sprite sheets.
- **Automatic handoff:** selected pets move between an unlocked phone and an awake watch, with one active device at a time. The source leaves before the destination appears, and ownership is remembered across restarts.
- Phone-only, watch-only, and an explicit **Both devices (separate copies)** mode. Settings show the current location.
- Optional video-area reactions: pets leave a marked or detected video area and watch from its edge. Automatic YouTube/Facebook detection is experimental; manual marking is available.
- Separate fullscreen-video fading on the phone, with 80% or 90% transparency and automatic restoration of normal opacity.
- Optional typing-cursor chasing, including selection movement from space-bar gestures in supported editors.
- Optional fast-scroll flings, affecting only pets inside the pane being scrolled; fixed headers and other areas stay unaffected.
- Synced appearance settings, Quiet Mode, and optional attention taps, hops, and gentle vibration.

## Requirements

- Phone: Android 9 / API 28 or newer, with display-over-other-apps permission.
- Watch: Wear OS 5 / API 34 or newer, with overlay permission.
- Phone/watch sync: a paired watch, Google Play services, and matching **connected** builds signed with the same key.
- The **foss** builds run independently without Google Play services or phone/watch sync.

## Install and set up

Use the matching phone and watch APKs from [GitHub Releases](https://github.com/voxvargr/ANeko_Plus/releases), or build them below. The local copies are named `ANeko-Plus-phone.apk` and `ANeko-Plus-watch.apk`.

1. Install the phone APK, open the app, grant overlay access, and start your pets.
2. Install the watch APK and grant its overlay permission using ADB as shown below.
3. Open ANeko on the watch and tap **Start pets** once.
4. On the phone, select **Settings → Phone and watch → Automatic handoff** for one companion moving between devices. Select **Both devices (separate copies)** only when you want pets visible on both.

Selected pets and appearance settings sync from the phone. A locked or sleeping phone lets an awake watch take over; unlocking the phone brings the pet back. Sleeping/ambient watch screens hide the pet. A disconnected device waits for ownership confirmation rather than creating another copy.

The watch stops its recurring companion polling while its screen is asleep or ambient. Incoming phone messages and screen-on events resume the needed work; the pet draws and seeks attention only while its screen is active and the phone has authorized it. Attention never wakes the display or replays a missed vibration after wake. Long-term battery use has not been measured.

### Typing, scrolling, and media reactions

The phone's **Typing and scrolling** settings contain two separate switches, both off by default. Enable **ANeko screen reactions** in Android Accessibility settings to use them. Cursor chasing needs character positions exposed by the focused editor; unsupported, password, Android-marked sensitive, or ambiguous fields are skipped. Space-bar cursor gestures work when the keyboard updates that exposed selection. Scroll flings use explicit pixel deltas or fresh absolute-offset differences from the same scrolling source; they affect only pets whose centers are inside that pane. Item indexes are not converted into guessed pixel movement. Both pause in Quiet Mode, during dragging, and while the pet is assigned to the watch.

Physical checks have confirmed scroll flings in Textra, YouTube and Facebook, and cursor chasing has been observed in a supported editor. Device tests also verify actual pet movement toward native caret geometry, support for a focused editor below 40 view layers, and rejection of deep password/sensitive ancestors. Messenger and ChatGPT scrolling still need confirmation with the latest fixes.

Media detection uses the same optional accessibility service, with its own switch. Explicit player containers can be recognized even when play/pause controls auto-hide. Manual marking remains available where an app hides its player bounds. These features do not type, tap, capture screenshots, save typed content, or send input data to the watch or a server.

**Fade pet over fullscreen video** is a separate phone setting, off by default, with its own disclosure. Choose **80% transparent (20% opacity)** or the default **90% transparent (10% opacity)**. Detection uses exposed foreground video-player geometry locally and is best effort across apps; it cannot guarantee every player or distinguish every rendered surface. Manual marking is the fallback. For automatically detected video, normal opacity returns when the fullscreen area disappears, shrinks, or its app leaves the foreground. A manually marked area stays fixed and can keep fading active until you choose **Clear marked area**, even after changing apps. The temporary effect does not change the normal opacity preference or watch appearance. Screen content is not recorded, logged, or uploaded. A controlled native `VideoView` fixture with the real pet overlay passed the 10%/20% opacity-cap and restoration checks. Third-party fullscreen detection still needs app-specific validation.

Facebook also has an experimental fallback for sufficiently large exposed `android.view.SurfaceView` or `android.view.TextureView` areas. These native rendered areas do not confirm video playback: a camera or game inside Facebook may also match. Use manual marking if the automatic area is wrong.

On Android 13+, a sideloaded APK may need **App info → ⋮ → Allow restricted settings** before accessibility can be enabled. Use **Open ANeko App info** beside a disconnected reaction setting, then return to **Accessibility → ANeko screen reactions** and enable it. If **Allow restricted settings** is absent, return to the normal Accessibility screen. See [Android's restricted-settings guidance](https://support.google.com/android/answer/12623953).

For diagnosis, `adb -s PHONE_SERIAL shell dumpsys activity service org.nqmgaming.aneko/.core.media.MediaAccessibilityService` reports bounded detector state and geometry without video titles or screen text.

### Watch ADB commands

The phone's **Watch setup** helper can export/share the matching watch APK, open an installed Bugjaeger client, and copy the watch shell permission command. Pair the watch in your ADB client, install the exported APK through its package picker, then paste and run the command in the **watch's shell**. That shell command starts with `appops`, without an `adb shell` prefix. A Shizuku grant on the phone does not grant authority on the watch. The helper does not execute undocumented external-app commands or embed an ADB client.

Install [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools), then enable **Developer options → Wireless debugging** on the watch. Keep the computer and watch on the same network.

On the watch, choose **Pair new device**, then run:

```sh
adb pair WATCH_IP:PAIRING_PORT
```

Enter the pairing code when prompted. Return to the watch's main **Wireless debugging** screen and use the address/port shown there for the connection. **The connection port is different from the pairing port.**

```sh
adb connect WATCH_IP:DEBUG_PORT
adb devices
adb -s WATCH_IP:DEBUG_PORT install -r ANeko-Plus-watch.apk
adb -s WATCH_IP:DEBUG_PORT shell appops set org.nqmgaming.aneko android:system_alert_window allow
```

The last command grants the watch app permission to draw the pet over other apps. It does not require root. Verify the grant with:

```sh
adb -s WATCH_IP:DEBUG_PORT shell appops get org.nqmgaming.aneko android:system_alert_window
```

Look for `allow`, then open ANeko on the watch and tap **Start pets**. Replace the uppercase placeholders with your watch's values. If connected through USB or another ADB transport, replace `WATCH_IP:DEBUG_PORT` with its serial from `adb devices`.

Optional phone installation through ADB:

```sh
adb -s PHONE_SERIAL install -r ANeko-Plus-phone.apk
```

## Build from source

The complete project is in this repository: `app/` is the phone app, `wear/` is the watch app, and `companion/` contains the shared handoff protocol and transport.

Install JDK 17 or 21 and Android SDK Platform 36. Set your SDK location through Android Studio, `ANDROID_HOME`, or an untracked `local.properties`. Gradle and Maven dependencies download on the first build.

From the repository root on Windows:

```powershell
.\gradlew.bat -PbundleWatchApk=true :app:assembleConnectedDebug :wear:assembleConnectedDebug
.\gradlew.bat :app:assembleFossDebug :wear:assembleFossDebug
.\gradlew.bat testFossDebugUnitTest testConnectedDebugUnitTest
```

On Linux/macOS, use `./gradlew` instead of `.\gradlew.bat`.

APKs are written to:

- `app/build/outputs/apk/connected/debug/app-connected-debug.apk`
- `wear/build/outputs/apk/connected/debug/wear-connected-debug.apk`
- Standalone builds use the corresponding `foss/debug/` directories.

Always build the connected pair together so their signing certificates match. The APKs currently copied into the project root are **debug/test builds**. Public release builds should use your own private signing key for both apps; signing keys are not included in the repository.

## Publishing

Commit the complete source, including the new `app/` files, `companion/`, `wear/`, Gradle wrapper, build files, and licenses. APKs and build caches are intentionally ignored by Git; attach the matching APK pair to a GitHub Release.

The bundled default skin is included. Online skin catalogs and skins downloaded onto a device are not copied into this repository.

The existing tag-release workflow requires signing secrets and Play Store credentials. Configure that workflow before using its automated release/Play publishing; source uploads and manually attached GitHub Release APKs do not require those credentials. Never commit `key.properties`, keystores, or Play Store service-account keys.

## License and credits

See [LICENSE](LICENSE) for GNU LGPL 2.1 and [wear/NOTICE.md](wear/NOTICE.md) for the watch module and sprite attribution.

Based on work by original ANeko creator [Tamanegi](https://github.com/lllllT), fork maintainer [choiman1559](https://github.com/choiman1559/ANeko), [Nguyen Quang Minh](https://github.com/nqmgaming), the [ANeko Reborn project](https://github.com/pass-with-high-score/ANeko), and the WearNeko companion prototype.
