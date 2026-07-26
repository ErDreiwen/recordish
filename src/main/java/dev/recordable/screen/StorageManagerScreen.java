package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.StorageManager;
import dev.recordable.theme.ThemedPanel;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * V1-0.09 storage dashboard, adapted to the Forge 1.8.9 GUI API.
 */
public final class StorageManagerScreen extends GuiScreen {
    private static final int PANEL_COLOR = 0xD0101010;
    private static final int PANEL_BORDER_COLOR = 0xFF424242;
    private static final int TEXT_COLOR = 0xFFD0D0D0;
    private static final int HEADER_COLOR = 0xFFFFFFFF;
    private static final int HIGHLIGHT_COLOR = 0xFF88CC88;
    private static final int WARNING_COLOR = 0xFFFFCC44;
    private static final int ROW_HEIGHT = 22;

    private static final int CLEAN_ID = 1;
    private static final int AUTO_CLEANUP_ID = 2;
    private static final int REFRESH_ID = 3;
    private static final int BACK_ID = 4;
    private static final int FILE_BUTTON_BASE = 100;

    private final GuiScreen parent;
    private List<StorageManager.StoredFile> files =
            new ArrayList<StorageManager.StoredFile>();
    private StorageManager.StorageStats stats;
    private String statusMessage = "";

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int listTop;
    private int listBottom;
    private int scrollOffset;

    public StorageManagerScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        panelWidth = Math.max(360, Math.min((int) (width * 0.85D), 640));
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(8, (int) (height * 0.05D));
        panelBottom = Math.min(
                height - 8,
                panelTop + Math.max(320, (int) (height * 0.88D)));
        listTop = panelTop + 96;
        listBottom = panelBottom - 40;
        scrollOffset = 0;
        refreshData();
        rebuildPanelWidgets();
    }

    private void refreshData() {
        RecordableConfig config = RecordableConfig.get();
        files = StorageManager.listRecordings(config);
        stats = StorageManager.computeStats(config);
        int maxScroll = Math.max(
                0,
                files.size() * ROW_HEIGHT - (listBottom - listTop));
        scrollOffset = clamp(scrollOffset, 0, maxScroll);
    }

    private void rebuildPanelWidgets() {
        buttonList.clear();
        RecordableConfig config = RecordableConfig.get();
        int buttonWidth = 110;
        int topButtonY = panelTop + 50;

        buttonList.add(new GuiButton(
                CLEAN_ID,
                panelLeft + 12,
                topButtonY,
                buttonWidth,
                20,
                "Clean Now"));
        buttonList.add(new GuiButton(
                AUTO_CLEANUP_ID,
                panelLeft + 12 + buttonWidth + 8,
                topButtonY,
                buttonWidth + 20,
                20,
                "Auto-Cleanup: "
                        + (config.autoCleanupEnabled ? "ON" : "OFF")));
        buttonList.add(new GuiButton(
                REFRESH_ID,
                panelLeft + panelWidth - buttonWidth - 12,
                topButtonY,
                buttonWidth,
                20,
                "Refresh"));

        int rightEdge = panelLeft + panelWidth - 12;
        for (int index = 0; index < files.size(); index++) {
            StorageManager.StoredFile file = files.get(index);
            int rowY = listTop + index * ROW_HEIGHT - scrollOffset;
            if (rowY < listTop - ROW_HEIGHT || rowY > listBottom) {
                continue;
            }
            buttonList.add(new GuiButton(
                    FILE_BUTTON_BASE + index * 2,
                    rightEdge - 120,
                    rowY,
                    56,
                    18,
                    file.protectedFlag() ? "Unlock" : "Protect"));
            GuiButton delete = new GuiButton(
                    FILE_BUTTON_BASE + index * 2 + 1,
                    rightEdge - 60,
                    rowY,
                    56,
                    18,
                    "Delete");
            delete.enabled = !file.protectedFlag();
            buttonList.add(delete);
        }

        buttonList.add(new GuiButton(
                BACK_ID,
                (width - 120) / 2,
                panelBottom - 28,
                120,
                20,
                "Back"));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null || !button.enabled) {
            return;
        }
        RecordableConfig config = RecordableConfig.get();
        if (button.id == CLEAN_ID) {
            StorageManager.CleanupResult result =
                    StorageManager.runCleanup(config, true);
            statusMessage = "Removed " + result.filesDeleted()
                    + " file(s), freed " + result.bytesFreedDisplay();
        } else if (button.id == AUTO_CLEANUP_ID) {
            config.autoCleanupEnabled = !config.autoCleanupEnabled;
            config.save();
        } else if (button.id == REFRESH_ID) {
            statusMessage = "";
        } else if (button.id == BACK_ID) {
            closeToParent();
            return;
        } else if (button.id >= FILE_BUTTON_BASE) {
            int relative = button.id - FILE_BUTTON_BASE;
            int fileIndex = relative / 2;
            if (fileIndex >= 0 && fileIndex < files.size()) {
                StorageManager.StoredFile file = files.get(fileIndex);
                if ((relative & 1) == 0) {
                    StorageManager.toggleProtected(
                            config,
                            file.filename());
                } else if (!file.protectedFlag()) {
                    if (StorageManager.deleteRecording(
                            config,
                            file.path())) {
                        statusMessage = "Deleted " + file.filename();
                    }
                } else {
                    statusMessage = file.filename() + " is protected.";
                }
            }
        }
        refreshData();
        rebuildPanelWidgets();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int maxScroll = Math.max(
                0,
                files.size() * ROW_HEIGHT - (listBottom - listTop));
        if (maxScroll <= 0) {
            return;
        }
        scrollOffset = clamp(
                scrollOffset + (wheel > 0 ? -ROW_HEIGHT : ROW_HEIGHT),
                0,
                maxScroll);
        rebuildPanelWidgets();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        ThemedPanel.drawMenuBackdrop(width, height);

        int accent = 0xFF000000
                | RecordableConfig.get().getMenuAccentColorRgb();
        int left = panelLeft - 6;
        int right = panelLeft + panelWidth + 6;
        Gui.drawRect(left, panelTop - 6, right, panelBottom, PANEL_COLOR);
        Gui.drawRect(left, panelTop - 6, right, panelTop - 5, accent);
        Gui.drawRect(
                left,
                panelBottom - 1,
                right,
                panelBottom,
                PANEL_BORDER_COLOR);
        Gui.drawRect(
                left,
                panelTop - 6,
                left + 1,
                panelBottom,
                PANEL_BORDER_COLOR);
        Gui.drawRect(
                right - 1,
                panelTop - 6,
                right,
                panelBottom,
                PANEL_BORDER_COLOR);

        drawCenteredString(
                fontRendererObj,
                "Storage Manager",
                width / 2,
                panelTop,
                0xFFFFFFFF);

        int textLeft = panelLeft + 14;
        if (stats != null) {
            String diskLine = "Disk: " + stats.diskFreeDisplay()
                    + " free / " + stats.diskTotalDisplay()
                    + " total  (" + stats.diskUsedPercent() + "% used)";
            fontRendererObj.drawStringWithShadow(
                    diskLine,
                    textLeft,
                    panelTop + 18,
                    stats.diskUsedPercent() >= 90
                            ? WARNING_COLOR
                            : HIGHLIGHT_COLOR);
            String recordingLine = "Recordings: "
                    + stats.recordingCount() + " file(s), "
                    + stats.recordingsDisplay();
            fontRendererObj.drawStringWithShadow(
                    recordingLine,
                    textLeft,
                    panelTop + 32,
                    TEXT_COLOR);
        }

        fontRendererObj.drawStringWithShadow(
                "\u00A7lRecordings",
                textLeft,
                listTop - 14,
                HEADER_COLOR);

        for (int index = 0; index < files.size(); index++) {
            StorageManager.StoredFile file = files.get(index);
            int rowY = listTop + index * ROW_HEIGHT - scrollOffset;
            if (rowY < listTop - ROW_HEIGHT || rowY > listBottom) {
                continue;
            }
            if ((index & 1) == 0) {
                Gui.drawRect(
                        panelLeft + 8,
                        rowY - 2,
                        panelLeft + panelWidth - 8,
                        rowY + ROW_HEIGHT - 4,
                        0x30FFFFFF);
            }

            String name = file.filename();
            int maxChars = Math.max(10, (panelWidth - 280) / 6);
            if (name.length() > maxChars) {
                name = name.substring(0, maxChars - 1) + "\u2026";
            }
            int nameX = textLeft;
            if (file.protectedFlag()) {
                drawLock(nameX, rowY + 3, HIGHLIGHT_COLOR);
                nameX += 11;
            }
            fontRendererObj.drawStringWithShadow(
                    name,
                    nameX,
                    rowY + 3,
                    file.protectedFlag()
                            ? HIGHLIGHT_COLOR
                            : TEXT_COLOR);
            fontRendererObj.drawStringWithShadow(
                    file.sizeDisplay(),
                    panelLeft + panelWidth - 200,
                    rowY + 3,
                    TEXT_COLOR);
        }

        if (files.isEmpty()) {
            drawCenteredString(
                    fontRendererObj,
                    "No recordings found.",
                    width / 2,
                    listTop + 10,
                    TEXT_COLOR);
        }
        if (!statusMessage.isEmpty()) {
            drawCenteredString(
                    fontRendererObj,
                    statusMessage,
                    width / 2,
                    panelBottom - 42,
                    HIGHLIGHT_COLOR);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** Pixel lock fallback for the unsupported U+1F512 glyph. */
    private static void drawLock(int x, int y, int color) {
        Gui.drawRect(x + 2, y, x + 6, y + 1, color);
        Gui.drawRect(x + 1, y + 1, x + 2, y + 4, color);
        Gui.drawRect(x + 6, y + 1, x + 7, y + 4, color);
        Gui.drawRect(x, y + 3, x + 8, y + 8, color);
        Gui.drawRect(x + 3, y + 5, x + 5, y + 7, 0xFF101010);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeToParent();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void closeToParent() {
        mc.displayGuiScreen(parent);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
