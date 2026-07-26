package dev.recordable.screen;

import dev.recordable.PlatformUtils;
import dev.recordable.RecordableConfig;
import dev.recordable.RecordableMod;
import dev.recordable.StorageManager;
import dev.recordable.VideoMetadata;
import dev.recordable.VideoShareUploader;
import dev.recordable.theme.ThemeColors;
import dev.recordable.theme.ThemeEngine;
import dev.recordable.theme.ThemePreset;
import dev.recordable.theme.ThemedPanel;
import dev.recordable.theme.VhsEffectsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * V1-0.09-style in-game browser for recordings and replay clips.
 *
 * <p>This screen deliberately keeps the modern 62-pixel entry geometry,
 * responsive toolbar, thumbnails, item actions, scrolling, and share modal.
 * Only the rendering/input plumbing is translated to Forge 1.8.9.</p>
 */
public final class VideoCollectionScreen extends GuiScreen {
    private static final int ENTRY_HEIGHT = 62;
    private static final int ACTION_WIDTH = 54;
    private static final int ACTION_HEIGHT = 14;
    private static final int DELETE_CONFIRM_MS = 6000;
    private static final int BUTTON_BACK = 100;
    private static final int BUTTON_SETTINGS = 101;
    private static final int BUTTON_OPEN_FOLDER = 102;
    private static final int BUTTON_REFRESH = 103;
    private static final int BUTTON_SORT = 104;
    private static final int BUTTON_TOGGLE_COLLECTION = 105;

    private static final int MAX_THUMBNAIL_DIMENSION = 8192;
    private static final long MAX_THUMBNAIL_PIXELS =
            64L * 1024L * 1024L;

    private final GuiScreen parent;
    private final boolean clipsMode;
    private final Object videosLock = new Object();
    private final List<VideoMetadata> allVideos =
            new ArrayList<VideoMetadata>();
    private final List<VideoMetadata> filteredVideos =
            new ArrayList<VideoMetadata>();
    private final List<ActionZone> actionZones =
            new ArrayList<ActionZone>();
    private final Map<Path, ThumbnailTexture> thumbnailCache =
            new HashMap<Path, ThumbnailTexture>();
    private final Set<Path> failedThumbnailPaths =
            new HashSet<Path>();
    private final AtomicBoolean durationProbeRunning =
            new AtomicBoolean(false);

    private GuiTextField searchField;
    private GuiButton sortButton;
    private String statusMessage;
    private boolean statusIsError;
    private boolean loading;
    private int scrollOffset;
    private boolean draggingScrollbar;

    private int contentLeft;
    private int contentWidth;
    private int listLeft;
    private int listRight;
    private int listTop;
    private int listBottom;
    private int headerTop;

    private long totalSizeBytes;
    private Path deleteConfirmPath;
    private long deleteConfirmUntil;

    private volatile long workGeneration;
    private volatile Thread loaderThread;
    private volatile ExecutorService durationProber;

    private VideoMetadata shareTarget;
    private boolean shareRetentionDrawerOpen;
    private volatile boolean sharing;
    private volatile ExecutorService shareUploader;
    private String shareOverlayMessage;
    private long shareOverlayUntilMs;

    public VideoCollectionScreen(GuiScreen parent) {
        this(parent, false);
    }

    public VideoCollectionScreen(
            GuiScreen parent,
            boolean clipsMode) {
        this.parent = parent;
        this.clipsMode = clipsMode;
    }

    @Override
    public void initGui() {
        cancelBackgroundWork();
        ThemeEngine.get().loadFromConfig();
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        actionZones.clear();

        contentWidth = Math.max(
                320,
                Math.min((int) (width * 0.92D), 980));
        contentWidth = Math.min(contentWidth, Math.max(20, width - 8));
        contentLeft = (width - contentWidth) / 2;
        headerTop = Math.max(6, (int) (height * 0.025D));

        int rowLeft = contentLeft + 8;
        int rowRight = contentLeft + contentWidth - 8;
        int availableWidth = Math.max(1, rowRight - rowLeft);
        int topBarY = headerTop + 18;
        int buttonGap = 4;
        int buttonHeight = 18;
        int[] buttonWidths = {74, 84, 130, 78, 110, 72};
        int totalButtonWidth = buttonGap * (buttonWidths.length - 1);
        for (int buttonWidth : buttonWidths) {
            totalButtonWidth += buttonWidth;
        }

        int buttonRowY = topBarY;
        // V1-0.09 keeps filter internals but does not instantiate a visible
        // search field in the shipped gallery composition.
        searchField = null;

        String[] labels = {
                tr(
                        "screen.recordable.video_collection.back",
                        "Back"),
                tr(
                        "screen.recordable.video_collection.settings",
                        "Settings"),
                tr(
                        "screen.recordable.video_collection.open_recordings_folder",
                        "Open Recordings Folder"),
                tr(
                        "screen.recordable.video_collection.refresh",
                        "Refresh"),
                sortLabel(),
                tr(
                        clipsMode
                                ? "screen.recordable.video_collection.recordings"
                                : "screen.recordable.video_collection.clips",
                        clipsMode ? "Recordings" : "Clips")
        };

        int lastRowY = buttonRowY;
        if (totalButtonWidth <= availableWidth) {
            int x = rowRight;
            for (int index = buttonWidths.length - 1;
                    index >= 0;
                    index--) {
                x -= buttonWidths[index];
                addToolbarButton(
                        index,
                        x,
                        buttonRowY,
                        buttonWidths[index],
                        buttonHeight,
                        labels[index]);
                x -= buttonGap;
            }
        } else {
            int x = rowLeft;
            int y = buttonRowY;
            for (int index = 0;
                    index < buttonWidths.length;
                    index++) {
                if (x > rowLeft
                        && x + buttonWidths[index] > rowRight) {
                    x = rowLeft;
                    y += buttonHeight + buttonGap;
                }
                addToolbarButton(
                        index,
                        x,
                        y,
                        buttonWidths[index],
                        buttonHeight,
                        labels[index]);
                x += buttonWidths[index] + buttonGap;
            }
            lastRowY = y;
        }

        listLeft = contentLeft + 8;
        listRight = contentLeft + contentWidth - 8;
        listTop = lastRowY + 28;
        listBottom = height - Math.max(
                24,
                (int) (height * 0.04D));
        if (listBottom < listTop + 24) {
            listBottom = Math.min(height - 5, listTop + 24);
        }

        applyFilter();
        refreshVideos();
    }

    private void addToolbarButton(
            int toolbarIndex,
            int x,
            int y,
            int buttonWidth,
            int buttonHeight,
            String label) {
        int buttonId;
        switch (toolbarIndex) {
            case 0:
                buttonId = BUTTON_BACK;
                break;
            case 1:
                buttonId = BUTTON_SETTINGS;
                break;
            case 2:
                buttonId = BUTTON_OPEN_FOLDER;
                break;
            case 3:
                buttonId = BUTTON_REFRESH;
                break;
            case 4:
                buttonId = BUTTON_SORT;
                break;
            default:
                buttonId = BUTTON_TOGGLE_COLLECTION;
                break;
        }

        GuiButton button = new GuiButton(
                buttonId,
                x,
                y,
                buttonWidth,
                buttonHeight,
                label);
        if (buttonId == BUTTON_SORT) {
            sortButton = button;
        }
        buttonList.add(button);
    }

    private void openSettings() {
        if (mc != null) {
            mc.displayGuiScreen(
                    new RecordableSettingsScreen(this));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null || !button.enabled) {
            return;
        }
        switch (button.id) {
            case BUTTON_BACK:
                onClose();
                break;
            case BUTTON_SETTINGS:
                openSettings();
                break;
            case BUTTON_OPEN_FOLDER:
                openRecordingsFolder();
                break;
            case BUTTON_REFRESH:
                refreshVideos();
                break;
            case BUTTON_SORT:
                cycleSortMode(true);
                break;
            case BUTTON_TOGGLE_COLLECTION:
                toggleClipsView();
                break;
            default:
                break;
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        cancelBackgroundWork();
        cancelShareUpload(true);
        clearThumbnails();
    }

    private void cancelBackgroundWork() {
        workGeneration++;
        Thread loader = loaderThread;
        if (loader != null) {
            loader.interrupt();
            loaderThread = null;
        }
        durationProbeRunning.set(false);
        ExecutorService prober = durationProber;
        if (prober != null) {
            prober.shutdownNow();
            durationProber = null;
        }
        loading = false;
    }

    private void refreshVideos() {
        final long generation = ++workGeneration;
        Thread oldLoader = loaderThread;
        if (oldLoader != null) {
            oldLoader.interrupt();
        }
        durationProbeRunning.set(false);
        ExecutorService oldProber = durationProber;
        if (oldProber != null) {
            oldProber.shutdownNow();
            durationProber = null;
        }

        loading = true;
        statusMessage = tr(
                "screen.recordable.video_collection.loading",
                "Loading...");
        statusIsError = false;
        scrollOffset = 0;
        deleteConfirmPath = null;
        clearThumbnails();
        synchronized (videosLock) {
            allVideos.clear();
            filteredVideos.clear();
        }
        totalSizeBytes = 0L;

        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<VideoMetadata> loaded =
                        new ArrayList<VideoMetadata>();
                String failure = null;
                boolean failed = false;
                try {
                    RecordableConfig config =
                            RecordableConfig.get();
                    Path scanDirectory = clipsMode
                            ? StorageManager.getAutoClipDirectory(config)
                            : config.getOutputDirectory();
                    if (scanDirectory != null
                            && Files.isDirectory(scanDirectory)) {
                        Stream<Path> stream = clipsMode
                                ? Files.walk(scanDirectory)
                                : Files.list(scanDirectory);
                        try {
                            stream.filter(Files::isRegularFile)
                                    .filter(
                                            VideoCollectionScreen
                                                    ::isSupportedVideo)
                                    .forEach(path -> {
                                        if (!Thread.currentThread()
                                                .isInterrupted()) {
                                            loaded.add(
                                                    VideoMetadata.readQuick(
                                                            path));
                                        }
                                    });
                        } finally {
                            stream.close();
                        }
                    }
                } catch (Throwable throwable) {
                    RecordableMod.LOGGER.warn(
                            "Failed to refresh video collection.",
                            throwable);
                    failure = tr(
                            "screen.recordable.video_collection.refresh_failed",
                            "Failed to refresh recordings list.");
                    failed = true;
                }

                if (Thread.currentThread().isInterrupted()
                        || generation != workGeneration) {
                    return;
                }

                VideoMetadata.sort(
                        loaded,
                        RecordableConfig.get().gallerySortMode);
                long bytes = 0L;
                for (VideoMetadata metadata : loaded) {
                    bytes += Math.max(0L, metadata.sizeBytes);
                }
                final long finalBytes = bytes;
                final String finalFailure = failure;
                final boolean finalFailed = failed;

                Minecraft.getMinecraft().addScheduledTask(
                        new Runnable() {
                    @Override
                    public void run() {
                        if (generation != workGeneration
                                || mc == null
                                || mc.currentScreen
                                        != VideoCollectionScreen.this) {
                            return;
                        }
                        synchronized (videosLock) {
                            allVideos.clear();
                            allVideos.addAll(loaded);
                        }
                        totalSizeBytes = finalBytes;
                        loading = false;
                        if (finalFailed) {
                            statusMessage = finalFailure;
                            statusIsError = true;
                        } else if (loaded.isEmpty()) {
                            statusMessage = null;
                            statusIsError = false;
                        } else {
                            statusMessage = null;
                            statusIsError = false;
                        }
                        applyFilter();
                        startDurationProbe(
                                new ArrayList<VideoMetadata>(loaded),
                                generation);
                    }
                });
            }
        }, "Recordable-GalleryLoad");
        loader.setDaemon(true);
        loaderThread = loader;
        loader.start();
    }

    private void startDurationProbe(
            List<VideoMetadata> videos,
            final long generation) {
        final List<Path> paths = new ArrayList<Path>();
        for (VideoMetadata metadata : videos) {
            if (metadata != null
                    && metadata.durationSeconds <= 0.0D
                    && metadata.file != null) {
                paths.add(metadata.file);
            }
        }
        if (paths.isEmpty()) {
            return;
        }

        durationProbeRunning.set(true);
        ExecutorService executor =
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "Recordable-GalleryProbe");
                    thread.setDaemon(true);
                    return thread;
                });
        durationProber = executor;
        executor.submit(new Runnable() {
            @Override
            public void run() {
                for (final Path path : paths) {
                    if (!durationProbeRunning.get()
                            || generation != workGeneration
                            || durationProber != executor
                            || Thread.currentThread()
                                    .isInterrupted()) {
                        break;
                    }
                    try {
                        final VideoMetadata replacement =
                                VideoMetadata.probeDurationFor(path);
                        if (replacement != null
                                && durationProbeRunning.get()
                                && generation == workGeneration
                                && durationProber == executor) {
                            Minecraft.getMinecraft().addScheduledTask(
                                    new Runnable() {
                                @Override
                                public void run() {
                                    if (generation
                                            != workGeneration
                                            || durationProber
                                                != executor) {
                                        return;
                                    }
                                    replaceProbedEntry(
                                            path,
                                            replacement);
                                }
                            });
                        }
                    } catch (Throwable throwable) {
                        RecordableMod.LOGGER.debug(
                                "Duration probe failed for {}",
                                path,
                                throwable);
                    }
                }
                if (generation == workGeneration
                        && durationProber == executor) {
                    durationProbeRunning.set(false);
                }
            }
        });
    }

    private void replaceProbedEntry(
            Path path,
            VideoMetadata replacement) {
        synchronized (videosLock) {
            replaceInList(allVideos, path, replacement);
            VideoMetadata.sort(
                    allVideos,
                    RecordableConfig.get().gallerySortMode);
        }
        applyFilter();
    }

    private static void replaceInList(
            List<VideoMetadata> videos,
            Path path,
            VideoMetadata replacement) {
        for (int index = 0; index < videos.size(); index++) {
            VideoMetadata existing = videos.get(index);
            if (existing != null
                    && existing.file != null
                    && existing.file.equals(path)) {
                videos.set(index, replacement);
                return;
            }
        }
    }

    private void applyFilter() {
        String query = searchField == null
                ? ""
                : searchField.getText();
        String needle = query == null
                ? ""
                : query.trim().toLowerCase(Locale.ROOT);

        synchronized (videosLock) {
            filteredVideos.clear();
            for (VideoMetadata metadata : allVideos) {
                if (metadata == null) {
                    continue;
                }
                String searchable = (
                        metadata.filename
                                + " "
                                + metadata.recordedAtDisplay)
                        .toLowerCase(Locale.ROOT);
                if (needle.isEmpty()
                        || searchable.contains(needle)) {
                    filteredVideos.add(metadata);
                }
            }
        }
        clampScroll();
    }

    private void cycleSortMode(boolean forward) {
        RecordableConfig config = RecordableConfig.get();
        String[] modes = RecordableConfig.GALLERY_SORT_MODES;
        int current = 0;
        for (int index = 0; index < modes.length; index++) {
            if (modes[index].equals(config.gallerySortMode)) {
                current = index;
                break;
            }
        }
        int step = forward ? 1 : -1;
        config.gallerySortMode =
                modes[(current + step + modes.length) % modes.length];
        config.save();
        synchronized (videosLock) {
            VideoMetadata.sort(
                    allVideos,
                    config.gallerySortMode);
        }
        applyFilter();
        if (sortButton != null) {
            sortButton.displayString = sortLabel();
        }
    }

    private String sortLabel() {
        return tr(
                "screen.recordable.video_collection.sort",
                "Sort")
                + ": "
                + sortModeLabel(
                        RecordableConfig.get().gallerySortMode);
    }

    private static String sortModeLabel(String mode) {
        if ("oldest".equals(mode)) {
            return "Oldest";
        }
        if ("name_az".equals(mode)) {
            return "A-Z";
        }
        if ("name_za".equals(mode)) {
            return "Z-A";
        }
        if ("largest".equals(mode)) {
            return "Largest";
        }
        if ("smallest".equals(mode)) {
            return "Smallest";
        }
        if ("longest".equals(mode)) {
            return "Longest";
        }
        if ("shortest".equals(mode)) {
            return "Shortest";
        }
        return "Newest";
    }

    @Override
    public void drawScreen(
            int mouseX,
            int mouseY,
            float partialTicks) {
        drawDefaultBackground();
        ThemedPanel.drawMenuBackdrop(width, height);
        ThemeColors colors = ThemeEngine.get().colors();
        ThemePreset preset = ThemeEngine.get().preset();

        int panelLeft = listLeft - 8;
        int panelRight = listRight + 8;
        int panelTop = headerTop - 4;
        int panelBottom = height - 8;
        if (preset == ThemePreset.CINEMA) {
            ThemedPanel.drawFilmPanel(
                    panelLeft,
                    panelTop,
                    panelRight,
                    panelBottom);
        } else {
            ThemedPanel.drawPanel(
                    panelLeft,
                    panelTop,
                    panelRight,
                    panelBottom);
        }

        String screenTitle = clipsMode
                ? tr(
                        "screen.recordable.video_collection.clips_title",
                        "Record-able Clips")
                : tr(
                        "screen.recordable.video_collection.title",
                        "Record-able Video Collection");
        String decoratedTitle;
        if (preset == ThemePreset.VHS) {
            decoratedTitle = "\u25B6 " + screenTitle;
        } else if (preset == ThemePreset.CINEMA) {
            decoratedTitle = "\u2605 " + screenTitle;
        } else {
            decoratedTitle = screenTitle;
        }
        drawCenteredString(
                fontRendererObj,
                decoratedTitle,
                width / 2,
                panelTop + 6,
                colors.headerText);

        String summary = tr(
                "screen.recordable.video_collection.summary",
                "Videos: %s | Total Size: %s",
                Integer.toString(allVideoCount()),
                formatSizeMb(totalSizeBytes));
        fontRendererObj.drawString(
                summary,
                listLeft,
                panelTop + 6,
                colors.textMuted);

        drawSearchField(mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);

        Gui.drawRect(
                listLeft,
                listTop,
                listRight,
                listBottom,
                colors.sectionBackground);
        Gui.drawRect(
                listLeft,
                listTop,
                listRight,
                listTop + 1,
                colors.accent);
        Gui.drawRect(
                listLeft,
                listBottom - 1,
                listRight,
                listBottom,
                colors.panelBorder);
        if (preset == ThemePreset.CINEMA) {
            VhsEffectsRenderer.renderSprocketHoles(
                    listLeft - 6,
                    listTop,
                    listBottom,
                    colors.accent);
            VhsEffectsRenderer.renderSprocketHoles(
                    listRight + 1,
                    listTop,
                    listBottom,
                    colors.accent);
        }

        actionZones.clear();
        if (filteredVideoCount() == 0) {
            String empty = loading
                    ? tr(
                            "screen.recordable.video_collection.loading",
                            "Loading...")
                    : tr(
                            clipsMode
                                    ? "screen.recordable.video_collection.no_clips"
                                    : "screen.recordable.video_collection.no_recordings",
                            clipsMode
                                    ? "No clips found"
                                    : "No recordings found");
            drawCenteredString(
                    fontRendererObj,
                    empty,
                    width / 2,
                    listTop
                            + Math.max(
                                    8,
                                    (listBottom - listTop) / 2 - 6),
                    colors.textMuted);
            if (loading
                    || preset == ThemePreset.VHS
                    || preset == ThemePreset.CINEMA) {
                ThemedPanel.drawReelLoading(
                        width / 2,
                        listTop
                                + (listBottom - listTop) / 2
                                + 16,
                        12);
            }
        } else {
            renderVideoEntries(mouseX, mouseY);
        }

        if (statusMessage != null
                && !statusMessage.isEmpty()) {
            fontRendererObj.drawString(
                    fontRendererObj.trimStringToWidth(
                            statusMessage,
                            Math.max(20, listRight - listLeft)),
                    listLeft,
                    height - 18,
                    statusIsError
                            ? colors.textError
                            : colors.textMuted);
        }

        if (shareTarget != null) {
            renderShareOverlay(mouseX, mouseY);
        }
    }

    private void drawSearchField(int mouseX, int mouseY) {
        if (searchField == null) {
            return;
        }
        ThemeColors colors = ThemeEngine.get().colors();
        int x = searchField.xPosition;
        int y = searchField.yPosition;
        int w = searchField.width;
        int h = searchField.height;
        Gui.drawRect(x, y, x + w, y + h, colors.buttonBorder);
        Gui.drawRect(
                x + 1,
                y + 1,
                x + w - 1,
                y + h - 1,
                colors.buttonBackground);
        searchField.drawTextBox();
        if (searchField.getText().isEmpty()
                && !searchField.isFocused()) {
            fontRendererObj.drawString(
                    tr(
                            "screen.recordable.video_collection.search_hint",
                            "Search recordings..."),
                    x + 4,
                    y + 5,
                    colors.textMuted);
        }
    }

    private void renderVideoEntries(int mouseX, int mouseY) {
        int viewHeight = Math.max(1, listBottom - listTop);
        int firstIndex = Math.max(0, scrollOffset / ENTRY_HEIGHT);
        int lastIndex = Math.min(
                filteredVideoCount(),
                firstIndex + viewHeight / ENTRY_HEIGHT + 3);
        int firstY = listTop - scrollOffset % ENTRY_HEIGHT;

        beginListScissor();
        try {
            for (int index = firstIndex;
                    index < lastIndex;
                    index++) {
                VideoMetadata metadata = filteredVideo(index);
                int top = firstY
                        + (index - firstIndex) * ENTRY_HEIGHT;
                int bottom = top + ENTRY_HEIGHT - 2;
                if (bottom < listTop || top > listBottom) {
                    continue;
                }
                renderEntry(
                        metadata,
                        top,
                        bottom,
                        mouseX,
                        mouseY);
            }
        } finally {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        renderScrollBar(viewHeight);
    }

    private void beginListScissor() {
        ScaledResolution resolution = new ScaledResolution(mc);
        int scale = resolution.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
                listLeft * scale,
                (height - listBottom) * scale,
                Math.max(0, listRight - listLeft) * scale,
                Math.max(0, listBottom - listTop) * scale);
    }

    private void renderEntry(
            VideoMetadata metadata,
            int top,
            int bottom,
            int mouseX,
            int mouseY) {
        if (metadata == null) {
            return;
        }
        ThemeColors colors = ThemeEngine.get().colors();
        boolean hovered = mouseX >= listLeft
                && mouseX <= listRight
                && mouseY >= Math.max(top, listTop)
                && mouseY <= Math.min(bottom, listBottom);
        int background = hovered
                ? colors.panelBackground
                : ThemeEngine.lerpColor(
                        colors.panelBackground,
                        0xFF000000,
                        0.3F);
        Gui.drawRect(
                listLeft + 2,
                top,
                listRight - 2,
                bottom,
                background);
        if (hovered) {
            Gui.drawRect(
                    listLeft + 2,
                    top,
                    listLeft + 4,
                    bottom,
                    colors.accent);
        }

        int thumbnailLeft = listLeft + 6;
        int thumbnailTop = top + 5;
        int thumbnailWidth = 74;
        int thumbnailHeight = 40;
        Gui.drawRect(
                thumbnailLeft,
                thumbnailTop,
                thumbnailLeft + thumbnailWidth,
                thumbnailTop + thumbnailHeight,
                colors.panelBackground);
        Gui.drawRect(
                thumbnailLeft,
                thumbnailTop,
                thumbnailLeft + thumbnailWidth,
                thumbnailTop + 1,
                colors.panelBorder);
        Gui.drawRect(
                thumbnailLeft,
                thumbnailTop + thumbnailHeight - 1,
                thumbnailLeft + thumbnailWidth,
                thumbnailTop + thumbnailHeight,
                colors.panelBorder);
        if (!drawThumbnail(
                metadata,
                thumbnailLeft + 1,
                thumbnailTop + 1,
                thumbnailWidth - 2,
                thumbnailHeight - 2)) {
            Gui.drawRect(
                    thumbnailLeft + 8,
                    thumbnailTop + 7,
                    thumbnailLeft + 66,
                    thumbnailTop + 33,
                    colors.sectionHover);
            drawCenteredString(
                    fontRendererObj,
                    "VIDEO",
                    thumbnailLeft + thumbnailWidth / 2,
                    thumbnailTop + 15,
                    colors.accent);
        }

        boolean protectedVideo = StorageManager.isProtected(
                RecordableConfig.get(),
                metadata.filename);
        int textX = thumbnailLeft + thumbnailWidth + 8;
        int buttonsRight = listRight - 6;
        int secondColumn = buttonsRight - ACTION_WIDTH * 2 - 5;
        int firstColumn = secondColumn - ACTION_WIDTH - 5;
        int textMaximumWidth = Math.max(
                24,
                firstColumn - textX - 5);

        String name = fontRendererObj.trimStringToWidth(
                metadata.filename,
                textMaximumWidth);
        if (protectedVideo) {
            drawLockIcon(textX, top + 4, colors.accent);
            textX += 9;
            textMaximumWidth = Math.max(16, textMaximumWidth - 9);
            name = fontRendererObj.trimStringToWidth(
                    metadata.filename,
                    textMaximumWidth);
        }
        fontRendererObj.drawStringWithShadow(
                name,
                textX,
                top + 4,
                protectedVideo
                        ? colors.accent
                        : colors.textPrimary);
        fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(
                        tr(
                                "screen.recordable.video_collection.meta.size_duration",
                                "Size: %s   Duration: %s",
                                metadata.sizeDisplay,
                                metadata.durationDisplay),
                        textMaximumWidth),
                textX,
                top + 17,
                colors.textSecondary);
        fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(
                        tr(
                                "screen.recordable.video_collection.meta.recorded_at",
                                "Recorded: %s",
                                metadata.recordedAtDisplay),
                        textMaximumWidth),
                textX,
                top + 29,
                colors.textSecondary);

        int rowOne = top + 5;
        int rowTwo = top + 24;
        int rowThree = top + 43;
        drawListActionButton(
                mouseX,
                mouseY,
                firstColumn,
                rowOne,
                ACTION_WIDTH,
                ACTION_HEIGHT,
                tr(
                        "screen.recordable.video_collection.play",
                        "Play"),
                () -> playInGame(metadata.file));
        drawListActionButton(
                mouseX,
                mouseY,
                secondColumn,
                rowOne,
                ACTION_WIDTH,
                ACTION_HEIGHT,
                tr(
                        "screen.recordable.video_collection.open_folder",
                        "Folder"),
                () -> openContainingFolder(metadata.file));
        drawListActionButton(
                mouseX,
                mouseY,
                firstColumn,
                rowTwo,
                ACTION_WIDTH,
                ACTION_HEIGHT,
                tr(
                        protectedVideo
                                ? "screen.recordable.video_collection.unprotect"
                                : "screen.recordable.video_collection.protect",
                        protectedVideo ? "Unlock" : "Protect"),
                () -> toggleProtect(metadata));
        drawListActionButton(
                mouseX,
                mouseY,
                secondColumn,
                rowTwo,
                ACTION_WIDTH,
                ACTION_HEIGHT,
                tr(
                        "screen.recordable.video_collection.delete",
                        "Delete"),
                () -> confirmDelete(metadata));
        drawListActionButton(
                mouseX,
                mouseY,
                firstColumn,
                rowThree,
                ACTION_WIDTH,
                ACTION_HEIGHT,
                tr(
                        "screen.recordable.video_collection.copy_path",
                        "Copy Path"),
                () -> copyPath(metadata.file));
        drawListActionButton(
                mouseX,
                mouseY,
                secondColumn,
                rowThree,
                ACTION_WIDTH,
                ACTION_HEIGHT,
                tr(
                        "screen.recordable.video_collection.share",
                        "Share"),
                () -> openShareDialog(metadata));
    }

    private void drawLockIcon(int x, int y, int color) {
        Gui.drawRect(x + 2, y, x + 6, y + 1, color);
        Gui.drawRect(x + 1, y + 1, x + 2, y + 4, color);
        Gui.drawRect(x + 6, y + 1, x + 7, y + 4, color);
        Gui.drawRect(x, y + 3, x + 8, y + 8, color);
        Gui.drawRect(x + 3, y + 5, x + 5, y + 7, 0xFF000000);
    }

    private boolean drawThumbnail(
            VideoMetadata metadata,
            int x,
            int y,
            int drawWidth,
            int drawHeight) {
        Path path = metadata.thumbnailPath;
        if (path == null
                || failedThumbnailPaths.contains(path)
                || !Files.isRegularFile(path)) {
            return false;
        }

        ThumbnailTexture thumbnail = thumbnailCache.get(path);
        if (thumbnail == null) {
            thumbnail = loadThumbnailTexture(path);
            if (thumbnail == null) {
                failedThumbnailPaths.add(path);
                return false;
            }
            thumbnailCache.put(path, thumbnail);
        }

        try {
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            mc.getTextureManager().bindTexture(
                    thumbnail.location);
            Gui.drawScaledCustomSizeModalRect(
                    x,
                    y,
                    0.0F,
                    0.0F,
                    thumbnail.imageWidth,
                    thumbnail.imageHeight,
                    drawWidth,
                    drawHeight,
                    thumbnail.imageWidth,
                    thumbnail.imageHeight);
            return true;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug(
                    "Failed to render thumbnail {}",
                    path,
                    throwable);
            failedThumbnailPaths.add(path);
            return false;
        } finally {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private ThumbnailTexture loadThumbnailTexture(Path path) {
        BufferedImage image = null;
        try {
            if (!Files.isReadable(path)) {
                return null;
            }
            image = ImageIO.read(path.toFile());
            if (image == null) {
                return null;
            }
            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();
            long pixels = imageWidth * (long) imageHeight;
            if (imageWidth <= 0
                    || imageHeight <= 0
                    || imageWidth > MAX_THUMBNAIL_DIMENSION
                    || imageHeight > MAX_THUMBNAIL_DIMENSION
                    || pixels > MAX_THUMBNAIL_PIXELS) {
                return null;
            }
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation location = mc.getTextureManager()
                    .getDynamicTextureLocation(
                            "recordable_thumb_"
                                    + Integer.toHexString(
                                            path.toAbsolutePath()
                                                    .toString()
                                                    .hashCode()),
                            texture);
            return new ThumbnailTexture(
                    location,
                    texture,
                    imageWidth,
                    imageHeight);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug(
                    "Failed to load thumbnail {}",
                    path,
                    throwable);
            return null;
        } finally {
            if (image != null) {
                image.flush();
            }
        }
    }

    private void clearThumbnails() {
        for (ThumbnailTexture thumbnail :
                thumbnailCache.values()) {
            try {
                if (mc != null
                        && mc.getTextureManager() != null
                        && thumbnail.location != null) {
                    mc.getTextureManager().deleteTexture(
                            thumbnail.location);
                } else if (thumbnail.texture != null) {
                    thumbnail.texture.deleteGlTexture();
                }
            } catch (Throwable ignored) {
            }
        }
        thumbnailCache.clear();
        failedThumbnailPaths.clear();
    }

    private void drawListActionButton(
            int mouseX,
            int mouseY,
            int x,
            int y,
            int buttonWidth,
            int buttonHeight,
            String label,
            Runnable action) {
        drawActionButton(
                mouseX,
                mouseY,
                x,
                y,
                buttonWidth,
                buttonHeight,
                label,
                action,
                true);
    }

    private void drawActionButton(
            int mouseX,
            int mouseY,
            int x,
            int y,
            int buttonWidth,
            int buttonHeight,
            String label,
            Runnable action,
            boolean clipToList) {
        ThemeColors colors = ThemeEngine.get().colors();
        boolean hovered = mouseX >= x
                && mouseX < x + buttonWidth
                && mouseY >= y
                && mouseY < y + buttonHeight;
        if (clipToList) {
            hovered = hovered
                    && mouseY >= listTop
                    && mouseY < listBottom;
        }
        Gui.drawRect(
                x,
                y,
                x + buttonWidth,
                y + buttonHeight,
                hovered
                        ? colors.sectionHover
                        : colors.sectionBackground);
        Gui.drawRect(
                x,
                y,
                x + buttonWidth,
                y + 1,
                hovered ? colors.accent : colors.panelBorder);
        Gui.drawRect(
                x,
                y + buttonHeight - 1,
                x + buttonWidth,
                y + buttonHeight,
                colors.panelBorder);
        Gui.drawRect(
                x,
                y,
                x + 1,
                y + buttonHeight,
                colors.panelBorder);
        Gui.drawRect(
                x + buttonWidth - 1,
                y,
                x + buttonWidth,
                y + buttonHeight,
                colors.panelBorder);
        drawCenteredString(
                fontRendererObj,
                label,
                x + buttonWidth / 2,
                y + 3,
                hovered
                        ? colors.textPrimary
                        : colors.textSecondary);

        int zoneTop = clipToList ? Math.max(y, listTop) : y;
        int zoneBottom = clipToList
                ? Math.min(y + buttonHeight, listBottom)
                : y + buttonHeight;
        if (zoneBottom > zoneTop) {
            actionZones.add(new ActionZone(
                    x,
                    zoneTop,
                    x + buttonWidth,
                    zoneBottom,
                    action));
        }
    }

    private void renderScrollBar(int viewHeight) {
        int contentHeight =
                filteredVideoCount() * ENTRY_HEIGHT;
        if (contentHeight <= viewHeight) {
            return;
        }
        int thumbHeight = Math.max(
                24,
                (int) (viewHeight
                        * (viewHeight / (double) contentHeight)));
        int maximumScroll = contentHeight - viewHeight;
        int available = viewHeight - thumbHeight;
        int thumbTop = listTop
                + (int) ((scrollOffset
                        / (double) maximumScroll) * available);
        ThemedPanel.drawScrollbar(
                listRight - 5,
                listTop,
                listBottom,
                thumbTop,
                thumbHeight);
        if (draggingScrollbar) {
            Gui.drawRect(
                    listRight - 5,
                    thumbTop,
                    listRight - 2,
                    thumbTop + thumbHeight,
                    ThemeEngine.get().colors().textPrimary);
        }
    }

    private void renderShareOverlay(int mouseX, int mouseY) {
        ThemeColors colors = ThemeEngine.get().colors();
        Gui.drawRect(0, 0, width, height, 0xE0000000);

        int panelWidth = Math.min(336, width - 20);
        int panelHeight = Math.min(210, height - 12);
        int panelX = (width - panelWidth) / 2;
        int panelY = (height - panelHeight) / 2;
        Gui.drawRect(
                panelX,
                panelY,
                panelX + panelWidth,
                panelY + panelHeight,
                colors.panelBackground);
        Gui.drawRect(
                panelX,
                panelY,
                panelX + panelWidth,
                panelY + 1,
                colors.accent);
        Gui.drawRect(
                panelX,
                panelY + panelHeight - 1,
                panelX + panelWidth,
                panelY + panelHeight,
                colors.panelBorder);
        Gui.drawRect(
                panelX,
                panelY,
                panelX + 1,
                panelY + panelHeight,
                colors.panelBorder);
        Gui.drawRect(
                panelX + panelWidth - 1,
                panelY,
                panelX + panelWidth,
                panelY + panelHeight,
                colors.panelBorder);

        int centerX = panelX + panelWidth / 2;
        String filename = shareTarget.filename == null
                ? tr(
                        "screen.recordable.video_collection.share",
                        "Share")
                : shareTarget.filename;
        drawCenteredString(
                fontRendererObj,
                tr(
                        "screen.recordable.video_collection.share_title",
                        "Share \"%s\"",
                        ellipsize(filename, 34)),
                centerX,
                panelY + 8,
                colors.headerText);
        drawCenteredString(
                fontRendererObj,
                tr(
                        "screen.recordable.video_collection.share_prompt",
                        "Choose where to upload this recording:"),
                centerX,
                panelY + 22,
                colors.textSecondary);

        actionZones.clear();
        int buttonLeft = panelX + 12;
        int buttonWidth = panelWidth - 24;

        if (!sharing && shareOverlayUntilMs > 0L) {
            if (System.currentTimeMillis()
                    >= shareOverlayUntilMs) {
                closeShareDialog();
                return;
            }
            drawCenteredString(
                    fontRendererObj,
                    shareOverlayMessage == null
                            ? tr(
                                    "screen.recordable.video_collection.share_copied",
                                    "Link copied to clipboard")
                            : shareOverlayMessage,
                    centerX,
                    panelY + panelHeight / 2 - 8,
                    colors.textPrimary);
            drawDisclosure(
                    buttonLeft,
                    panelY + panelHeight,
                    colors);
            return;
        }

        if (sharing) {
            String hostName = shareTarget == null
                    ? tr(
                            "screen.recordable.video_collection.share",
                            "Share")
                    : tr(
                            "screen.recordable.video_collection.share",
                            "Share");
            String uploading = tr(
                    "screen.recordable.video_collection.share_uploading",
                    "Uploading to %s... this may take a while.",
                    hostName);
            if (VideoShareUploader.lastProgressPercent > 0) {
                uploading += " "
                        + VideoShareUploader.lastProgressPercent
                        + "%";
            }
            drawCenteredString(
                    fontRendererObj,
                    uploading,
                    centerX,
                    panelY + panelHeight / 2 - 4,
                    colors.textPrimary);
            return;
        }

        int recordableY = panelY + 40;
        drawActionButton(
                mouseX,
                mouseY,
                buttonLeft,
                recordableY,
                buttonWidth,
                16,
                tr(
                        "screen.recordable.video_collection.share_recordable",
                        "re.share-abl.ink (60 days)"),
                () -> shareRetentionDrawerOpen =
                        !shareRetentionDrawerOpen,
                false);
        fontRendererObj.drawString(
                tr(
                        "screen.recordable.video_collection.share_recordable_desc1",
                        "Compressed and hosted by re.share-abl.ink."),
                buttonLeft + 2,
                recordableY + 20,
                colors.textSecondary);
        fontRendererObj.drawString(
                tr(
                        "screen.recordable.video_collection.share_recordable_desc2",
                        "The share link lasts 60 days."),
                buttonLeft + 2,
                recordableY + 32,
                colors.textSecondary);

        int litterboxY = panelY + 94;
        drawActionButton(
                mouseX,
                mouseY,
                buttonLeft,
                litterboxY,
                buttonWidth,
                16,
                tr(
                        "screen.recordable.video_collection.share_litterbox",
                        "Litterbox (temporary)"),
                () -> shareTo(
                        shareTarget,
                        VideoShareUploader.Host.LITTERBOX,
                        VideoShareUploader.DEFAULT_RETENTION_DAYS),
                false);
        fontRendererObj.drawString(
                tr(
                        "screen.recordable.video_collection.share_litterbox_desc1",
                        "Temporary video storage. Up to 1 GB per file."),
                buttonLeft + 2,
                litterboxY + 20,
                colors.textSecondary);
        fontRendererObj.drawString(
                tr(
                        "screen.recordable.video_collection.share_litterbox_desc2",
                        "The share link expires after 72 hours."),
                buttonLeft + 2,
                litterboxY + 32,
                colors.textSecondary);

        drawDisclosure(
                buttonLeft,
                panelY + panelHeight,
                colors);
        drawActionButton(
                mouseX,
                mouseY,
                centerX - 30,
                panelY + panelHeight - 19,
                60,
                14,
                tr(
                        "screen.recordable.video_collection.share_cancel",
                        "Cancel"),
                this::closeShareDialog,
                false);

        if (shareRetentionDrawerOpen) {
            renderRetentionDrawer(
                    mouseX,
                    mouseY,
                    panelX,
                    panelWidth,
                    recordableY,
                    buttonLeft,
                    buttonWidth);
        }
    }

    private void drawDisclosure(
            int x,
            int panelBottom,
            ThemeColors colors) {
        fontRendererObj.drawString(
                tr(
                        "screen.recordable.video_collection.share_note_line1",
                        "The video is uploaded and becomes publicly accessible."),
                x + 6,
                panelBottom - 44,
                colors.textMuted);
        fontRendererObj.drawString(
                tr(
                        "screen.recordable.video_collection.share_note_line2",
                        "Anyone with the link can view or download it."),
                x + 6,
                panelBottom - 31,
                colors.textMuted);
    }

    private void renderRetentionDrawer(
            int mouseX,
            int mouseY,
            int panelX,
            int panelWidth,
            int recordableY,
            int buttonLeft,
            int buttonWidth) {
        ThemeColors colors = ThemeEngine.get().colors();
        int drawerWidth = 66;
        int drawerButtonHeight = 16;
        int drawerGap = 3;
        int padding = 3;
        int drawerX = buttonLeft + buttonWidth + 6;
        if (drawerX + drawerWidth + padding > width - 2) {
            drawerX = buttonLeft - drawerWidth - 6;
        }
        if (drawerX < 2) {
            drawerX = Math.max(
                    2,
                    panelX + panelWidth - drawerWidth - 6);
        }
        int drawerTop = recordableY - padding;
        int drawerHeight = padding * 2
                + drawerButtonHeight * 3
                + drawerGap * 2;
        Gui.drawRect(
                drawerX - padding,
                drawerTop,
                drawerX + drawerWidth + padding,
                drawerTop + drawerHeight,
                colors.panelBackground);
        Gui.drawRect(
                drawerX - padding,
                drawerTop,
                drawerX + drawerWidth + padding,
                drawerTop + 1,
                colors.accent);
        Gui.drawRect(
                drawerX - padding,
                drawerTop + drawerHeight - 1,
                drawerX + drawerWidth + padding,
                drawerTop + drawerHeight,
                colors.panelBorder);
        Gui.drawRect(
                drawerX - padding,
                drawerTop,
                drawerX - padding + 1,
                drawerTop + drawerHeight,
                colors.panelBorder);
        Gui.drawRect(
                drawerX + drawerWidth + padding - 1,
                drawerTop,
                drawerX + drawerWidth + padding,
                drawerTop + drawerHeight,
                colors.panelBorder);

        int[] options =
                VideoShareUploader.RETENTION_DAY_OPTIONS;
        for (int index = 0; index < options.length; index++) {
            final int retentionDays = options[index];
            int buttonY = recordableY
                    + index * (drawerButtonHeight + drawerGap);
            drawActionButton(
                    mouseX,
                    mouseY,
                    drawerX,
                    buttonY,
                    drawerWidth,
                    drawerButtonHeight,
                    retentionDays + " days",
                    () -> {
                        shareRetentionDrawerOpen = false;
                        shareTo(
                                shareTarget,
                                VideoShareUploader.Host.RECORDABLE,
                                retentionDays);
                    },
                    false);
        }
    }

    private void openShareDialog(VideoMetadata metadata) {
        if (metadata == null
                || metadata.file == null
                || sharing) {
            return;
        }
        shareTarget = metadata;
        shareRetentionDrawerOpen = false;
        shareOverlayMessage = null;
        shareOverlayUntilMs = 0L;
    }

    private void closeShareDialog() {
        if (sharing) {
            return;
        }
        shareTarget = null;
        shareRetentionDrawerOpen = false;
        shareOverlayMessage = null;
        shareOverlayUntilMs = 0L;
    }

    private void shareTo(
            VideoMetadata metadata,
            VideoShareUploader.Host host,
            int retentionDays) {
        if (metadata == null
                || metadata.file == null
                || host == null
                || sharing) {
            return;
        }
        final Path file = metadata.file;
        shareRetentionDrawerOpen = false;
        sharing = true;
        shareOverlayMessage = null;
        shareOverlayUntilMs = 0L;
        VideoShareUploader.lastProgressPercent = 0;
        setStatus(
                tr(
                        "screen.recordable.video_collection.share_uploading",
                        "Uploading to %s... this may take a while.",
                        host.displayName),
                false);

        ExecutorService executor =
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "Recordable-ShareUpload");
                    thread.setDaemon(true);
                    return thread;
                });
        shareUploader = executor;
        executor.submit(new Runnable() {
            @Override
            public void run() {
                boolean success;
                String result;
                try {
                    result = VideoShareUploader.upload(
                            file,
                            host,
                            retentionDays);
                    success = result != null
                            && (result.startsWith("https://")
                                    || result.startsWith("http://"));
                } catch (Throwable throwable) {
                    RecordableMod.LOGGER.warn(
                            "Share upload failed for {}",
                            file,
                            throwable);
                    result = throwable.getMessage() == null
                            ? throwable.toString()
                            : throwable.getMessage();
                    success = false;
                }
                final boolean finalSuccess = success;
                final String finalResult = result;
                Minecraft.getMinecraft().addScheduledTask(
                        new Runnable() {
                    @Override
                    public void run() {
                        if (mc != null
                                && mc.currentScreen
                                        == VideoCollectionScreen.this) {
                            onShareComplete(
                                    finalSuccess,
                                    finalResult);
                        }
                    }
                });
            }
        });
    }

    private void onShareComplete(
            boolean success,
            String result) {
        sharing = false;
        cancelShareUpload(false);
        if (success) {
            try {
                GuiScreen.setClipboardString(result);
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.debug(
                        "Could not copy share link to clipboard.",
                        throwable);
            }
            shareOverlayMessage = tr(
                    "screen.recordable.video_collection.share_copied",
                    "Link copied to clipboard");
            shareOverlayUntilMs =
                    System.currentTimeMillis() + 1000L;
            setStatus(shareOverlayMessage, false);
        } else {
            shareTarget = null;
            shareRetentionDrawerOpen = false;
            shareOverlayMessage = null;
            shareOverlayUntilMs = 0L;
            setStatus(
                    tr(
                            "screen.recordable.video_collection.share_failed",
                            "Share failed: %s",
                            result == null ? "" : result),
                    true);
        }
    }

    private void cancelShareUpload(boolean interrupt) {
        ExecutorService executor = shareUploader;
        if (executor != null) {
            if (interrupt) {
                executor.shutdownNow();
            } else {
                executor.shutdown();
            }
            shareUploader = null;
        }
        if (interrupt) {
            sharing = false;
        }
    }

    @Override
    protected void mouseClicked(
            int mouseX,
            int mouseY,
            int mouseButton) throws IOException {
        if (shareTarget != null) {
            if (mouseButton == 0) {
                runActionZone(mouseX, mouseY);
            }
            return;
        }

        if (mouseButton == 1
                && sortButton != null
                && sortButton.mousePressed(mc, mouseX, mouseY)) {
            cycleSortMode(false);
            sortButton.playPressSound(mc.getSoundHandler());
            return;
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (searchField != null) {
            searchField.mouseClicked(
                    mouseX,
                    mouseY,
                    mouseButton);
        }
        if (mouseButton != 0) {
            return;
        }
        if (isOverScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            scrollToMouse(mouseY);
            return;
        }
        runActionZone(mouseX, mouseY);
    }

    private boolean runActionZone(int mouseX, int mouseY) {
        for (int index = actionZones.size() - 1;
                index >= 0;
                index--) {
            ActionZone zone = actionZones.get(index);
            if (zone.contains(mouseX, mouseY)) {
                zone.action.run();
                return true;
            }
        }
        return false;
    }

    @Override
    protected void mouseClickMove(
            int mouseX,
            int mouseY,
            int clickedMouseButton,
            long timeSinceLastClick) {
        if (draggingScrollbar) {
            scrollToMouse(mouseY);
            return;
        }
        super.mouseClickMove(
                mouseX,
                mouseY,
                clickedMouseButton,
                timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(
            int mouseX,
            int mouseY,
            int state) {
        if (draggingScrollbar) {
            draggingScrollbar = false;
            return;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || shareTarget != null) {
            return;
        }
        int delta = wheel > 0 ? -20 : 20;
        scrollOffset += delta;
        clampScroll();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (shareTarget != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                if (shareRetentionDrawerOpen) {
                    shareRetentionDrawerOpen = false;
                } else {
                    closeShareDialog();
                }
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            onClose();
            return;
        }
        String before = searchField == null
                ? ""
                : searchField.getText();
        if (searchField != null
                && searchField.textboxKeyTyped(
                        typedChar,
                        keyCode)) {
            if (!before.equals(searchField.getText())) {
                applyFilter();
            }
            return;
        }
        if (keyCode == Keyboard.KEY_DOWN) {
            scrollOffset += ENTRY_HEIGHT;
            clampScroll();
            return;
        }
        if (keyCode == Keyboard.KEY_UP) {
            scrollOffset -= ENTRY_HEIGHT;
            clampScroll();
            return;
        }
        if (keyCode == Keyboard.KEY_NEXT) {
            scrollOffset += Math.max(
                    ENTRY_HEIGHT,
                    listBottom - listTop);
            clampScroll();
            return;
        }
        if (keyCode == Keyboard.KEY_PRIOR) {
            scrollOffset -= Math.max(
                    ENTRY_HEIGHT,
                    listBottom - listTop);
            clampScroll();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private boolean isOverScrollbar(int mouseX, int mouseY) {
        int viewHeight = Math.max(0, listBottom - listTop);
        int contentHeight =
                filteredVideoCount() * ENTRY_HEIGHT;
        return contentHeight > viewHeight
                && mouseX >= listRight - 7
                && mouseX <= listRight
                && mouseY >= listTop
                && mouseY <= listBottom;
    }

    private void scrollToMouse(int mouseY) {
        int viewHeight = Math.max(1, listBottom - listTop);
        int contentHeight =
                filteredVideoCount() * ENTRY_HEIGHT;
        int maximumScroll = Math.max(
                0,
                contentHeight - viewHeight);
        if (maximumScroll <= 0) {
            return;
        }
        int thumbHeight = Math.max(
                24,
                (int) (viewHeight
                        * (viewHeight / (double) contentHeight)));
        int available = Math.max(1, viewHeight - thumbHeight);
        double ratio = (
                mouseY - listTop - thumbHeight / 2.0D)
                / available;
        ratio = Math.max(0.0D, Math.min(1.0D, ratio));
        scrollOffset = (int) Math.round(
                ratio * maximumScroll);
        clampScroll();
    }

    private void clampScroll() {
        int viewHeight = Math.max(0, listBottom - listTop);
        int maximumScroll = Math.max(
                0,
                filteredVideoCount() * ENTRY_HEIGHT - viewHeight);
        scrollOffset = Math.max(
                0,
                Math.min(maximumScroll, scrollOffset));
    }

    private void toggleClipsView() {
        if (mc != null) {
            if (clipsMode) {
                onClose();
            } else {
                mc.displayGuiScreen(
                        new VideoCollectionScreen(this, true));
            }
        }
    }

    private void openRecordingsFolder() {
        try {
            Path directory = clipsMode
                    ? StorageManager.getAutoClipDirectory(
                            RecordableConfig.get())
                    : RecordableConfig.get().getOutputDirectory();
            if (directory == null) {
                throw new IOException(
                        "The recording output directory is not configured.");
            }
            Files.createDirectories(directory);
            if (!PlatformUtils.open(directory)) {
                throw new IOException(
                        "The operating system rejected the folder open request.");
            }
            setStatus(
                    tr(
                            "screen.recordable.video_collection.opened_folder",
                            "Opened recordings folder."),
                    false);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn(
                    "Failed to open recordings folder.",
                    throwable);
            setStatus(
                    tr(
                            "screen.recordable.video_collection.open_folder_failed",
                            "Could not open folder."),
                    true);
        }
    }

    private void openContainingFolder(Path file) {
        if (file == null) {
            return;
        }
        try {
            Path directory = file.getParent();
            if (directory == null
                    || !PlatformUtils.open(directory)) {
                throw new IOException(
                        "The operating system rejected the folder open request.");
            }
            setStatus(
                    tr(
                            "screen.recordable.video_collection.opened_folder",
                            "Opened recordings folder."),
                    false);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn(
                    "Failed to open folder for {}",
                    file,
                    throwable);
            setStatus(
                    tr(
                            "screen.recordable.video_collection.open_folder_failed",
                            "Could not open folder."),
                    true);
        }
    }

    private void playInGame(Path file) {
        if (file == null || mc == null) {
            return;
        }
        clearThumbnails();
        mc.displayGuiScreen(
                new VideoPlayerScreen(file, this));
    }

    private void copyPath(Path file) {
        if (file == null) {
            return;
        }
        try {
            GuiScreen.setClipboardString(
                    file.toAbsolutePath().toString());
            setStatus(
                    tr(
                            "screen.recordable.video_collection.copied_path",
                            "Copied video path."),
                    false);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn(
                    "Failed to copy video path {}",
                    file,
                    throwable);
            setStatus(
                    tr(
                            "screen.recordable.video_collection.copy_failed",
                            "Could not copy path."),
                    true);
        }
    }

    private void toggleProtect(VideoMetadata metadata) {
        if (metadata == null || metadata.filename == null) {
            return;
        }
        try {
            StorageManager.toggleProtected(
                    RecordableConfig.get(),
                    metadata.filename);
            boolean nowProtected = StorageManager.isProtected(
                    RecordableConfig.get(),
                    metadata.filename);
            if (nowProtected
                    && metadata.file != null
                    && metadata.file.equals(deleteConfirmPath)) {
                deleteConfirmPath = null;
                deleteConfirmUntil = 0L;
            }
            setStatus(
                    tr(
                            nowProtected
                                    ? "screen.recordable.video_collection.protected"
                                    : "screen.recordable.video_collection.unprotected",
                            nowProtected
                                    ? "Protected: %s"
                                    : "Unlocked: %s",
                            metadata.filename),
                    false);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn(
                    "Failed to toggle protection for {}",
                    metadata.filename,
                    throwable);
            setStatus(
                    "Could not update recording protection.",
                    true);
        }
    }

    private void confirmDelete(VideoMetadata metadata) {
        if (metadata == null || metadata.file == null) {
            return;
        }
        Path file = metadata.file;
        if (StorageManager.isProtected(
                RecordableConfig.get(),
                file)) {
            setStatus(
                    tr(
                            "screen.recordable.video_collection.delete_protected",
                            "Protected. Unlock first to delete: %s",
                            filename(file)),
                    true);
            return;
        }

        long now = System.currentTimeMillis();
        if (!file.equals(deleteConfirmPath)
                || now > deleteConfirmUntil) {
            deleteConfirmPath = file;
            deleteConfirmUntil = now + DELETE_CONFIRM_MS;
            setStatus(
                    tr(
                            "screen.recordable.video_collection.delete_confirm",
                            "Click Delete again to confirm: %s",
                            filename(file)),
                    true);
            return;
        }

        deleteConfirmPath = null;
        deleteConfirmUntil = 0L;
        if (StorageManager.deleteRecording(
                RecordableConfig.get(),
                file)) {
            setStatus(
                    tr(
                            "screen.recordable.video_collection.deleted",
                            "Deleted recording: %s",
                            filename(file)),
                    false);
            refreshVideos();
        } else {
            setStatus(
                    tr(
                            "screen.recordable.video_collection.delete_failed",
                            "Could not delete recording: %s",
                            filename(file)),
                    true);
        }
    }

    public void onClose() {
        if (mc != null) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void setStatus(String message, boolean error) {
        statusMessage = message;
        statusIsError = error;
    }

    private int allVideoCount() {
        synchronized (videosLock) {
            return allVideos.size();
        }
    }

    private int filteredVideoCount() {
        synchronized (videosLock) {
            return filteredVideos.size();
        }
    }

    private VideoMetadata filteredVideo(int index) {
        synchronized (videosLock) {
            return index >= 0 && index < filteredVideos.size()
                    ? filteredVideos.get(index)
                    : null;
        }
    }

    private static String filename(Path path) {
        return path == null || path.getFileName() == null
                ? ""
                : path.getFileName().toString();
    }

    private static boolean isSupportedVideo(Path path) {
        String name = filename(path).toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".mkv");
    }

    private static String formatSizeMb(long bytes) {
        return String.format(
                Locale.ROOT,
                "%.2f MB",
                Math.max(0L, bytes)
                        / (1024.0D * 1024.0D));
    }

    private static String ellipsize(String text, int maximum) {
        if (text == null) {
            return "";
        }
        return text.length() > maximum
                ? text.substring(0, Math.max(0, maximum - 3))
                        + "..."
                : text;
    }

    private static String tr(
            String key,
            String fallback,
            Object... arguments) {
        try {
            String translated = I18n.format(key, arguments);
            if (!translated.equals(key)) {
                return translated;
            }
        } catch (Throwable ignored) {
        }
        try {
            return arguments == null || arguments.length == 0
                    ? fallback
                    : String.format(
                            Locale.ROOT,
                            fallback,
                            arguments);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static final class ActionZone {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;
        private final Runnable action;

        private ActionZone(
                int left,
                int top,
                int right,
                int bottom,
                Runnable action) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.action = action;
        }

        private boolean contains(int x, int y) {
            return action != null
                    && x >= left
                    && x < right
                    && y >= top
                    && y < bottom;
        }
    }

    private static final class ThumbnailTexture {
        private final ResourceLocation location;
        private final DynamicTexture texture;
        private final int imageWidth;
        private final int imageHeight;

        private ThumbnailTexture(
                ResourceLocation location,
                DynamicTexture texture,
                int imageWidth,
                int imageHeight) {
            this.location = location;
            this.texture = texture;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }
    }
}
