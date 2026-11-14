package com.chesstweek.gamereview.internal.analysis;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One fixed-node search of a position: its best lines in engine order, best first, each from
 * the side to move. Only the first line decides the position's value.
 */
public record Evaluation(List<Line> lines) {

    public static final int MAX_LINES = 3;

    public Evaluation {
        Objects.requireNonNull(lines);
        if (lines.isEmpty() || lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("An evaluation has one to three lines");
        }
        lines = List.copyOf(lines);
    }

    public Line best() {
        return lines.getFirst();
    }

    /** One principal variation; {@code pv} starts with {@code move}. */
    public record Line(String move, Score score, Wdl wdl, List<String> pv) {

        private static final Pattern UCI_MOVE = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
        public static final int MAX_PV_LENGTH = 64;

        public Line {
            Objects.requireNonNull(score);
            Objects.requireNonNull(wdl);
            Objects.requireNonNull(pv);
            pv = List.copyOf(pv);
            if (!isUciMove(move) || pv.isEmpty() || pv.size() > MAX_PV_LENGTH
                    || !pv.getFirst().equals(move) || !pv.stream().allMatch(Line::isUciMove)) {
                throw new IllegalArgumentException("A line needs a UCI move starting a bounded UCI variation");
            }
        }

        static boolean isUciMove(String move) {
            return move != null && UCI_MOVE.matcher(move).matches();
        }
    }
}
