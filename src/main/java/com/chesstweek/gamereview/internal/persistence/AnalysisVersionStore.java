package com.chesstweek.gamereview.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.internal.analysis.GamePhases;
import com.chesstweek.gamereview.internal.analysis.TwoPassAnalysis;

public interface AnalysisVersionStore {

    /** Stores every version and all its moves in one transaction, or nothing. Returns their ids. */
    List<Long> saveAll(List<NewVersion> versions);

    /** One side's findings from one analysis, ready to be stored. */
    record NewVersion(
            String accountId,
            String gameId,
            Side side,
            CatalogGame.Color analyzedColor,
            long profileId,
            int rulesVersion,
            GamePhases phases,
            List<TwoPassAnalysis.MoveFinding> findings,
            Instant startedAt,
            Instant completedAt) {

        public NewVersion {
            Objects.requireNonNull(accountId);
            Objects.requireNonNull(gameId);
            Objects.requireNonNull(side);
            Objects.requireNonNull(analyzedColor);
            Objects.requireNonNull(phases);
            Objects.requireNonNull(startedAt);
            Objects.requireNonNull(completedAt);
            findings = List.copyOf(findings);
        }
    }

    enum Side {
        OWNER,
        OPPONENT
    }
}
