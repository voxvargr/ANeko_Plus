# ANeko watch companion

Imported from the local WearNeko 0.1.1 prototype. The original project is unchanged.
This module uses the parent build tools and preserves the original source, sprites,
tests, license and notices. See [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE).

Requires Wear OS 5 / Android API 34 or newer. The package is now
`org.nqmgaming.aneko`, matching the phone app; an older `org.foss.wearneko`
prototype installation is a separate app. Development builds of both modules use
the same local debug certificate. Connected builds must also use matching release
signing certificates.

The watch needs overlay access. On firmware without an overlay permission screen,
grant it after installing using a connected ADB session:

```text
adb -s WATCH_SERIAL shell appops set org.nqmgaming.aneko android:system_alert_window allow
```

Pair and connect through the watch's Wireless debugging screen first; the pairing
port and connection port are different. See the [main setup guide](../README.md#watch-adb-commands)
for the complete installation commands. The phone's **Watch setup** helper can
export the matching watch APK and copy the permission command for a paired ADB
client. The helper does not grant watch permissions from the phone automatically.

Open the watch app and start pets. It displays an overlay on ordinary active
watch screens and pauses/removes it while the display is asleep or ambient.
Protected screens and firmware may hide overlays. Phone/watch transfer and
sleep/wake behavior have been tested on one physical paired device set. Other
firmware and long-term battery impact still need testing.

Build from the repository root:

```powershell
.\gradlew.bat :wear:testFossDebugUnitTest :wear:lintFossDebug :wear:assembleFossDebug
.\gradlew.bat :wear:testConnectedDebugUnitTest :wear:lintConnectedDebug :wear:assembleConnectedDebug
```

On Linux/macOS, use `./gradlew` instead of `.\gradlew.bat`.
To include the watch APK in the connected phone's export helper, build the phone
with `-PbundleWatchApk=true`; this also builds the matching watch APK:

```powershell
.\gradlew.bat -PbundleWatchApk=true :app:assembleConnectedDebug
```

Install matching `connected` builds on the phone and watch, start pets on both,
then choose Automatic in phone settings for a single companion moving between devices.
Both is explicitly a separate-copies mode. Settings show the current assigned device. The phone syncs up
to six selected companions, their normalized animation frames and size, speed,
opacity, Quiet Mode and optional attention/haptics settings. Watch sizes use the
same multiplier around a watch-sized 16 dp baseline and are limited to one quarter
of the display diameter. The FOSS builds run locally without phone/watch transport.

An awake source runs fully offscreen before acknowledging a handoff, after which
the destination appears. If the source display is already off or ambient, its
window is removed before acknowledgement; an off display cannot show an exit
animation. Neither transfer nor attention wakes a display. Optional attention
occurs at most once every 8–15 minutes while awake, visible and outside Quiet Mode;
brief landing/tap haptics honor the watch system haptic setting.
Haptics also require a visible, authorized pet and an already active display;
sleep cancels attention without replaying a missed landing or vibration on wake.

The connected watch stops its recurring discovery, presence and retry timers
while asleep or ambient. The phone avoids routine pings to a known sleeping watch
when no transfer is pending. Screen-on and explicit phone actions resume the
needed work; messages and settings can still be received during sleep. This does
not start a stopped watch app or turn on its display. Awake idle checks use a
slower schedule, while phone transfer retries remain prompt. Battery impact has
not been measured.

Cursor chasing, scroll flings and accessibility-based media detection run on the
phone only. They are separate, optional settings and require the phone's
**ANeko screen reactions** accessibility access. Cursor chasing needs real
character bounds from supported editors; scroll flings affect only pets centered
inside the pane actually scrolling. Typed content and scroll events are not sent
to the watch. See the [phone reaction settings](../README.md#typing-scrolling-and-media-reactions)
for limitations.

Frame packs are restricted to PNG frames up to 256×256 pixels, 64 frames per motion,
512 total frame references and 8 MiB compressed/extracted. Files are validated and
installed into private storage before replacing the previous pack. Invalid updates
keep the previous companions; without a synced pack, original ANeko sprites are
used by standalone FOSS mode; the connected watch waits for a valid synced skin pack
before becoming ready for a transfer. Decoded images use a shared 4 MiB cache.
Transfer ownership is saved before activation, and stale session messages cannot
reuse a previous grant. Broader overlay compatibility and power impact still need
testing on additional devices.
