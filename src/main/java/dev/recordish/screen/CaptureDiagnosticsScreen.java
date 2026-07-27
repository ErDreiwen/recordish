package dev.recordish.screen;

import dev.recordish.CaptureDiagnostics;
import dev.recordish.RecordishConfig;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * V1-0.09 "Test Capture" report, backed by the render-thread-safe legacy
 * capture health engine.
 */
public final class CaptureDiagnosticsScreen extends GuiScreen {
    private static final int RUN_AGAIN_ID = 1;
    private static final int BACK_ID = 2;

    private static final int PANEL_COLOR = 0xD0101010;
    private static final int PANEL_BORDER_COLOR = 0xFF424242;
    private static final int TEXT_COLOR = 0xFFD0D0D0;
    private static final int OK_COLOR = 0xFF88CC88;
    private static final int WARN_COLOR = 0xFFFFCC44;
    private static final int FAIL_COLOR = 0xFFFF6060;
    private static final int INFO_COLOR = 0xFFB8C4D0;
    private static final int LINE_HEIGHT = 12;

    private final GuiScreen parent;
    private final List<DiagnosticLine> lines =
            new ArrayList<DiagnosticLine>();

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int scrollOffset;
    private int contentHeight;
    private int bodyTop;
    private int bodyBottom;

    public CaptureDiagnosticsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        scrollOffset = 0;

        panelWidth = Math.max(
                340,
                Math.min((int) (width * 0.80D), 600));
        panelWidth = Math.min(
                panelWidth, Math.max(240, width - 16));
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(8, (int) (height * 0.05D));
        panelBottom = Math.min(
                height - 8,
                panelTop
                        + Math.max(
                                300,
                                (int) (height * 0.88D)));
        bodyTop = panelTop + 38;
        bodyBottom = panelBottom - 34;

        CaptureDiagnostics.requestSelfTest();

        int runWidth = 150;
        int gap = 10;
        int backWidth = 110;
        int totalWidth = runWidth + gap + backWidth;
        int startX = (width - totalWidth) / 2;
        int buttonY = panelBottom - 28;
        buttonList.add(new GuiButton(
                RUN_AGAIN_ID,
                startX,
                buttonY,
                runWidth,
                20,
                "Run Test Again"));
        buttonList.add(new GuiButton(
                BACK_ID,
                startX + runWidth + gap,
                buttonY,
                backWidth,
                20,
                "Back"));
    }

    @Override
    protected void actionPerformed(GuiButton button)
            throws IOException {
        if (button == null || !button.enabled) {
            return;
        }
        if (button.id == RUN_AGAIN_ID) {
            CaptureDiagnostics.requestSelfTest();
        } else if (button.id == BACK_ID) {
            closeToParent();
        }
    }

    /** Rebuilds the lightweight capture-only report every rendered frame. */
    private void rebuildLines() {
        lines.clear();
        CaptureDiagnostics.Inputs inputs =
                CaptureDiagnostics.collectInputs();
        List<CaptureDiagnostics.Check> checks =
                CaptureDiagnostics.buildReport(inputs);
        CaptureDiagnostics.Status verdict =
                CaptureDiagnostics.overallVerdict(checks);

        addLine(
                CaptureDiagnostics.verdictSummary(verdict),
                colorFor(verdict),
                true);
        addBlank();

        int detailWidth = panelWidth - 40;
        for (CaptureDiagnostics.Check check : checks) {
            int color = colorFor(check.status());
            addLine(
                    statusTag(check.status())
                            + " " + check.label(),
                    color,
                    true);
            for (String wrapped : wrap(
                    check.detail(), detailWidth)) {
                addLine(
                        "   " + wrapped,
                        TEXT_COLOR,
                        false);
            }
            addBlank();
        }
        contentHeight = lines.size() * LINE_HEIGHT + 10;
        scrollOffset = clamp(
                scrollOffset, 0, maximumScroll());
    }

    @Override
    public void drawScreen(
            int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        dev.recordish.theme.ThemedPanel.drawMenuBackdrop(width, height);
        rebuildLines();

        int accent = 0xFF000000
                | RecordishConfig.get()
                        .getMenuAccentColorRgb();
        int left = panelLeft - 6;
        int right = panelLeft + panelWidth + 6;
        Gui.drawRect(
                left,
                panelTop - 6,
                right,
                panelBottom,
                PANEL_COLOR);
        Gui.drawRect(
                left,
                panelTop - 6,
                right,
                panelTop - 5,
                accent);
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
                "Capture Diagnostics",
                width / 2,
                panelTop,
                0xFFFFFFFF);

        int textLeft = panelLeft + 14;
        for (int index = 0; index < lines.size(); index++) {
            int y = bodyTop
                    + index * LINE_HEIGHT
                    - scrollOffset;
            if (y < bodyTop - LINE_HEIGHT
                    || y > bodyBottom + 2) {
                continue;
            }
            DiagnosticLine line = lines.get(index);
            if (line.text.length() == 0) {
                continue;
            }
            fontRendererObj.drawStringWithShadow(
                    (line.bold ? "\u00A7l" : "")
                            + line.text,
                    textLeft,
                    y,
                    line.color);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && maximumScroll() > 0) {
            scrollOffset = clamp(
                    scrollOffset
                            + (wheel > 0 ? -20 : 20),
                    0,
                    maximumScroll());
        }
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
        if (mc != null) {
            mc.displayGuiScreen(parent);
        }
    }

    private void addLine(
            String text, int color, boolean bold) {
        lines.add(new DiagnosticLine(text, color, bold));
    }

    private void addBlank() {
        lines.add(
                new DiagnosticLine("", TEXT_COLOR, false));
    }

    private List<String> wrap(
            String text, int maximumWidth) {
        List<String> output = new ArrayList<String>();
        if (text == null || text.length() == 0) {
            return output;
        }
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.length() == 0
                    ? word
                    : current + " " + word;
            if (fontRendererObj.getStringWidth(candidate)
                    > maximumWidth
                    && current.length() > 0) {
                output.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            output.add(current.toString());
        }
        return output;
    }

    private int maximumScroll() {
        return Math.max(
                0,
                contentHeight
                        - Math.max(1, bodyBottom - bodyTop));
    }

    private static String statusTag(
            CaptureDiagnostics.Status status) {
        if (status == CaptureDiagnostics.Status.OK) {
            return "[OK]";
        }
        if (status == CaptureDiagnostics.Status.WARN) {
            return "[!]";
        }
        if (status == CaptureDiagnostics.Status.FAIL) {
            return "[X]";
        }
        return "[i]";
    }

    private static int colorFor(
            CaptureDiagnostics.Status status) {
        if (status == CaptureDiagnostics.Status.OK) {
            return OK_COLOR;
        }
        if (status == CaptureDiagnostics.Status.WARN) {
            return WARN_COLOR;
        }
        if (status == CaptureDiagnostics.Status.FAIL) {
            return FAIL_COLOR;
        }
        return INFO_COLOR;
    }

    private static int clamp(
            int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class DiagnosticLine {
        private final String text;
        private final int color;
        private final boolean bold;

        private DiagnosticLine(
                String text, int color, boolean bold) {
            this.text = text == null ? "" : text;
            this.color = color;
            this.bold = bold;
        }
    }
}
