package dev.recordable;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
    modid = RecordableMod.MOD_ID,
    name = RecordableMod.NAME,
    version = RecordableMod.VERSION,
    acceptedMinecraftVersions = "[1.8.9]",
    clientSideOnly = true
)
public final class RecordableMod {
    public static final String MOD_ID = "recordable";
    public static final String NAME = "Record-able";
    public static final String VERSION = "1.0.0-forge-1.8.9";
    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @Mod.Instance(MOD_ID)
    public static RecordableMod INSTANCE;

    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        RecordableConfig.initialize(
            event.getModConfigurationDirectory().getParentFile(),
            event.getModConfigurationDirectory());
        LOGGER.info("Record-able Forge 1.8.9 configuration loaded from {}",
            RecordableConfig.getConfigPath());
    }

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        ModCompatibilityChecker.checkAndLog();
        RecordingManager.getInstance().initialize();
        RecordableClientEvents.register();
        LOGGER.info("Record-able Forge 1.8.9 initialized.");
        Runtime.getRuntime().addShutdownHook(new Thread(
            new Runnable() {
                @Override
                public void run() {
                    try {
                        RecordingManager.getInstance().shutdown();
                    } catch (Throwable throwable) {
                        LOGGER.error(
                            "Emergency recording shutdown failed.",
                            throwable);
                    } finally {
                        RecordableConfig.get().save();
                    }
                }
            },
            "Recordable-ConfigShutdown"));
    }
}
