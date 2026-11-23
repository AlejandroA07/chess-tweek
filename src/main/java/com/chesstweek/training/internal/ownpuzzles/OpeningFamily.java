package com.chesstweek.training.internal.ownpuzzles;

/**
 * The opening family Own Puzzles are grouped by (owner decision, 2026-11-23): the stored opening
 * name before its first {@code :} (Lichess, "Indian Game: London System") or {@code ...}
 * (chess.com, "Indian Game...3.e3 d5"), so one opening from either site lands in one group.
 */
final class OpeningFamily {

    static final String UNKNOWN = "Unknown opening";

    private OpeningFamily() {}

    static String of(String openingName) {
        if (openingName == null) {
            return UNKNOWN;
        }
        int end = openingName.length();
        int colon = openingName.indexOf(':');
        int ellipsis = openingName.indexOf("...");
        if (colon >= 0) {
            end = Math.min(end, colon);
        }
        if (ellipsis >= 0) {
            end = Math.min(end, ellipsis);
        }
        String family = openingName.substring(0, end).strip();
        return family.isEmpty() ? UNKNOWN : family;
    }
}
