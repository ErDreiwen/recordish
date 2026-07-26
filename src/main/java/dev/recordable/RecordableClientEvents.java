package dev.recordable;

import dev.recordable.screen.FfmpegWelcomeScreen;
import dev.recordable.screen.RecordableHomeButton;
import dev.recordable.screen.VideoCollectionScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AchievementEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Constructor;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Keybindings and Forge event bridges for the fixed 1.8.9 client target.
 */
public final class RecordableClientEvents {
    private static final String CATEGORY =
            "key.categories.recordable.main";
    private static final int HOME_BUTTON_ID = 0x524543;
    private static final int[] FKEY_PRIORITY_ORDER = {
        Keyboard.KEY_F6,
        Keyboard.KEY_F7,
        Keyboard.KEY_F8,
        Keyboard.KEY_F9,
        Keyboard.KEY_F10,
        Keyboard.KEY_F11,
        Keyboard.KEY_F12,
        Keyboard.KEY_F4
    };
    private static final RecordableClientEvents INSTANCE =
            new RecordableClientEvents();

    private final AutoRecordManager autoRecordManager =
            new AutoRecordManager();

    private KeyBinding toggleRecording;
    private KeyBinding pauseResume;
    private KeyBinding openSettings;
    private KeyBinding openVideos;
    private KeyBinding addBookmark;
    private KeyBinding pushToTalk;
    private KeyBinding saveReplay;
    private KeyBinding toggleCensor;
    private KeyBinding openCensorEditor;
    private boolean missingFfmpegNoticeShown;
    private boolean ffmpegWelcomeCheckStarted;
    private RecordableHomeButton homeButton;
    private GuiScreen initializedMainMenu;

    private RecordableClientEvents() {
    }

    public static void register() {
        INSTANCE.registerKeybindings();
        AutoClipManager.getInstance().initialize();
        FMLCommonHandler.instance().bus().register(INSTANCE);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    private void registerKeybindings() {
        RecordableConfig config = RecordableConfig.get();
        Set<Integer> claimedKeys = collectClaimedKeyBindings();
        toggleRecording = register(
                "key.recordable.toggle_recording",
                resolveHotkeyDefault(
                    config.hotkeyToggleRecording,
                    RecordableConfig.DEFAULT_HOTKEY_TOGGLE_RECORDING,
                    claimedKeys));
        pauseResume = register(
                "key.recordable.pause_resume",
                resolveHotkeyDefault(
                    config.hotkeyPauseResume,
                    RecordableConfig.DEFAULT_HOTKEY_PAUSE_RESUME,
                    claimedKeys));
        openSettings = register(
                "key.recordable.open_settings",
                resolveHotkeyDefault(
                    config.hotkeyOpenSettings,
                    RecordableConfig.DEFAULT_HOTKEY_OPEN_SETTINGS,
                    claimedKeys));
        openVideos = register(
                "key.recordable.open_video_collection",
                resolveHotkeyDefault(
                    config.hotkeyOpenVideoCollection,
                    RecordableConfig.DEFAULT_HOTKEY_OPEN_VIDEO_COLLECTION,
                    claimedKeys));
        addBookmark = register(
                "key.recordable.add_bookmark",
                config.hotkeyAddBookmark);
        pushToTalk = register(
                "key.recordable.push_to_talk",
                resolveHotkeyDefault(
                    config.hotkeyPushToTalk,
                    RecordableConfig.DEFAULT_HOTKEY_PUSH_TO_TALK,
                    claimedKeys));
        saveReplay = register(
                "key.recordable.save_replay_buffer",
                config.hotkeySaveReplayBuffer);
        toggleCensor = register(
                "key.recordable.toggle_censor_overlay",
                config.hotkeyToggleCensorOverlay);
        openCensorEditor = register(
                "key.recordable.open_censor_editor",
                config.hotkeyOpenCensorEditor);
    }

    private static KeyBinding register(String description, int keyCode) {
        KeyBinding binding = new KeyBinding(
                description,
                keyCode,
                CATEGORY);
        ClientRegistry.registerKeyBinding(binding);
        return binding;
    }

    private static Set<Integer> collectClaimedKeyBindings() {
        Set<Integer> claimed = new LinkedHashSet<Integer>();
        claimed.add(Integer.valueOf(Keyboard.KEY_F1));
        claimed.add(Integer.valueOf(Keyboard.KEY_F2));
        claimed.add(Integer.valueOf(Keyboard.KEY_F3));
        claimed.add(Integer.valueOf(Keyboard.KEY_F5));
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft != null
                    && minecraft.gameSettings != null
                    && minecraft.gameSettings.keyBindings != null) {
                for (KeyBinding binding
                        : minecraft.gameSettings.keyBindings) {
                    int code = binding.getKeyCode();
                    if (code != Keyboard.KEY_NONE) {
                        claimed.add(Integer.valueOf(code));
                    }
                }
            }
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug(
                "Could not inspect existing key bindings.",
                throwable);
        }
        return claimed;
    }

    private static int resolveHotkeyDefault(
            int configured,
            int compileDefault,
            Set<Integer> claimed) {
        if (configured == Keyboard.KEY_NONE
                && compileDefault == Keyboard.KEY_NONE) {
            return Keyboard.KEY_NONE;
        }
        if (configured != compileDefault) {
            if (configured != Keyboard.KEY_NONE) {
                claimed.add(Integer.valueOf(configured));
            }
            return configured;
        }
        if (compileDefault != Keyboard.KEY_NONE
                && !claimed.contains(Integer.valueOf(compileDefault))) {
            claimed.add(Integer.valueOf(compileDefault));
            return compileDefault;
        }
        for (int candidate : FKEY_PRIORITY_ORDER) {
            if (!claimed.contains(Integer.valueOf(candidate))) {
                claimed.add(Integer.valueOf(candidate));
                return candidate;
            }
        }
        return Keyboard.KEY_NONE;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft minecraft = Minecraft.getMinecraft();
        ensureInitialMainMenuEvents(minecraft);
        RecordingManager manager = RecordingManager.getInstance();
        while (toggleRecording.isPressed()) {
            if (!manager.isRecording()) {
                ModCompatibilityChecker.warnPlayerIfConflicts();
            }
            manager.toggleRecording();
        }
        while (pauseResume.isPressed()) {
            manager.togglePause();
        }
        while (openSettings.isPressed()) {
            openScreen(
                    "dev.recordable.screen.RecordableSettingsScreen",
                    minecraft.currentScreen);
        }
        while (openVideos.isPressed()) {
            if (!openScreen(
                    "dev.recordable.screen.VideoCollectionScreen",
                    minecraft.currentScreen)) {
                PlatformUtils.open(
                        RecordableConfig.get().getOutputDirectory());
            }
        }
        while (addBookmark.isPressed()) {
            manager.addBookmark();
        }
        while (saveReplay.isPressed()) {
            if (!invokeReplaySave()) {
                RecordableMessages.send(
                        ChatCategory.REPLAY_BUFFER,
                        "Replay buffer is not active.");
            }
        }
        while (toggleCensor.isPressed()) {
            RecordableConfig config = RecordableConfig.get();
            config.censorOverlayHidden =
                    !config.censorOverlayHidden;
            config.save();
            RecordableMessages.send(
                    ChatCategory.GENERAL,
                    config.censorOverlayHidden
                            ? "Censor overlay hidden."
                            : "Censor overlay shown.");
        }
        while (openCensorEditor.isPressed()) {
            openScreen(
                    "dev.recordable.screen.CensorOverlayEditorScreen",
                    minecraft.currentScreen);
        }

        boolean pttHeld = pushToTalk != null
                && GameSettings.isKeyDown(pushToTalk);
        manager.setPushToTalkActive(pttHeld);
        autoRecordManager.onClientTick();
        ReplayCompatBridge.onClientTick(minecraft);
        AutoClipManager.getInstance().onClientTick();
        PerformanceMetrics.getInstance().onClientTick();
        PerformanceOptimizer.getInstance().onClientTick();

        if (!missingFfmpegNoticeShown
                && RecordingManager.isInGameState(minecraft)) {
            FfmpegBundleManager.Status status =
                    FfmpegBundleManager.getStatus();
            if (status == FfmpegBundleManager.Status.CHECKING
                    || status == FfmpegBundleManager.Status.DOWNLOADING) {
                return;
            }
            missingFfmpegNoticeShown = true;
            if (status != FfmpegBundleManager.Status.AVAILABLE) {
                RecordableMessages.send(
                        ChatCategory.WARNINGS,
                        "FFmpeg is missing. Press "
                                + Keyboard.getKeyName(
                                        openSettings.getKeyCode())
                                + " to open Record-able settings.");
            }
        }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type == RenderGameOverlayEvent.ElementType.ALL) {
            LiveEffectsRenderer.renderHud(event.resolution);
            RecordingOverlay.render(event.resolution);
        }
    }

    @SubscribeEvent
    public void onDrawScreenPost(
            GuiScreenEvent.DrawScreenEvent.Post event) {
        LiveEffectsRenderer.renderScreen(
                new net.minecraft.client.gui.ScaledResolution(
                        Minecraft.getMinecraft()));
        if (event.gui instanceof GuiMainMenu && homeButton != null) {
            homeButton.drawTooltip(
                    Minecraft.getMinecraft(),
                    event.mouseX,
                    event.mouseY);
        }
    }

    @SubscribeEvent
    public void onConnected(
            FMLNetworkEvent.ClientConnectedToServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                autoRecordManager.onConnected();
            }
        });
    }

    @SubscribeEvent
    public void onDisconnected(
            FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                AutoClipManager.getInstance().reset();
                ReplayCompatBridge.resetPlaybackState();
                PerformanceMetrics.getInstance().reset();
                PerformanceOptimizer.getInstance().reset();
                autoRecordManager.onDisconnected();
            }
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerAttack(AttackEntityEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (event.entityPlayer == minecraft.thePlayer) {
            AutoClipManager.getInstance()
                    .onPlayerAttackEntity(event.target);
        }
    }

    @SubscribeEvent
    public void onAchievement(AchievementEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (event.entityPlayer != minecraft.thePlayer
                || event.achievement == null) {
            return;
        }
        AutoClipManager.getInstance().onAchievementEarned(
                event.achievement.getStatName().getUnformattedText());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingAttack(LivingAttackEvent event) {
        if (event.ammount <= 0.0F) {
            return;
        }
        AutoClipManager.getInstance().onLivingAttackCandidate(
                event.entityLiving,
                event.source,
                event.ammount);
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null
                || event.source == null
                || event.source.getEntity() != minecraft.thePlayer
                || (!(event.entityLiving instanceof EntityDragon)
                    && !(event.entityLiving instanceof EntityWither))) {
            return;
        }
        AutoClipManager.getInstance().onBossKilled(
                event.entityLiving.getName());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onMainMenuInitialized(
            GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.gui instanceof GuiMainMenu)) {
            return;
        }

        initializedMainMenu = event.gui;
        homeButton = null;
        RecordableConfig config = RecordableConfig.get();
        if (config.showHomeButton) {
            int x = event.gui.width / 2 + 104;
            int y = event.gui.height / 4 + 72;
            homeButton = new RecordableHomeButton(
                    HOME_BUTTON_ID,
                    x,
                    y);
            event.buttonList.add(homeButton);
        }
        scheduleFfmpegWelcome(event.gui);
    }

    /**
     * Forge can construct and initialize the first title screen before client
     * mods receive FMLInitializationEvent. Reinitializing that same screen
     * once makes its normal Forge GUI events observable without replacing a
     * custom GuiMainMenu subclass installed by another mod.
     */
    private void ensureInitialMainMenuEvents(Minecraft minecraft) {
        GuiScreen current = minecraft == null
                ? null
                : minecraft.currentScreen;
        if (!(current instanceof GuiMainMenu)
                || current == initializedMainMenu
                || current.width <= 0
                || current.height <= 0) {
            return;
        }
        initializedMainMenu = current;
        current.setWorldAndResolution(
                minecraft,
                current.width,
                current.height);
    }

    @SubscribeEvent
    public void onGuiAction(
            GuiScreenEvent.ActionPerformedEvent.Post event) {
        if (event.gui instanceof GuiMainMenu
                && event.button != null
                && event.button == homeButton
                && event.button.id == HOME_BUTTON_ID) {
            Minecraft.getMinecraft().displayGuiScreen(
                    new VideoCollectionScreen(event.gui));
        }
    }

    private void scheduleFfmpegWelcome(final GuiScreen parent) {
        final RecordableConfig config = RecordableConfig.get();
        if (config.ffmpegFirstRunShown || ffmpegWelcomeCheckStarted) {
            return;
        }

        ffmpegWelcomeCheckStarted = true;
        CompletableFuture
                .supplyAsync(FfmpegBundleManager::detectFfmpeg)
                .whenComplete((status, failure) -> {
                    Minecraft minecraft = Minecraft.getMinecraft();
                    if (minecraft == null) {
                        ffmpegWelcomeCheckStarted = false;
                        return;
                    }
                    minecraft.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            if (failure == null
                                    && status != null
                                    && status.isFound()) {
                                config.ffmpegFirstRunShown = true;
                                config.save();
                                return;
                            }
                            GuiScreen current = minecraft.currentScreen;
                            if (current instanceof GuiMainMenu) {
                                minecraft.displayGuiScreen(
                                        new FfmpegWelcomeScreen(current));
                            } else {
                                ffmpegWelcomeCheckStarted = false;
                            }
                        }
                    });
                });
    }

    private static boolean openScreen(
            String className,
            GuiScreen parent) {
        try {
            Class<?> screenClass = Class.forName(className);
            if (parent != null && screenClass.isInstance(parent)) {
                return true;
            }
            Constructor<?> constructor =
                    screenClass.getConstructor(GuiScreen.class);
            Object screen = constructor.newInstance(parent);
            Minecraft.getMinecraft().displayGuiScreen(
                    (GuiScreen) screen);
            return true;
        } catch (ClassNotFoundException missing) {
            RecordableMessages.send(
                    ChatCategory.GENERAL,
                    "That Record-able screen is not available yet.");
            return false;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn(
                    "Unable to open Record-able screen {}.",
                    className,
                    throwable);
            return false;
        }
    }

    private static boolean invokeReplaySave() {
        try {
            Class<?> replayClass =
                    Class.forName("dev.recordable.ReplayBuffer");
            Object instance = replayClass
                    .getMethod("getInstance")
                    .invoke(null);
            Boolean active = (Boolean) replayClass
                    .getMethod("isActive")
                    .invoke(instance);
            if (!active.booleanValue()) return false;
            replayClass.getMethod("saveBuffer").invoke(instance);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
