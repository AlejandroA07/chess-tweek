package com.chesstweek.training.internal.explorer;

import java.util.Set;

import com.chesstweek.shared.chess.ExplorerPosition;
import com.chesstweek.training.OpeningExplorer;

/** One upstream request to the Lichess opening explorer; no caching or pacing. */
public interface LichessExplorerClient {

    /**
     * @throws ExplorerClientException when Lichess refuses, limits, fails, or answers invalidly
     */
    ExplorerAnswer masters(String accessToken, ExplorerPosition position);

    /**
     * @throws ExplorerClientException when Lichess refuses, limits, fails, or answers invalidly
     */
    ExplorerAnswer lichess(
            String accessToken,
            ExplorerPosition position,
            Set<OpeningExplorer.Speed> speeds,
            Set<OpeningExplorer.RatingGroup> ratings);
}
