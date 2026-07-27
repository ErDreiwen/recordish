package dev.recordish;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
    modid = RecordishMod.MOD_ID,
    name = RecordishMod.NAME,
    version = RecordishMod.VERSION,
    acceptedMinecraftVersions = "[1.8.9]",
    clientSideOnly = true
)
public final class RecordishMod {
    public static final String MOD_ID = "recordish";
    public static final String NAME = "Recordish";
    public static final String VERSION = BuildInfo.VERSION;
    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @Mod.Instance(MOD_ID)
    public static RecordishMod INSTANCE;

    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        if (Loader.isModLoaded("recordable")) {
            throw new IllegalStateException(
                    "Both Recordish and the old Record-able Forge port are "
                            + "installed. Remove the recordable-*.jar before "
                            + "launching Recordish.");
        }
        RecordishConfig.initialize(
            event.getModConfigurationDirectory().getParentFile(),
            event.getModConfigurationDirectory());
        LOGGER.info("Recordish Forge 1.8.9 configuration loaded from {}",
            RecordishConfig.getConfigPath());
    }

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        ModCompatibilityChecker.checkAndLog();
        RecordingManager.getInstance().initialize();
        RecordishClientEvents.register();
        LOGGER.info("Recordish Forge 1.8.9 initialized.");
        Runtime.getRuntime().addShutdownHook(new Thread(
            new Runnable() {
                @Override
                public void run() {
                    try {
                        LOGGER.info(
                                "JVM shutdown hook checking Recordish media.");
                        RecordingManager.getInstance().emergencyShutdown();
                    } catch (Throwable throwable) {
                        LOGGER.error(
                            "Emergency recording shutdown failed.",
                            throwable);
                    } finally {
                        RecordishConfig.get().save();
                    }
                }
            },
            "Recordish-ConfigShutdown"));
    }
}
