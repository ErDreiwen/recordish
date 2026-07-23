package dev.recordable.mixin;

import dev.recordable.OpenALLoopbackCapture;
import dev.recordable.ReplayCompatBridge;
import org.lwjgl.LWJGLException;
import org.lwjgl.openal.AL;
import paulscode.sound.libraries.LibraryLWJGLOpenAL;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Installs the LWJGL 2 OpenAL Soft loopback before Paulscode creates any
 * sources. LaunchWrapper excludes {@code org.lwjgl.*} from transformation,
 * making Paulscode the earliest safe 1.8.9 hook.
 */
@Mixin(value = LibraryLWJGLOpenAL.class, remap = false)
public abstract class LibraryLWJGLOpenALMixin {
    @Redirect(
        method = "init()V",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/openal/AL;create()V",
            remap = false
        ),
        remap = false
    )
    private void recordable$createLoopbackOpenAl()
            throws LWJGLException {
        AL.create();
        if (ReplayCompatBridge.shouldYieldAudioDevice()) {
            return;
        }
        OpenALLoopbackCapture.getInstance()
            .replaceCurrentDevice();
    }

    @Redirect(
        method = "cleanup()V",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/openal/AL;destroy()V",
            remap = false
        ),
        remap = false
    )
    private void recordable$destroyLoopbackOpenAl() {
        OpenALLoopbackCapture.getInstance()
            .beforeDeviceClose();
        AL.destroy();
    }
}
