# Changelog

## 0.0.3 — pre-release

- Replace on-the-fly reverse rendering with a separate template-generation step that writes a silent H.264 MP4 in frame order `0…N−1, N−2…1`.
- Keep generated wallpaper videos at the source resolution and store them privately in an in-app library.
- Add template selection, deletion, progress reporting, and cancellation; the live wallpaper now plays the prepared file with MediaPlayer looping.
- Save and report a separate Android wallpaper palette for each template.
- Release the persisted SAF permission after processing; the original video is not modified or retained by the app.

## 0.0.2 — pre-release

- Add default ping-pong playback: play the MP4 forward, then render reverse frames without repeating the first or last endpoint.
- Decode reverse frames asynchronously, one at a time, with a 1080-pixel maximum long edge and a 30 fps lookup cap.
- Resume forward playback after the reverse pass; fall back to a forward loop if reverse frame retrieval is unsupported for a selected file.
- Update Russian/English descriptions, release link, and F-Droid metadata to version 0.0.2.

## 0.0.1 — pre-release

- Initial Android live-wallpaper app for selecting and looping MP4 files.
- Extract a representative palette from five video frames with `WallpaperColors.fromBitmap()`.
- Provide the cached colors through `WallpaperService.Engine.onComputeColors()` and notify Android with `notifyColorsChanged()` when the selection changes.
- Use the Android Storage Access Framework and retain access only to the selected document URI.
- Add Russian and English UI, an unsigned release build, and an fdroiddata metadata template.
