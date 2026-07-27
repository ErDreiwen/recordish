package dev.recordish.theme;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

/**
 * Character-by-character title animation used by the theme configuration UI.
 */
public final class TypewriterText {
    private final String fullText;
    private final long startTimeMillis;
    private final int charactersPerSecond;
    private boolean completed;
    private boolean forceCompleted;

    public TypewriterText(String text, int charactersPerSecond) {
        fullText = text == null ? "" : text;
        startTimeMillis = System.currentTimeMillis();
        this.charactersPerSecond =
                Math.max(1, charactersPerSecond);
    }

    /**
     * Draws the currently revealed text and returns its visible character count.
     */
    public int render(
            FontRenderer font, int x, int y, int color) {
        long elapsed =
                System.currentTimeMillis() - startTimeMillis;
        int visibleCharacters = forceCompleted
                ? fullText.length()
                : (int) (elapsed
                        * charactersPerSecond / 1000.0D);
        visibleCharacters = Math.min(
                Math.max(0, visibleCharacters),
                fullText.length());
        if (visibleCharacters >= fullText.length()) {
            completed = true;
        }
        String visible =
                fullText.substring(0, visibleCharacters);
        font.drawStringWithShadow(visible, x, y, color);

        if (!completed
                || (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int cursorX = x + font.getStringWidth(visible);
            Gui.drawRect(
                    cursorX,
                    y,
                    cursorX + 1,
                    y + 9,
                    ThemeEngine.get().colors().accent);
        }
        if (ThemeEngine.get().preset() == ThemePreset.VHS
                && !completed
                && elapsed % 400L < 30L) {
            Gui.drawRect(
                    x,
                    y - 1,
                    x + font.getStringWidth(visible) + 2,
                    y + 10,
                    0x30000000);
        }
        return visibleCharacters;
    }

    public boolean isCompleted() {
        return completed;
    }

    public String getFullText() {
        return fullText;
    }

    public void complete() {
        forceCompleted = true;
        completed = true;
    }

    public static void renderFlickerText(
            FontRenderer font,
            String text,
            int x,
            int y,
            int baseColor) {
        float pulse = ThemeEngine.pulse(
                System.currentTimeMillis(), 3000);
        int alpha = (int) (200.0F + 55.0F * pulse);
        int color =
                alpha << 24 | baseColor & 0x00FFFFFF;
        font.drawStringWithShadow(
                text == null ? "" : text, x, y, color);
    }
}
