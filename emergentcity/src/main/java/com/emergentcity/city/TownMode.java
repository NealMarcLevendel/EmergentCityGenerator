package com.emergentcity.city;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A mode an addon mod gives an evolving villager town (the plague mod's plague): while it is active it takes the
 * town's reviews over, adds its own jobs to each analysis, and may end the town or start a new one on the site.
 * Set on a town as {@code job.mode}; everything here is called on the server thread unless noted.
 */
public interface TownMode {
    /** Returned by {@link #review()} when the mode has ended the town (stopped it, or started a new one). */
    List<LiveCityJob.Change> ENDED = Collections.unmodifiableList(new ArrayList<>());

    /** Running now: the town does not grow, nobody repairs anything, and {@link #cycleSeconds()} applies. */
    boolean active();

    /** Seconds between reviews while active (before the speed-up). */
    int cycleSeconds();

    /** Masons the crew needs while active. */
    int masons();

    /** The town starts (or resumes) evolving; fresh: brought back by a command. */
    void evolveStarts(boolean fresh);

    /**
     * This review's changes (or null for none); while {@link #active()} they are all the review does, otherwise the
     * town's ordinary changes are added to them. {@link #ENDED} when the mode has ended the town.
     */
    List<LiveCityJob.Change> review();

    /** Before an analysis: the work to do off-thread with that analysis's voxelizer, adding jobs to tasks (or null). */
    Work prepareAnalysis();

    /** Off-thread work for one analysis. */
    interface Work {
        void run(Voxelizer vx, List<LiveCityJob.BuildTask> tasks);
    }

    /** A job has been built (any job, the mode's own or the town's). */
    void taskDone(LiveCityJob.BuildTask t);

    /** A new town starts growing on the site (after {@link LiveCityJob#newTown}): its model exists, nothing is built yet. */
    void growStarts();

    /** The boss bar line, or null for the usual one. */
    String barText();

    /** A clause for the status line and the evolution summary, or null. */
    String stats();

    /** Words for the mode's change counts in the review reports ("abandoned" -> "house(s) left empty"). */
    Map<String, String> labels();
}
