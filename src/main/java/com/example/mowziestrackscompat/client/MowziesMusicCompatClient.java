package com.example.mowziestrackscompat.client;

import com.example.mowziestrackscompat.MowziesTracksCompat;
import com.example.mowziestrackscompat.sound.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;

/**
 * Boss music layer for Mowzie's Mobs bosses.
 *
 * Goals:
 *  - Always mute Mowzie's built-in boss themes (so you never get double-music).
 *  - When a boss is within 20 blocks, fade out any currently playing MUSIC (OverhauledMusic/vanilla/etc), then start our boss track.
 *  - While boss is active, prevent new MUSIC from starting.
 */
@Mod.EventBusSubscriber(modid = MowziesTracksCompat.MODID, value = Dist.CLIENT)
public final class MowziesMusicCompatClient {

    private static final Logger LOGGER = LogManager.getLogger(MowziesTracksCompat.MODID);

    // User requested: start music within 20 blocks of boss.
    private static final double BOSS_RADIUS_BLOCKS = 20.0;

    // How often to scan for bosses (ticks). 5 ticks = 0.25s.
    private static final int SCAN_INTERVAL_TICKS = 5;

    // Fade duration for transitions.
    private static final int FADE_TICKS = 20;

    // Boss entity IDs we care about (Mowzie's Mobs 1.7.3).
    private static final ResourceLocation BOSS_WROUGHTNAUT = new ResourceLocation("mowziesmobs", "ferrous_wroughtnaut");
    private static final ResourceLocation BOSS_UMVUTHI     = new ResourceLocation("mowziesmobs", "umvuthi");
    private static final ResourceLocation BOSS_FROSTMAW    = new ResourceLocation("mowziesmobs", "frostmaw");

    // Our music events (resource pack provides these).
    // IMPORTANT: keep these as Suppliers so we don't call RegistryObject.get() during class init.
    // Calling .get() too early will crash with ExceptionInInitializerError because registries aren't ready yet.
    private static final Map<ResourceLocation, Supplier<SoundEvent>> BOSS_TO_SOUND = new HashMap<>();

    // Mowzie's boss theme sound EVENTS (these are what we want to always mute).
    private static final Set<ResourceLocation> MOWZIE_THEME_EVENTS = Set.of(
            new ResourceLocation("mowziesmobs", "music.ferrous_wroughtnaut_theme"),
            new ResourceLocation("mowziesmobs", "music.umvuthi_theme"),
            new ResourceLocation("mowziesmobs", "music.frostmaw_theme"),
            // Newer versions also have sculptor theme; harmless to include.
            new ResourceLocation("mowziesmobs", "music.sculptor_theme"),
            new ResourceLocation("mowziesmobs", "music.sculptor_transition")
    );

    // Some mods (or custom code) can play the raw sound file RL directly.
    private static final Set<ResourceLocation> MOWZIE_THEME_FILES = Set.of(
            new ResourceLocation("mowziesmobs", "music/ferrous_wroughtnaut"),
            new ResourceLocation("mowziesmobs", "music/umvuthi"),
            new ResourceLocation("mowziesmobs", "music/frostmaw"),
            new ResourceLocation("mowziesmobs", "music/sculptor/ferrous_wroughtnaut"),
            new ResourceLocation("mowziesmobs", "music/sculptor/transition")
    );

    // ---- State ----
    private static int scanCooldown = 0;

    /** Boss entity id that we are currently overriding for (null = none). */
    private static ResourceLocation activeBoss = null;

    /** When >0, we are fading out other music and will start boss music when it hits 0. */
    private static int pendingBossStartTicks = 0;

    private static BossMusicSound currentBossSound = null;

    // --- Global MUSIC ducking (smooth fade other music out while a boss track plays) ---
    private static boolean duckingMusic = false;
    private static boolean restoringMusic = false;
    private static boolean keepMusicMuted = false;
    private static int musicFadeAge = 0;
    private static float originalMusicVol = -1.0f;

    private MowziesMusicCompatClient() {}

    static {
        // Map bosses -> our sounds (registered in ModSounds).
        BOSS_TO_SOUND.put(BOSS_WROUGHTNAUT, ModSounds.FERROUS_WROUGHTNAUT_BOSS);
        BOSS_TO_SOUND.put(BOSS_UMVUTHI, ModSounds.UMVUTHI_BOSS);
        BOSS_TO_SOUND.put(BOSS_FROSTMAW, ModSounds.FROSTMAW_BOSS);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            // Safety: if we muted music and the client is leaving a world, restore volumes.
            if (keepMusicMuted || duckingMusic || restoringMusic) {
                float v = (originalMusicVol >= 0.0f) ? originalMusicVol : 1.0f;
                setMusicVolumeSafe(mc, v);
                keepMusicMuted = false;
                duckingMusic = false;
                restoringMusic = false;
                musicFadeAge = 0;
                originalMusicVol = -1.0f;
            }
            return;
        }

        // If we're globally ducking/restoring the MUSIC category, do that every tick.
        tickMusicDucking();

        // Always enforce: Mowzie boss themes are muted.
        stopMowzieThemes(mc);

        // If we're waiting to start boss music after fading others, count down.
        if (activeBoss != null && pendingBossStartTicks > 0) {
            pendingBossStartTicks--;
            if (pendingBossStartTicks == 0) {
                // Start our boss track now that the global music has faded down.
                startBossMusic(mc, activeBoss);
            }
        }

        // Scan for bosses periodically.
        if (scanCooldown > 0) {
            scanCooldown--;
            return;
        }
        scanCooldown = SCAN_INTERVAL_TICKS;

        ResourceLocation nearby = findNearbyBoss(mc, BOSS_RADIUS_BLOCKS);

        if (nearby == null) {
            if (activeBoss != null) {
                LOGGER.info("Boss left range; ending override.");
                endBossOverride(mc);
            }
            return;
        }

        // Boss in range.
        if (!nearby.equals(activeBoss)) {
            LOGGER.info("Boss in range: {} (starting override)", nearby);
            beginBossOverride(mc, nearby);
        }
    }

    /**
     * Cancels Mowzie boss themes (always) and cancels any other music (only while boss override is active).
     */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        if (event.getSound() == null) return;

        ResourceLocation loc = event.getSound().getLocation();
        if (loc == null) return;

        // Always mute Mowzie boss themes so we never get double music.
        if (MOWZIE_THEME_EVENTS.contains(loc) || MOWZIE_THEME_FILES.contains(loc)) {
            event.setSound(null);
            return;
        }

        // Only block other music while boss override is active (or pending start).
        if (activeBoss != null) {
            // Only touch MUSIC, otherwise we break normal SFX.
            if (event.getSound().getSource() == SoundSource.MUSIC) {
                // Let our own boss track play.
                if (!MowziesTracksCompat.MODID.equals(loc.getNamespace())) {
                    // Also block OverhauledMusic and vanilla music.
                    event.setSound(null);
                }
            }
        }
    }

    private static void beginBossOverride(Minecraft mc, ResourceLocation bossId) {
        // Stop any previous boss music immediately.
        stopBossMusic();

        activeBoss = bossId;

        // Tell OverhauledMusic (if present) to fade out its current track.
        fadeOutOverhauledMusic(FADE_TICKS);

        // Smoothly duck the global MUSIC channel so vanilla/other mods don't overlap.
        startDuckingMusic(mc);

        // Always wait for our fade to finish before starting the boss track.
        pendingBossStartTicks = FADE_TICKS;

        // Safety: make sure Mowzie themes are killed right now.
        stopMowzieThemes(mc);
    }

    private static void endBossOverride(Minecraft mc) {
        activeBoss = null;
        pendingBossStartTicks = 0;
        stopBossMusic();

        // Restore global MUSIC volume smoothly.
        startRestoringMusic(mc);

        // Do NOT force-start any music here. OverhauledMusic/vanilla will resume naturally.
    }

    private static void startBossMusic(Minecraft mc, ResourceLocation bossId) {
        Supplier<SoundEvent> supplier = BOSS_TO_SOUND.get(bossId);
        if (supplier == null) {
            LOGGER.warn("No boss track mapped for boss id {}. (Only ferrous_wroughtnaut, umvuthi, frostmaw are supported.)", bossId);
            return;
        }

        SoundEvent event;
        try {
            event = supplier.get();
        } catch (Throwable t) {
            LOGGER.error("Failed to resolve SoundEvent for boss id {} (registry not ready?)", bossId, t);
            return;
        }

        currentBossSound = new BossMusicSound(event);
        mc.getSoundManager().play(currentBossSound);
    }

    private static void stopBossMusic() {
        if (currentBossSound == null) return;

        try {
            // Fade out smoothly.
            currentBossSound.beginFadeOut();
        } catch (Throwable t) {
            // Fallback.
            try { currentBossSound.stopNow(); } catch (Throwable ignored) {}
        }
        currentBossSound = null;
    }

    private static void startDuckingMusic(Minecraft mc) {
        // Save the player's current MUSIC volume once, then fade it down to 0.
        if (originalMusicVol < 0.0f) {
            originalMusicVol = getMusicVolumeSafe(mc);
        }
        duckingMusic = true;
        restoringMusic = false;
        keepMusicMuted = false;
        musicFadeAge = 0;
    }

    private static void startRestoringMusic(Minecraft mc) {
        if (originalMusicVol < 0.0f) {
            // Nothing to restore.
            duckingMusic = false;
            restoringMusic = false;
            keepMusicMuted = false;
            musicFadeAge = 0;
            return;
        }
        duckingMusic = false;
        restoringMusic = true;
        keepMusicMuted = false;
        musicFadeAge = 0;
    }

    private static void tickMusicDucking(Minecraft mc) {
        if (duckingMusic) {
            musicFadeAge++;
            float t = Math.min(1.0f, musicFadeAge / (float) FADE_TICKS);
            float vol = lerp(originalMusicVol, 0.0f, t);
            setMusicVolumeSafe(mc, vol);
            if (musicFadeAge >= FADE_TICKS) {
                duckingMusic = false;
                keepMusicMuted = true;
                setMusicVolumeSafe(mc, 0.0f);
            }
            return;
        }

        if (keepMusicMuted) {
            // Keep other music effectively paused/silenced while boss is active.
            setMusicVolumeSafe(mc, 0.0f);
            return;
        }

        if (restoringMusic) {
            musicFadeAge++;
            float t = Math.min(1.0f, musicFadeAge / (float) FADE_TICKS);
            float vol = lerp(0.0f, originalMusicVol, t);
            setMusicVolumeSafe(mc, vol);
            if (musicFadeAge >= FADE_TICKS) {
                restoringMusic = false;
                originalMusicVol = -1.0f;
            }
        }
    }

    private static ResourceLocation findNearbyBoss(Minecraft mc, double radius) {
        Vec3 pos = mc.player.position();
        AABB box = new AABB(pos.x - radius, pos.y - radius, pos.z - radius, pos.x + radius, pos.y + radius, pos.z + radius);

        // Quick scan for any of our target entity IDs.
        List<Entity> list = mc.level.getEntities(mc.player, box);
        for (Entity e : list) {
            if (e == null) continue;
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            if (id == null) continue;
            if (BOSS_TO_SOUND.containsKey(id)) {
                return id;
            }
        }
        return null;
    }

    /**
     * Fade out OverhauledMusic's currently playing tracks (if installed).
     *
     * IMPORTANT: This only fades OverhauledMusic's own tracks.
     * We still hard-stop MUSIC right before starting our boss track.
     */
    private static boolean fadeOutOverhauledMusic(int ticks) {
        try {
            Class<?> clientEvents = Class.forName("com.overhauledmusic.client.ClientEvents");
            Field directorField = clientEvents.getDeclaredField("DIRECTOR");
            directorField.setAccessible(true);
            Object director = directorField.get(null);
            if (director == null) return false;

            Field instancesField = director.getClass().getDeclaredField("instances");
            instancesField.setAccessible(true);
            Object instancesObj = instancesField.get(director);
            if (!(instancesObj instanceof Map)) return true;

            Map<?, ?> map = (Map<?, ?>) instancesObj;
            for (Object inst : map.values()) {
                if (inst == null) continue;

                // fadeTo(float target, int ticks)
                try {
                    Method fadeTo = inst.getClass().getMethod("fadeTo", float.class, int.class);
                    fadeTo.invoke(inst, 0.0F, ticks);
                } catch (Throwable ignored) {}

                // setActive(boolean)
                try {
                    Method setActive = inst.getClass().getMethod("setActive", boolean.class);
                    setActive.invoke(inst, false);
                } catch (Throwable ignored) {}
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Stops ALL currently playing MUSIC (prevents overlap with OverhauledMusic/vanilla/other mods). */
    private static void stopAllMusicCategory(Minecraft mc) {
        try {
            mc.getSoundManager().stop((ResourceLocation) null, SoundSource.MUSIC);
        } catch (Throwable ignored) {
            try { mc.getMusicManager().stopPlaying(); } catch (Throwable ignored2) {}
        }
    }

    /** Best-effort: stop any Mowzie boss theme sounds that might already be playing. */
    private static void stopMowzieThemes(Minecraft mc) {
        for (ResourceLocation rl : MOWZIE_THEME_EVENTS) {
            stopSoundOnCommonChannels(mc, rl);
        }
        for (ResourceLocation rl : MOWZIE_THEME_FILES) {
            stopSoundOnCommonChannels(mc, rl);
        }
    }

    private static void stopSoundOnCommonChannels(Minecraft mc, ResourceLocation rl) {
        try { mc.getSoundManager().stop(rl, SoundSource.MUSIC); } catch (Throwable ignored) {}
        try { mc.getSoundManager().stop(rl, SoundSource.RECORDS); } catch (Throwable ignored) {}
        try { mc.getSoundManager().stop(rl, SoundSource.AMBIENT); } catch (Throwable ignored) {}
    }

    private static final class BossMusicSound extends AbstractTickableSoundInstance {
        private int age = 0;
        private boolean fadingOut = false;
        private int outAge = 0;

        private BossMusicSound(SoundEvent event) {
            super(event, SoundSource.MASTER, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.0F; // fade in
            this.relative = true;
            this.x = 0;
            this.y = 0;
            this.z = 0;
        }

        @Override
        public void tick() {
            if (!fadingOut) {
                age++;
                float t = Math.min(1.0F, age / (float) FADE_TICKS);
                this.volume = t;
            } else {
                outAge++;
                float t = Math.max(0.0F, 1.0F - (outAge / (float) FADE_TICKS));
                this.volume = t;
                if (outAge >= FADE_TICKS) {
                    this.stop();
                }
            }
        }

        private void beginFadeOut() {
            this.fadingOut = true;
            this.outAge = 0;
        }

        private void stopNow() {
            this.stop();
        }
    }

    private static float lerp(float a, float b, float t) {
        float tt = Math.max(0.0f, Math.min(1.0f, t));
        return a + (b - a) * tt;
    }

    private static float getMusicVolumeSafe(Minecraft mc) {
        try {
            if (mc == null || mc.options == null) return 1.0f;
            return mc.options.getSoundSourceVolume(SoundSource.MUSIC);
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    private static void setMusicVolumeSafe(Minecraft mc, float vol) {
        if (mc == null) return;
        float v = Math.max(0.0f, Math.min(1.0f, vol));
        try {
            if (mc.getSoundManager() != null) {
                mc.getSoundManager().updateSourceVolume(SoundSource.MUSIC, v);
            }
        } catch (Throwable ignored) {
        }
    }

private static boolean isMowziesTheme(ResourceLocation loc) {
    return loc != null
            && "mowziesmobs".equals(loc.getNamespace())
            && loc.getPath() != null
            && (loc.getPath().startsWith("music/") || loc.getPath().startsWith("music."));
}



}
