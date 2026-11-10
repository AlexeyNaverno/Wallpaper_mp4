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
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Imports MP4s, creates reusable ping-pong templates, and opens Android's wallpaper preview. */
public final class MainActivity extends Activity {
    private static final String TAG = "WallpaperMain";
    private static final int REQUEST_PICK_VIDEO = 1001;

    private static final ExecutorService TEMPLATE_WORKER = Executors.newSingleThreadExecutor();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Future<?> processingTask;
    private Uri processingSourceUri;
    private long processingGeneration;
    private volatile boolean cancelRequested;
    private boolean processing;

    private Button createButton;
    private Button applyButton;
    private Button cancelProcessingButton;
    private TextView selectedTemplateText;
    private TextView paletteHintText;
    private TextView processingStatusText;
    private TextView noTemplatesText;
    private ProgressBar processingProgress;
    private LinearLayout templatesContainer;
    private View primarySwatch;
    private View secondarySwatch;
    private View tertiarySwatch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();

        createButton = findViewById(R.id.choose_video_button);
        applyButton = findViewById(R.id.apply_wallpaper_button);
        cancelProcessingButton = findViewById(R.id.cancel_processing_button);
        selectedTemplateText = findViewById(R.id.selected_video_text);
        paletteHintText = findViewById(R.id.palette_hint_text);
        processingStatusText = findViewById(R.id.processing_status);
        processingProgress = findViewById(R.id.processing_progress);
        noTemplatesText = findViewById(R.id.no_templates_text);
        templatesContainer = findViewById(R.id.templates_container);
        primarySwatch = findViewById(R.id.palette_primary);
        secondarySwatch = findViewById(R.id.palette_secondary);
        tertiarySwatch = findViewById(R.id.palette_tertiary);

        createButton.setOnClickListener(view -> openVideoPicker());
        applyButton.setOnClickListener(view -> previewWallpaper());
        cancelProcessingButton.setOnClickListener(view -> cancelProcessing());
        renderScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderScreen();
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
        createTemplate(uri);
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

    private void createTemplate(Uri sourceUri) {
        if (processing) {
            return;
        }
        processing = true;
        cancelRequested = false;
        processingSourceUri = sourceUri;
        long generation = ++processingGeneration;
        processingProgress.setProgress(0);
        processingStatusText.setText(R.string.processing_starting);
        processingProgress.setVisibility(View.VISIBLE);
        processingStatusText.setVisibility(View.VISIBLE);
        cancelProcessingButton.setVisibility(View.VISIBLE);
        renderScreen();

        final android.content.Context applicationContext = getApplicationContext();
        processingTask = TEMPLATE_WORKER.submit(() -> {
            String templateId = WallpaperTemplateStore.createId();
            File templateDirectory = WallpaperTemplateStore.getTemplateDirectory(applicationContext);
            File partialFile = new File(templateDirectory, templateId + ".part.mp4");
            File finalFile = new File(templateDirectory, templateId + ".mp4");
            boolean templateSaved = false;
            boolean selectedSaved = false;
            String previousTemplateId = WallpaperTemplateStore.getSelectedId(applicationContext);
            Uri previouslySelectedUri = WallpaperPaletteStore.getVideoUri(applicationContext);
            Exception failure = null;
            boolean cancelled = false;

            try {
                checkCancellation();
                if (!templateDirectory.isDirectory() && !templateDirectory.mkdirs()) {
                    throw new IOException("Could not create private wallpaper storage.");
                }

                String displayName = readDisplayName(applicationContext, sourceUri);
                String templateName = stripExtension(displayName);
                WallpaperColors colors = VideoPaletteExtractor.fromVideo(applicationContext, sourceUri);
                LoopTemplateGenerator.Result result = LoopTemplateGenerator.generate(
                        applicationContext,
                        sourceUri,
                        partialFile,
                        applicationContext.getCacheDir(),
                        percent -> reportProgress(generation, percent));
                checkCancellation();

                if (!partialFile.renameTo(finalFile)) {
                    throw new IOException("Could not move the finished MP4 into template storage.");
                }
                checkCancellation();
                WallpaperTemplateStore.Template template = new WallpaperTemplateStore.Template(
                        templateId,
                        templateName,
                        result.durationUs,
                        result.width,
                        result.height,
                        System.currentTimeMillis(),
                        colors);
                if (!WallpaperTemplateStore.save(applicationContext, template)) {
                    throw new IOException("Could not save wallpaper template details.");
                }
                templateSaved = true;
                checkCancellation();

                if (!WallpaperTemplateStore.setSelectedId(applicationContext, templateId)) {
                    throw new IOException("Could not select the new wallpaper template.");
                }
                checkCancellation();
                if (!WallpaperPaletteStore.saveVideo(
                        applicationContext,
                        Uri.fromFile(finalFile),
                        templateName,
                        colors)) {
                    WallpaperTemplateStore.setSelectedId(applicationContext, previousTemplateId);
                    throw new IOException("Could not save the wallpaper color palette.");
                }
                selectedSaved = true;
                notifyWallpaperEngine(applicationContext);
            } catch (Exception exception) {
                failure = exception;
                cancelled = cancelRequested || Thread.currentThread().isInterrupted()
                        || (exception.getMessage() != null
                        && exception.getMessage().toLowerCase(Locale.ROOT).contains("cancel"));
                Log.e(TAG, "Could not create a wallpaper template.", exception);
            } finally {
                if (selectedSaved || previouslySelectedUri == null
                        || !previouslySelectedUri.equals(sourceUri)) {
                    releaseReadPermission(applicationContext, sourceUri);
                }
                if (failure != null) {
                    if (templateSaved) {
                        WallpaperTemplateStore.Template template =
                                WallpaperTemplateStore.find(applicationContext, templateId);
                        if (template != null) {
                            WallpaperTemplateStore.remove(applicationContext, template);
                        }
                    } else if (finalFile.exists()) {
                        finalFile.delete();
                    }
                    partialFile.delete();
                    if (!selectedSaved) {
                        WallpaperTemplateStore.setSelectedId(
                                applicationContext, previousTemplateId);
                    }
                }
            }

            Exception finalFailure = failure;
            boolean wasCancelled = cancelled;
            mainHandler.post(() -> {
                if (generation != processingGeneration || isFinishing() || isDestroyed()) {
                    return;
                }
                processing = false;
                processingTask = null;
                processingSourceUri = null;
                processingProgress.setVisibility(View.GONE);
                processingStatusText.setVisibility(View.GONE);
                cancelProcessingButton.setVisibility(View.GONE);
                renderScreen();
                if (finalFailure == null) {
                    Toast.makeText(this, R.string.video_ready, Toast.LENGTH_LONG).show();
                } else if (wasCancelled) {
                    Toast.makeText(this, R.string.cancelled, Toast.LENGTH_LONG).show();
                } else {
                    showMessage(R.string.video_error);
                }
            });
        });
    }

    private void reportProgress(long generation, int percent) {
        if (cancelRequested || Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            return;
        }
        int safeProgress = Math.max(0, Math.min(100, percent));
        mainHandler.post(() -> {
            if (generation == processingGeneration
                    && !isFinishing() && !isDestroyed() && processing) {
                processingProgress.setProgress(safeProgress);
                processingStatusText.setText(
                        getString(R.string.processing_progress, safeProgress));
            }
        });
    }

    private void checkCancellation() throws IOException {
        if (cancelRequested || Thread.currentThread().isInterrupted()) {
            throw new IOException("Wallpaper creation was cancelled.");
        }
    }

    private void cancelProcessing() {
        if (!processing) {
            return;
        }
        cancelRequested = true;
        if (processingTask != null) {
            processingTask.cancel(true);
        }
        Uri sourceUri = processingSourceUri;
        Uri activeUri = WallpaperPaletteStore.getVideoUri(this);
        if (sourceUri != null && !sourceUri.equals(activeUri)) {
            releaseReadPermission(this, sourceUri);
        }

        // Ignore any late callbacks from the cancelled worker; it still cleans its temporary files.
        processingGeneration++;
        processing = false;
        processingTask = null;
        processingSourceUri = null;
        processingProgress.setVisibility(View.GONE);
        processingStatusText.setVisibility(View.GONE);
        cancelProcessingButton.setVisibility(View.GONE);
        renderScreen();
        Toast.makeText(this, R.string.cancelled, Toast.LENGTH_LONG).show();
    }

    private void renderScreen() {
        if (selectedTemplateText == null) {
            return;
        }
        renderSelection();
        renderTemplates();

        createButton.setEnabled(!processing);
        applyButton.setEnabled(!processing && WallpaperPaletteStore.getVideoUri(this) != null);
        cancelProcessingButton.setEnabled(processing && !cancelRequested);
    }

    private void renderSelection() {
        String selectedId = WallpaperTemplateStore.getSelectedId(this);
        WallpaperTemplateStore.Template selectedTemplate =
                WallpaperTemplateStore.find(this, selectedId);
        Uri activeUri = WallpaperPaletteStore.getVideoUri(this);
        String selectedName = WallpaperPaletteStore.getVideoName(this);

        if (selectedTemplate != null) {
            selectedTemplateText.setText(
                    getString(R.string.selected_template_name, selectedTemplate.name));
        } else if (activeUri != null) {
            // Keep a pre-0.0.3 selection usable until the user creates a saved template.
            selectedTemplateText.setText(getString(
                    R.string.legacy_video_name,
                    selectedName == null ? activeUri.getLastPathSegment() : selectedName));
        } else {
            selectedTemplateText.setText(R.string.no_template_selected);
        }

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

    private void renderTemplates() {
        List<WallpaperTemplateStore.Template> templates = WallpaperTemplateStore.list(this);
        templatesContainer.removeAllViews();
        noTemplatesText.setVisibility(templates.isEmpty() ? View.VISIBLE : View.GONE);

        String selectedId = WallpaperTemplateStore.getSelectedId(this);
        for (WallpaperTemplateStore.Template template : templates) {
            boolean selected = template.id.equals(selectedId);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.card_background);
            row.setPadding(dp(12), dp(8), dp(8), dp(8));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = dp(8);
            templatesContainer.addView(row, rowParams);

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setClickable(true);
            labels.setFocusable(true);
            labels.setOnClickListener(view -> selectTemplate(template));
            LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(labels, labelsParams);

            TextView name = new TextView(this);
            String itemName = selected
                    ? template.name + " · " + getString(R.string.selected_badge)
                    : template.name;
            name.setText(itemName);
            name.setTextColor(getColor(R.color.app_text));
            name.setTextSize(16f);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            labels.addView(name);

            TextView details = new TextView(this);
            details.setText(getString(
                    R.string.template_item_details,
                    formatDuration(template.durationUs),
                    template.width,
                    template.height));
            details.setTextColor(getColor(R.color.app_text_secondary));
            details.setTextSize(13f);
            labels.addView(details);

            Button selectButton = new Button(this);
            selectButton.setAllCaps(false);
            selectButton.setText(selected ? R.string.selected_badge : R.string.select_template);
            selectButton.setEnabled(!processing && !selected);
            selectButton.setOnClickListener(view -> selectTemplate(template));
            row.addView(selectButton, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            Button deleteButton = new Button(this);
            deleteButton.setAllCaps(false);
            deleteButton.setText(R.string.delete_template);
            deleteButton.setEnabled(!processing);
            deleteButton.setOnClickListener(view -> confirmDeleteTemplate(template));
            row.addView(deleteButton, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void selectTemplate(WallpaperTemplateStore.Template template) {
        if (processing) {
            return;
        }
        File videoFile = WallpaperTemplateStore.getVideoFile(this, template);
        if (!videoFile.isFile()) {
            showMessage(R.string.video_error);
            renderScreen();
            return;
        }

        String previousId = WallpaperTemplateStore.getSelectedId(this);
        if (!WallpaperTemplateStore.setSelectedId(this, template.id)
                || !WallpaperPaletteStore.saveVideo(
                        this,
                        Uri.fromFile(videoFile),
                        template.name,
                        template.getWallpaperColors())) {
            WallpaperTemplateStore.setSelectedId(this, previousId);
            showMessage(R.string.save_error);
            return;
        }
        notifyWallpaperEngine(this);
        renderScreen();
    }

    private void confirmDeleteTemplate(WallpaperTemplateStore.Template template) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) -> deleteTemplate(template))
                .show();
    }

    private void deleteTemplate(WallpaperTemplateStore.Template template) {
        boolean wasSelected = template.id.equals(WallpaperTemplateStore.getSelectedId(this));
        if (!WallpaperTemplateStore.remove(this, template)) {
            showMessage(R.string.save_error);
            return;
        }
        if (wasSelected) {
            WallpaperPaletteStore.clear(this);
            notifyWallpaperEngine(this);
        }
        renderScreen();
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

    private static String stripExtension(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4).trim();
        }
        return trimmed.isEmpty() ? "MP4 wallpaper" : trimmed;
    }

    private static String formatDuration(long durationUs) {
        long totalSeconds = Math.max(0L, durationUs / 1_000_000L);
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showMessage(int messageResource) {
        Toast.makeText(this, messageResource, Toast.LENGTH_LONG).show();
    }
}
