package com.example.mowziestrackscompat;

import com.example.mowziestrackscompat.sound.ModSounds;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(MowziesTracksCompat.MODID)
public class MowziesTracksCompat {
    public static final String MODID = "mowziestrackscompat";
    private static final Logger LOGGER = LogUtils.getLogger();

    public MowziesTracksCompat() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModSounds.SOUNDS.register(bus);
        LOGGER.info("[{}] Loaded. Boss music will replace Mowzie themes when bosses are nearby.", MODID);
    }
}
