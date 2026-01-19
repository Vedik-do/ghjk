MowziesTracksCompat (Forge 1.20.1) - coexist with OverhauledMusic

Goal:
- Keep OverhauledMusic for Biome/Mob/LowHP.
- Add Mowzie boss music (Frostmaw / Ferrous Wroughtnaut / Umvuthi) without hearing TWO tracks.

How it works:
1) Always cancels Mowzie's built-in boss themes:
   - mowziesmobs:music.frostmaw_theme
   - mowziesmobs:music.ferrous_wroughtnaut_theme
   - mowziesmobs:music.umvuthi_theme
   (and Sculptor themes too)

2) When you are within 20 blocks of a Mowzie boss, this mod:
   - Stops currently playing MUSIC (including OverhauledMusic's biome/lowhp track)
   - Blocks new OverhauledMusic + vanilla MUSIC while the boss layer is active
   - Plays your custom boss music with a simple smooth fade-in/out

Boss detection radius: 20 blocks.
Fade time: 2 seconds (in and out).

Boss entity IDs (Mowzie's Mobs 1.7.3):
- mowziesmobs:frostmaw
- mowziesmobs:ferrous_wroughtnaut
- mowziesmobs:umvuthi

Your resource pack must provide these sound IDs:
- mowziestrackscompat:ferrous_wroughtnaut_boss
- mowziestrackscompat:frostmaw_boss
- mowziestrackscompat:umvuthi_boss

Install:
- Put the built JAR in /mods
- Put the resource pack zip in /resourcepacks and enable it
- You can keep OverhauledMusic enabled.


OverhauledMusic integration: when a Mowzie boss is near, the mod tries to call OverhauledMusic's internal fade system via reflection so the transition is smooth. If OverhauledMusic isn't installed, it falls back to stopping the MUSIC channel.
