# Photo Frame Saver

A small, native Android photo frame and Android screensaver for **public iCloud
shared albums**. No Apple account, WebView, server, analytics, or runtime libraries.
Designed for touchscreens and older frames, including Android 6.0 (API 23).

## What it does

- Full-screen, center-cropped photos with gentle cross-fades.
- An optional clock and date on a rounded, translucent black panel. Enable
  **Show clock and date** in settings; it follows Android's date, locale, time
  zone, and 12/24-hour preferences and updates when those change. Off by default.
- Randomized cycles covering the whole album before repeating. A startup photo
  counts toward that cycle; successful metadata checks remove deleted photos
  and add new photos without resetting progress through the current cycle.
- A configurable interval: **30 seconds by default**, available during first-run
  setup and in settings.
- **Streaming by default**: download the current full-resolution photo and
  prefetch the **next five**, not the whole album. Images are sampled to a bounded
  screen-sized pixel budget before decoding.
- Separately, the **ten most recently shared photos** are kept in persistent app
  storage for startup, not Android's disposable cache. Setup and metadata checks
  maintain this small pinned set, independently of the current-plus-five streaming
  window (at most sixteen distinct completed files, often fewer).
  After a restart, choose randomly from the saved recent ten before network work,
  then continue a full-album shuffle without repeating that startup photo early.
  Cached startups fade straight into the first image without a loading banner.
  First-ever setup needs a connection, and smaller albums cache all their photos.
- Optional, explicit offline album downloads to app-private storage or a folder
  selected with Android's Storage Access Framework (including writable removable
  storage). Downloads run in a foreground service and can be stopped and resumed.
- Metadata checks **hourly by default**, or daily, weekly, or manual, with a
  **Force check...** button. Background checks use Android's inexact JobScheduler;
  playback also checks when due.
- Persistent settings and album metadata. Loss of connectivity keeps the last
  successful metadata and saved photos. An unavailable album does not erase them.
- A launcher settings screen, a live slideshow, and a genuine `DreamService`
  screensaver using the same playback engine.

Only still images are displayed; videos are excluded. EXIF orientation and album
orientation metadata are honored. When a format is not supported by an old device,
an available JPEG rendition is used instead.

## Install and use

1. Download the APK from [Releases](https://github.com/martinwoodward/iphoto-frame-saver/releases/latest),
   or build one below, and sideload it.
2. Open **Photo Frame Saver**. Paste a public shared album link and choose the
   number of seconds between photos. The link must be one of these forms:

   ```text
   https://photos.icloud.com/shared/album/SyntheticExampleToken123/
   https://www.icloud.com/sharedalbum/#SyntheticExampleToken123
   ```
   You get this by creating a shared album, in the sharing options select `Anyone with the Link` and then press `Copy Link`.
   I do it with a dedicated album called `Frame` that I know should contain images destined for the family dashboard.
   
   The corresponding `icloud.com.cn` routes are supported too. Examples here are
   deliberately synthetic, not working albums.
4. Tap **Start slideshow**. Tap the slideshow to show **Next photo** and
   **Settings** controls.
5. For automatic screensaver playback, tap **Choose Android screensaver**, select
   **Photo Frame Saver**, and choose the device's charging/docking activation
   options. Touching a running screensaver exits it, as on Android's native
   screensavers.

Some frame vendors hide the Android screensaver settings; see [device setup](docs/device-setup.md).
The app does not replace your launcher or change your system timeout.

### Offline playback

Choose **Offline / app-local storage** or **Offline / attached storage**, then
tap **Download / update offline album**. For attached storage, select a writable
folder in Android's folder picker. The folder provider must support creating,
deleting, and renaming files. The app creates its own `Photo Frame Saver`
directory, with opaque album/file names rather than album titles.

Keep the frame powered and connected during the download. Completed files survive
a stopped download; tapping Download again resumes missing files. Do not remove
attached storage during a write. A missing or revoked folder grant produces a
storage error; reconnect/reselect it, or use app-local storage.

Offline copies are retained independently of the six-file streaming window.
Downloading is explicit: metadata checks discover new photos but do not silently
download a whole offline album. Tap Download again to save additions. Existing
offline files are not automatically erased when photos disappear from an album.
They do leave playback rotation at the next successful configured metadata check
or **Force check...**, even if an older offline file remains on storage.
Choose streaming again to stop further bulk downloads; existing offline copies
remain available. Uninstalling the app removes app-private data, but not copies
on storage you selected.

If there is no connection, the slideshow exhausts unseen saved photos first, then
cycles through the available saved subset instead of going blank. It resumes
unfinished full-album progress when connectivity returns; photos that could not
be fetched are not counted as shown. An older cached photo is a startup fallback
if the recent set is not available yet. Before the first successful connection/
download there are no photos to show; an explanation appears instead.

## Build

Requirements: JDK **21**, Android SDK platform **34**, Android build tools
**36.0.0**, Bash, `zip`, `curl`, and `shasum`. There is no Gradle dependency.
Use the official [Android command-line tools](https://developer.android.com/studio#command-tools)
to install SDK packages in a directory you control, and review/accept the SDK
licenses:

```sh
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" 'platforms;android-34' 'build-tools;36.0.0'
bash scripts/test.sh
bash scripts/build.sh release
```

The result is `build/photo-frame-saver-release.apk`. `bash scripts/build.sh debug`
produces a debuggable APK for local device testing. The build compiles resources
with `aapt2`, Java to Java-8 bytecode, desugars with `d8`, then aligns and signs the
APK. Current D8 is needed for JDK 21; the older SDK 34 D8 has compiler compatibility
issues even though API 34 remains the compile/target platform.

By default a **local development signing key** is generated in
`build/signing/local.jks`, with the conventional non-secret `changeit` password.
Keep that key to install updates over your existing local install. It is not a
distribution identity. For distribution use your own protected signing key:

```sh
export FRAME_KEYSTORE=/private/path/to/release.jks
export FRAME_KEY_ALIAS=release
read -rs FRAME_SIGNING_PASSWORD
export FRAME_SIGNING_PASSWORD
bash scripts/build.sh release
unset FRAME_SIGNING_PASSWORD
```

Never commit signing keys, private albums, runtime preferences, APKs containing
custom configuration, or device captures. Build products, `.local/`, and local
browser captures are ignored.

## Tests

```sh
bash scripts/test.sh
```

The JVM tests use synthetic fixtures for both album protocols, pagination,
cross-page master/asset joins, video exclusion, edited images, serialization,
shuffle coverage/boundaries, five-photo look-ahead, interval/refresh policies, and
the decoded pixel budget. A checksum-pinned, test-only `org.json` JAR is downloaded
from Maven Central; the app uses Android's platform JSON API.

Optional Android integration tests require an authorized test device:

```sh
bash scripts/build.sh debug
ADB=adb bash scripts/device-tests.sh
```

These install the debug app and a separate test APK. They use isolated synthetic
preferences/files and a synthetic SAF document provider, never the configured
album. They exercise process-style repository recreation, offline cache reads,
current-plus-five cache bounds, backup policy, SAF writes/reads/reindexing, and
playback start/stop/restart/stale-result rejection. They also cover clock
preferences and system date formatting, clock layering during cross-fades, and
the read-only companion dream-state provider and its lifecycle notifications.
The test APK can be removed:

```sh
adb uninstall io.github.martinwoodward.iphotoframesaver.tests
```

GitHub Actions runs the core tests and release build, and retains the generic APK
as a build artifact. It never needs an album URL or device credentials.

## Privacy and limitations

An iCloud public album URL is a **capability link**: anyone with it may be able to
read the album. Treat it as a secret even if Apple describes the album as public.
The app sends requests only to HTTPS Apple/iCloud API and content hosts. No
credentials are accepted, no personal account pages are scraped, and no data is
uploaded to an application server.

Album links, temporary public-access tokens, resource URLs, titles, and photos are
not logged. The app persists only the photo metadata needed for playback, not
owner profiles or album titles. Preferences and metadata are app-private;
Android backup is disabled. Attached-storage copies are subject to that storage's
access controls. See [privacy and protocol notes](docs/privacy-and-protocol.md).

This project uses **undocumented public Apple web APIs**. It is not affiliated
with, endorsed by, or supported by Apple. These APIs can change or stop working;
sanitized errors and saved photos are the fallback, not a guarantee of future
service availability. Full-resolution means the largest rendition made available
by the shared-album service, which need not be the original camera file.
Unusual image formats without a compatible JPEG rendition may be skipped with an
error. Battery restrictions, vendor firmware, and Android's idle scheduling can
delay background checks; playback still checks while active.

The app has no notification/upload backend and no Play Store publishing setup.
It is intended for sideloading. MIT licensed; see [LICENSE](LICENSE).
