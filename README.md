# YT Converter

An Android app that turns a video link into an audio file and drops it straight into
the device's Music folder. Choose **MP3 (320 kbps)**, **FLAC**, or **Original**.

Everything runs on the device: the app bundles
[yt-dlp](https://github.com/yt-dlp/yt-dlp) (via
[youtubedl-android](https://github.com/yausername/youtubedl-android)) plus FFmpeg, so
there is no server and no account. Once installed it works offline, apart from the
actual download.

## Install

Grab one APK from `app/build/outputs/apk/release/`:

| File | Size | Use it when |
| --- | --- | --- |
| `app-arm64-v8a-release.apk` | ~77 MB | **Recommended.** Any phone made since ~2017. |
| `app-armeabi-v7a-release.apk` | ~71 MB | Older 32-bit-only devices. |
| `app-universal-release.apk` | ~118 MB | You are not sure; works on either. |

Copy it to the phone, tap it, and allow "install unknown apps" for whatever app you
opened it from. The APK is signed with the debug-independent key in
`app/keystore/release.jks`.

You can also share a link to the app from YouTube or a browser — it is registered as a
`text/plain` share target.

## Build

Requirements: JDK 17+ and an Android SDK with **platform 37** and **build-tools 36.0.0**.
`local.properties` already points at the SDK on this machine.

```sh
JAVA_HOME=/path/to/jdk ./gradlew :app:assembleRelease
```

Release signing reads `keystore.properties` (git-ignored):

```properties
storeFile=app/keystore/release.jks
storePassword=…
keyAlias=ytconverter
keyPassword=…
```

If that file is missing, the release build simply comes out unsigned — useful for a
quick `:app:assembleDebug` instead.

### Toolchain notes

This project targets AGP 9, which **compiles Kotlin itself**:

* Do **not** apply `org.jetbrains.kotlin.android` — AGP 9 raises an error if you do.
  Built-in Kotlin (`android.builtInKotlin`, on by default) is used instead.
* AGP 9.3.2 ships Kotlin Gradle plugin **2.2.10**, so the Compose compiler plugin is
  pinned to 2.2.10 to match.
* Libraries therefore have to be built against Kotlin ≤ 2.3. Coil is pinned to 3.3.0
  for exactly this reason — 3.4+ is compiled against Kotlin 2.3/2.4 and the 2.2.10
  compiler cannot read its metadata.
* `packaging.jniLibs.useLegacyPackaging = true` is required: the bundled Python and
  FFmpeg payloads are shipped as compressed `.so` archives that the app unpacks to disk
  at runtime, which only works when native libraries are extracted from the APK.

## How it works

```
MainActivity ──▶ MainViewModel ──▶ DownloadBus (StateFlow)
                      │                    ▲
                      │ probe metadata     │ progress / events
                      ▼                    │
                YtDlpEngine  ◀──── DownloadService (foreground, dataSync)
                      │                    │
              yt-dlp + FFmpeg              ▼
                                  MediaPublisher ──▶ Music/<folder> via MediaStore
```

* `MainViewModel.probe()` reads title, uploader, thumbnail and duration with
  `--dump-single-json` so the progress card is populated before the download starts.
* `DownloadService` runs one conversion at a time in the foreground, so it survives the
  screen turning off. Cancelling calls `destroyProcessById`, which makes the library
  raise `CanceledException`.
* If post-processing fails after the audio has already been written, the file is still
  published rather than thrown away.
* Finished files are inserted into `MediaStore` with `IS_PENDING` so they appear in
  every music player, under `Music/<Save to>/`.
* History is a small JSON blob in `SharedPreferences` and can be cleared from the list
  header; deleting an entry also removes the file.

## Notes and limitations

* **FLAC and 320 kbps MP3 cannot add quality that was never there.** YouTube serves
  lossy audio (Opus/AAC, roughly 128–160 kbps). Re-encoding to 320 kbps MP3 only makes
  the file bigger; FLAC rewraps the same lossy audio in a lossless container. **Original**
  is the only setting that keeps true source quality and is also the smallest of the three.
* YouTube changes its player regularly. If downloads start failing, use
  **Settings → Update engine** to pull the newest yt-dlp.
* `minSdk` is 29 (Android 10) because the app writes through `MediaStore` scoped
  storage instead of requesting legacy storage permissions.
* Downloading content may conflict with YouTube's Terms of Service and with copyright
  law depending on what you download and where you are. This is a personal-use tool;
  use it accordingly. It also cannot be published on Google Play, which prohibits
  video-downloading apps.

## Renaming

The app id (`com.ytconverter`), label (`YT Converter`), and the default save folder
(`YT Converter`) are all easy to change in `app/build.gradle.kts`,
`app/src/main/res/values/strings.xml`, and
`app/src/main/java/com/ytconverter/data/SettingsRepository.kt`.
