package com.chesstweek.gamereview.internal.analysis;

/**
 * Stockfish's centipawn or mate score from the side to move. It is stored for explanation;
 * classification uses expected score, except for the forced-mate rules.
 */
public sealed interface Score {

    /** Centipawns; positive favors the side to move. */
    record Centipawns(int value) implements Score {

        public Centipawns {
            if (Math.abs(value) > 100_000) {
                throw new IllegalArgumentException("Centipawn score out of range");
            }
        }
    }

    /**
     * Moves to mate. Positive means the side to move mates; negative means it is mated. Zero is
     * rejected: Stockfish reports it only for a position that is already checkmate, and such
     * positions are judged on the board, never searched.
     */
    record Mate(int moves) implements Score {

        public Mate {
            if (moves == 0 || Math.abs(moves) > 1_000) {
                throw new IllegalArgumentException("Mate distance out of range");
            }
        }
    }
}
