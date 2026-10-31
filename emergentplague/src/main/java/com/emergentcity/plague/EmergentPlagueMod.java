package com.emergentcity.plague;

import net.minecraftforge.fml.common.Mod;

/**
 * The plague: an addon to Emergent City (which it needs). Adds the plague and forever options to /emergentcity
 * generate, instant and evolve; see the README's Plague mode.
 */
@Mod(EmergentPlagueMod.MOD_ID)
public final class EmergentPlagueMod {
    public static final String MOD_ID = "emergentplague";

    public EmergentPlagueMod() {
        PlagueAddon.register();
    }
}
