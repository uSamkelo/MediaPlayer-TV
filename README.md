# Media Player (Android TV)

Netflix-style home screen for videos on a USB SSD. Compose for TV UI, Media3 playback.

## Build
1. Open this folder in Android Studio (Koala or newer) and let Gradle sync (it generates the wrapper).
2. Run on an Android TV device or emulator.

## Use
- First launch: **Choose folder** and pick the folder on your SSD. No storage permission needed; access is remembered.
- D-pad to browse (the backdrop follows focus), OK to play, Back to return.
- Partly watched videos appear under **Continue watching** and resume automatically.
- **Rescan** after adding files; **Change folder** to switch drives.

## Notes
- Format support depends on the TV's decoders (MP4/H.264/H.265 safest; MKV usually fine; some DTS/AC3 audio may be silent).
- Package: com.ssdplayer.tv
