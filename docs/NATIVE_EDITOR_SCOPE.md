# Native editor rewrite scope

## Implemented in 2.0.0-alpha1

- Native compositing canvas and touch transforms
- Native timeline transport and layer management
- Audio playback, FFT analysis, spectrum and waveform layers
- Images, videos, text, lyrics, particles, confetti, shapes, and procedural shader layers
- Standard/enhanced LRC import
- Blend modes, keyframes, automations, and an initial native effects collection
- Portable native project format and autosave
- H.264/H.265 + AAC MP4 rendering through MediaCodec, EGL, MediaMuxer, and SAF
- Device-reported custom resolutions and bitrates with no app-level file-size cap
- Foreground exports with progress and cancellation

## Still expanding

- Clean-room equivalents for the remaining Vizzy effects and all specialized analyzer presets
- Butterchurn preset compatibility
- User-provided GLSL shader compilation and validation
- More advanced timeline editing and reusable presets/templates
- Broader codec/container choices and transparent-video export where device codecs support it
- Compatibility import for public Vizzy project/preset formats if a stable documented schema becomes available
