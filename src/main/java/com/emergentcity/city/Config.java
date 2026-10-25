package com.emergentcity.city;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Properties;

/**
 * Typed configuration with a documented registry of every parameter.
 * All values are tunable prototype defaults, NOT historical standards. Units: metres unless noted.
 * Unknown keys are rejected so typos never silently fall back to defaults.
 */
final class Config {
    record Param(String section, String key, String def, String doc) {}

    static final LinkedHashMap<String, Param> PARAMS = new LinkedHashMap<>();

    private static void def(String sec, String key, Object def, String doc) {
        PARAMS.put(key, new Param(sec, key, String.valueOf(def), doc));
    }

    static {
        String S = "General";

        S = "Growth loop (section G)";
        def(S, "growth.phases", 7, "Number of growth phases; analysis + feedback runs after each");
        def(S, "growth.stepsPerPhase", 280, "Street proposals evaluated per phase (growth budget)");
        def(S, "growth.initialRadius", 140, "Soft growth radius around seeded centres in phase 0 (m)");
        def(S, "growth.radiusPerPhase", 55, "Growth radius increase per phase (m)");
        def(S, "growth.mapMargin", 40, "No streets closer than this to the terrain edge (m)");
        def(S, "growth.temperature", 0.30, "Softmax temperature for controlled stochastic proposal selection");
        def(S, "growth.turnSamplesDeg", "-18,-11,-5,-2,0,2,5,11,18", "Candidate heading changes per step (degrees)");
        def(S, "growth.maxTurnDeg", 18, "Largest heading change per segment; with segment length sets min bend radius");
        def(S, "growth.curvatureStrength", 0.4, "0..1 persistence of gentle bends (section C curvature strength)");
        def(S, "growth.minJunctionAngleDeg", 38, "Hard minimum angle between streets meeting at a node");
        def(S, "growth.minCrossAngleDeg", 40, "Hard minimum angle when a new street meets an existing one");
        def(S, "growth.segmentLength.local", 22, "Segment length for local lanes (m)");
        def(S, "growth.segmentLength.connector", 28, "Segment length for connectors (m)");
        def(S, "growth.segmentLength.primary", 34, "Segment length for primary streets (m)");
        def(S, "growth.lookAhead", 1.6, "Look-ahead factor: connect to a street within this many segment lengths");
        def(S, "growth.snapNodeFraction", 0.45, "Snap to an existing node within this fraction of segment length");
        def(S, "growth.snapEndpoint", 7, "When hitting a street this close to its end node, join the node (m)");
        def(S, "growth.minSegment", 7, "Hard minimum street segment length (m)");
        def(S, "growth.clearanceFree", 22, "Free street ends must keep this clearance from other streets (m)");
        def(S, "growth.minBlockDepth", 0, "Streets running alongside each other leave at least this much buildable depth between them (m), so the block holds two rows of plots and a courtyard (0 = off)");
        def(S, "growth.boulevardReach", 0.6, "How far a boulevard runs from the main centre, as a share of the town radius, before ending in its own square");
        def(S, "growth.boulevardBranchSpacing", 40, "Distance between side streets branching off a boulevard (m)");
        def(S, "growth.clearanceNode", 9, "Clearance between new geometry and unrelated nodes (m)");
        def(S, "growth.minLoopPerimeter", 190, "Reject connections that would close a block smaller than this perimeter (m)");
        def(S, "growth.goodLoopPerimeterMax", 460, "Closing loops up to this perimeter earns the full connection bonus (m)");
        def(S, "growth.maxLoopSearch", 700, "Search limit for loop-perimeter estimation (m)");
        def(S, "growth.branchFrontageMin", 50, "Branching becomes possible after this much frontage since last branch (m)");
        def(S, "growth.branchFrontageMax", 100, "Branching is near-certain after this much frontage (m)");
        def(S, "growth.branchAngleJitterDeg", 15, "Std-dev of branch angle around 90 degrees");
        def(S, "growth.maxGrade.local", 0.16, "Hard max grade for local lanes");
        def(S, "growth.maxGrade.connector", 0.12, "Hard max grade for connectors");
        def(S, "growth.maxGrade.primary", 0.09, "Hard max grade for primary streets");
        def(S, "growth.bridgeMaxLength", 70, "Longest permitted bridge water span (m)");
        def(S, "growth.maxBridges", 3, "Maximum number of bridges (scarcity)");
        def(S, "growth.bridgeMinPhase", 2, "Bridges only after this phase");
        def(S, "growth.seedRoads", 4, "Approach roads seeded from each initial centre");
        def(S, "growth.infillPerPhase", 14, "Max passages/streets cutting oversize blocks per phase");
        def(S, "growth.repairPerPhase", 20, "Max dead-end repair attempts per phase");

        S = "Proposal cost weights (lambda terms of E, section H)";
        def(S, "w.grade", 1.2, "Terrain grade cost weight (E_terrain)");
        def(S, "w.curvature", 0.6, "Deviation from smooth persistent curvature (E_streets)");
        def(S, "w.straight", 0.15, "Small penalty for perfectly straight unaimed segments");
        def(S, "w.vista", 1.4, "Penalty for straight runs beyond max vista with no endpoint (E_vistas)");
        def(S, "w.attraction", 1.0, "Bonus for growing toward centres and density (E_accessibility/E_centers)");
        def(S, "w.radius", 2.0, "Soft penalty for exceeding the current growth radius");
        def(S, "w.connect", 0.8, "Bonus for joining the network and closing good-sized blocks (E_blocks)");
        def(S, "w.bridge", 1.5, "Cost of a bridge");
        def(S, "w.landmarkAxis", 0.8, "Bonus for streets aimed at a landmark (purposeful vista)");

        S = "Centres and landmarks (section A)";
        def(S, "centers.initialCount", 1, "Seeded centres (1-5); location chosen by terrain suitability, not coordinates");
        def(S, "centers.max", 4, "Maximum total centres (seeded + emergent)");
        def(S, "centers.attraction", 0.9, "Base attraction of a seeded centre (0-1)");
        def(S, "centers.minSpacing", 320, "Minimum distance between centres (m)");
        def(S, "centers.densityThreshold", 55, "Houses within 130 m needed for an emergent centre");
        def(S, "centers.convergenceThreshold", 1.5, "Sum of incident normalised demand needed for an emergent centre");
        def(S, "centers.minPhase", 3, "Emergent centres only from this phase");
        def(S, "centers.waterAffinity", 1.0, "0..1 preference for siting the first centre near (not on) water");
        def(S, "landmark.max", 3, "Maximum monumental landmarks (scarcity)");
        def(S, "landmark.minHeightToWidth", 1.0, "Minimum height / principal facade width for monuments");
        def(S, "landmark.strengthThreshold", 0.45, "Centre strength needed before a landmark is built");
        def(S, "landmark.minHouses", 45, "Houses within 200 m needed before a landmark is built");
        def(S, "landmark.cathedral.width", 28, "Cathedral facade width target (m)");
        def(S, "landmark.cathedral.depth", 62, "Cathedral nave depth target (m)");
        def(S, "landmark.hall.width", 24, "Civic/market hall facade width target (m)");
        def(S, "landmark.hall.depth", 26, "Civic/market hall depth target (m)");
        def(S, "mc.boulevards", 0, "Straight boulevards laid out from the main centre (0-4; 2 = one avenue straight through the centre). Enable with the 'boulevards' command option");
        def(S, "street.boulevard.width", 14, "Boulevard width (m); scaled to the radius in Minecraft");
        def(S, "plaza.blockTarget", 1200, "Preferred area of a centre's square (m2): the block beside the centre closest to this becomes the square");
        def(S, "plaza.blockMaxArea", 3200, "Largest block that may become a centre's square (m2; 40% more for a monument's square)");
        def(S, "plaza.blockMinArea", 150, "Smallest block that may become a square (m2)");
        def(S, "plaza.smallMaxArea", 1000, "Largest block that may become a junction square away from the centres (m2)");
        def(S, "mc.freestanding", false, "Monuments stand free in the middle of their squares, which grow by merging blocks (command option 'freestanding')");
        def(S, "plaza.freeFactor", 1.6, "A free-standing monument's square: this many times the monument's footprint plus a 6 m margin all round");
        def(S, "landmark.grand", false, "Build one oversized landmark (a grand cathedral) with a square to match; off by default, enable with the 'grand' command option");
        def(S, "landmark.grandScale", 1.5, "Size of the grand landmark relative to an ordinary cathedral");
        def(S, "plaza.maxRadius", 70, "Largest plaza radius (m); the grand landmark's square may exceed it to stay in proportion");
        def(S, "landmark.enableAxis", true, "Allow one Baroque-style straight approach aimed at the main landmark");
        def(S, "landmark.axisMaxLength", 320, "Maximum length of that approach axis (m)");

        S = "Streets (sections B, C, F)";
        def(S, "street.local.min", 4.5, "Local lane min width (m)");
        def(S, "street.local.max", 8, "Local lane max width (m)");
        def(S, "street.connector.min", 8, "Connector min width (m)");
        def(S, "street.connector.max", 14, "Connector max width (m)");
        def(S, "street.primary.min", 14, "Primary street min width (m)");
        def(S, "street.primary.max", 22, "Primary street max width (m)");
        def(S, "street.civic.min", 18, "Civic approach min width (m)");
        def(S, "street.civic.max", 28, "Civic approach max width (m)");
        def(S, "street.primaryDemand", 0.80, "Normalised demand for primary class");
        def(S, "street.connectorDemand", 0.55, "Normalised demand for connector class");
        def(S, "street.classHysteresis", 0.05, "Demand drop tolerated before a street is downgraded");
        def(S, "street.maxStepPerEdge", 2.5, "Max width change between consecutive segments of one street (m)");
        def(S, "street.maxRatePerPhase", 3.0, "Max width change per phase (prevents runaway widening) (m)");
        def(S, "street.proportionNudge", 0.25, "0..1 how strongly local widths adapt toward facade heights");
        def(S, "street.junctionExpansion", 1.3, "Junction opening radius as a multiple of half the widest street");
        def(S, "demand.sampleRoots", 50, "Sampled origins for trip/betweenness demand estimate");
        def(S, "demand.localTripWeight", 3.0, "Total weight of local (non-centre) trips");

        S = "Plazas (section C)";
        def(S, "plaza.radiusWidths", 2.2, "Plaza radius in mean incident street widths (2-5)");
        def(S, "plaza.tendency", 0.5, "0..1 plaza-formation tendency (shifts the threshold)");
        def(S, "plaza.enterThreshold", 0.66, "Score needed to form a plaza");
        def(S, "plaza.exitThreshold", 0.52, "Score below which a non-central plaza dissolves (hysteresis)");
        def(S, "plaza.minSpacing", 140, "Minimum spacing between plazas (m)");
        def(S, "plaza.maxCount", 8, "Maximum plazas (scarcity)");
        def(S, "plaza.landmarkFactor", 1.5, "Plaza enlargement when a landmark fronts it");

        S = "Vistas and enclosure (section D)";
        def(S, "vista.maxWidths", 8, "Max unbroken straight run / view in street widths (4-10)");
        def(S, "vista.sampleSpacing", 12, "Spacing of vista samples along streets (m)");
        def(S, "vista.maxRay", 600, "Longest evaluated sightline (m)");

        S = "Blocks and houses (section E)";
        def(S, "block.minArea", 300, "Smallest face treated as a block (m2)");
        def(S, "block.maxArea", 8500, "Blocks larger than this attract a cutting passage/street (m2)");
        def(S, "block.maxHousingFace", 45000, "Faces larger than this stay as fields (m2)");
        def(S, "block.houseFrontageMin", 4.5, "Minimum house frontage (m)");
        def(S, "block.houseFrontageMax", 8.0, "Maximum house frontage (m)");
        def(S, "block.houseFrontageTarget", 6.0, "Typical house frontage (m)");
        def(S, "block.houseDepthMin", 8, "Minimum house depth (m)");
        def(S, "block.houseDepthMax", 15, "Maximum house depth (m)");
        def(S, "block.continuity", 0.9, "Share of perimeter units built (rest are yards)");
        def(S, "density.houseUnitArea", 60, "Footprint area counted as one house when measuring density (m2)");
        def(S, "block.gateWalls", true, "Close any stretch of a built block's street edge still left open with a hofje-style garden wall and a door into the courtyard");
        def(S, "block.infill", true, "Fill any stretch of a block outline left without a building (wedges, collapsed corners, rejected plots) with infill and corner buildings, so street walls are closed");
        def(S, "block.frontageToDepth", 0.0, "If > 0, target frontage = this x plot depth (1.0 = square plots); 0 uses houseFrontageTarget");
        def(S, "block.minCornerAngleDeg", 70, "Block corners sharper than this stay open as a small paved square instead of a pointed house");
        def(S, "block.gapFillDepth", 6, "Gaps in a street wall up to this many blocks are absorbed by the neighbouring house");
        def(S, "block.gapFillFront", 9, "Along the street, a house may widen this many cells to close a gap in the street wall before a hofje wall is used");
        def(S, "block.passageMinPerimeter", 220, "Blocks with longer perimeter get a passage into the garden (m)");
        def(S, "block.simplifyTolerance", 0.9, "Block outline simplification tolerance (m)");
        def(S, "block.minEdge", 4.5, "Minimum block outline edge after simplification (m)");
        def(S, "house.minArea", 14, "Hard minimum footprint area (m2)");
        def(S, "house.minEdge", 2.2, "Hard minimum footprint edge (m)");
        def(S, "house.minAngleDeg", 20, "Hard minimum footprint interior angle (degrees)");

        S = "Architecture (section N)";
        def(S, "arch.floorHeight", 3.4, "Typical storey height (m)");
        def(S, "arch.groundFloorExtra", 0.8, "Extra ground-floor height (m)");
        def(S, "arch.rTarget.local", 1.0, "Width/height target R on local streets");
        def(S, "arch.rTarget.connector", 1.0, "Width/height target R on connectors");
        def(S, "arch.rTarget.primary", 1.4, "Relaxed R on primary streets");
        def(S, "arch.rTarget.civic", 1.6, "Relaxed R on civic approaches");
        def(S, "arch.rTarget.plaza", 2.5, "Relaxed R for plaza frontages (plaza diameter / height)");
        def(S, "arch.tier2Quota", 0.12, "Max share of houses in tier 2 (scarcity)");
        def(S, "use.restaurantShare", 0.04, "Share of buildings that are restaurants (ground-floor dining, home above, outdoor seating)");
        def(S, "use.bookstoreShare", 0.025, "Share of buildings that are standalone bookstores");
        def(S, "use.mixedShare", 0.2, "Share of buildings with a shop on the ground floor and a home above");
        def(S, "use.cornerShopShare", 0.7, "Share of corner and infill buildings used as shops (ground-floor shop, rooms above)");
        def(S, "use.minStreetClass", 3, "Shops, bookstores and restaurants only on streets of at least this class (1 lane, 2 connector, 3 main street, 4 civic) or on a plaza; smaller streets stay residential");
        def(S, "use.accentDoorShare", 0.3, "Share of homes whose entrance is flanked by painted wood (all shops have one)");
        def(S, "mc.maxStoreys", 0, "Player's storey limit for houses (0 = automatic, 4 at Minecraft scale); set with the storeys=<n> command option");
        def(S, "arch.heightScale", 1.0, "Multiplier on storey counts from street proportions (set automatically when storeys=<n> is above the usual limit)");
        def(S, "arch.maxFloors", 6, "Hard cap on storeys for any house, including plaza frontages (monuments excepted)");
        def(S, "arch.maxHeightToFrontage", 3.0, "Hard cap on house height / frontage, so narrow plots never become towers");
        def(S, "arch.tier2Threshold", 0.45, "Importance needed for tier 2");
        def(S, "arch.tier4Share", 0.15, "Max share of houses in tier 4");
        def(S, "arch.synthesis", "core=georgian_classical;outer=medieval_vernacular;monument=neoclassical_monumental",
                "Explicit historical synthesis by district (intentional blend, not period-authentic)");
        def(S, "arch.coreRadiusFraction", 0.42, "Core district radius as fraction of city radius");

        S = "Minecraft (1 block = 1 m)";
        def(S, "mc.scaleToRadius", true, "Derive growth radius, budget and centre spacing from the command radius (keys you set explicitly win)");
        def(S, "mc.margin", 16, "Extra terrain sampled beyond the radius (blocks)");
        def(S, "mc.minRadius", 48, "Smallest radius accepted by the command");
        def(S, "mc.maxRadius", 400, "Largest radius accepted by the command");
        def(S, "mc.tickBudgetMs", 25, "Server time per tick spent on sampling/placing (ms); lower = smoother, slower");
        def(S, "mc.blurRadius", 3, "Smoothing of sampled heights before simulation (blocks)");
        def(S, "mc.storeyMinBlocks", 3, "Smallest storey height in blocks");
        def(S, "mc.gardenTreeChance", 0.015, "Chance per garden cell of a small tree");
        def(S, "mc.lanterns", true, "Place a lantern on each storey and in plazas (stops mob spawns)");
        def(S, "mc.fountains", true, "Fountain in central plazas");
        def(S, "mc.preserveStructures", true, "Leave existing man-made structures (villages, earlier builds) untouched: streets route around them, nothing is built on them");
        def(S, "mc.lampSpacing", 12, "Distance between glowstone lamp posts along streets (blocks); 0 = no lamps");
        def(S, "mc.palette", "warm", "Town material palette: warm (sandstone/plaster, red tile roofs), redbrick (brick facades in several tones, red nether brick roofs), brick, whitewash or stone (slate roofs)");
        def(S, "mc.paletteVariation", 0.0, "0..1 share of street blocks built in the district's alternate wall tone (0 = fully uniform)");
        def(S, "mc.roofForm", "uniform", "uniform = every house gets the same pitched roof with eaves to the street; auto = by style (parapets, gables)");
        def(S, "mc.units", true, "Street-side houses are built from unit designs (structure files: ground, shop, upper and roof pieces) on plots sized to the units; off = every house drawn from scratch (command option 'units=off')");
        def(S, "units.maxAngleError", 8.0, "Largest difference (degrees) between a street and the angle a unit was designed for (designs exist every 11.25 degrees)");
        def(S, "mc.maxRoofRise", 4, "Highest a pitched roof rises above the top storey (blocks)");
        def(S, "mc.live.surveyors", 12, "Villager mode: most surveyor villagers growing streets at once");
        def(S, "mc.live.masons", 16, "Villager mode: most mason villagers building at once");
        def(S, "mc.live.walkSpeed", 0.32, "Villager mode: walking speed in blocks per tick (they hurry on long trips)");
        def(S, "mc.live.buildRate", 10, "Villager mode: blocks one mason places per tick on a house");
        def(S, "mc.live.groundRate", 40, "Villager mode: blocks per tick when paving a block or a plaza");
        def(S, "mc.evolve.cycleSeconds", 60, "Evolving town: seconds between town reviews (each review grows streets a little and changes a few buildings)");
        def(S, "mc.evolve.changesPerCycle", 4, "Evolving town: buildings changed per review (raise, rebuild, merge, split, clear)");
        def(S, "mc.evolve.growthSteps", 12, "Evolving town: street growth decisions per review (never beyond the command radius)");
        def(S, "mc.evolve.surveyors", 2, "Evolving town: surveyors kept on the job after the town is complete");
        def(S, "mc.evolve.masons", 4, "Evolving town: masons kept on the job after the town is complete");
        def(S, "mc.evolve.vacantCycles", 4, "Evolving town: reviews a cleared lot stays a garden before it is rebuilt");
        def(S, "mc.evolve.minAgeCycles", 5, "Evolving town: reviews before a building may be demolished and rebuilt");
        def(S, "mc.evolve.weights", "raise=3,renew=2.5,convert=2,merge=1.5,split=1,clear=1.2,redevelop=0.6",
                "Evolving town: relative frequency of each kind of building change (redevelop clears a whole block for re-planning)");
        def(S, "mc.evolve.relaysPerCycle", 3, "Evolving town: streets re-laid per review when their width has drifted with traffic (widen into free space or narrow)");
        def(S, "mc.evolve.reportEvery", 5, "Evolving town: post a chat summary every this many reviews");
        def(S, "mc.evolve.maxMinutes", 0, "Evolving town: stop by itself after this many minutes (0 = until /emergentcity stop)");
        def(S, "mc.maintain.delaySeconds", 30, "With the 'maintain' option: seconds before a mason replaces a house block a player broke (sneak-break to remove a block for good)");
        def(S, "mc.live.senseRadius", 24, "Villager mode: how far a surveyor re-reads the terrain before deciding (blocks)");

        S = "Objective lambdas for the reported composite score (section H / N)";
        for (String k : new String[]{"streets", "vistas", "blocks", "proportions", "centers", "terrain", "accessibility",
                "hierarchy", "archProportion", "facadeRhythm", "ornament", "neighbor", "style"})
            def(S, "lambda." + k, 1.0, "Weight of E_" + k + " in the reported objective");
    }

    private final Properties p = new Properties();

    static Config defaults() { return new Config(); }

    static Config load(Path f) throws IOException {
        Config c = new Config();
        try (Reader r = Files.newBufferedReader(f)) { c.p.load(r); }
        for (String k : c.p.stringPropertyNames())
            if (!PARAMS.containsKey(k)) throw new IllegalArgumentException("Unknown config key: " + k);
        return c;
    }

    Config with(String k, Object v) {
        if (!PARAMS.containsKey(k)) throw new IllegalArgumentException("Unknown config key: " + k);
        Config c = new Config();
        c.p.putAll(p);
        c.p.setProperty(k, String.valueOf(v));
        return c;
    }

    /** True when the key was set explicitly (file or with()), not just defaulted. */
    boolean has(String k) {
        // A value equal to the built-in default does not count as an override, so files written by
        // older versions (which listed every key) do not switch off radius-based scaling.
        Param pr = PARAMS.get(k);
        return p.containsKey(k) && (pr == null || !p.getProperty(k).trim().equals(pr.def()));
    }

    String s(String k) {
        Param pr = PARAMS.get(k);
        if (pr == null) throw new IllegalArgumentException("Unknown config key: " + k);
        return p.getProperty(k, pr.def()).trim();
    }

    double d(String k) { return Double.parseDouble(s(k)); }
    int i(String k) { return (int) Math.round(d(k)); }
    boolean b(String k) { return Boolean.parseBoolean(s(k)); }

    double[] arr(String k) {
        String[] parts = s(k).split(",");
        double[] r = new double[parts.length];
        for (int i = 0; i < parts.length; i++) r[i] = Double.parseDouble(parts[i].trim());
        return r;
    }

    void write(Path f) throws IOException { write(f, false); }

    /** Writes every parameter with its documentation; commented=true writes "#key=value" so nothing is overridden. */
    void write(Path f, boolean commented) throws IOException {
        if (f.getParent() != null) Files.createDirectories(f.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(f)) {
            w.write("# Emergent historic city generator - configuration\n");
            w.write("# Units: metres, square metres, degrees as noted. All values are tunable prototype\n");
            w.write("# defaults, not universal historical facts.\n");
            w.write("# Lines starting with # are inactive; remove the # to override a value. In Minecraft, many values\n");
            w.write("# are derived from the command radius (mc.scaleToRadius) unless you override them here.\n");
            String sec = null;
            for (Param pr : PARAMS.values()) {
                if (!pr.section().equals(sec)) { sec = pr.section(); w.write("\n# ===== " + sec + " =====\n"); }
                w.write("# " + pr.doc() + "\n");
                w.write((commented && !p.containsKey(pr.key()) ? "#" : "") + pr.key() + "=" + s(pr.key()) + "\n");
            }
        }
    }
}
