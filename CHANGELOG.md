# Changelog

## 0.0.1 — pre-release

- Initial Android live-wallpaper app for selecting and looping MP4 files.
- Extract a representative palette from five video frames with `WallpaperColors.fromBitmap()`.
- Provide the cached colors through `WallpaperService.Engine.onComputeColors()` and notify Android with `notifyColorsChanged()` when the selection changes.
- Use the Android Storage Access Framework and retain access only to the selected document URI.
- Add Russian and English UI, an unsigned release build, and an fdroiddata metadata template.
