package dev.recordish;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tiny deterministic 5x7 font used for labels painted into RGB24 frames.
 *
 * <p>The recording worker cannot use Minecraft's renderer, and relying on an
 * installed desktop font makes the baked result vary between machines.</p>
 */
public final class CensorFont {
    public static final int GLYPH_W = 5;
    public static final int GLYPH_H = 7;
    public static final int GLYPH_GAP = 1;

    private static final Map<Character, int[]> GLYPHS =
        new HashMap<Character, int[]>();

    private CensorFont() {
    }

    private static void put(
            char character,
            int r0,
            int r1,
            int r2,
            int r3,
            int r4,
            int r5,
            int r6) {
        GLYPHS.put(Character.valueOf(character),
            new int[]{r0, r1, r2, r3, r4, r5, r6});
    }

    static {
        put(' ', 0, 0, 0, 0, 0, 0, 0);
        put('A', 0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001);
        put('B', 0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110);
        put('C', 0b01111, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b01111);
        put('D', 0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110);
        put('E', 0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111);
        put('F', 0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000);
        put('G', 0b01111, 0b10000, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111);
        put('H', 0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001);
        put('I', 0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b11111);
        put('J', 0b00111, 0b00010, 0b00010, 0b00010, 0b10010, 0b10010, 0b01100);
        put('K', 0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001);
        put('L', 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111);
        put('M', 0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001);
        put('N', 0b10001, 0b11001, 0b10101, 0b10101, 0b10011, 0b10001, 0b10001);
        put('O', 0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110);
        put('P', 0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000);
        put('Q', 0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101);
        put('R', 0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001);
        put('S', 0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110);
        put('T', 0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100);
        put('U', 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110);
        put('V', 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100);
        put('W', 0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001);
        put('X', 0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001);
        put('Y', 0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100);
        put('Z', 0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111);
        put('0', 0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110);
        put('1', 0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110);
        put('2', 0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111);
        put('3', 0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110);
        put('4', 0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010);
        put('5', 0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110);
        put('6', 0b01110, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110);
        put('7', 0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000);
        put('8', 0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110);
        put('9', 0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00001, 0b01110);
        put('.', 0, 0, 0, 0, 0, 0b01100, 0b01100);
        put(':', 0, 0b01100, 0b01100, 0, 0b01100, 0b01100, 0);
        put('-', 0, 0, 0, 0b11111, 0, 0, 0);
        put('_', 0, 0, 0, 0, 0, 0, 0b11111);
        put('!', 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0, 0b00100);
        put('?', 0b01110, 0b10001, 0b00001, 0b00110, 0b00100, 0, 0b00100);
        put('/', 0b00001, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b10000);
        put('#', 0b01010, 0b01010, 0b11111, 0b01010, 0b11111, 0b01010, 0b01010);
        put('+', 0, 0b00100, 0b00100, 0b11111, 0b00100, 0b00100, 0);
        put(',', 0, 0, 0, 0, 0b01100, 0b00100, 0b01000);
    }

    public static int textWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() * GLYPH_W
            + (text.length() - 1) * GLYPH_GAP;
    }

    public static void drawText(
            byte[] rgb,
            int frameWidth,
            int frameHeight,
            int startX,
            int startY,
            String text,
            int rgbColor,
            int scale) {
        if (rgb == null || text == null || text.isEmpty() || scale < 1) {
            return;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        byte red = (byte) ((rgbColor >> 16) & 0xFF);
        byte green = (byte) ((rgbColor >> 8) & 0xFF);
        byte blue = (byte) (rgbColor & 0xFF);
        int rowStride = frameWidth * 3;
        int penX = startX;
        for (int index = 0; index < upper.length(); index++) {
            int[] glyph = GLYPHS.get(
                Character.valueOf(upper.charAt(index)));
            if (glyph != null) {
                for (int glyphY = 0; glyphY < GLYPH_H; glyphY++) {
                    int bits = glyph[glyphY];
                    for (int glyphX = 0; glyphX < GLYPH_W; glyphX++) {
                        if (((bits >> (GLYPH_W - 1 - glyphX)) & 1) == 0) {
                            continue;
                        }
                        int blockX = penX + glyphX * scale;
                        int blockY = startY + glyphY * scale;
                        for (int scaleY = 0; scaleY < scale; scaleY++) {
                            int frameY = blockY + scaleY;
                            if (frameY < 0 || frameY >= frameHeight) {
                                continue;
                            }
                            int rowBase = frameY * rowStride;
                            for (int scaleX = 0; scaleX < scale; scaleX++) {
                                int frameX = blockX + scaleX;
                                if (frameX < 0 || frameX >= frameWidth) {
                                    continue;
                                }
                                int pixel = rowBase + frameX * 3;
                                rgb[pixel] = red;
                                rgb[pixel + 1] = green;
                                rgb[pixel + 2] = blue;
                            }
                        }
                    }
                }
            }
            penX += (GLYPH_W + GLYPH_GAP) * scale;
        }
    }
}
