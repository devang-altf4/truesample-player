# TrueSample Player

**Bit-perfect USB DAC playback on Android. Free and open source, no root needed.**

TrueSample Player sends your music straight to a USB DAC or dongle using its own
USB audio driver, skipping Android's audio mixer. The samples in your file reach
the DAC unchanged, at their real sample rate and bit depth.

## Why it's needed

When you plug a USB DAC into an Android phone, every app's sound goes through
Android's audio mixer (AudioFlinger) first. On most phones the mixer:

- **resamples everything to one fixed rate**, usually 48 kHz, so a 44.1 kHz CD
  rip or a 96/192 kHz hi-res file is converted before your DAC ever sees it;
- **mixes and processes the audio** (volume scaling, effects, other apps' sounds),
  so the bits that arrive are not the bits in the file;
- **never uses the DAC's higher modes**, even when it supports 192 kHz / 32-bit.

Android 14 added an official bit-perfect USB mode, but phone makers have to turn
it on, and most haven't. The apps that work around this are paid and closed
source. TrueSample Player does the same job for free, in the open, so anyone can
check exactly what happens to their audio.

## What it does

- **Takes exclusive control of the DAC** over USB with libusb (no root) and
  streams PCM itself with isochronous transfers.
- **Bit-perfect when possible:** the DAC runs at the file's own sample rate, and
  samples go out untouched. When the DAC can't (e.g. 44.1 kHz on a 48 kHz-only
  dongle), it converts with a high-quality resampler and TPDF dither, and says so.
- **Signal path display:** `SOURCE → RATE → DEPTH → DAC`, each stage lit green when
  audio passes untouched and amber when it's altered, with a plain-words reason.
- **Output mode picker:** lists every mode your DAC really supports (read from the
  DAC itself). Pick *Auto* to match each song, *MAX* for the DAC's best mode, or any
  mode you like.
- **Hardware volume:** set inside the DAC, so the audio data is never scaled. It
  starts quiet to protect sensitive IEMs, and the DAC's original volume is restored
  when you give it back to Android.
- **Plays almost any format:** FLAC, WAV, AIFF, ALAC, WavPack, APE, TAK, MP3, AAC/M4A,
  Ogg Vorbis, Opus, MKA, CAF and DSD (as PCM for now).
- **Albums and cover art:** albums grouped from your files' tags (sorted by disc and track), with
  covers from the file itself, Android's thumbnails, or a `cover.jpg` in the folder.
- **Choose your sources:** all music on the phone and/or specific folders, including files Android
  doesn't index (DSF, APE, WavPack). Short voice clips are hidden by default.
- **Background playback** with a media notification and lock-screen controls (play/pause,
  previous/next, close), plus the play/pause buttons on headsets and many USB DACs.
- **Pause and resume where you left off**, a seek bar, and a play queue (next/previous, auto-advance).
- **Graphic EQ** (10 bands with presets) and **parametric EQ** (up to 8 peak/shelf filters), with a
  live frequency-response curve and automatic preamp so boosts never clip. The EQ shapes the sound,
  so playback isn't bit-perfect while it's on, and the signal path says so.
- **Music library** with search, and each track's real format read from the file.
- **Share diagnostics:** one tap sends the DAC's descriptors and driver logs, for
  reporting problems with a specific DAC.

## Compatibility

| | Status |
|---|---|
| Android | 8.0+ (arm64 phones) |
| USB Audio Class 1 DACs (synchronous/adaptive) | ✅ Tested: Portronics USB-C dongle on an iQOO Z7 Pro (Android 16) |
| USB Audio Class 2 DACs (most hi-res dongles: async with feedback) | 🧪 Implemented and unit-tested, not yet confirmed on hardware |
| DSD native / DoP, MQA | ❌ Not supported (DSD files play as PCM; MQA is proprietary) |

If your DAC doesn't work, open **LOG → Share diagnostics** in the app and attach
the file to an issue.

## Install

Download the APK from the [latest release](../../releases/latest), open it on your
phone and allow installs from that source when Android asks. Play Protect may warn
about an unknown developer, because the app isn't from the Play Store. Tap
**Install anyway**.

Then plug in your DAC, open TrueSample Player, pick a song and press **Play**.
Android will ask once whether the app may use the USB device.

> While TrueSample Player holds the DAC, other apps can't use it. Tap
> **Give back** to return it to Android.

## How it works

```
 file ──▶ decoder ──▶ int32 PCM ──▶ [resample + dither, only if the DAC needs it]
                                              │
                                              ▼
 DAC ◀── USB isochronous packets ◀── ring buffer ◀── pack to the DAC's slot size
   │
   └── feedback endpoint (async DACs) ──▶ packet sizes follow the DAC's own clock
```

1. Android's `UsbManager` grants the app access to the DAC. The app claims its
   audio interfaces, which detaches Android's kernel driver (`snd-usb-audio`).
2. The USB file descriptor is handed to **libusb** (`libusb_wrap_sys_device`), so
   no root is needed.
3. The DAC's descriptors are parsed (UAC1 and UAC2: formats, clocks, feature units,
   feedback endpoints), and the best mode for each song is chosen.
4. Audio is sent in isochronous packets sized from the sample rate, or from the
   DAC's feedback for asynchronous DACs.
5. On release, the DAC's volume is restored and Android's driver is reattached.

## Project layout

```
usbaudio/   Reusable Android library (Apache-2.0): the USB audio driver
  src/main/cpp/uac/      descriptor parser, packet scheduling, dither, ring buffer (pure C++)
  src/main/cpp/          libusb streaming, UAC1/UAC2 control requests, JNI
  src/test/cpp/          unit tests, run on a phone via adb
app/        TrueSample Player (GPL-3.0): Jetpack Compose UI, library, FFmpeg decoding
scripts/    build-ffmpeg.sh, run-native-tests.sh
third_party/ libusb, libsamplerate, dr_libs, fonts (FFmpeg is built by the script)
```

### Using the library in your own app

```kotlin
val output = UsbAudioOutput.open(usbManager, device)    // after USB permission is granted
val info = output.start(sampleRate = 44100, bitsPerSample = 24, channels = 2)
// info.bitPerfect, info.outputRate, info.resampled ...
output.write(pcm, frames)   // direct ByteBuffer, interleaved int32, left-justified
output.drain(); output.stop(); output.close()
```

## Building from source

Requirements: Android SDK with NDK `28.2.13676358` and CMake `3.22.1`, JDK 17+
(Android Studio's bundled JBR works).

```bash
# 1. Build the audio-only FFmpeg (once). On Windows run this from Git Bash.
ANDROID_HOME=/path/to/Android/Sdk scripts/build-ffmpeg.sh /tmp/ffmpeg-android

# 2. Build the app
./gradlew assembleDebug

# 3. Run the native unit tests on a connected phone
ANDROID_HOME=/path/to/Android/Sdk scripts/run-native-tests.sh
```

Release builds are signed with a key you provide in a `keystore.properties` file
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). That file is git-ignored.

## Roadmap

- Confirm and tune UAC2 on real hi-res dongles
- Native DSD (DoP), gapless playback, playlists
- Implicit-feedback DACs, more CPU architectures

## Credits and licences

- TrueSample Player app: **GPL-3.0** ([LICENSE](LICENSE))
- `usbaudio` library: **Apache-2.0** ([usbaudio/LICENSE](usbaudio/LICENSE))
- [libusb](https://libusb.info): LGPL-2.1, shipped as its own shared library
- [FFmpeg](https://ffmpeg.org): LGPL-2.1, built from source by `scripts/build-ffmpeg.sh`
- [libsamplerate](https://libsndfile.github.io/libsamplerate/): BSD-2-Clause
- [dr_flac / dr_wav](https://github.com/mackron/dr_libs): public domain / MIT-0
- Fonts Michroma, Barlow and Share Tech Mono: SIL Open Font License

TrueSample Player is an independent project and is not affiliated with any DAC
maker or other audio app.
