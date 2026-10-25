package com.emergentcity;

import com.emergentcity.city.CityCommand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/** Emergent historic city generator: villagers grow a town with /emergentcity generate <radius> [seed]. Server-side logic only. */
@Mod(EmergentCityMod.MOD_ID)
public final class EmergentCityMod {
    public static final String MOD_ID = "emergentcity";

    public EmergentCityMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CityCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        CityCommand.shutdown();
    }

    /** Lets an evolving town with the 'maintain' option repair house blocks players break. */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;
        var p = event.getPlayer();
        CityCommand.onBlockBroken(event.getLevel(), event.getPos(), p != null && p.isShiftKeyDown());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) CityCommand.tick(server);
    }
}
