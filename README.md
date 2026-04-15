# DualFrame

**Android MVP** — Record once, export twice. One rear camera, two aspect ratios.

DualFrame captures a single high-quality master video from your rear camera and automatically exports two versions:
- **16:9** for YouTube / landscape content
- **9:16** for Shorts / Reels / TikTok

## How It Works

1. **One camera stream** — Uses CameraX with a single rear camera pipeline (Preview + ImageAnalysis + VideoCapture).
2. **Dual live preview** — Two separate live video regions on screen:
   - **Top**: 16:9 landscape preview (native CameraX PreviewView, full framerate)
   - **Bottom**: 9:16 portrait preview (ImageAnalysis frames, center-cropped, displayed as bitmap)
3. **One master recording** — Records a single high-quality file (automatic quality: UHD→FHD→HD fallback).
4. **Two automatic exports** — After recording stops, Media3 Transformer center-crops the master file into both aspect ratios using actual video metadata for accurate crop math.
5. **Gallery-ready** — Exported files saved to `Movies/DualFrame/` via MediaStore.

## Architecture

```
com.dualframe/
├── camera/
│   └── CameraManager.kt        # CameraX pipeline: Preview + ImageAnalysis + VideoCapture
├── data/
│   ├── AppSettings.kt          # Settings model + SharedPreferences persistence
│   └── UiState.kt              # UI state model + AppStatus enum
├── export/
│   └── ExportManager.kt        # Media3 Transformer crop/export with real metadata
├── ui/
│   ├── MainActivity.kt         # Permissions + keep-screen-awake
│   ├── MainScreen.kt           # Top-level Compose scaffold
│   ├── RecordControls.kt       # Record button states + timer + countdown
│   ├── SettingsSheet.kt        # Settings bottom sheet
│   └── theme/
│       └── Theme.kt            # Material3 dark theme
├── util/
│   ├── FileStorage.kt          # File creation + MediaStore integration
│   ├── TimeFormat.kt           # Timer formatting
│   └── VideoMetadata.kt        # MediaMetadataRetriever for actual video dimensions
├── viewmodel/
│   └── MainViewModel.kt        # MVVM coordinator
└── DualFrameApp.kt             # Application subclass
```

## Settings

| Setting | Options | Default |
|---------|---------|---------|
| Audio Recording | On / Off | On |
| Countdown | Off / 3s / 5s | Off |
| Keep Screen Awake | On / Off | On |
| Show Crop Guides | On / Off | On |

## Dual Preview Architecture

CameraX only supports one `Preview` surface. To show two separate live video regions:

1. **Top: 9:16 portrait** — `ImageAnalysis` captures frames at 640×480, rotates, center-crops to 9:16, and emits as `Bitmap` via `StateFlow`, displayed as a Compose `Image`
2. **Bottom: 16:9 landscape** — native CameraX `PreviewView` renders at full framerate
3. Both regions are live and derived from the same rear camera source
4. If a device can't bind all 3 use cases (rare on minSdk 29+), the app falls back to single preview with overlay guides

## Export Crop Math

The export pipeline reads actual video metadata using `MediaMetadataRetriever`:

1. Extract `METADATA_KEY_VIDEO_WIDTH`, `METADATA_KEY_VIDEO_HEIGHT`, `METADATA_KEY_VIDEO_ROTATION`
2. Compute display dimensions (swap width/height for 90°/270° rotation)
3. Calculate center-crop in Media3's normalized [-1, 1] coordinate space:
   - If target is wider than source → crop top/bottom
   - If target is taller than source → crop left/right
   - If aspects match → pass-through (no crop)
4. Validate bounds before applying

## Requirements

- **Android Studio**: Ladybug (2024.2.1) or later
- **JDK**: 17
- **Min SDK**: 29 (Android 10)
- **Target SDK**: 35
- **Device**: Physical Android device with rear camera

## Setup & Build

```bash
git clone https://github.com/sunhee-lee/dualframe.git
cd dualframe
# Open in Android Studio → Sync Gradle → Run on physical device
# Or: ./gradlew assembleDebug
```

## Test Steps

| Feature | How to Test |
|---------|-------------|
| Audio on/off | Settings → Audio Recording → toggle off → record → verify no audio in playback |
| Countdown | Settings → Countdown → 3s → tap record → verify 3-2-1 countdown before recording starts |
| Keep screen awake | Settings → toggle off → wait → screen should dim normally; toggle on → screen stays on |
| Guide toggle | Settings → Show Crop Guides → toggle → verify guide borders appear/disappear on preview |
| Recording flow | Tap record → see REC status + timer → tap stop → see export 16:9 → export 9:16 → Done |
| Open action | After export → tap Open → should launch video player |
| Share action | After export → tap Share → should show share sheet |
| Folder action | After export → tap Folder → should open gallery/video collection |

## Known Limitations

1. **9:16 preview runs at lower resolution** — ImageAnalysis captures at 640×480 for performance. The 9:16 preview is functional but not as crisp as the native 16:9 preview.
2. **Sequential exports** — 16:9 and 9:16 exports run one after the other to avoid GPU contention.
3. **Portrait orientation only** — App is locked to portrait.
4. **"Show Folder" is approximate** — Android doesn't support opening a specific folder reliably. The action opens the device's default video gallery.
5. **No playback preview** — After export, files can be opened via the Open button but there's no in-app player.

## License

MIT
