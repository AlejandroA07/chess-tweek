package com.chesstweek.gamereview.internal.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.EngineProfile;
import com.chesstweek.gamereview.EngineStatus;
import com.chesstweek.gamereview.GameAnalysis;
import com.chesstweek.gamereview.internal.analysis.Evaluation;
import com.chesstweek.gamereview.internal.analysis.GamePhases;
import com.chesstweek.gamereview.internal.analysis.MoveRules;
import com.chesstweek.gamereview.internal.analysis.ReplayedGame;
import com.chesstweek.gamereview.internal.analysis.TwoPassAnalysis;
import com.chesstweek.gamereview.internal.persistence.AnalysisVersionStore;
import com.chesstweek.gamereview.internal.persistence.EngineProfileStore;
import com.chesstweek.gamereview.internal.persistence.GameLookup;

/**
 * Runs one owner-requested analysis at a time in the background. The engine runs with the
 * stored profile's settings and node budgets, and only when it is the engine that was
 * benchmarked. Findings are stored only after every search succeeded, in one transaction, as
 * one new version per analyzed side; a failed or canceled analysis stores nothing.
 */
@Service
class StockfishGameAnalysisService implements GameAnalysis {

    static final Duration SETTINGS_TIMEOUT = Duration.ofSeconds(15);
    static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(60);

    private final StockfishEngineAccess engine;
    private final GameLookup catalog;
    private final EngineProfileStore profiles;
    private final AnalysisVersionStore versions;
    private final Executor executor;
    private final Clock clock;
    private final Object jobLock = new Object();
    private RunningJob latest;

    @Autowired
    StockfishGameAnalysisService(
            StockfishEngineAccess engine,
            GameLookup catalog,
            EngineProfileStore profiles,
            AnalysisVersionStore versions) {
        this(engine, catalog, profiles, versions,
                task -> Thread.ofVirtual().name("game-analysis").start(task),
                Clock.systemUTC());
    }

    StockfishGameAnalysisService(
            StockfishEngineAccess engine,
            GameLookup catalog,
            EngineProfileStore profiles,
            AnalysisVersionStore versions,
            Executor executor,
            Clock clock) {
        this.engine = engine;
        this.catalog = catalog;
        this.profiles = profiles;
        this.versions = versions;
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public StartResult start(String accountId, String gameId, Perspective perspective) {
        CatalogGame game = catalog.find(accountId, gameId).orElse(null);
        if (game == null) {
            return new StartResult(StartResult.Outcome.GAME_NOT_FOUND, null);
        }
        if (!game.analysisEligible()) {
            return new StartResult(StartResult.Outcome.NOT_ELIGIBLE, null);
        }
        ReplayedGame replay;
        try {
            replay = ReplayedGame.fromSan(game.movesSan());
        } catch (IllegalArgumentException exception) {
            return new StartResult(StartResult.Outcome.NOT_ELIGIBLE, null);
        }
        EngineProfile profile = profiles.latest().orElse(null);
        if (profile == null) {
            return new StartResult(StartResult.Outcome.NO_PROFILE, null);
        }
        RunningJob job;
        synchronized (jobLock) {
            if (latest != null && latest.isRunning()) {
                return new StartResult(StartResult.Outcome.BUSY, null);
            }
            job = new RunningJob(accountId, game.id(), perspective, now());
            latest = job;
        }
        executor.execute(() -> run(job, game, replay, profile));
        return new StartResult(StartResult.Outcome.STARTED, job.snapshot());
    }

    @Override
    public Optional<Job> current(String accountId) {
        synchronized (jobLock) {
            return latest != null && latest.accountId.equals(accountId)
                    ? Optional.of(latest.snapshot())
                    : Optional.empty();
        }
    }

    @Override
    public Optional<Job> cancel(String accountId) {
        synchronized (jobLock) {
            if (latest == null || !latest.accountId.equals(accountId) || !latest.isRunning()) {
                return Optional.empty();
            }
            latest.cancelRequested = true;
            return Optional.of(latest.snapshot());
        }
    }

    private void run(RunningJob job, CatalogGame game, ReplayedGame replay, EngineProfile profile) {
        try {
            GamePhases phases = GamePhases.of(game.phaseDivision(), replay);
            Set<ReplayedGame.Color> sides = sides(job.perspective, color(game.ownerColor()));
            var outcome = engine.withReadyEngine((connection, engineName) ->
                    analyze(connection, engineName, profile, replay, phases, sides, job));
            if (outcome.status().state() != EngineStatus.State.READY) {
                job.fail(Failure.ENGINE_UNAVAILABLE, now());
                return;
            }
            EngineRun result = outcome.value();
            if (result == null) {
                job.fail(Failure.ENGINE_FAILED, now());
                return;
            }
            switch (result.ending()) {
                case PROFILE_OUTDATED -> job.fail(Failure.PROFILE_OUTDATED, now());
                case CANCELED -> job.finish(State.CANCELED, null, List.of(), now());
                case SEARCH_FAILED -> job.fail(Failure.ENGINE_FAILED, now());
                case COMPLETED -> store(job, game, profile, phases, result.findings());
            }
        } catch (RuntimeException exception) {
            job.fail(Failure.ENGINE_FAILED, now());
        }
    }

    private EngineRun analyze(
            UciConnection connection,
            String engineName,
            EngineProfile profile,
            ReplayedGame replay,
            GamePhases phases,
            Set<ReplayedGame.Color> sides,
            RunningJob job) throws UciConnection.UciException {
        if (!engineName.equals(profile.engineName())) {
            return EngineRun.ended(EngineRun.Ending.PROFILE_OUTDATED);
        }
        connection.send("setoption name Threads value " + profile.threads());
        connection.send("setoption name Hash value " + profile.hashMb());
        connection.send("setoption name MultiPV value " + profile.multiPv());
        connection.send("setoption name UCI_ShowWDL value true");
        connection.send("ucinewgame");
        connection.send("isready");
        connection.awaitLine("readyok"::equals, line -> {}, SETTINGS_TIMEOUT);
        TwoPassAnalysis.PositionSearch search = (position, nodes) -> search(connection, job, position, nodes);
        try {
            List<TwoPassAnalysis.MoveFinding> findings =
                    new TwoPassAnalysis(search, profile.quickNodes(), profile.deepNodes())
                            .analyze(replay, phases, sides, job);
            return new EngineRun(EngineRun.Ending.COMPLETED, findings);
        } catch (TwoPassAnalysis.SearchFailedException exception) {
            return EngineRun.ended(job.cancelRequested
                    ? EngineRun.Ending.CANCELED
                    : EngineRun.Ending.SEARCH_FAILED);
        }
    }

    private static Evaluation search(
            UciConnection connection, RunningJob job, ReplayedGame.Position position, long nodes)
            throws TwoPassAnalysis.SearchFailedException {
        if (job.cancelRequested) {
            throw new TwoPassAnalysis.SearchFailedException("Analysis canceled");
        }
        List<String> moves = position.movesFromStart();
        try {
            connection.send(moves.isEmpty()
                    ? "position startpos"
                    : "position startpos moves " + String.join(" ", moves));
            connection.send("go nodes " + nodes);
            var output = new UciSearchOutput();
            String bestMove = connection.awaitLine(
                    line -> line.startsWith("bestmove "), output::accept, SEARCH_TIMEOUT);
            Evaluation evaluation = output.finish(bestMove);
            if (evaluation == null) {
                throw new TwoPassAnalysis.SearchFailedException("Engine gave no usable evaluation");
            }
            return evaluation;
        } catch (UciConnection.UciException exception) {
            throw new TwoPassAnalysis.SearchFailedException("Engine search failed");
        }
    }

    private void store(
            RunningJob job,
            CatalogGame game,
            EngineProfile profile,
            GamePhases phases,
            List<TwoPassAnalysis.MoveFinding> findings) {
        job.saving();
        ReplayedGame.Color owner = color(game.ownerColor());
        Instant completedAt = now();
        Set<ReplayedGame.Color> requested = sides(job.perspective, owner);
        var newVersions = new ArrayList<AnalysisVersionStore.NewVersion>();
        // The owner's version is always stored first, so ids follow a stable order.
        for (ReplayedGame.Color side : List.of(owner, opponent(owner))) {
            if (!requested.contains(side)) {
                continue;
            }
            boolean ownerSide = side == owner;
            newVersions.add(new AnalysisVersionStore.NewVersion(
                    job.accountId,
                    game.id(),
                    ownerSide ? AnalysisVersionStore.Side.OWNER : AnalysisVersionStore.Side.OPPONENT,
                    side == ReplayedGame.Color.WHITE ? CatalogGame.Color.WHITE : CatalogGame.Color.BLACK,
                    profile.id(),
                    MoveRules.RULES_VERSION,
                    phases,
                    findings.stream().filter(finding -> finding.move().mover() == side).toList(),
                    job.startedAt,
                    completedAt));
        }
        List<Long> ids;
        try {
            ids = versions.saveAll(newVersions);
        } catch (RuntimeException exception) {
            job.fail(Failure.STORAGE_FAILED, now());
            return;
        }
        job.finish(State.COMPLETED, null, ids, now());
    }

    static Set<ReplayedGame.Color> sides(Perspective perspective, ReplayedGame.Color owner) {
        return switch (perspective) {
            case MY_MOVES -> EnumSet.of(owner);
            case OPPONENT_MOVES -> EnumSet.of(opponent(owner));
            case BOTH -> EnumSet.allOf(ReplayedGame.Color.class);
        };
    }

    private static ReplayedGame.Color opponent(ReplayedGame.Color color) {
        return color == ReplayedGame.Color.WHITE ? ReplayedGame.Color.BLACK : ReplayedGame.Color.WHITE;
    }

    private static ReplayedGame.Color color(CatalogGame.Color color) {
        return color == CatalogGame.Color.WHITE ? ReplayedGame.Color.WHITE : ReplayedGame.Color.BLACK;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private record EngineRun(Ending ending, List<TwoPassAnalysis.MoveFinding> findings) {

        static EngineRun ended(Ending ending) {
            return new EngineRun(ending, List.of());
        }

        enum Ending {
            COMPLETED,
            CANCELED,
            SEARCH_FAILED,
            PROFILE_OUTDATED
        }
    }

    /** Mutable progress of the analysis in flight; read only through {@link #snapshot()}. */
    private static final class RunningJob implements TwoPassAnalysis.Progress {

        private final String accountId;
        private final String gameId;
        private final Perspective perspective;
        private final Instant startedAt;
        private volatile boolean cancelRequested;
        private State state = State.RUNNING;
        private Pass pass = Pass.STARTING;
        private int judged;
        private int total;
        private Failure failure;
        private Instant finishedAt;
        private List<Long> versionIds = List.of();

        RunningJob(String accountId, String gameId, Perspective perspective, Instant startedAt) {
            this.accountId = accountId;
            this.gameId = gameId;
            this.perspective = perspective;
            this.startedAt = startedAt;
        }

        synchronized boolean isRunning() {
            return state == State.RUNNING;
        }

        @Override
        public synchronized void quick(int judged, int total) {
            progress(Pass.QUICK, judged, total);
        }

        @Override
        public synchronized void deep(int judged, int total) {
            progress(Pass.DEEP, judged, total);
        }

        synchronized void saving() {
            pass = Pass.SAVING;
        }

        synchronized void fail(Failure reason, Instant at) {
            finish(State.FAILED, reason, List.of(), at);
        }

        synchronized void finish(State ending, Failure reason, List<Long> ids, Instant at) {
            state = ending;
            failure = reason;
            versionIds = List.copyOf(ids);
            finishedAt = at;
        }

        synchronized Job snapshot() {
            return new Job(gameId, perspective, state, pass, judged, total, failure, startedAt,
                    finishedAt, versionIds);
        }

        private void progress(Pass current, int judgedMoves, int totalMoves) {
            pass = current;
            judged = judgedMoves;
            total = totalMoves;
        }
    }
}
