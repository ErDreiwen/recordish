package dev.recordable.screen;

import dev.recordable.PlatformUtils;
import dev.recordable.RecordableConfig;
import dev.recordable.StorageManager;
import dev.recordable.VideoMetadata;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Searchable recording/clip collection with protection, deletion, compression,
 * metadata, and operating-system playback.
 */
public final class VideoCollectionScreen extends GuiScreen {
    private static final int ROW_HEIGHT = 30;

    private final GuiScreen parent;
    private final Object entriesLock = new Object();
    private final List<VideoMetadata> allEntries =
            new ArrayList<VideoMetadata>();
    private final List<VideoMetadata> visibleEntries =
            new ArrayList<VideoMetadata>();

    private GuiTextField search;
    private int scrollRows;
    private int selected = -1;
    private boolean loading;
    private boolean confirmDelete;
    private String status = "";
    private long statusExpires;
    private long lastClickMillis;
    private int lastClickedIndex = -1;

    public VideoCollectionScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        search = new GuiTextField(
                20,
                fontRendererObj,
                12,
                30,
                Math.max(100, width - 24),
                18);
        search.setMaxStringLength(100);

        int bottom = height - 50;
        int buttonWidth = Math.max(60, Math.min(90, (width - 24) / 7));
        int x = 12;
        buttonList.add(new GuiButton(1, x, bottom, buttonWidth, 20, "Done"));
        x += buttonWidth + 2;
        buttonList.add(new GuiButton(2, x, bottom, buttonWidth, 20, "Refresh"));
        x += buttonWidth + 2;
        buttonList.add(new GuiButton(3, x, bottom, buttonWidth, 20, sortLabel()));
        x += buttonWidth + 2;
        buttonList.add(new GuiButton(4, x, bottom, buttonWidth, 20, "Play"));
        x += buttonWidth + 2;
        buttonList.add(new GuiButton(5, x, bottom, buttonWidth, 20, "Protect"));
        x += buttonWidth + 2;
        buttonList.add(new GuiButton(6, x, bottom, buttonWidth, 20, "Delete"));
        x += buttonWidth + 2;
        buttonList.add(new GuiButton(7, x, bottom, buttonWidth, 20, "Compress"));

        buttonList.add(new GuiButton(
                8,
                12,
                height - 25,
                Math.min(130, width / 3),
                20,
                "Open Folder"));
        refreshAsync();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null || !button.enabled) return;
        if (button.id == 1) {
            mc.displayGuiScreen(parent);
        } else if (button.id == 2) {
            refreshAsync();
        } else if (button.id == 3) {
            cycleSort();
            button.displayString = sortLabel();
            rebuildVisible();
        } else if (button.id == 4) {
            VideoMetadata entry = selectedEntry();
            if (entry != null) PlatformUtils.open(entry.getFile());
        } else if (button.id == 5) {
            toggleProtection();
        } else if (button.id == 6) {
            deleteSelected();
        } else if (button.id == 7) {
            compressSelected();
        } else if (button.id == 8) {
            PlatformUtils.open(RecordableConfig.get().getOutputDirectory());
        }
    }

    private void refreshAsync() {
        if (loading) return;
        loading = true;
        status = "Loading recordings...";
        selected = -1;
        confirmDelete = false;
        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<VideoMetadata> loaded =
                        new ArrayList<VideoMetadata>();
                for (StorageManager.StoredFile file :
                        StorageManager.listRecordings(RecordableConfig.get())) {
                    loaded.add(VideoMetadata.readQuick(file.getPath()));
                }
                VideoMetadata.sort(
                        loaded,
                        RecordableConfig.get().gallerySortMode);
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (entriesLock) {
                            allEntries.clear();
                            allEntries.addAll(loaded);
                        }
                        loading = false;
                        status = loaded.size() + " video"
                                + (loaded.size() == 1 ? "" : "s");
                        statusExpires = System.currentTimeMillis() + 3000L;
                        rebuildVisible();
                        probeDurationsAsync(loaded);
                    }
                });
            }
        }, "Recordable-GalleryLoad");
        loader.setDaemon(true);
        loader.start();
    }

    private void probeDurationsAsync(final List<VideoMetadata> loaded) {
        Thread probe = new Thread(new Runnable() {
            @Override
            public void run() {
                for (VideoMetadata entry : loaded) {
                    final VideoMetadata full =
                            VideoMetadata.probeDurationFor(entry.getFile());
                    if (full == null) continue;
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            synchronized (entriesLock) {
                                for (int index = 0;
                                        index < allEntries.size();
                                        index++) {
                                    if (allEntries.get(index).getFile()
                                            .equals(full.getFile())) {
                                        allEntries.set(index, full);
                                    }
                                }
                            }
                            rebuildVisible();
                        }
                    });
                }
            }
        }, "Recordable-GalleryProbe");
        probe.setDaemon(true);
        probe.start();
    }

    private void rebuildVisible() {
        String needle = search == null
                ? ""
                : search.getText().trim().toLowerCase(Locale.ROOT);
        synchronized (entriesLock) {
            visibleEntries.clear();
            for (VideoMetadata entry : allEntries) {
                if (needle.isEmpty()
                        || entry.getFilename()
                                .toLowerCase(Locale.ROOT)
                                .contains(needle)) {
                    visibleEntries.add(entry);
                }
            }
            VideoMetadata.sort(
                    visibleEntries,
                    RecordableConfig.get().gallerySortMode);
        }
        scrollRows = Math.max(
                0,
                Math.min(scrollRows, maximumScrollRows()));
        if (selected >= visibleEntries.size()) selected = -1;
        confirmDelete = false;
        updateButtons();
    }

    private void cycleSort() {
        String[] modes = RecordableConfig.GALLERY_SORT_MODES;
        RecordableConfig config = RecordableConfig.get();
        int index = 0;
        for (int current = 0; current < modes.length; current++) {
            if (modes[current].equals(config.gallerySortMode)) {
                index = current;
                break;
            }
        }
        config.gallerySortMode = modes[(index + 1) % modes.length];
        config.save();
    }

    private String sortLabel() {
        String mode = RecordableConfig.get().gallerySortMode;
        return "Sort: " + (mode == null
                ? "newest"
                : mode.replace('_', ' '));
    }

    private void toggleProtection() {
        VideoMetadata entry = selectedEntry();
        if (entry == null) return;
        StorageManager.toggleProtected(
                RecordableConfig.get(),
                entry.getFilename());
        confirmDelete = false;
        status = StorageManager.isProtected(
                RecordableConfig.get(),
                entry.getFile())
                ? "Protected " + entry.getFilename()
                : "Unprotected " + entry.getFilename();
        statusExpires = System.currentTimeMillis() + 3000L;
        updateButtons();
    }

    private void deleteSelected() {
        VideoMetadata entry = selectedEntry();
        if (entry == null) return;
        if (StorageManager.isProtected(
                RecordableConfig.get(),
                entry.getFile())) {
            status = "Unprotect this video before deleting it.";
            statusExpires = System.currentTimeMillis() + 4000L;
            return;
        }
        if (!confirmDelete) {
            confirmDelete = true;
            status = "Click Delete again to permanently remove "
                    + entry.getFilename();
            statusExpires = System.currentTimeMillis() + 6000L;
            updateButtons();
            return;
        }
        confirmDelete = false;
        boolean deleted = StorageManager.deleteRecording(
                RecordableConfig.get(),
                entry.getFile());
        status = deleted
                ? "Deleted " + entry.getFilename()
                : "Could not delete " + entry.getFilename();
        statusExpires = System.currentTimeMillis() + 4000L;
        refreshAsync();
    }

    private void compressSelected() {
        final VideoMetadata entry = selectedEntry();
        if (entry == null) return;
        status = "Compressing " + entry.getFilename() + "...";
        statusExpires = Long.MAX_VALUE;
        setActionsEnabled(false);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final java.nio.file.Path result =
                        StorageManager.compressRecording(
                                RecordableConfig.get(),
                                entry.getFile());
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        status = result == null
                                ? "Compression failed."
                                : "Created " + result.getFileName();
                        statusExpires =
                                System.currentTimeMillis() + 5000L;
                        setActionsEnabled(true);
                        refreshAsync();
                    }
                });
            }
        }, "Recordable-Compress");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(
                fontRendererObj,
                "Record-able Video Collection",
                width / 2,
                10,
                0xFFFFFF);
        search.drawTextBox();

        int top = 55;
        int bottom = height - 55;
        GuiButton.drawRect(8, top - 2, width - 8, bottom, 0x90000000);
        int visibleRows = visibleRowCount();
        synchronized (entriesLock) {
            for (int row = 0; row < visibleRows; row++) {
                int entryIndex = scrollRows + row;
                if (entryIndex >= visibleEntries.size()) break;
                VideoMetadata entry = visibleEntries.get(entryIndex);
                int y = top + row * ROW_HEIGHT;
                boolean selectedRow = entryIndex == selected;
                int background = selectedRow
                        ? 0xA0446699
                        : (row % 2 == 0 ? 0x50202020 : 0x50303030);
                GuiButton.drawRect(
                        10,
                        y,
                        width - 10,
                        y + ROW_HEIGHT - 2,
                        background);
                boolean protectedFile = StorageManager.isProtected(
                        RecordableConfig.get(),
                        entry.getFile());
                String name = (protectedFile ? "[LOCK] " : "")
                        + entry.getFilename();
                fontRendererObj.drawStringWithShadow(
                        fontRendererObj.trimStringToWidth(
                                name,
                                Math.max(40, width - 180)),
                        15,
                        y + 5,
                        protectedFile ? 0xFFD966 : 0xFFFFFF);
                fontRendererObj.drawString(
                        entry.getDurationDisplay()
                                + "  " + entry.getSizeDisplay(),
                        15,
                        y + 17,
                        0xAAAAAA);
                String date = entry.getRecordedAtDisplay();
                fontRendererObj.drawString(
                        fontRendererObj.trimStringToWidth(date, 145),
                        width - 155,
                        y + 10,
                        0xBBBBBB);
            }
        }

        if (visibleEntries.isEmpty() && !loading) {
            drawCenteredString(
                    fontRendererObj,
                    "No matching recordings.",
                    width / 2,
                    top + 20,
                    0xAAAAAA);
        }
        if (status != null
                && (!status.isEmpty())
                && System.currentTimeMillis() <= statusExpires) {
            drawCenteredString(
                    fontRendererObj,
                    status,
                    width / 2,
                    height - 63,
                    0xDDDDDD);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton)
            throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        search.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        int top = 55;
        int bottom = height - 55;
        if (mouseX < 10 || mouseX >= width - 10
                || mouseY < top || mouseY >= bottom) {
            return;
        }
        int row = (mouseY - top) / ROW_HEIGHT;
        int entryIndex = scrollRows + row;
        synchronized (entriesLock) {
            if (entryIndex < 0
                    || entryIndex >= visibleEntries.size()) return;
            selected = entryIndex;
        }
        confirmDelete = false;
        updateButtons();

        long now = System.currentTimeMillis();
        if (lastClickedIndex == entryIndex
                && now - lastClickMillis < 400L) {
            VideoMetadata entry = selectedEntry();
            if (entry != null) PlatformUtils.open(entry.getFile());
        }
        lastClickedIndex = entryIndex;
        lastClickMillis = now;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        String before = search.getText();
        if (search.textboxKeyTyped(typedChar, keyCode)
                && !before.equals(search.getText())) {
            rebuildVisible();
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        scrollRows += wheel < 0 ? 1 : -1;
        scrollRows = Math.max(
                0,
                Math.min(scrollRows, maximumScrollRows()));
    }

    private int visibleRowCount() {
        return Math.max(1, (height - 110) / ROW_HEIGHT);
    }

    private int maximumScrollRows() {
        synchronized (entriesLock) {
            return Math.max(
                    0,
                    visibleEntries.size() - visibleRowCount());
        }
    }

    private VideoMetadata selectedEntry() {
        synchronized (entriesLock) {
            return selected >= 0 && selected < visibleEntries.size()
                    ? visibleEntries.get(selected)
                    : null;
        }
    }

    private void updateButtons() {
        VideoMetadata entry = selectedEntry();
        for (GuiButton button : buttonList) {
            if (button.id >= 4 && button.id <= 7) {
                button.enabled = entry != null;
            }
            if (button.id == 5 && entry != null) {
                button.displayString = StorageManager.isProtected(
                        RecordableConfig.get(),
                        entry.getFile())
                        ? "Unprotect"
                        : "Protect";
            }
            if (button.id == 6) {
                button.displayString = confirmDelete
                        ? "CONFIRM"
                        : "Delete";
            }
        }
    }

    private void setActionsEnabled(boolean enabled) {
        for (GuiButton button : buttonList) {
            if (button.id >= 2 && button.id <= 7) {
                button.enabled = enabled;
            }
        }
    }
}
