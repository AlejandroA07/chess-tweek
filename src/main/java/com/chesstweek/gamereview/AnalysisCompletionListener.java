package com.chesstweek.gamereview;

/**
 * Hears about every analysis whose findings were stored. It runs on the analysis thread after the
 * job is already reported as completed; whatever it does, or throws, never changes that job.
 */
public interface AnalysisCompletionListener {

    void completed(String accountId, GameRef game, GameAnalysis.Perspective perspective);
}
