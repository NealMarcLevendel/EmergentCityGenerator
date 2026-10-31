package com.emergentcity.plague;

import com.emergentcity.city.CityAddon;
import com.emergentcity.city.CityCommand;
import com.emergentcity.city.Config;
import com.emergentcity.city.LiveCityJob;

/**
 * The plague mod's options for /emergentcity generate, instant and evolve, and its settings:
 *   plague     a town that goes on, with a plague now and then (implies evolve, catacombs unless catacombs=off, cemeteries)
 *   noplague   (evolve) the town goes on without plagues
 *   catacombs  (evolve, with plague) the quarrymen dig catacombs first, for a town that has none
 */
public final class PlagueAddon implements CityAddon {
    private static boolean registered;

    /** Registers the options and settings (once). */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        String S = "Plague mode (the plague mod)";
        Config.define(S, "plague.firstAfter", 4, "Reviews of ordinary life before the first plague");
        Config.define(S, "plague.intervalMin", 18, "Reviews between one plague and the next, at least");
        Config.define(S, "plague.intervalMax", 40, "Reviews between one plague and the next, at most");
        Config.define(S, "plague.minShare", 0.06, "The share of the houses a plague strikes, at least (most plagues are mild)");
        Config.define(S, "plague.maxShare", 0.45, "The share of the houses a great plague strikes, at most");
        Config.define(S, "plague.spreadReviews", 8, "Reviews a plague takes to run its course");
        Config.define(S, "plague.cycleSeconds", 30, "Seconds between reviews while a plague lasts");
        Config.define(S, "plague.masons", 6, "Masons left to do the work while a plague lasts");
        Config.define(S, "plague.gravesPerReview", 60, "Graves squeezed into each cemetery per review while a plague lasts, until it is full");
        Config.define(S, "plague.shutReviews", 2, "Reviews a struck house stays shut up (household inside, red cross on the door) before it is empty");
        Config.define(S, "plague.decayReviews", 6, "Reviews an empty house stands before it decays (roof in), unless it is taken again");
        Config.define(S, "plague.collapseReviews", 8, "Reviews a decayed house stands before it falls in");
        Config.define(S, "plague.wearReviews", 16, "Reviews a ruin stands before it is worn down to stumps and grassed over");
        Config.define(S, "plague.standingShare", 0.25, "Share of fallen houses left as tall shells (the rest fall to stumps of wall)");
        Config.define(S, "plague.rebuildPerReview", 3, "Between plagues: empty houses taken again or ruins rebuilt per review (the middle of town first)");
        Config.define(S, "plague.lostShare", 0.5, "Share of the houses a plague empties that are not taken again or rebuilt soon after (they decay; the town rebuilds them only slowly)");
        Config.define(S, "plague.maxRise", 4, "Levels a street junction may rise over the centuries, at most");
        Config.define(S, "plague.antiquities", 4, "Grand ruins near the monuments kept as they are, at most");
        Config.define(S, "plague.monumentShare", 0.25, "A plague striking at least this share of the houses may leave a church in ruins");
        Config.define(S, "plague.ruinedMonumentsMax", 1, "Monuments in ruins at a time, at most (no new ones are founded while one is)");
        Config.define(S, "plague.restoreMonumentAfter", 12, "Reviews after the plague before a ruined monument is restored");
        Config.define(S, "plague.ruinBones", 0.05, "Share of a fallen house's floor with rubble and the odd bone");
        Config.define(S, "plague.cartloads", 24, "Cartloads of bones that fill the catacombs (a batch of graves emptied into them each review between plagues)");
        Config.define(S, "plague.boneRate", 150, "Bones a carter stacks per tick");
        Config.define(S, "plague.pileLeft", 0.2, "Share of each heap of dug-up bones left lying when the carters take the rest");
        CityCommand.addAddon(new PlagueAddon());
    }

    @Override
    public boolean option(String word, CityCommand.Options o) {
        switch (word) {
            case "plague", "peste", "forever" -> { o.extra.add("plague"); o.evolve = true; return true; }
            case "noplague" -> { o.extra.add("noplague"); return true; } // only reaches here from /emergentcity evolve
            case "catacombs" -> { o.extra.add("catacombs"); return true; } // likewise
            default -> { return false; }
        }
    }

    @Override
    public String check(CityCommand.Options o) {
        if (!o.extra.contains("plague")) return null;
        // the plague needs cemeteries to fill; catacombs by default (catacombs=off: plague pits only)
        if (Boolean.FALSE.equals(o.cemeteries)) return "The plague needs cemeteries: leave out cemeteries=off.";
        o.cemeteries = true;
        if (o.catacombs == null) o.catacombs = true;
        return null;
    }

    @Override
    public String configure(LiveCityJob job, CityCommand.Options o, boolean evolveCommand) {
        PlagueMode pm = job.mode instanceof PlagueMode p ? p : null;
        boolean on = o.extra.contains("plague"), off = o.extra.contains("noplague"), cat = o.extra.contains("catacombs");
        if (on && off) return "Choose plague or noplague.";
        if (!evolveCommand) {
            if (on) { pm = new PlagueMode(job); pm.plague = true; job.mode = pm; }
            return null;
        }
        // a town with plagues keeps them when brought back, unless told otherwise
        boolean plague = on || (pm != null && pm.plague && !off);
        if (cat && !plague) return "'catacombs' goes with 'plague': the quarrymen dig them for the bones of the dead.";
        if (!plague) { if (pm != null) pm.plague = false; return null; }
        if (pm == null) { pm = new PlagueMode(job); job.mode = pm; }
        pm.plague = true;
        pm.digCatacombs = cat;
        return null;
    }

    @Override public String words() { return "plague (evolve: noplague, catacombs)"; }
}
