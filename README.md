# Vizzy.io Wrapper

An unofficial native Android wrapper for **[Vizzy.io](https://vizzy.io/)**, built for a desktop-style editing experience on Android.

> This project is not affiliated with or endorsed by Vizzy. The Vizzy name and supplied logo artwork belong to their respective owner(s).

## Features

- Opens the Vizzy editor directly at `https://vizzy.io/editor`
- Fully immersive fullscreen UI with transient system bars
- Hardware-accelerated Android WebView with JavaScript, WebGL/Web Audio, DOM storage, cookies, and large-heap support
- Recommended **Windows Chrome 150** desktop user agent by default
- User-agent presets for Windows Chrome, Windows Edge, Linux Chrome, macOS Chrome, Android WebView, and a custom UA
- Desktop viewport presets: device width, 980, 1280, 1440, and 1920 px
- Automatic, landscape, and portrait orientation modes
- Native multi-file picker for audio, images, video, fonts, and project files
- Direct HTTP(S) downloads through Android Download Manager
- Native bridge for `blob:` downloads
- Streaming `showSaveFilePicker()` compatibility bridge for WebViews that do not expose the desktop File System Access API
- Persistent cookies, login state, and website storage
- Camera and microphone permission bridging when requested by the website
- Fullscreen custom-view support, external-link handling, renderer-crash recovery, and WebView debugging in debug builds
- Supplied Vizzy waveform artwork recreated as the app icon
- Supplied wide Vizzy logo composition recreated as the immersive splash screen

## Wrapper controls

Tap the floating **⋮** button to open native controls. The button can be hidden; press Android **Back** while on the editor's root page to reopen the control panel.

The default setup is:

- Browser identity: Windows Chrome 150
- Layout viewport: 1280 px desktop
- Orientation: automatic
- Immersive mode: always enabled

## Building

Requirements:

- JDK 17
- Android SDK platform 35
- Android build tools 35.0.0
- Gradle 8.9

Build the signed release APK:

```bash
gradle assembleRelease
```

The APK is written to:

```text
app/build/outputs/apk/release/app-release.apk
```

GitHub Actions also builds and uploads `Vizzy.io-Wrapper-v1.0.0.apk` after each push to `main`.

## Signing

The repository includes a Base64-encoded dedicated app-installation key that Gradle decodes during configuration, so builds from this repository retain the same Android signing identity and can update each other.

This key is intentionally **not suitable for Google Play or security-sensitive production distribution**, because the repository is public. A future Play Store release should use a private upload key stored outside the repository.

## Compatibility notes

Android WebView uses Chromium, but it is not identical to desktop Chrome. The wrapper fills several gaps with native file and export bridges. Actual rendering and codec support still depend on the installed **Android System WebView** version and the device GPU.

Google may reject OAuth sign-in from embedded browsers. Vizzy username/password login should remain in the wrapper; Google sign-in may need to be completed in an external browser.
