package dev.icewall;

import dev.icewall.command.IceWallCommands;
import dev.icewall.wall.IceWallAdvancer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class IceWallMod implements ModInitializer {
    public static final String MOD_ID = "icewall";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private final IceWallAdvancer iceWallAdvancer = new IceWallAdvancer();

    @Override
    public void onInitialize() {
        iceWallAdvancer.register();
        IceWallCommands.register(iceWallAdvancer);
        LOGGER.info("Ice Wall initialized");
    }
}