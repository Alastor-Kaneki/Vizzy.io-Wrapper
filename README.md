# Vizzy.io Native for Android

An unofficial, fully native Android audiovisual editor inspired by the feature set of Vizzy.io. Version `2.0.0-alpha2` replaces the WebView as the primary editor; the previous web wrapper remains available from **More → Legacy Vizzy.io web editor** while native parity is expanded.

> This project is not affiliated with or endorsed by Vizzy. The Vizzy name and supplied logo artwork belong to their respective owner(s).

## Native alpha features

- Immersive AMOLED editor UI built with Android views and Canvas; no WebView is used by the primary editor
- Native project canvas with arbitrary custom width, height, frame rate, duration, and background gradient
- Image, video, text, synchronized lyrics, spectrum, waveform, particles, confetti, shape, shader, and camera layer types
- Layer selection, positioning, pinch scaling, two-finger rotation, opacity, blend modes, ordering, duplication, and visibility
- Keyframes with linear, ease-in, ease-out, ease-in/out, and step interpolation
- Audio-reactive spectrum and waveform rendering using Android audio decoding and FFT analysis
- Standard and enhanced `.lrc` import, including metadata, offsets, multiple timestamps, and per-word timing tags
- LRC selection across Android file providers even when `.lrc` is reported with an unknown or non-text MIME type
- Native effects including glow, blur, glitch, VHS, vignette, camera shake, fisheye, kaleidoscope, chroma key, colorize, motion blur, god rays, and sharpening controls
- Crash-safe autosave plus portable JSON project import/export
- Native H.264/AVC and H.265/HEVC MP4 export with AAC audio
- Foreground export service with progress and cancellation
- Existing icon, splash, immersive behavior, package ID, and signing identity retained

## Maximum-size and custom-resolution export

The app does not impose a fixed 4K, duration, or output-file-size ceiling. It queries the encoders installed on the current device and accepts any even resolution/frame-rate combination they report as supported. Video is streamed through Android's Storage Access Framework and a 64-bit file descriptor rather than accumulated in memory.

The practical limits are therefore the selected codec implementation, available RAM for the render surface, free storage, and the destination document provider/filesystem. The export dialog reports the active AVC and HEVC encoder ranges before rendering.

## Current alpha limitations

This is a functional native foundation, not a claim of complete one-to-one compatibility with every Vizzy community preset. Butterchurn preset import, arbitrary third-party GLSL, every historical Vizzy effect, and Vizzy's private project format still require further reverse engineering or clean-room implementations. The legacy wrapper remains included for those cases.

## Building

Requirements:

- JDK 17
- Android SDK platform 35
- Android build tools 35.0.0
- Gradle 8.9

```bash
gradle --no-daemon assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`. GitHub Actions uploads `Vizzy.io-Native-v2.0.0-alpha2.apk`.

## Signing

The repository retains its dedicated installation key, so native alpha builds can update v1.x installations. Because the key is present in a public repository, it is intended for this repository's direct APK distribution rather than security-sensitive store publishing.
