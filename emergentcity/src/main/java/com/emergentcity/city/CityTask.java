package com.emergentcity.city;

import net.minecraft.server.MinecraftServer;

/** A running city generation (instant plan or villager mode). */
interface CityTask {
    /** Advances by one server tick; returns true when finished (successfully or not). */
    boolean tick(MinecraftServer server);
    String status();
    void cancel();
    UndoLog undoLog();
}
