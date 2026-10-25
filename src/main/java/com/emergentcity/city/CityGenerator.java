package com.emergentcity.city;

/** Orchestrates the emergent growth loop: seed -> (grow, evaluate, feed back) x phases -> final evaluation. */
final class CityGenerator {
    private CityGenerator() {}

    static CityModel generate(Config cfg, Terrain t, long seed, boolean verbose) {
        return generate(cfg, t, seed, verbose, null);
    }

    /** onPhase receives the phase number as each growth phase starts (phases + 1 = final evaluation). */
    static CityModel generate(Config cfg, Terrain t, long seed, boolean verbose, java.util.function.IntConsumer onPhase) {
        return generate(cfg, t, seed, verbose, onPhase, null);
    }

    /** keepOut: grid cells to leave alone (existing structures), or null. */
    static CityModel generate(Config cfg, Terrain t, long seed, boolean verbose, java.util.function.IntConsumer onPhase, boolean[] keepOut) {
        CityModel m = new CityModel(cfg, t, seed);
        m.keepOut = keepOut;
        GrowthEngine ge = new GrowthEngine(m);
        ge.seed();
        Analysis.updateAttraction(m);
        int phases = cfg.i("growth.phases");
        for (int ph = 1; ph <= phases; ph++) {
            m.phase = ph;
            if (onPhase != null) onPhase.accept(ph);
            long t0 = System.currentTimeMillis();
            ge.runPhase(ph);
            Analysis.phaseUpdate(m, ph, ge, false);
            if (verbose) System.out.printf("  phase %d (%d ms): %s%n", ph, System.currentTimeMillis() - t0, m.phaseSummaries.get("phase " + ph));
        }
        ge.cutOversizeBlocks(phases);
        m.phase = phases + 1;
        if (onPhase != null) onPhase.accept(phases + 1);
        Analysis.phaseUpdate(m, phases + 1, ge, true);
        // squares that merged blocks for a free-standing monument in the last pass: re-plan once with the merged blocks
        if (Analysis.applySquareMerges(m, ge) > 0) Analysis.phaseUpdate(m, phases + 1, ge, true);
        m.metrics = Metrics.compute(m);
        return m;
    }
}
