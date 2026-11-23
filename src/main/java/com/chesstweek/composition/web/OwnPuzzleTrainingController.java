package com.chesstweek.composition.web;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;
import com.chesstweek.training.OwnPuzzleTraining;
import com.chesstweek.training.PuzzleTraining;

/**
 * Own Puzzle training for the connected owner: New and Discarded Own Puzzles, attempts, and the
 * separate Own Puzzles repetition. Writes are same-origin {@code POST}s; responses never include an
 * unrevealed solution.
 */
@RestController
@RequestMapping("/api/training/own-puzzles")
public final class OwnPuzzleTrainingController {

    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    private static final Pattern TACTIC = Pattern.compile("[a-z_]{1,40}");
    private static final List<String> PHASES = List.of("opening", "middlegame", "endgame");
    private static final int MAX_OPENING_FAMILY = 160;
    private static final int MAX_PAGE = 50;

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final OwnPuzzleTraining training;

    OwnPuzzleTrainingController(LichessSessionAuthorizer sessionAuthorizer, OwnPuzzleTraining training) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.training = training;
    }

    /** New and Discarded counts, Training List counts, and the groups; read-only. */
    @GetMapping
    public ResponseEntity<?> overview(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        return read(sealedSession, account -> {
            OwnPuzzleTraining.Overview overview = training.overview(account);
            return TrainingRequests.ok(new OverviewResponse(
                    overview.newPuzzles(),
                    new NewBySourceResponse(overview.newLichess(), overview.newChessCom()),
                    overview.discarded(),
                    lists(overview.lists()),
                    groups(overview.groups())));
        });
    }

    @GetMapping("/new")
    public ResponseEntity<?> newPuzzles(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestParam(name = "source", defaultValue = "all") String source,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        OwnPuzzleTraining.Site site = site(source);
        if (site == null) {
            return TrainingRequests.error(HttpStatus.BAD_REQUEST, TrainingErrorCodes.INVALID_REQUEST);
        }
        return page(sealedSession, offset, limit,
                account -> training.newPuzzles(account, site, offset, limit));
    }

    @GetMapping("/discarded")
    public ResponseEntity<?> discarded(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return page(sealedSession, offset, limit, account -> training.discarded(account, offset, limit));
    }

    @PostMapping("/{ownPuzzleId}/discard")
    public ResponseEntity<?> discard(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("ownPuzzleId") long ownPuzzleId) {
        return write(request, sealedSession, ownPuzzleId > 0,
                account -> changed(training.discard(account, ownPuzzleId)));
    }

    @PostMapping("/{ownPuzzleId}/restore")
    public ResponseEntity<?> restore(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("ownPuzzleId") long ownPuzzleId) {
        return write(request, sealedSession, ownPuzzleId > 0,
                account -> changed(training.restore(account, ownPuzzleId)));
    }

    /** Starts the newest, or a random, New Own Puzzle from one site or all. */
    @PostMapping("/new/attempts")
    public ResponseEntity<?> startNext(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestParam(name = "source", defaultValue = "all") String source,
            @RequestParam(name = "order", defaultValue = "newest") String order) {
        OwnPuzzleTraining.Site site = site(source);
        OwnPuzzleTraining.NewOrder newOrder = order(order);
        return write(request, sealedSession, site != null && newOrder != null, account -> started(
                training.startNext(account, site, newOrder), TrainingErrorCodes.NO_NEW_OWN_PUZZLES));
    }

    /** Starts one New Own Puzzle. */
    @PostMapping("/{ownPuzzleId}/attempts")
    public ResponseEntity<?> start(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("ownPuzzleId") long ownPuzzleId) {
        return write(request, sealedSession, ownPuzzleId > 0, account -> started(
                training.start(account, ownPuzzleId), TrainingErrorCodes.OWN_PUZZLE_NOT_FOUND));
    }

    @PostMapping("/attempts/{attemptId}/moves")
    public ResponseEntity<?> move(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("attemptId") long attemptId,
            @RequestBody(required = false) MoveRequest body) {
        boolean valid = attemptId > 0 && body != null && body.move() != null
                && UCI.matcher(body.move()).matches();
        return write(request, sealedSession, valid, account -> {
            OwnPuzzleTraining.MoveResult result = training.move(account, attemptId, body.move());
            return switch (result.outcome()) {
                case CORRECT, SOLVED, WRONG -> TrainingRequests.ok(new MoveResponse(
                        lower(result.outcome()),
                        result.opponentReply(),
                        result.wrongMoves(),
                        completion(result.completion())));
                case ILLEGAL -> TrainingRequests.error(
                        HttpStatus.BAD_REQUEST, TrainingErrorCodes.ILLEGAL_MOVE);
                case NOT_FOUND -> TrainingRequests.error(
                        HttpStatus.NOT_FOUND, TrainingErrorCodes.ATTEMPT_NOT_FOUND);
                case FINISHED -> TrainingRequests.error(
                        HttpStatus.CONFLICT, TrainingErrorCodes.ATTEMPT_FINISHED);
            };
        });
    }

    @PostMapping("/attempts/{attemptId}/hint")
    public ResponseEntity<?> hint(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("attemptId") long attemptId) {
        return write(request, sealedSession, attemptId > 0, account -> {
            PuzzleTraining.HintResult result = training.hint(account, attemptId);
            return switch (result.outcome()) {
                case SHOWN -> TrainingRequests.ok(new HintResponse(result.fromSquare()));
                case NOT_FOUND -> TrainingRequests.error(
                        HttpStatus.NOT_FOUND, TrainingErrorCodes.ATTEMPT_NOT_FOUND);
                case FINISHED -> TrainingRequests.error(
                        HttpStatus.CONFLICT, TrainingErrorCodes.ATTEMPT_FINISHED);
            };
        });
    }

    @PostMapping("/attempts/{attemptId}/reveal")
    public ResponseEntity<?> reveal(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("attemptId") long attemptId) {
        return write(request, sealedSession, attemptId > 0, account -> {
            OwnPuzzleTraining.RevealResult result = training.reveal(account, attemptId);
            return switch (result.outcome()) {
                case REVEALED -> TrainingRequests.ok(new RevealResponse(
                        result.remainingMoves(), completion(result.completion())));
                case NOT_FOUND -> TrainingRequests.error(
                        HttpStatus.NOT_FOUND, TrainingErrorCodes.ATTEMPT_NOT_FOUND);
                case FINISHED -> TrainingRequests.error(
                        HttpStatus.CONFLICT, TrainingErrorCodes.ATTEMPT_FINISHED);
            };
        });
    }

    /** The account's Own Puzzle Training List sizes; read-only. */
    @GetMapping("/repetition")
    public ResponseEntity<?> repetition(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        return read(sealedSession, account -> TrainingRequests.ok(
                new RepetitionResponse(lists(training.listCounts(account)))));
    }

    /** One page of the Own Puzzles in a Training List, including Mastered; read-only. */
    @GetMapping("/lists/{list}")
    public ResponseEntity<?> inList(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("list") String list,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        PuzzleTraining.TrainingList trainingList = "mastered".equals(list)
                ? PuzzleTraining.TrainingList.MASTERED
                : activeList(list);
        if (trainingList == null) {
            return TrainingRequests.error(HttpStatus.BAD_REQUEST, TrainingErrorCodes.INVALID_REQUEST);
        }
        return page(sealedSession, offset, limit,
                account -> training.inList(account, trainingList, offset, limit));
    }

    /** Mastered Own Puzzles by opening family, phase, and tactic; read-only. */
    @GetMapping("/repetition/mastered")
    public ResponseEntity<?> mastered(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        return read(sealedSession, account -> TrainingRequests.ok(groups(training.mastered(account))));
    }

    @PostMapping("/repetition/{list}/sessions")
    public ResponseEntity<?> startSession(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("list") String list) {
        PuzzleTraining.TrainingList trainingList = activeList(list);
        return write(request, sealedSession, trainingList != null, account ->
                session(training.startSession(account, trainingList)));
    }

    @PostMapping("/repetition/mastered/{kind}/{key}/sessions")
    public ResponseEntity<?> startMasteredSession(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key) {
        OwnPuzzleTraining.GroupKind groupKind = groupKind(kind);
        return write(request, sealedSession, validGroup(groupKind, key), account ->
                session(training.startMasteredSession(account, groupKind, key)));
    }

    @PostMapping("/sessions/{sessionId}/attempts")
    public ResponseEntity<?> nextInSession(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("sessionId") long sessionId) {
        return write(request, sealedSession, sessionId > 0, account -> {
            OwnPuzzleTraining.SessionAttempt next = training.nextInSession(account, sessionId);
            return switch (next.outcome()) {
                case STARTED -> TrainingRequests.created(new SessionAttemptResponse(
                        attempt(next.attempt()), next.position(), next.total()));
                case COMPLETE -> TrainingRequests.error(
                        HttpStatus.CONFLICT, TrainingErrorCodes.SESSION_COMPLETE);
                case NOT_FOUND -> TrainingRequests.error(
                        HttpStatus.NOT_FOUND, TrainingErrorCodes.SESSION_NOT_FOUND);
            };
        });
    }

    private ResponseEntity<?> read(String sealedSession, Function<String, ResponseEntity<?>> action) {
        return TrainingRequests.read(sessionAuthorizer, sealedSession, action);
    }

    private ResponseEntity<?> write(
            HttpServletRequest request,
            String sealedSession,
            boolean valid,
            Function<String, ResponseEntity<?>> action) {
        return TrainingRequests.write(request, sessionAuthorizer, sealedSession, valid, action);
    }

    /** Input is validated before the session is checked, as for writes. */
    private ResponseEntity<?> page(
            String sealedSession,
            int offset,
            int limit,
            Function<String, OwnPuzzleTraining.Page> query) {
        if (offset < 0 || limit < 1 || limit > MAX_PAGE) {
            return TrainingRequests.error(HttpStatus.BAD_REQUEST, TrainingErrorCodes.INVALID_REQUEST);
        }
        return read(sealedSession, account -> {
            OwnPuzzleTraining.Page page = query.apply(account);
            return TrainingRequests.ok(new PageResponse(
                    page.total(),
                    page.puzzles().stream().map(OwnPuzzleTrainingController::puzzle).toList()));
        });
    }

    private static ResponseEntity<?> changed(OwnPuzzleTraining.ChangeOutcome outcome) {
        return switch (outcome) {
            case DONE -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            case NOT_FOUND -> TrainingRequests.error(
                    HttpStatus.NOT_FOUND, TrainingErrorCodes.OWN_PUZZLE_NOT_FOUND);
            case CLASSIFIED -> TrainingRequests.error(
                    HttpStatus.CONFLICT, TrainingErrorCodes.OWN_PUZZLE_CLASSIFIED);
            case NOT_DISCARDED -> TrainingRequests.error(
                    HttpStatus.CONFLICT, TrainingErrorCodes.OWN_PUZZLE_NOT_DISCARDED);
        };
    }

    private static ResponseEntity<?> started(Optional<OwnPuzzleTraining.Attempt> attempt, String missing) {
        return attempt.<ResponseEntity<?>>map(value -> TrainingRequests.created(attempt(value)))
                .orElseGet(() -> TrainingRequests.error(HttpStatus.NOT_FOUND, missing));
    }

    private static ResponseEntity<?> session(Optional<PuzzleTraining.Session> session) {
        return session.<ResponseEntity<?>>map(value -> TrainingRequests.created(
                        new SessionResponse(value.sessionId(), lower(value.list()), value.total())))
                .orElseGet(() -> TrainingRequests.error(
                        HttpStatus.NOT_FOUND, TrainingErrorCodes.NO_PUZZLE_AVAILABLE));
    }

    private static AttemptResponse attempt(OwnPuzzleTraining.Attempt value) {
        return new AttemptResponse(
                value.attemptId(),
                puzzle(value.puzzle()),
                value.positionFen(),
                value.previousMove(),
                value.ownerMoves(),
                value.fastSolveLimitMillis(),
                value.currentList() == null ? null : lower(value.currentList()));
    }

    private static CompletionResponse completion(OwnPuzzleTraining.Completion completion) {
        if (completion == null) {
            return null;
        }
        return new CompletionResponse(
                completion.previousList() == null ? null : lower(completion.previousList()),
                lower(completion.performance().list()),
                completion.performance().successes(),
                completion.performance().strikes(),
                completion.wrongMoves(),
                completion.hintUsed(),
                completion.elapsedMillis(),
                completion.fastSolveLimitMillis(),
                source(completion.sourceGame()));
    }

    private static PuzzleResponse puzzle(OwnPuzzleTraining.OwnPuzzle puzzle) {
        return new PuzzleResponse(
                puzzle.id(),
                puzzle.label(),
                puzzle.phase(),
                puzzle.openingFamily(),
                puzzle.openingName(),
                puzzle.tactics().stream()
                        .map(tactic -> new TacticResponse(tactic.theme(), tactic.kind()))
                        .toList(),
                source(puzzle.sourceGame()),
                puzzle.gamePlayedAt().toString());
    }

    private static SourceGameResponse source(OwnPuzzleTraining.SourceGame game) {
        return new SourceGameResponse(game.source(), game.gameId(), game.ply());
    }

    private static List<ListCountResponse> lists(List<PuzzleTraining.ListCount> counts) {
        return counts.stream()
                .map(count -> new ListCountResponse(lower(count.list()), count.puzzles()))
                .toList();
    }

    private static GroupsResponse groups(OwnPuzzleTraining.Groups groups) {
        return new GroupsResponse(
                groups.total(),
                groupCounts(groups.openings()),
                groupCounts(groups.phases()),
                groupCounts(groups.tactics()));
    }

    private static List<GroupCountResponse> groupCounts(List<OwnPuzzleTraining.GroupCount> counts) {
        return counts.stream().map(count -> new GroupCountResponse(count.key(), count.puzzles())).toList();
    }

    /** The four lists a repetition Practice Session can cover; Mastered is practiced by group. */
    private static PuzzleTraining.TrainingList activeList(String list) {
        return switch (list) {
            case "hard_to_solve" -> PuzzleTraining.TrainingList.HARD_TO_SOLVE;
            case "wrong_moves" -> PuzzleTraining.TrainingList.WRONG_MOVES;
            case "worth_repeating" -> PuzzleTraining.TrainingList.WORTH_REPEATING;
            case "retiring" -> PuzzleTraining.TrainingList.RETIRING;
            default -> null;
        };
    }

    private static OwnPuzzleTraining.Site site(String source) {
        return switch (source) {
            case "all" -> OwnPuzzleTraining.Site.ALL;
            case "lichess" -> OwnPuzzleTraining.Site.LICHESS;
            case "chesscom" -> OwnPuzzleTraining.Site.CHESSCOM;
            default -> null;
        };
    }

    private static OwnPuzzleTraining.NewOrder order(String order) {
        return switch (order) {
            case "newest" -> OwnPuzzleTraining.NewOrder.NEWEST;
            case "random" -> OwnPuzzleTraining.NewOrder.RANDOM;
            default -> null;
        };
    }

    private static OwnPuzzleTraining.GroupKind groupKind(String kind) {
        return switch (kind) {
            case "opening" -> OwnPuzzleTraining.GroupKind.OPENING;
            case "phase" -> OwnPuzzleTraining.GroupKind.PHASE;
            case "tactic" -> OwnPuzzleTraining.GroupKind.TACTIC;
            default -> null;
        };
    }

    private static boolean validGroup(OwnPuzzleTraining.GroupKind kind, String key) {
        if (kind == null || key == null) {
            return false;
        }
        return switch (kind) {
            case PHASE -> PHASES.contains(key);
            case TACTIC -> TACTIC.matcher(key).matches();
            case OPENING -> !key.isBlank() && key.length() <= MAX_OPENING_FAMILY
                    && key.equals(key.strip())
                    && key.chars().noneMatch(Character::isISOControl);
        };
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    public record MoveRequest(String move) {}

    public record ListCountResponse(String list, int puzzles) {}

    public record GroupCountResponse(String key, int puzzles) {}

    public record GroupsResponse(
            int total,
            List<GroupCountResponse> openings,
            List<GroupCountResponse> phases,
            List<GroupCountResponse> tactics) {}

    public record NewBySourceResponse(int lichess, int chesscom) {}

    public record OverviewResponse(
            int newPuzzles,
            NewBySourceResponse newBySource,
            int discarded,
            List<ListCountResponse> lists,
            GroupsResponse groups) {}

    public record RepetitionResponse(List<ListCountResponse> lists) {}

    public record TacticResponse(String theme, String kind) {}

    public record SourceGameResponse(String source, String gameId, int ply) {}

    public record PuzzleResponse(
            long id,
            String label,
            String phase,
            String openingFamily,
            String openingName,
            List<TacticResponse> tactics,
            SourceGameResponse sourceGame,
            String gamePlayedAt) {}

    public record PageResponse(int total, List<PuzzleResponse> puzzles) {}

    public record AttemptResponse(
            long attemptId,
            PuzzleResponse puzzle,
            String positionFen,
            String previousMove,
            int ownerMoves,
            long fastSolveLimitMillis,
            String currentList) {}

    public record CompletionResponse(
            String previousList,
            String list,
            int successes,
            int strikes,
            int wrongMoves,
            boolean hintUsed,
            long elapsedMillis,
            long fastSolveLimitMillis,
            SourceGameResponse sourceGame) {}

    public record MoveResponse(
            String outcome, String opponentReply, int wrongMoves, CompletionResponse completion) {}

    public record HintResponse(String fromSquare) {}

    public record RevealResponse(List<String> remainingMoves, CompletionResponse completion) {}

    public record SessionResponse(long sessionId, String list, int total) {}

    public record SessionAttemptResponse(AttemptResponse attempt, int position, int total) {}
}
