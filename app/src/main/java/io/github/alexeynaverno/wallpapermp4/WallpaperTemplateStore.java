package io.github.alexeynaverno.wallpapermp4;

import android.app.WallpaperColors;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Persists generated MP4 wallpaper templates in the app's private files directory. */
final class WallpaperTemplateStore {
    private static final String TAG = "WallpaperTemplates";
    private static final String PREFERENCES = "wallpaper_template_selection";
    private static final String KEY_SELECTED_ID = "selected_template_id";
    private static final String VIDEO_DIRECTORY = "wallpaper_templates";
    private static final String METADATA_SUFFIX = ".json";
    private static final String VIDEO_SUFFIX = ".mp4";
    private static final int NO_COLOR = Integer.MIN_VALUE;

    static final class Template {
        final String id;
        final String name;
        final long durationUs;
        final int width;
        final int height;
        final long createdAt;
        final int primaryColor;
        final int secondaryColor;
        final int tertiaryColor;
        final int colorHints;

        Template(
                String id,
                String name,
                long durationUs,
                int width,
                int height,
                long createdAt,
                WallpaperColors colors) {
            this(
                    id,
                    name,
                    durationUs,
                    width,
                    height,
                    createdAt,
                    colors.getPrimaryColor().toArgb(),
                    colors.getSecondaryColor() == null
                            ? NO_COLOR : colors.getSecondaryColor().toArgb(),
                    colors.getTertiaryColor() == null
                            ? NO_COLOR : colors.getTertiaryColor().toArgb(),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? colors.getColorHints() : 0);
        }

        private Template(
                String id,
                String name,
                long durationUs,
                int width,
                int height,
                long createdAt,
                int primaryColor,
                int secondaryColor,
                int tertiaryColor,
                int colorHints) {
            this.id = id;
            this.name = name;
            this.durationUs = durationUs;
            this.width = width;
            this.height = height;
            this.createdAt = createdAt;
            this.primaryColor = primaryColor;
            this.secondaryColor = secondaryColor;
            this.tertiaryColor = tertiaryColor;
            this.colorHints = colorHints;
        }

        WallpaperColors getWallpaperColors() {
            Color primary = Color.valueOf(primaryColor);
            Color secondary = secondaryColor == NO_COLOR ? null : Color.valueOf(secondaryColor);
            Color tertiary = tertiaryColor == NO_COLOR ? null : Color.valueOf(tertiaryColor);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return new WallpaperColors(primary, secondary, tertiary, colorHints);
            }
            return new WallpaperColors(primary, secondary, tertiary);
        }

        String getVideoFileName() {
            return id + VIDEO_SUFFIX;
        }
    }

    private WallpaperTemplateStore() {
    }

    static File getTemplateDirectory(Context context) {
        return new File(context.getFilesDir(), VIDEO_DIRECTORY);
    }

    static List<Template> list(Context context) {
        File directory = getTemplateDirectory(context);
        File[] metadataFiles = directory.listFiles(
                (parent, name) -> name.endsWith(METADATA_SUFFIX));
        if (metadataFiles == null || metadataFiles.length == 0) {
            return Collections.emptyList();
        }

        List<Template> templates = new ArrayList<>(metadataFiles.length);
        for (File metadataFile : metadataFiles) {
            Template template = readTemplate(metadataFile);
            if (template != null && getVideoFile(context, template).isFile()) {
                templates.add(template);
            }
        }
        templates.sort(Comparator.comparingLong((Template item) -> item.createdAt).reversed());
        return templates;
    }

    static Template find(Context context, String id) {
        if (!isSafeId(id)) {
            return null;
        }
        File metadataFile = new File(getTemplateDirectory(context), id + METADATA_SUFFIX);
        Template template = readTemplate(metadataFile);
        return template != null && getVideoFile(context, template).isFile() ? template : null;
    }

    static File getVideoFile(Context context, Template template) {
        return new File(getTemplateDirectory(context), template.getVideoFileName());
    }

    static boolean save(Context context, Template template) {
        if (!isSafeId(template.id)) {
            return false;
        }
        File directory = getTemplateDirectory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return false;
        }

        File destination = new File(directory, template.id + METADATA_SUFFIX);
        File temporary = new File(directory, template.id + METADATA_SUFFIX + ".tmp");
        try (FileOutputStream fileStream = new FileOutputStream(temporary);
             OutputStreamWriter writer = new OutputStreamWriter(
                     fileStream, StandardCharsets.UTF_8)) {
            writer.write(toJson(template).toString());
            writer.flush();
            fileStream.getFD().sync();
        } catch (IOException | JSONException exception) {
            Log.e(TAG, "Could not save template metadata.", exception);
            if (temporary.exists()) {
                temporary.delete();
            }
            return false;
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            return false;
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            return false;
        }
        return true;
    }

    static boolean remove(Context context, Template template) {
        File videoFile = getVideoFile(context, template);
        File metadataFile = new File(getTemplateDirectory(context), template.id + METADATA_SUFFIX);
        boolean videoRemoved = !videoFile.exists() || videoFile.delete();
        boolean metadataRemoved = !metadataFile.exists() || metadataFile.delete();
        if (videoRemoved && metadataRemoved
                && template.id.equals(getSelectedId(context))) {
            setSelectedId(context, null);
        }
        return videoRemoved && metadataRemoved;
    }

    static String getSelectedId(Context context) {
        return preferences(context).getString(KEY_SELECTED_ID, null);
    }

    static boolean setSelectedId(Context context, String id) {
        SharedPreferences.Editor editor = preferences(context).edit();
        if (id == null) {
            editor.remove(KEY_SELECTED_ID);
        } else if (isSafeId(id)) {
            editor.putString(KEY_SELECTED_ID, id);
        } else {
            return false;
        }
        return editor.commit();
    }

    static String createId() {
        return UUID.randomUUID().toString();
    }

    private static JSONObject toJson(Template template) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", template.id);
        json.put("name", template.name);
        json.put("durationUs", template.durationUs);
        json.put("width", template.width);
        json.put("height", template.height);
        json.put("createdAt", template.createdAt);
        json.put("primaryColor", template.primaryColor);
        json.put("secondaryColor", template.secondaryColor);
        json.put("tertiaryColor", template.tertiaryColor);
        json.put("colorHints", template.colorHints);
        return json;
    }

    private static Template readTemplate(File metadataFile) {
        if (!metadataFile.isFile()) {
            return null;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(metadataFile.toPath());
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String id = json.getString("id");
            if (!isSafeId(id) || !metadataFile.getName().equals(id + METADATA_SUFFIX)) {
                return null;
            }
            return new Template(
                    id,
                    json.optString("name", "Wallpaper"),
                    Math.max(0L, json.optLong("durationUs", 0L)),
                    Math.max(0, json.optInt("width", 0)),
                    Math.max(0, json.optInt("height", 0)),
                    Math.max(0L, json.optLong("createdAt", 0L)),
                    json.getInt("primaryColor"),
                    json.optInt("secondaryColor", NO_COLOR),
                    json.optInt("tertiaryColor", NO_COLOR),
                    json.optInt("colorHints", 0));
        } catch (IOException | JSONException | RuntimeException exception) {
            Log.w(TAG, "Skipping invalid wallpaper template metadata: " + metadataFile, exception);
            return null;
        }
    }

    private static boolean isSafeId(String id) {
        if (id == null || id.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
