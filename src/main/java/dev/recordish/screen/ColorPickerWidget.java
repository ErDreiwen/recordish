package dev.recordish.screen;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Lightweight V1-0.09 hex color picker for the legacy GUI API.
 */
public final class ColorPickerWidget {
    public static final int WIDGET_HEIGHT = 20;

    private static final Pattern HEX_COLOR =
            Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final int INPUT_WIDTH = 80;
    private static final int INNER_GAP = 5;

    private final FontRenderer font;
    private final Consumer<String> onColorChanged;
    private final String label;
    private final int fieldId;

    private GuiTextField textField;
    private int x;
    private int y;
    private final int width;
    private final int height;
    private String lastNotifiedColor;

    public ColorPickerWidget(
            FontRenderer font,
            int fieldId,
            int x,
            int y,
            int width,
            int height,
            String label,
            String currentValue,
            Consumer<String> onColorChanged) {
        this.font = font;
        this.fieldId = fieldId;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.label = label == null ? "" : label;
        this.onColorChanged = onColorChanged;
        this.lastNotifiedColor = sanitizeColor(currentValue);
        rebuildTextField(lastNotifiedColor, false);
    }

    public String getColor() {
        String candidate = normalize(textField.getText());
        return isValidHex(candidate)
                ? candidate.toUpperCase(Locale.ROOT)
                : "#FF0000";
    }

    public void setPosition(int x, int y) {
        boolean focused = textField != null && textField.isFocused();
        String value = textField == null ? "#FF0000" : textField.getText();
        this.x = x;
        this.y = y;
        rebuildTextField(value, focused);
    }

    public void drawWidget() {
        int previewColor = parseColor(getColor());
        Gui.drawRect(x, y, x + width, y + height, 0xAA151515);
        Gui.drawRect(x, y, x + width, y + 1, 0xFF424242);
        Gui.drawRect(
                x,
                y + height - 1,
                x + width,
                y + height,
                0xFF424242);
        Gui.drawRect(x, y, x + 1, y + height, 0xFF424242);
        Gui.drawRect(
                x + width - 1,
                y,
                x + width,
                y + height,
                0xFF424242);

        int labelAreaWidth = getLabelAreaWidth();
        String renderLabel = getRenderLabel(labelAreaWidth);
        font.drawStringWithShadow(
                renderLabel,
                x + 2,
                y + Math.max(0, (height - 8) / 2),
                0xFFFFFFFF);

        int previewSize = Math.max(12, height - 4);
        int previewX = x + labelAreaWidth + INNER_GAP;
        int previewY = y + 2;
        Gui.drawRect(
                previewX,
                previewY,
                previewX + previewSize,
                previewY + previewSize,
                0xFF000000 | previewColor);
        Gui.drawRect(
                previewX,
                previewY,
                previewX + previewSize,
                previewY + 1,
                0xFFFFFFFF);
        Gui.drawRect(
                previewX,
                previewY + previewSize - 1,
                previewX + previewSize,
                previewY + previewSize,
                0xFFFFFFFF);

        textField.drawTextBox();
        if (!isValidHex(normalize(textField.getText()))) {
            font.drawStringWithShadow(
                    "!",
                    x + width - 10,
                    y + height / 2 - 4,
                    0xFFFF6666);
        }
    }

    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        textField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    public boolean contains(int mouseX, int mouseY) {
        return mouseX >= x
                && mouseX < x + width
                && mouseY >= y
                && mouseY < y + height;
    }

    public boolean keyTyped(char typedChar, int keyCode) {
        if (!textField.isFocused()
                || !textField.textboxKeyTyped(typedChar, keyCode)) {
            return false;
        }
        notifyIfValid();
        return true;
    }

    public void updateCursorCounter() {
        textField.updateCursorCounter();
    }

    public boolean isFocused() {
        return textField.isFocused();
    }

    public void setFocused(boolean focused) {
        textField.setFocused(focused);
    }

    private void notifyIfValid() {
        String candidate = normalize(textField.getText());
        if (!isValidHex(candidate)) {
            return;
        }
        String normalized = candidate.toUpperCase(Locale.ROOT);
        if (normalized.equals(lastNotifiedColor)) {
            return;
        }
        lastNotifiedColor = normalized;
        if (onColorChanged != null) {
            onColorChanged.accept(normalized);
        }
    }

    private void rebuildTextField(String value, boolean focused) {
        int previewSize = Math.max(12, height - 4);
        int labelAreaWidth = getLabelAreaWidth();
        int inputX = x + labelAreaWidth
                + INNER_GAP + previewSize + INNER_GAP;
        int maxInputWidth = Math.max(
                56,
                width - (inputX - x) - INNER_GAP);
        int inputWidth = Math.min(INPUT_WIDTH, maxInputWidth);
        int inputHeight = Math.max(16, height - 2);
        int inputY = y + Math.max(0, (height - 18) / 2);
        textField = new GuiTextField(
                fieldId,
                font,
                inputX,
                inputY,
                inputWidth,
                inputHeight);
        textField.setMaxStringLength(7);
        textField.setText(value == null ? "" : value);
        textField.setFocused(focused);
    }

    private int getLabelAreaWidth() {
        int previewSize = Math.max(12, height - 4);
        return Math.max(
                30,
                width - (INPUT_WIDTH + previewSize + INNER_GAP * 3));
    }

    private String getRenderLabel(int labelAreaWidth) {
        if (label.isEmpty()) {
            return "";
        }
        if (font.getStringWidth(label) <= labelAreaWidth) {
            return label;
        }
        int trimmedWidth = Math.max(
                10,
                labelAreaWidth - font.getStringWidth("\u2026"));
        return font.trimStringToWidth(label, trimmedWidth) + "\u2026";
    }

    private static boolean isValidHex(String value) {
        return HEX_COLOR.matcher(value).matches();
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.startsWith("#")) {
            normalized = "#" + normalized;
        }
        return normalized;
    }

    private static String sanitizeColor(String value) {
        String normalized = normalize(value);
        return isValidHex(normalized)
                ? normalized.toUpperCase(Locale.ROOT)
                : "#FF0000";
    }

    private static int parseColor(String hex) {
        try {
            return Integer.parseInt(hex.substring(1), 16);
        } catch (RuntimeException ignored) {
            return 0xFF0000;
        }
    }
}
