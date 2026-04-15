# DualFrame

**Android MVP** — Record once, export twice. One rear camera, two aspect ratios.

DualFrame captures a single high-quality master video from your rear camera and automatically exports two versions:
- **16:9** for YouTube / landscape content
- **9:16** for Shorts / Reels / TikTok

## How It Works

1. **One camera stream** — Uses CameraX with a single rear camera pipeline. No dual-camera tricks.
2. **Live crop guides** — Two overlay rectangles on the preview show exactly what each export will frame (16:9 and 9:16 center crops).
3. **One master recording** — Records a single high-quality file (prefers UHD, falls back to FHD/HD).
4. **Two automatic exports** — After recording stops, Media3 Transformer crops the master file into both aspect ratios sequentially.
5. **Gallery-ready** — Exported files are saved to `Movies/DualFrame/` via MediaStore and appear in your gallery.

## Architecture

```
com.dualframe/
├── camera/
│   └── CameraManager.kt       # CameraX preview + video recording
├── data/
│   └── UiState.kt             # UI state model + AppStatus enum
├── export/
│   └── ExportManager.kt       # Media3 Transformer crop/export pipeline
├── ui/
│   ├── MainActivity.kt        # Permission handling + entry point
│   ├── MainScreen.kt          # Compose UI (preview, controls, guides)
│   └── theme/
│       └── Theme.kt           # Material3 dark theme
├── util/
│   ├── FileStorage.kt         # File creation + MediaStore integration
│   └── TimeFormat.kt          # Recording timer formatting
├── viewmodel/
│   └── MainViewModel.kt       # MVVM coordinator (camera, recording, export, state)
└── DualFrameApp.kt            # Application subclass
```

### Key Libraries

| Library | Version | Purpose |
|---------|---------|---------|
| CameraX | 1.4.1 | Camera preview + video recording |
| Media3 Transformer | 1.5.1 | Post-recording crop/export |
| Jetpack Compose (BOM 2024.12) | — | UI framework |
| Material3 | — | Dark theme, components |

## Requirements

- **Android Studio**: Ladybug (2024.2.1) or later
- **JDK**: 17
- **Min SDK**: 29 (Android 10)
- **Target SDK**: 35
- **Device**: Physical Android device with rear camera (emulator won't work for real camera testing)

## Setup & Build

```bash
# 1. Clone the repo
git clone https://github.com/sunhee-lee/dualframe.git
cd dualframe

# 2. Open in Android Studio
#    File → Open → select the `dualframe` directory

# 3. Sync Gradle
#    Android Studio will prompt to sync; click "Sync Now"

# 4. Connect a physical device
#    USB debugging must be enabled

# 5. Run
#    Click Run ▶ or: ./gradlew installDebug

# Or build from command line:
./gradlew assembleDebug
```

## Permissions

The app requests these at runtime:

| Permission | Required | Purpose |
|------------|----------|---------|
| `CAMERA` | Yes | Camera preview and recording |
| `RECORD_AUDIO` | No (optional) | Record audio with video |

If camera permission is denied, the app shows a clear prompt to grant it. Audio permission denial is gracefully handled — video records without audio.

No storage permissions are needed: the app uses scoped storage (app cache for temp files) and MediaStore API for saving to gallery.

## Usage

1. Launch the app → grant camera permission
2. You'll see the live camera preview with two crop guide overlays:
   - **Cyan solid rectangle**: 16:9 landscape crop region
   - **Yellow dashed rectangle**: 9:16 portrait crop region
3. Tap the **record button** (white circle) to start recording
4. The button turns red with a stop icon; a blinking timer shows duration
5. Tap **stop** to end recording
6. The app automatically exports:
   - 16:9 version (progress shown)
   - Then 9:16 version (progress shown)
7. Both files appear in `Movies/DualFrame/` in your gallery
8. Tap **New Recording** to reset

## Recording Quality Strategy

The app tries to record at the highest stable quality:
1. **UHD (4K)** — preferred for maximum crop headroom
2. **FHD (1080p)** — fallback if UHD unsupported
3. **HD (720p)** — last resort

This is configured via CameraX `QualitySelector` with automatic fallback.

## Export Pipeline

After recording:
1. The master file is center-cropped to 16:9 using Media3 Transformer's `Crop` effect
2. Then center-cropped to 9:16 using the same pipeline
3. Each export is hardware-accelerated where available
4. Exports run sequentially to avoid GPU contention
5. Audio is preserved in both exports

## Known Limitations (MVP)

These are deliberate scope limits for the MVP, not bugs:

1. **No true dual live preview**: CameraX only supports one Preview surface at a time. The app uses crop guide overlays instead of two independent live views. This is the most robust approach. A future version could use a custom GL renderer to feed two TextureViews.

2. **Crop math assumes 16:9 source**: The export `Crop` effect assumes the master recording is ~16:9 (standard rear camera aspect). If a device records at a different native aspect (e.g., 4:3), the crop coordinates will be slightly off. A future version should read the actual video dimensions and compute crop dynamically.

3. **No zoom/focus controls**: MVP uses auto-focus and 1x zoom. Tap-to-focus and pinch-to-zoom are natural future additions.

4. **No preview of export results**: After export, files appear in the gallery but the app doesn't show a playback preview. Add an in-app player with Media3 ExoPlayer.

5. **Sequential export only**: Exports run one at a time to avoid GPU contention. Parallel export could reduce total time but risks OOM or encoder errors on lower-end devices.

6. **Fixed portrait orientation**: The app is locked to portrait. Supporting landscape recording would require reworking the crop guide math and layout.

7. **No ProGuard/R8 optimization**: Minification is disabled for MVP. Enable for release builds.

8. **Launcher icon is placeholder**: Uses a basic adaptive icon. Replace with a proper designed icon.

## Future Improvements (Roadmap)

### Phase 2 — Quality
- [ ] Read actual video dimensions before export for precise crop math
- [ ] Add resolution/quality picker in settings
- [ ] Add bitrate control for exports
- [ ] Implement tap-to-focus and pinch-to-zoom
- [ ] Support landscape recording orientation

### Phase 3 — UX
- [ ] In-app video playback after export
- [ ] Custom crop positioning (drag the guide boxes)
- [ ] Share button for each export
- [ ] Recording countdown timer
- [ ] Haptic feedback on record start/stop
- [ ] Dark/light theme toggle

### Phase 4 — Advanced
- [ ] True dual live preview via custom GL renderer
- [ ] Parallel export with resource management
- [ ] Background export with notification progress
- [ ] Cloud upload integration
- [ ] Multiple aspect ratio presets (1:1, 4:5, etc.)

## License

MIT — see LICENSE file.
