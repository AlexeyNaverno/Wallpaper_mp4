package io.github.alexeynaverno.wallpapermp4;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal picker and hand-off to Android's standard live-wallpaper preview. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PICK_VIDEO = 1001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private Button chooseButton;
    private Button applyButton;
    private Button clearButton;
    private TextView selectedVideoText;
    private TextView paletteHintText;
    private View primarySwatch;
    private View secondarySwatch;
    private View tertiarySwatch;
    private boolean analyzingVideo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();
        worker = Executors.newSingleThreadExecutor();

        chooseButton = findViewById(R.id.choose_video_button);
        applyButton = findViewById(R.id.apply_wallpaper_button);
        clearButton = findViewById(R.id.clear_video_button);
        selectedVideoText = findViewById(R.id.selected_video_text);
        paletteHintText = findViewById(R.id.palette_hint_text);
        primarySwatch = findViewById(R.id.palette_primary);
        secondarySwatch = findViewById(R.id.palette_secondary);
        tertiarySwatch = findViewById(R.id.palette_tertiary);

        chooseButton.setOnClickListener(view -> openVideoPicker());
        applyButton.setOnClickListener(view -> previewWallpaper());
        clearButton.setOnClickListener(view -> confirmClearVideo());
        renderSelection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderSelection();
    }

    @Override
    protected void onDestroy() {
        if (worker != null) {
            worker.shutdown();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_VIDEO || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        int readPermission = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        int persistablePermission = data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        if (readPermission == 0 || persistablePermission == 0) {
            showMessage(R.string.permission_error);
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(uri, readPermission);
        } catch (SecurityException | IllegalArgumentException exception) {
            showMessage(R.string.permission_error);
            return;
        }

        analyzeAndSaveVideo(uri);
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.root_container);
        int baseLeft = root.getPaddingLeft();
        int baseTop = root.getPaddingTop();
        int baseRight = root.getPaddingRight();
        int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    baseLeft + insets.getSystemWindowInsetLeft(),
                    baseTop + insets.getSystemWindowInsetTop(),
                    baseRight + insets.getSystemWindowInsetRight(),
                    baseBottom + insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
    }

    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/mp4");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_VIDEO);
        } catch (ActivityNotFoundException exception) {
            showMessage(R.string.picker_unavailable);
        }
    }

    private void analyzeAndSaveVideo(Uri uri) {
        analyzingVideo = true;
        chooseButton.setEnabled(false);
        applyButton.setEnabled(false);
        paletteHintText.setText(R.string.analyzing_video);

        final android.content.Context applicationContext = getApplicationContext();
        final Uri previouslySelected = WallpaperPaletteStore.getVideoUri(applicationContext);
        worker.execute(() -> {
            try {
                WallpaperColors colors = VideoPaletteExtractor.fromVideo(applicationContext, uri);
                String displayName = readDisplayName(applicationContext, uri);
                if (!WallpaperPaletteStore.saveVideo(
                        applicationContext, uri, displayName, colors)) {
                    throw new IOException("Could not persist the video selection.");
                }

                notifyWallpaperEngine(applicationContext);
                if (previouslySelected != null && !previouslySelected.equals(uri)) {
                    releaseReadPermission(applicationContext, previouslySelected);
                }

                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    analyzingVideo = false;
                    renderSelection();
                    Toast.makeText(this, R.string.video_ready, Toast.LENGTH_LONG).show();
                });
            } catch (Exception exception) {
                if (previouslySelected == null || !previouslySelected.equals(uri)) {
                    releaseReadPermission(applicationContext, uri);
                }
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    analyzingVideo = false;
                    renderSelection();
                    showMessage(R.string.video_error);
                });
            }
        });
    }

    private void renderSelection() {
        if (selectedVideoText == null) {
            return;
        }

        Uri uri = WallpaperPaletteStore.getVideoUri(this);
        String name = WallpaperPaletteStore.getVideoName(this);
        boolean hasVideo = uri != null;
        selectedVideoText.setText(hasVideo && name != null && !name.isEmpty()
                ? getString(R.string.selected_video_name, name)
                : getString(R.string.no_video_selected));
        clearButton.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
        clearButton.setEnabled(!analyzingVideo);
        applyButton.setEnabled(hasVideo && !analyzingVideo);
        chooseButton.setEnabled(!analyzingVideo);

        WallpaperColors colors = WallpaperPaletteStore.getWallpaperColors(this);
        if (colors == null) {
            setSwatch(primarySwatch, null);
            setSwatch(secondarySwatch, null);
            setSwatch(tertiarySwatch, null);
            paletteHintText.setText(R.string.palette_not_available);
        } else {
            setSwatch(primarySwatch, colors.getPrimaryColor().toArgb());
            setSwatch(secondarySwatch, colors.getSecondaryColor() == null
                    ? colors.getPrimaryColor().toArgb()
                    : colors.getSecondaryColor().toArgb());
            setSwatch(tertiarySwatch, colors.getTertiaryColor() == null
                    ? colors.getPrimaryColor().toArgb()
                    : colors.getTertiaryColor().toArgb());
            paletteHintText.setText(R.string.palette_hint);
        }
    }

    private void setSwatch(View swatch, Integer color) {
        swatch.setBackgroundColor(color == null
                ? getColor(R.color.palette_empty)
                : color);
    }

    private void previewWallpaper() {
        if (WallpaperPaletteStore.getVideoUri(this) == null) {
            showMessage(R.string.select_video_first);
            return;
        }

        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, VideoWallpaperService.class));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            showMessage(R.string.picker_unavailable);
        }
    }

    private void confirmClearVideo() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_confirm_title)
                .setMessage(R.string.clear_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) -> clearVideo())
                .show();
    }

    private void clearVideo() {
        Uri previous = WallpaperPaletteStore.getVideoUri(this);
        if (!WallpaperPaletteStore.clear(this)) {
            showMessage(R.string.save_error);
            return;
        }
        notifyWallpaperEngine(this);
        if (previous != null) {
            releaseReadPermission(this, previous);
        }
        renderSelection();
    }

    private void notifyWallpaperEngine(android.content.Context context) {
        Intent update = new Intent(VideoWallpaperService.ACTION_VIDEO_OR_PALETTE_CHANGED);
        update.setPackage(context.getPackageName());
        context.sendBroadcast(update);
    }

    private static void releaseReadPermission(android.content.Context context, Uri uri) {
        try {
            context.getContentResolver().releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // A provider may have already revoked the grant.
        }
    }

    private static String readDisplayName(android.content.Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameColumn >= 0) {
                    String name = cursor.getString(nameColumn);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Some providers do not expose a display name; use the URI as a fallback.
        }
        String lastSegment = uri.getLastPathSegment();
        return lastSegment == null || lastSegment.isEmpty() ? uri.toString() : lastSegment;
    }

    private void showMessage(int messageResource) {
        Toast.makeText(this, messageResource, Toast.LENGTH_LONG).show();
    }
}
