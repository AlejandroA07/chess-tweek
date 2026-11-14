package com.chesstweek.gamereview.internal.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.chesstweek.gamereview.internal.analysis.Evaluation;
import com.chesstweek.gamereview.internal.analysis.Score;
import com.chesstweek.gamereview.internal.analysis.Wdl;

/**
 * Collects one search's {@code info} lines and turns the final ones into an {@link Evaluation}.
 * Engine output is untrusted: every number is bounded, every move must be UCI, lines without
 * WDL or a variation are ignored, and the result must agree with Stockfish's {@code bestmove}.
 */
final class UciSearchOutput {

    private static final int MAX_NUMBER_DIGITS = 9;

    private final Map<Integer, Evaluation.Line> latestByRank = new TreeMap<>();

    /** Reads one output line; anything that is not a complete principal-variation line is ignored. */
    void accept(String line) {
        if (!line.startsWith("info ")) {
            return;
        }
        String[] tokens = line.split(" ");
        Integer rank = null;
        Score score = null;
        Wdl wdl = null;
        List<String> pv = null;
        try {
            for (int i = 1; i < tokens.length; i++) {
                switch (tokens[i]) {
                    case "multipv" -> rank = number(tokens, ++i, 1, Evaluation.MAX_LINES);
                    case "score" -> {
                        String kind = token(tokens, ++i);
                        int value = number(tokens, ++i, -100_000, 100_000);
                        score = switch (kind) {
                            case "cp" -> new Score.Centipawns(value);
                            case "mate" -> value == 0 ? null : new Score.Mate(value);
                            default -> null;
                        };
                        if (score == null) {
                            return;
                        }
                    }
                    case "lowerbound", "upperbound" -> {
                        // When the node limit ends a search mid-iteration, Stockfish's final
                        // report for the move it plays can be a bound. Its score is within the
                        // narrow aspiration window, so it is kept like an exact line.
                    }
                    case "string", "currmove" -> {
                        return;
                    }
                    case "wdl" -> wdl = new Wdl(
                            number(tokens, ++i, 0, 1_000),
                            number(tokens, ++i, 0, 1_000),
                            number(tokens, ++i, 0, 1_000));
                    case "pv" -> {
                        pv = new ArrayList<>();
                        for (int j = i + 1; j < tokens.length && pv.size() < Evaluation.Line.MAX_PV_LENGTH; j++) {
                            pv.add(tokens[j]);
                        }
                        i = tokens.length;
                    }
                    default -> {
                        // Other fields (depth, nodes, nps, time, hashfull, ...) are not needed.
                    }
                }
            }
            if (rank == null || score == null || wdl == null || pv == null || pv.isEmpty()) {
                return;
            }
            latestByRank.put(rank, new Evaluation.Line(pv.getFirst(), score, wdl, pv));
        } catch (IllegalArgumentException exception) {
            // A malformed or out-of-range field makes the whole line unusable; later lines may
            // still complete the search.
        }
    }

    /**
     * The evaluation once Stockfish has answered {@code bestmove}. Returns null when no usable
     * line was seen, the ranks are not contiguous from 1, or the best line disagrees with the
     * engine's chosen move.
     */
    Evaluation finish(String bestMoveLine) {
        String[] tokens = bestMoveLine.split(" ");
        if (tokens.length < 2 || !tokens[0].equals("bestmove")) {
            return null;
        }
        var lines = new ArrayList<Evaluation.Line>();
        for (int rank = 1; latestByRank.containsKey(rank); rank++) {
            lines.add(latestByRank.get(rank));
        }
        if (lines.isEmpty() || lines.size() != latestByRank.size()
                || !lines.getFirst().move().equals(tokens[1])) {
            return null;
        }
        return new Evaluation(lines);
    }

    private static String token(String[] tokens, int index) {
        if (index >= tokens.length) {
            throw new IllegalArgumentException("Missing field");
        }
        return tokens[index];
    }

    private static int number(String[] tokens, int index, int min, int max) {
        String text = token(tokens, index);
        if (text.isEmpty() || text.length() > MAX_NUMBER_DIGITS + 1
                || !text.matches("-?\\d{1," + MAX_NUMBER_DIGITS + "}")) {
            throw new IllegalArgumentException("Not a bounded number");
        }
        int value = Integer.parseInt(text);
        if (value < min || value > max) {
            throw new IllegalArgumentException("Number out of range");
        }
        return value;
    }
}
