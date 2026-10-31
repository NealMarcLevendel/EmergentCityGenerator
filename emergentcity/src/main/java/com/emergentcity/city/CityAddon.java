package com.emergentcity.city;

/**
 * An addon mod's options for /emergentcity generate, instant and evolve (the plague mod's "plague" and "forever").
 * Register with {@link CityCommand#addAddon} when the mod is constructed.
 */
public interface CityAddon {
    /** An option word the city mod does not know: true when it is this addon's (it notes it in o.extra). */
    boolean option(String word, CityCommand.Options o);

    /** After parsing generate/instant options: checks and completes them; an error message, or null. */
    String check(CityCommand.Options o);

    /**
     * Sets the addon up on a villager town: a new one (evolveCommand false), or the last one, brought back by
     * /emergentcity evolve (called for every evolve, so a mode stopped half way can pick up again). An error, or null.
     */
    String configure(LiveCityJob job, CityCommand.Options o, boolean evolveCommand);

    /** Its option words, for the usage lines. */
    String words();
}
