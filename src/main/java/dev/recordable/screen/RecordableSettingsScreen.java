package dev.recordable.screen;

import net.minecraft.client.gui.GuiScreen;

/**
 * Public settings entry point backed by the V1-0.09 parity composition.
 */
public final class RecordableSettingsScreen
        extends RecordableSettingsScreenV109 {
    public RecordableSettingsScreen() {
        this(null);
    }

    public RecordableSettingsScreen(GuiScreen parent) {
        super(parent);
    }
}
