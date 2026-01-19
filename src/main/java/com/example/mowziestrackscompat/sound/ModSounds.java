package com.example.mowziestrackscompat.sound;

import com.example.mowziestrackscompat.MowziesTracksCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MowziesTracksCompat.MODID);

    public static final RegistryObject<SoundEvent> FERROUS_WROUGHTNAUT_BOSS =
            SOUNDS.register("ferrous_wroughtnaut_boss",
                    () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(MowziesTracksCompat.MODID, "ferrous_wroughtnaut_boss")));

    public static final RegistryObject<SoundEvent> FROSTMAW_BOSS =
            SOUNDS.register("frostmaw_boss",
                    () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(MowziesTracksCompat.MODID, "frostmaw_boss")));

    public static final RegistryObject<SoundEvent> UMVUTHI_BOSS =
            SOUNDS.register("umvuthi_boss",
                    () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(MowziesTracksCompat.MODID, "umvuthi_boss")));
}
