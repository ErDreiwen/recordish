package dev.recordable.mixin;

import dev.recordable.CaptureDiagnostics;
import dev.recordable.RecordableMod;
import dev.recordable.RecordingManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.achievement.GuiAchievement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures after the achievement toast but before Minecraft unbinds and blits
 * its main framebuffer. This is later than Forge's render-tick END event and
 * therefore preserves the exact V1-0.08 final-frame semantics.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(
        method = "runGameLoop",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/achievement/GuiAchievement;"
                + "updateAchievementWindow()V",
            shift = At.Shift.AFTER
        )
    )
    private void recordable$captureFinalFrame(CallbackInfo callbackInfo) {
        try {
            RecordingManager.getInstance().onRenderFrame();
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.error(
                "Unexpected failure in the final-frame recording hook.",
                throwable);
        }
        try {
            CaptureDiagnostics.onRenderFrame();
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn(
                "Capture diagnostics failed for this frame.",
                throwable);
        }
    }

    /**
     * Called as soon as the window/quit button requests exit, while the render
     * context and normal client lifecycle are still intact.
     */
    @Inject(method = "shutdown", at = @At("HEAD"))
    private void recordable$finishWhenExitRequested(
            CallbackInfo callbackInfo) {
        try {
            RecordableMod.LOGGER.info(
                    "Minecraft quit requested; finalizing Record-able media.");
            RecordingManager.getInstance().requestShutdown();
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.error(
                    "Unable to complete the early recording shutdown hook.",
                    throwable);
        }
    }

    @Inject(method = "shutdownMinecraftApplet", at = @At("HEAD"))
    private void recordable$finishBeforeShutdown(CallbackInfo callbackInfo) {
        try {
            RecordableMod.LOGGER.info(
                    "Minecraft applet shutdown reached; checking Record-able "
                            + "finalization.");
            RecordingManager.getInstance().shutdown();
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.error(
                "Unable to complete the recording shutdown hook.",
                throwable);
        }
    }
}
