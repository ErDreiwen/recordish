package dev.recordish.screen;

import dev.recordish.CensorRegion;
import dev.recordish.RecordishConfig;
import dev.recordish.theme.ThemeEngine;
import dev.recordish.theme.ThemedButton;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * V1-0.09 full-screen live censor editor, adapted to Forge 1.8.9.
 */
public final class CensorOverlayEditorScreen extends GuiScreen {
    private static final int WIDGET_HEIGHT = 20;
    private static final double MIN_SIZE = 0.03D;
    private static final int HANDLE = 7;

    private final GuiScreen parent;
    private int selected = -1;
    private int dragMode;
    private double pressFx;
    private double pressFy;
    private double originalX;
    private double originalY;

    public CensorOverlayEditorScreen(GuiScreen parent) {
        this.parent = parent;
    }

    private List<CensorRegion> regions() {
        return RecordishConfig.get().censorRegions;
    }

    @Override
    public void initGui() {
        ThemeEngine.get().applyPreset(RecordishConfig.get().uiTheme);
        buttonList.clear();
        int buttonWidth = 96;
        int gap = 6;
        int totalWidth = buttonWidth * 4 + gap * 3;
        int x = (width - totalWidth) / 2;
        int y = height - 28;

        buttonList.add(ThemedButton.create(
                1,
                x,
                y,
                buttonWidth,
                WIDGET_HEIGHT,
                "Add Bar",
                button -> addRegion()));
        x += buttonWidth + gap;
        buttonList.add(ThemedButton.create(
                2,
                x,
                y,
                buttonWidth,
                WIDGET_HEIGHT,
                "Remove",
                button -> removeSelected()));
        x += buttonWidth + gap;
        buttonList.add(ThemedButton.create(
                3,
                x,
                y,
                buttonWidth,
                WIDGET_HEIGHT,
                "Clear All",
                button -> {
                    regions().clear();
                    selected = -1;
                    RecordishConfig.get().save();
                }));
        x += buttonWidth + gap;
        buttonList.add(ThemedButton.create(
                4,
                x,
                y,
                buttonWidth,
                WIDGET_HEIGHT,
                "Done",
                button -> closeToParent()));
    }

    private void addRegion() {
        RecordishConfig config = RecordishConfig.get();
        CensorRegion region = new CensorRegion(
                0.40D,
                0.45D,
                0.20D,
                0.08D,
                parseStyle(config.streamerDefaultCensorStyle),
                "Censor");
        regions().add(region);
        selected = regions().size() - 1;
        config.save();
    }

    private void removeSelected() {
        if (selected >= 0 && selected < regions().size()) {
            regions().remove(selected);
            selected = -1;
            RecordishConfig.get().save();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        // The themed controls dispatch through their own callbacks.
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton)
            throws IOException {
        boolean controlHit = mouseButton == 0
                && isControlAt(mouseX, mouseY);
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (!controlHit) {
            canvasPress(mouseX, mouseY, mouseButton);
        }
    }

    private boolean isControlAt(int mouseX, int mouseY) {
        for (GuiButton button : buttonList) {
            if (button.visible
                    && button.enabled
                    && mouseX >= button.xPosition
                    && mouseX < button.xPosition + button.width
                    && mouseY >= button.yPosition
                    && mouseY < button.yPosition + button.height) {
                return true;
            }
        }
        return false;
    }

    private void canvasPress(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 || width <= 0 || height <= 0) {
            return;
        }
        double fx = mouseX / (double) width;
        double fy = mouseY / (double) height;
        List<CensorRegion> list = regions();

        if (selected >= 0 && selected < list.size()) {
            CensorRegion region = list.get(selected);
            int handleX = (int) ((region.x + region.width) * width);
            int handleY = (int) ((region.y + region.height) * height);
            if (Math.abs(mouseX - handleX) <= HANDLE
                    && Math.abs(mouseY - handleY) <= HANDLE) {
                dragMode = 2;
                pressFx = fx;
                pressFy = fy;
                originalX = region.x;
                originalY = region.y;
                return;
            }
        }

        for (int index = list.size() - 1; index >= 0; index--) {
            CensorRegion region = list.get(index);
            if (fx >= region.x
                    && fx <= region.x + region.width
                    && fy >= region.y
                    && fy <= region.y + region.height) {
                selected = index;
                dragMode = 1;
                pressFx = fx;
                pressFy = fy;
                originalX = region.x;
                originalY = region.y;
                return;
            }
        }

        RecordishConfig config = RecordishConfig.get();
        CensorRegion created = new CensorRegion(
                clamp(fx, 0.0D, 1.0D - MIN_SIZE),
                clamp(fy, 0.0D, 1.0D - MIN_SIZE),
                MIN_SIZE,
                MIN_SIZE,
                parseStyle(config.streamerDefaultCensorStyle),
                "Censor");
        list.add(created);
        selected = list.size() - 1;
        dragMode = 3;
        pressFx = created.x;
        pressFy = created.y;
    }

    @Override
    protected void mouseClickMove(
            int mouseX,
            int mouseY,
            int clickedMouseButton,
            long timeSinceLastClick) {
        if (dragMode == 0 || clickedMouseButton != 0) {
            return;
        }
        canvasDrag(mouseX, mouseY);
    }

    private void canvasDrag(int mouseX, int mouseY) {
        if (selected < 0 || selected >= regions().size()) {
            return;
        }
        CensorRegion region = regions().get(selected);
        double fx = clamp(mouseX / (double) width, 0.0D, 1.0D);
        double fy = clamp(mouseY / (double) height, 0.0D, 1.0D);
        if (dragMode == 1) {
            double deltaX = fx - pressFx;
            double deltaY = fy - pressFy;
            region.x = clamp(
                    originalX + deltaX,
                    0.0D,
                    1.0D - region.width);
            region.y = clamp(
                    originalY + deltaY,
                    0.0D,
                    1.0D - region.height);
        } else if (dragMode == 2) {
            region.width = clamp(
                    fx - region.x,
                    MIN_SIZE,
                    1.0D - region.x);
            region.height = clamp(
                    fy - region.y,
                    MIN_SIZE,
                    1.0D - region.y);
        } else if (dragMode == 3) {
            double x0 = Math.min(pressFx, fx);
            double y0 = Math.min(pressFy, fy);
            double x1 = Math.max(pressFx, fx);
            double y1 = Math.max(pressFy, fy);
            region.x = x0;
            region.y = y0;
            region.width = Math.max(MIN_SIZE, x1 - x0);
            region.height = Math.max(MIN_SIZE, y1 - y0);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (dragMode != 0) {
            canvasRelease();
        }
    }

    private void canvasRelease() {
        if (selected >= 0 && selected < regions().size()) {
            regions().get(selected).sanitize();
        }
        dragMode = 0;
        RecordishConfig.get().save();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_DELETE
                || keyCode == Keyboard.KEY_BACK) {
            removeSelected();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeToParent();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        RecordishConfig config = RecordishConfig.get();
        if (config != null) {
            List<CensorRegion> list = regions();
            for (int index = 0; index < list.size(); index++) {
                CensorRegion region = list.get(index);
                int x0 = (int) (region.x * width);
                int y0 = (int) (region.y * height);
                int x1 = (int) ((region.x + region.width) * width);
                int y1 = (int) ((region.y + region.height) * height);

                Gui.drawRect(
                        x0,
                        y0,
                        x1,
                        y1,
                        0xCC000000 | region.color & 0xFFFFFF);
                int borderColor = index == selected
                        ? 0xFF44FF44
                        : 0xFFFFFFFF;
                drawBorder(x0, y0, x1, y1, borderColor);

                String tag = region.showLabel
                        && region.label != null
                        && !region.label.trim().isEmpty()
                        ? region.label
                        : safeStyle(region).name();
                fontRendererObj.drawStringWithShadow(
                        "\u25CF " + tag,
                        x0 + 3,
                        y0 + 3,
                        0xFFFFFFFF);

                if (index == selected) {
                    Gui.drawRect(
                            x1 - HANDLE,
                            y1 - HANDLE,
                            x1,
                            y1,
                            0xFFFF8844);
                }
            }

            String info = "Drag to move  -  drag the orange corner to "
                    + "stretch  -  click empty space to add  -  "
                    + "Delete removes selected";
            drawCenteredString(
                    fontRendererObj,
                    info,
                    width / 2,
                    8,
                    0xFFFFFFFF);
            drawCenteredString(
                    fontRendererObj,
                    list.isEmpty()
                            ? "No censor bars yet"
                            : "Bars: " + list.size(),
                    width / 2,
                    20,
                    0xFFB0B0B0);
            if (!config.streamerModeEnabled) {
                drawCenteredString(
                        fontRendererObj,
                        "Streamer Mode is OFF - bars are saved but not "
                                + "shown in-game",
                        width / 2,
                        32,
                        0xFFFFAA55);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void onGuiClosed() {
        RecordishConfig.get().save();
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void closeToParent() {
        mc.displayGuiScreen(parent);
    }

    private static CensorRegion.Style safeStyle(CensorRegion region) {
        return region.style == null
                ? CensorRegion.Style.SOLID
                : region.style;
    }

    private static CensorRegion.Style parseStyle(String value) {
        if (value != null) {
            try {
                return CensorRegion.Style.valueOf(
                        value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to the official default.
            }
        }
        return CensorRegion.Style.SOLID;
    }

    private static void drawBorder(
            int left,
            int top,
            int right,
            int bottom,
            int color) {
        Gui.drawRect(left, top, right, top + 1, color);
        Gui.drawRect(left, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top, left + 1, bottom, color);
        Gui.drawRect(right - 1, top, right, bottom, color);
    }

    private static double clamp(
            double value,
            double minimum,
            double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
