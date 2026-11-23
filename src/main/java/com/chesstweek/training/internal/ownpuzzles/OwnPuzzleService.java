package com.chesstweek.training.internal.ownpuzzles;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chesstweek.training.OwnPuzzles;
import com.chesstweek.training.internal.persistence.JdbcOwnPuzzleStore;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

/**
 * Turns labelled owner moves into stored Own Puzzles. A puzzle with an invalid field, an unplayable
 * solution, or a previous move that does not lead to its position is skipped on its own, so one
 * bad move never costs the game its other puzzles. An unusable opening is stored as unknown.
 */
@Service
class OwnPuzzleService implements OwnPuzzles {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern LICHESS_ID = Pattern.compile("[A-Za-z0-9]{8,32}");
    private static final Pattern CHESSCOM_ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    private static final Pattern ECO = Pattern.compile("[A-E][0-9]{2}");
    private static final Pattern THEME = Pattern.compile("[a-z_]{1,40}");
    private static final Set<String> LABELS = Set.of("inaccuracy", "mistake", "blunder");
    private static final Set<String> PHASES = Set.of("opening", "middlegame", "endgame");
    private static final Set<String> TACTIC_KINDS = Set.of("allowed", "missed");
    private static final int MAX_OPENING_NAME = 160;
    static final int MAX_PLY = 1_200;
    static final int MAX_PUZZLES = 1_200;
    private static final int FULL_SOLUTION = 3;

    private final JdbcOwnPuzzleStore store;
    private final Clock clock;

    @Autowired
    OwnPuzzleService(JdbcOwnPuzzleStore store) {
        this(store, Clock.systemUTC());
    }

    OwnPuzzleService(JdbcOwnPuzzleStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public int create(String accountId, List<NewOwnPuzzle> puzzles) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("Account ID is invalid");
        }
        if (puzzles == null || puzzles.size() > MAX_PUZZLES) {
            throw new IllegalArgumentException("Own Puzzles are invalid");
        }
        List<JdbcOwnPuzzleStore.Row> rows = new ArrayList<>();
        for (NewOwnPuzzle puzzle : puzzles) {
            if (!valid(puzzle)) {
                continue;
            }
            String solution = solution(puzzle);
            if (solution != null && previousMoveLeadsToPosition(puzzle)) {
                rows.add(row(accountId, puzzle, solution));
            }
        }
        return rows.isEmpty() ? 0 : store.insertNew(rows, clock.millis());
    }

    @Override
    public List<StoredLabels> labels() {
        return store.labels().stream()
                .map(row -> new StoredLabels(
                        new PuzzleSource(row.accountId(), row.gameSource(), row.gameId(), row.sourcePly()),
                        row.analysisVersionId(),
                        row.tactics().stream().map(tactic -> new Tactic(tactic.theme(), tactic.kind())).toList()))
                .toList();
    }

    @Override
    public boolean replaceTactics(PuzzleSource source, List<Tactic> tactics) {
        if (source == null || source.accountId() == null || !ACCOUNT_ID.matcher(source.accountId()).matches()
                || !validGame(source.gameSource(), source.gameId())
                || source.sourcePly() < 1 || source.sourcePly() > MAX_PLY) {
            throw new IllegalArgumentException("Own Puzzle source is invalid");
        }
        if (tactics == null || !tactics.stream().allMatch(OwnPuzzleService::validTactic)) {
            throw new IllegalArgumentException("Tactic labels are invalid");
        }
        Set<Tactic> wanted = new java.util.HashSet<>(tactics);
        Set<Tactic> stored = store.tactics(source.accountId(), source.gameSource(), source.gameId(),
                        source.sourcePly()).stream()
                .map(tactic -> new Tactic(tactic.theme(), tactic.kind()))
                .collect(java.util.stream.Collectors.toSet());
        if (wanted.equals(stored)) {
            return false;
        }
        return store.replaceTactics(source.accountId(), source.gameSource(), source.gameId(), source.sourcePly(),
                wanted.stream()
                        .map(tactic -> new JdbcOwnPuzzleStore.TacticRow(tactic.theme(), tactic.kind()))
                        .toList());
    }

    private static boolean validTactic(Tactic tactic) {
        return tactic != null && tactic.theme() != null && THEME.matcher(tactic.theme()).matches()
                && TACTIC_KINDS.contains(tactic.kind());
    }

    private static boolean valid(NewOwnPuzzle puzzle) {
        return puzzle != null
                && validGame(puzzle.gameSource(), puzzle.gameId())
                && puzzle.sourcePly() >= 1 && puzzle.sourcePly() <= MAX_PLY
                && puzzle.analysisVersionId() >= 1
                && validFen(puzzle.positionFen())
                && (puzzle.previousPositionFen() == null || validFen(puzzle.previousPositionFen()))
                && LABELS.contains(puzzle.label())
                && PHASES.contains(puzzle.phase())
                && puzzle.gamePlayedAt() != null && puzzle.gamePlayedAt().toEpochMilli() >= 0
                && puzzle.tactics().stream().allMatch(OwnPuzzleService::validTactic);
    }

    private static String eco(String eco) {
        return eco != null && ECO.matcher(eco).matches() ? eco : null;
    }

    private static String openingName(String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_OPENING_NAME ? name : null;
    }

    private static boolean validGame(String source, String gameId) {
        if (source == null || gameId == null) {
            return false;
        }
        return switch (source) {
            case "lichess" -> LICHESS_ID.matcher(gameId).matches();
            case "chesscom" -> CHESSCOM_ID.matcher(gameId).matches();
            default -> false;
        };
    }

    private static boolean validFen(String fen) {
        return fen != null && fen.length() >= 20 && fen.length() <= 100;
    }

    /** The first three moves of the top line, or only its first when shorter; null when unplayable. */
    private static String solution(NewOwnPuzzle puzzle) {
        List<String> line = puzzle.engineLine();
        if (line.isEmpty()) {
            return null;
        }
        List<String> moves = line.subList(0, line.size() >= FULL_SOLUTION ? FULL_SOLUTION : 1);
        ChessGame game = game(puzzle.positionFen());
        if (game == null) {
            return null;
        }
        for (String uci : moves) {
            if (!play(game, uci)) {
                return null;
            }
        }
        return String.join(" ", moves);
    }

    /** True when there is no previous move, or when it turns the previous position into this one. */
    private static boolean previousMoveLeadsToPosition(NewOwnPuzzle puzzle) {
        if (puzzle.previousPositionFen() == null && puzzle.previousMoveUci() == null) {
            return true;
        }
        if (puzzle.previousPositionFen() == null || puzzle.previousMoveUci() == null) {
            return false;
        }
        ChessGame game = game(puzzle.previousPositionFen());
        return game != null && play(game, puzzle.previousMoveUci())
                && samePosition(game.getFen(), puzzle.positionFen());
    }

    /** Placement, side to move, castling, and en passant agree; move counters may differ. */
    private static boolean samePosition(String left, String right) {
        String[] a = left.split(" ");
        String[] b = right.split(" ");
        return a.length >= 4 && b.length >= 4
                && Arrays.equals(Arrays.copyOf(a, 4), Arrays.copyOf(b, 4));
    }

    private static ChessGame game(String fen) {
        try {
            return new ChessGame(fen);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean play(ChessGame game, String uci) {
        if (uci == null || !UCI.matcher(uci).matches()) {
            return false;
        }
        try {
            Move move = game.getMove(NotationType.UCI, uci);
            if (move == null || !game.getLegalMoves().contains(move)) {
                return false;
            }
            game.playMove(move);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static JdbcOwnPuzzleStore.Row row(String accountId, NewOwnPuzzle puzzle, String solution) {
        return new JdbcOwnPuzzleStore.Row(
                accountId,
                puzzle.gameSource(),
                puzzle.gameId(),
                puzzle.sourcePly(),
                puzzle.analysisVersionId(),
                puzzle.positionFen(),
                puzzle.previousMoveUci(),
                solution,
                puzzle.label(),
                puzzle.phase(),
                eco(puzzle.openingEco()),
                openingName(puzzle.openingName()),
                puzzle.gamePlayedAt().toEpochMilli(),
                puzzle.tactics().stream()
                        .distinct()
                        .map(tactic -> new JdbcOwnPuzzleStore.TacticRow(tactic.theme(), tactic.kind()))
                        .toList());
    }
}
