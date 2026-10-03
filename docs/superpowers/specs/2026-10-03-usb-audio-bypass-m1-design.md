# Free Audio Bypasser — Milestone 1 design

Date: 2026-10-03
Status: approved (architecture), build started

## Goal

Open-source, free alternative to paid "USB audio player" apps on Android.
Milestone 1 (M1) is a **proof of concept**: an Android app that takes exclusive
control of a USB DAC and streams PCM to it directly over USB (no root),
bypassing Android's AudioFlinger mixer/resampler, so 48 kHz/24-bit FLAC plays
bit-perfect.

Scope order: **A** (proof-of-concept demo) + **C** (reusable library) now,
**B** (full music player) later.

## Test hardware

- Phone: iQOO Z7 Pro (I2302), Android 16 / SDK 36, MediaTek MT6886, arm64-v8a.
  Vendor has **not** enabled Android 14+ `MIXER_BEHAVIOR_BIT_PERFECT`, so a
  userspace driver is the only bit-perfect path on this phone.
- DAC: Portronics USB-C dongle, reports as `Generic GHW-123P`, VID 0x0020 PID 0x0B21.
  USB Audio Class 1, full speed. Iface 0 = AudioControl, iface 1 alt 1 =
  streaming OUT, iso EP 0x03, **synchronous**, wMaxPacketSize 384, bInterval 1.
  Also has a mic (iface 2) and HID buttons (iface 3), both ignored.
  384 B/ms ⇒ at most 48 kHz × 2 ch × 4 B, or 96 kHz × 2 ch × 2 B.
- Test files (git-ignored, copyrighted): two 48 kHz/24-bit FLAC, one 44.1 kHz/16-bit FLAC.

## Architecture

```
usbaudio/   Android library (Apache-2.0) — the reusable part
  cpp/libusb/        third-party libusb (LGPL-2.1), built as its own .so
  cpp/uac/           pure C++, no Android deps, unit-tested:
    UacDescriptors   parse raw config descriptors -> formats, alt settings, feature unit
    PacketScheduler  frames per iso packet for a given rate (e.g. 44/45 pattern for 44.1k)
    PcmConvert       int32 left-justified -> DAC subslot (2/3/4 bytes)
    RingBuffer       SPSC byte ring
  cpp/UsbStreamer    libusb iso OUT transfers, event thread, underrun -> silence
  cpp/UacControl     SET_CUR sample rate (endpoint), volume/mute (feature unit)
  cpp/jni            bridge
  kotlin/UsbAudioOutput   public API
app/        demo player (GPL-3.0)
  cpp/decoder        dr_flac / dr_wav (public domain) over a file descriptor
  MainActivity       pick DAC + file, show descriptors, play/stop, volume, status
```

### Public library API (Kotlin)

```kotlin
UsbAudioOutput.open(connection: UsbDeviceConnection, device: UsbDevice): UsbAudioOutput
  .dacInfo: DacInfo              // formats (rate list, bits, channels), volume range
  .rawDescriptors: ByteArray
  .start(sampleRate, bitsPerSample, channels): StreamInfo   // isBitPerfect, chosen subslot
  .write(pcm: ByteBuffer /* direct, int32 LE left-justified, interleaved */, frames): Int  // blocks
  .setVolumeDb(db: Float) / .volumeRangeDb
  .stats(): underruns, framesSent
  .stop(); .close()
```

PCM at the API boundary is always 32-bit signed, left-justified (24-bit sample
`<< 8`, 16-bit sample `<< 16`). The library converts to the DAC's subslot
without altering sample values. Output is **bit-perfect** when the DAC supports
the source rate and its bit resolution ≥ source bits; otherwise the library
refuses the format (M1 has no resampler).

## USB driver behaviour

1. App requests USB permission (explicit `PendingIntent`, `FLAG_MUTABLE`).
2. Kotlin `claimInterface(iface, force = true)` on AudioControl and the streaming
   interface — this detaches Android's `snd-usb-audio` kernel driver. Phone audio
   falls back to the speaker while we own the DAC.
3. Native: `libusb_set_option(NO_DEVICE_DISCOVERY)`, `libusb_init`,
   `libusb_wrap_sys_device(fd)`, `libusb_claim_interface` (no-op re-claim).
4. Choose alt setting whose format matches; `set_interface_alt_setting`;
   UAC1 `SET_CUR SAMPLING_FREQ` to the endpoint (if the endpoint supports it),
   read back with `GET_CUR`.
5. Streaming: 8 iso transfers in flight × 10 packets (1 ms each at full speed)
   ≈ 80 ms buffer. Synchronous EP ⇒ frames/packet from `PacketScheduler`
   (no feedback endpoint in M1; async/feedback is M2 with UAC2).
   Each completed transfer is refilled from the ring buffer and resubmitted;
   underrun pads with silence and increments a counter.
6. Volume: hardware Feature Unit volume (data stays bit-perfect). On start the
   volume is set **low (−30 dB)** to protect ears with sensitive IEMs.
7. Stop/close: cancel transfers, set alt 0, release interfaces,
   `libusb_attach_kernel_driver` so Android gets the DAC back.

### Errors

- Device unplugged (`LIBUSB_ERROR_NO_DEVICE` / transfer status NO_DEVICE) →
  stop streaming, report to UI, close cleanly.
- Permission denied / claim fails → message in UI.
- Unsupported format → `start()` throws with the reason ("DAC has no 44100 Hz").
- UAC2 device → reported as "UAC2 not supported in M1".

## Testing

- `uac/` unit tests: plain C++ test executable built with the NDK, pushed to
  the phone with adb and run from `/data/local/tmp` (no host compiler needed).
  Fixture = the GHW-123P descriptor layout.
- Manual on-device: play the three test files; status must read
  `48000 Hz · 24-bit → USB direct · BIT-PERFECT`, underruns stay 0, unplug is handled.

## Out of scope for M1

UAC2 / async feedback endpoints, resampling, DSD, MP3/APE/WavPack, library
browsing, playlists, EQ, background playback service, MQA (never — proprietary).

## Build setup

AGP 8.13, Gradle 8.14.3, Kotlin 2.2, NDK 28.2, CMake 3.22.1, compileSdk 36,
minSdk 26, ABI arm64-v8a (more ABIs later). CLI builds use Android Studio's JBR
(Java 21) as `JAVA_HOME`.
