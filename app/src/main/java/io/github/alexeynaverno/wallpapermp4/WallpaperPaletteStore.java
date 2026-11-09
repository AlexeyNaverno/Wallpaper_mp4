package io.github.alexeynaverno.wallpapermp4;

import android.app.WallpaperColors;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;

/** Persists the selected SAF document and the palette Android should use for it. */
final class WallpaperPaletteStore {
    private static final String PREFERENCES = "video_wallpaper";
    private static final String KEY_VIDEO_URI = "video_uri";
    private static final String KEY_VIDEO_NAME = "video_name";
    private static final String KEY_PRIMARY = "palette_primary";
    private static final String KEY_SECONDARY = "palette_secondary";
    private static final String KEY_TERTIARY = "palette_tertiary";
    private static final String KEY_COLOR_HINTS = "palette_hints";

    private WallpaperPaletteStore() {
    }

    static Uri getVideoUri(Context context) {
        String value = preferences(context).getString(KEY_VIDEO_URI, null);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Uri.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String getVideoName(Context context) {
        return preferences(context).getString(KEY_VIDEO_NAME, null);
    }

    static boolean saveVideo(Context context, Uri uri, String displayName, WallpaperColors colors) {
        if (uri == null || colors == null || colors.getPrimaryColor() == null) {
            return false;
        }

        SharedPreferences.Editor editor = preferences(context).edit()
                .putString(KEY_VIDEO_URI, uri.toString())
                .putString(KEY_VIDEO_NAME, displayName)
                .putInt(KEY_PRIMARY, colors.getPrimaryColor().toArgb());

        Color secondary = colors.getSecondaryColor();
        if (secondary == null) {
            editor.remove(KEY_SECONDARY);
        } else {
            editor.putInt(KEY_SECONDARY, secondary.toArgb());
        }

        Color tertiary = colors.getTertiaryColor();
        if (tertiary == null) {
            editor.remove(KEY_TERTIARY);
        } else {
            editor.putInt(KEY_TERTIARY, tertiary.toArgb());
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            editor.putInt(KEY_COLOR_HINTS, colors.getColorHints());
        } else {
            editor.remove(KEY_COLOR_HINTS);
        }
        return editor.commit();
    }

    static WallpaperColors getWallpaperColors(Context context) {
        SharedPreferences preferences = preferences(context);
        if (!preferences.contains(KEY_PRIMARY)) {
            return null;
        }

        Color primary = Color.valueOf(preferences.getInt(KEY_PRIMARY, Color.BLACK));
        Color secondary = preferences.contains(KEY_SECONDARY)
                ? Color.valueOf(preferences.getInt(KEY_SECONDARY, Color.BLACK)) : null;
        Color tertiary = preferences.contains(KEY_TERTIARY)
                ? Color.valueOf(preferences.getInt(KEY_TERTIARY, Color.BLACK)) : null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int hints = preferences.getInt(KEY_COLOR_HINTS, 0);
            return new WallpaperColors(primary, secondary, tertiary, hints);
        }
        return new WallpaperColors(primary, secondary, tertiary);
    }

    static boolean clear(Context context) {
        return preferences(context).edit().clear().commit();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
