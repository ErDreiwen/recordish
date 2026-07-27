package dev.recordish.screen;

import net.minecraft.client.gui.GuiScreen;

/**
 * Public settings entry point backed by the V1-0.09 parity composition.
 */
public final class RecordishSettingsScreen
        extends RecordishSettingsScreenV109 {
    public RecordishSettingsScreen() {
        this(null);
    }

    public RecordishSettingsScreen(GuiScreen parent) {
        super(parent);
    }
}
