# Device setup and safe restoration

Use an already authorized ADB connection. Do not enable unauthenticated ADB on an
untrusted network. Replace the example endpoint with your frame's address:

```sh
adb connect FRAME_IP:5555
adb devices -l
adb install -r build/photo-frame-saver-release.apk
adb shell am start -n io.github.martinwoodward.iphotoframesaver/.MainActivity
```

Enter the album in the app. **Do not put album links in ADB intent extras, shell
history, public scripts, CI secrets, bug reports, or Git.** There is no exported
configuration receiver or deep link for supplying private albums. For automated
local development, a debug build's app-private preferences can be provisioned
through `run-as` using a private input file/stdin; never publish that file.

## Android screensaver

Prefer the system settings opened by the app. Where a vendor hides that UI, first
record the original values somewhere private/ignored:

```sh
mkdir -p .local
adb shell settings get secure screensaver_components > .local/original-screensaver-component.txt
adb shell settings get secure screensaver_enabled > .local/original-screensaver-enabled.txt
adb shell settings get secure screensaver_activate_on_sleep > .local/original-screensaver-sleep.txt
adb shell settings get secure screensaver_activate_on_dock > .local/original-screensaver-dock.txt
```

Configure this component without disabling unrelated applications or replacing
the launcher:

```sh
adb shell settings put secure screensaver_components io.github.martinwoodward.iphotoframesaver/.FrameDreamService
adb shell settings put secure screensaver_enabled 1
adb shell settings put secure screensaver_activate_on_sleep 1
```

On Android 6, the hidden dream-manager Binder interface can start a dream for
testing:

```sh
adb shell service call dreams 1
adb shell dumpsys dreams
```

The service-transaction number is **version-specific**; do not assume it is the
same on other Android releases. Use the system's **Start now**/**Preview** option
instead on newer devices. A launch of `SlideshowActivity` is **not** proof that a
screensaver works: `dumpsys dreams` should identify `FrameDreamService` as the
current dream, and photos should continue changing.

Exit a dream by touching the display. There is no need to change screen-off
timeouts, force a device to stay awake globally, disable a vendor launcher, or
wipe any data. The slideshow keeps the screen awake only while it is visible;
the dream uses the normal DreamService lifecycle.

If the device's screen-off timeout is set to never sleep, touching the dream can
leave it stopped until another system dream trigger or an explicit start. The
app does not override that setting or force a screensaver over someone using the
device.

A separate Kiosk app may draw its own navigation controls using an Android
accessibility overlay. On Android 6 the screensaver cannot hide another app's
accessibility window through immersive/fullscreen flags. That Kiosk app needs a
scoped integration to hide its controls while a dream is visible and restore
them afterward. Disabling an accessibility service globally or replacing the
launcher is not an appropriate substitute.

Companion apps can query the read-only URI
`content://io.github.martinwoodward.iphotoframesaver.playback/dream`. It returns one
row with the integer column `active` (`1` while this DreamService is running,
otherwise `0`). The provider notifies observers at lifecycle transitions. There
are no writable methods and no album, photo, clock, or settings fields. State is
process-local and resets to inactive after process death. A companion should
restore its controls if the provider is absent/unavailable and recheck while
hidden so a process death cannot leave navigation hidden.

## Restore

Exit the dream first. Restore **each value that you changed** from your private
record:

```sh
adb shell settings put secure screensaver_components ORIGINAL_COMPONENT
adb shell settings put secure screensaver_enabled ORIGINAL_ENABLED_VALUE
adb shell settings put secure screensaver_activate_on_sleep ORIGINAL_SLEEP_VALUE
```

If a saved value was the literal `null` (the key did not exist), use `settings
delete secure KEY` instead of writing the string `null`. Leave keys you did not
change alone. If you changed activation-on-dock, restore that separately using
the same rule. Restore the original component **before** uninstalling this app.
These commands deliberately contain no person's actual component, address, or
album information.

## Local diagnostics

The app maintains an app-private `files/health.json` with the playback surface
(`activity` or `dream`), render count, active state, photo count, sampled bitmap
dimensions, interval, refresh mode, storage mode, and whether the optional clock
is enabled. It contains **no album URL, photo identifier, title, or resource URL**.
A debug build can read it using:

```sh
adb exec-out run-as io.github.martinwoodward.iphotoframesaver cat files/health.json
```

`run-as` is intentionally unavailable in a release build. Do not dump all device
logs, preferences, or unrelated apps' data. Keep screenshots private: they may
contain personal photos even when the UI itself contains no secret.
