package com.chesstweek.gamereview.internal.analysis;

import com.chesstweek.gamereview.CatalogGame;

/**
 * Opening, middlegame, and endgame boundaries for one game, as position indexes. Lichess's
 * division uses the same indexes: scalachess's {@code Divider} indexes a board list that starts
 * with the initial position ({@code CanPlay.playBoards}). A move belongs
 * to the phase of the position it was played from, so move {@code ply} uses position
 * {@code ply - 1}. Phases are contiguous and never return to an earlier phase.
 *
 * <p>Lichess's supplied division is preferred. Without one, the opening covers plies 1 through
 * 20, and the endgame starts at the first later position with no queens and at most 20 points of
 * combined non-pawn material.
 */
public record GamePhases(Source source, int middlegameStart, Integer endgameStart) {

    public static final int INFERRED_OPENING_PLIES = 20;
    public static final int INFERRED_ENDGAME_MAX_NON_PAWN_MATERIAL = 20;

    public GamePhases {
        if (source == null || middlegameStart < 0
                || (endgameStart != null && endgameStart < middlegameStart)) {
            throw new IllegalArgumentException("Phase boundaries must be ordered");
        }
    }

    public static GamePhases of(CatalogGame.PhaseDivision supplied, ReplayedGame game) {
        if (supplied != null && (supplied.middlePly() != null || supplied.endPly() != null)) {
            return fromSupplied(supplied, game.plyCount());
        }
        return inferred(game);
    }

    private static GamePhases fromSupplied(CatalogGame.PhaseDivision supplied, int plyCount) {
        Integer end = supplied.endPly();
        // Lichess omits the middlegame start when the game never left the opening; with only
        // an endgame start, the opening runs directly into the endgame.
        int middle = supplied.middlePly() != null
                ? supplied.middlePly()
                : end != null ? end : plyCount + 1;
        if (end != null && end < middle) {
            throw new IllegalArgumentException("Supplied phase division is out of order");
        }
        return new GamePhases(Source.SUPPLIED, middle, end);
    }

    private static GamePhases inferred(ReplayedGame game) {
        Integer end = null;
        for (ReplayedGame.Position position : game.positions()) {
            if (position.index() >= INFERRED_OPENING_PLIES
                    && position.queens() == 0
                    && position.nonPawnMaterial() <= INFERRED_ENDGAME_MAX_NON_PAWN_MATERIAL) {
                end = position.index();
                break;
            }
        }
        return new GamePhases(Source.INFERRED, INFERRED_OPENING_PLIES, end);
    }

    /** The phase of move {@code ply} (1-based). */
    public Phase phaseOfMove(int ply) {
        if (ply < 1) {
            throw new IllegalArgumentException("Plies start at 1");
        }
        int position = ply - 1;
        if (position < middlegameStart) {
            return Phase.OPENING;
        }
        if (endgameStart != null && position >= endgameStart) {
            return Phase.ENDGAME;
        }
        return Phase.MIDDLEGAME;
    }

    public enum Phase {
        OPENING,
        MIDDLEGAME,
        ENDGAME
    }

    public enum Source {
        SUPPLIED,
        INFERRED
    }
}
