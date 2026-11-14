package com.chesstweek.gamereview.internal.engine;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.EngineProfile;
import com.chesstweek.gamereview.GameAnalysis;
import com.chesstweek.gamereview.GameAnalysis.Failure;
import com.chesstweek.gamereview.GameAnalysis.Perspective;
import com.chesstweek.gamereview.GameAnalysis.StartResult.Outcome;
import com.chesstweek.gamereview.GameAnalysis.State;
import com.chesstweek.gamereview.internal.analysis.MoveRules;
import com.chesstweek.gamereview.internal.analysis.ReplayedGame;
import com.chesstweek.gamereview.internal.persistence.AnalysisVersionStore;
import com.chesstweek.gamereview.internal.persistence.EngineProfileStore;
import com.chesstweek.gamereview.internal.persistence.GameLookup;

import static org.assertj.core.api.Assertions.assertThat;

class StockfishGameAnalysisServiceTest {

    private static final String ACCOUNT = "owner";
    private static final String GAME_ID = "abcd1234";
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00.123456Z");
    private static final EngineProfile PROFILE = new EngineProfile(
            7, "Stockfish 19", 2, 256, 3, 6, 1_000_000, 1_180_000, 750, 3_000,
            880_000, 3_540_000, Instant.parse("2026-11-14T09:00:00Z"));

    private final Catalog catalog = new Catalog();
    private final Profiles profiles = new Profiles();
    private final Versions versions = new Versions();
    private final List<Runnable> pending = new ArrayList<>();

    @Test
    void analyzesTheOwnersMovesWithTheProfileAndStoresOneOwnerVersion(@TempDir Path directory)
            throws Exception {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5 Nf3 Nc6", true));
        var engine = levelEngine("Stockfish 19");
        var service = service(directory, engine);

        GameAnalysis.StartResult started = service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES);
        assertThat(started.outcome()).isEqualTo(Outcome.STARTED);
        assertThat(started.job().state()).isEqualTo(State.RUNNING);
        runPending();

        GameAnalysis.Job job = service.current(ACCOUNT).orElseThrow();
        assertThat(job.state()).isEqualTo(State.COMPLETED);
        assertThat(job.versionIds()).containsExactly(1L);
        assertThat(job.finishedAt()).isEqualTo(Instant.parse("2026-11-14T12:00:00.123Z"));

        AnalysisVersionStore.NewVersion version = versions.saved.getFirst();
        assertThat(version.side()).isEqualTo(AnalysisVersionStore.Side.OWNER);
        assertThat(version.requestedPerspective()).isEqualTo(Perspective.MY_MOVES);
        assertThat(version.analyzedColor()).isEqualTo(CatalogGame.Color.WHITE);
        assertThat(version.profileId()).isEqualTo(7);
        assertThat(version.rulesVersion()).isEqualTo(MoveRules.RULES_VERSION);
        assertThat(version.findings()).extracting(finding -> finding.move().san())
                .containsExactly("e4", "Nf3");

        assertThat(engine.received()).containsSubsequence(
                "setoption name Threads value 2",
                "setoption name Hash value 256",
                "setoption name MultiPV value 3",
                "setoption name UCI_ShowWDL value true",
                "ucinewgame",
                "position startpos",
                "go nodes 880000",
                "position startpos moves e2e4",
                "go nodes 880000");
        assertThat(engine.received().getLast()).isEqualTo("quit");
        assertThat(engine.stopped()).isTrue();
    }

    @Test
    void bothSidesStoreSeparateOwnerAndOpponentVersionsOwnerFirst(@TempDir Path directory)
            throws Exception {
        catalog.add(game(CatalogGame.Color.BLACK, "e4 e5 Nf3 Nc6", true));
        var service = service(directory, levelEngine("Stockfish 19"));

        service.start(ACCOUNT, GAME_ID, Perspective.BOTH);
        runPending();

        assertThat(service.current(ACCOUNT).orElseThrow().versionIds()).containsExactly(1L, 2L);
        AnalysisVersionStore.NewVersion owner = versions.saved.get(0);
        AnalysisVersionStore.NewVersion opponent = versions.saved.get(1);
        assertThat(owner.side()).isEqualTo(AnalysisVersionStore.Side.OWNER);
        assertThat(owner.analyzedColor()).isEqualTo(CatalogGame.Color.BLACK);
        assertThat(owner.findings()).extracting(finding -> finding.move().mover())
                .containsOnly(ReplayedGame.Color.BLACK);
        assertThat(opponent.side()).isEqualTo(AnalysisVersionStore.Side.OPPONENT);
        assertThat(opponent.findings()).extracting(finding -> finding.move().mover())
                .containsOnly(ReplayedGame.Color.WHITE);
    }

    @Test
    void refusesUnknownIneligibleOrUnprofiledGamesBeforeStarting(@TempDir Path directory)
            throws Exception {
        var service = service(directory, levelEngine("Stockfish 19"));
        assertThat(service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES).outcome())
                .isEqualTo(Outcome.GAME_NOT_FOUND);

        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5", false));
        assertThat(service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES).outcome())
                .isEqualTo(Outcome.NOT_ELIGIBLE);

        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5", true));
        profiles.profile = null;
        assertThat(service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES).outcome())
                .isEqualTo(Outcome.NO_PROFILE);
        assertThat(pending).isEmpty();
        assertThat(service.current(ACCOUNT)).isEmpty();
    }

    @Test
    void anotherGameCannotStartWhileOneIsRunning(@TempDir Path directory) throws Exception {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5", true));
        var service = service(directory, levelEngine("Stockfish 19"));

        assertThat(service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES).outcome())
                .isEqualTo(Outcome.STARTED);
        assertThat(service.start(ACCOUNT, GAME_ID, Perspective.BOTH).outcome())
                .isEqualTo(Outcome.BUSY);

        runPending();
        assertThat(service.start(ACCOUNT, GAME_ID, Perspective.BOTH).outcome())
                .isEqualTo(Outcome.STARTED);
    }

    @Test
    void missingStockfishFailsRecoverablyAndStoresNothing(@TempDir Path directory) {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5", true));
        var access = new StockfishEngineAccess(
                new StockfishLocator(
                        null, directory.resolve("bin/stockfish"), directory.resolve("Cellar/stockfish")),
                executable -> {
                    throw new AssertionError("must not launch");
                },
                Duration.ofMillis(300));
        var service = new StockfishGameAnalysisService(
                access, catalog, profiles, versions, pending::add, clock());

        service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES);
        runPending();

        GameAnalysis.Job job = service.current(ACCOUNT).orElseThrow();
        assertThat(job.state()).isEqualTo(State.FAILED);
        assertThat(job.failure()).isEqualTo(Failure.ENGINE_UNAVAILABLE);
        assertThat(versions.saved).isEmpty();
    }

    @Test
    void aDifferentStockfishThanTheBenchmarkedOneNeedsANewBenchmark(@TempDir Path directory)
            throws Exception {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5", true));
        var engine = levelEngine("Stockfish 20");
        var service = service(directory, engine);

        service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES);
        runPending();

        assertThat(service.current(ACCOUNT).orElseThrow().failure())
                .isEqualTo(Failure.PROFILE_OUTDATED);
        assertThat(engine.received()).noneMatch(command -> command.startsWith("go "));
        assertThat(versions.saved).isEmpty();
    }

    @Test
    void anUnusableSearchFailsTheAnalysisAndStoresNothing(@TempDir Path directory)
            throws Exception {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5 Nf3 Nc6", true));
        var engine = engine("Stockfish 19", position -> List.of(
                "info depth 5 multipv 1 score cp 10 wdl 50 900 50 pv a2a3", "bestmove h2h3"));
        var service = service(directory, engine);

        service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES);
        runPending();

        GameAnalysis.Job job = service.current(ACCOUNT).orElseThrow();
        assertThat(job.failure()).isEqualTo(Failure.ENGINE_FAILED);
        assertThat(versions.saved).isEmpty();
        assertThat(engine.stopped()).isTrue();
    }

    @Test
    void aCrashedEngineFailsTheAnalysisAndStoresNothing(@TempDir Path directory) throws Exception {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5", true));
        FakeEngineProcess[] engine = new FakeEngineProcess[1];
        engine[0] = engine("Stockfish 19", position -> {
            engine[0].stop(Duration.ZERO);
            return List.of();
        });
        var service = service(directory, engine[0]);

        service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES);
        runPending();

        assertThat(service.current(ACCOUNT).orElseThrow().failure()).isEqualTo(Failure.ENGINE_FAILED);
        assertThat(versions.saved).isEmpty();
    }

    @Test
    void cancelingStopsBeforeTheNextSearchAndStoresNothing(@TempDir Path directory)
            throws Exception {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5 Nf3 Nc6", true));
        StockfishGameAnalysisService[] service = new StockfishGameAnalysisService[1];
        var searches = new int[1];
        var engine = engine("Stockfish 19", position -> {
            if (++searches[0] == 2) {
                service[0].cancel(ACCOUNT);
            }
            return level(position);
        });
        service[0] = service(directory, engine);

        service[0].start(ACCOUNT, GAME_ID, Perspective.MY_MOVES);
        runPending();

        assertThat(service[0].current(ACCOUNT).orElseThrow().state()).isEqualTo(State.CANCELED);
        assertThat(searches[0]).isEqualTo(2);
        assertThat(versions.saved).isEmpty();
        assertThat(service[0].cancel(ACCOUNT)).isEmpty();
    }

    @Test
    void aCancelAfterTheLastSearchStillStoresNothing(@TempDir Path directory) throws Exception {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5", true));
        StockfishGameAnalysisService[] service = new StockfishGameAnalysisService[1];
        var searches = new int[1];
        var engine = engine("Stockfish 19", position -> {
            // White's only move needs two searches; cancel during the last one.
            if (++searches[0] == 2) {
                service[0].cancel(ACCOUNT);
            }
            return level(position);
        });
        service[0] = service(directory, engine);

        service[0].start(ACCOUNT, GAME_ID, Perspective.MY_MOVES);
        runPending();

        assertThat(searches[0]).isEqualTo(2);
        assertThat(service[0].current(ACCOUNT).orElseThrow().state()).isEqualTo(State.CANCELED);
        assertThat(versions.saved).isEmpty();
    }

    @Test
    void anEngineHeldByAHealthCheckIsReportedAsBusy(@TempDir Path directory) throws Exception {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5", true));
        var access = new StockfishEngineAccess(
                StockfishHealthServiceTest.locator(directory),
                executable -> levelEngine("Stockfish 19"),
                Duration.ofMillis(300));
        var service = new StockfishGameAnalysisService(
                access, catalog, profiles, versions, pending::add, clock());
        service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES);

        // Another engine operation holds the single engine slot while the analysis runs.
        access.withReadyEngine((connection, name) -> {
            Thread worker = Thread.ofVirtual().start(this::runPending);
            try {
                worker.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        assertThat(service.current(ACCOUNT).orElseThrow().failure()).isEqualTo(Failure.ENGINE_BUSY);
        assertThat(versions.saved).isEmpty();
    }

    @Test
    void anUnexpectedErrorNeverLeavesTheAnalysisRunning(@TempDir Path directory) throws Exception {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5", true));
        versions.error = true;
        var service = service(directory, levelEngine("Stockfish 19"));

        service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES);
        try {
            runPending();
        } catch (AssertionError expected) {
            // The error still surfaces; the job must not stay running.
        }

        assertThat(service.current(ACCOUNT).orElseThrow().state()).isEqualTo(State.FAILED);
        versions.error = false;
        assertThat(service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES).outcome())
                .isEqualTo(Outcome.STARTED);
    }

    @Test
    void aRejectedBackgroundStartFailsInsteadOfBlockingLaterAnalyses(@TempDir Path directory)
            throws Exception {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5", true));
        var access = new StockfishEngineAccess(
                StockfishHealthServiceTest.locator(directory),
                executable -> levelEngine("Stockfish 19"),
                Duration.ofMillis(300));
        var service = new StockfishGameAnalysisService(access, catalog, profiles, versions, task -> {
            throw new java.util.concurrent.RejectedExecutionException("no threads");
        }, clock());

        GameAnalysis.StartResult result = service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES);

        assertThat(result.job().state()).isEqualTo(State.FAILED);
        assertThat(service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES).job().state())
                .isEqualTo(State.FAILED);
    }

    @Test
    void aStorageFailureIsReportedAndLeavesNothingBehind(@TempDir Path directory) throws Exception {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5", true));
        versions.fail = true;
        var service = service(directory, levelEngine("Stockfish 19"));

        service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES);
        runPending();

        assertThat(service.current(ACCOUNT).orElseThrow().failure()).isEqualTo(Failure.STORAGE_FAILED);
    }

    @Test
    void anotherAccountCannotSeeOrCancelTheAnalysis(@TempDir Path directory) throws Exception {
        catalog.add(game(CatalogGame.Color.WHITE, "e4 e5", true));
        var service = service(directory, levelEngine("Stockfish 19"));

        service.start(ACCOUNT, GAME_ID, Perspective.MY_MOVES);

        assertThat(service.current("someone")).isEmpty();
        assertThat(service.cancel("someone")).isEmpty();
        assertThat(service.current(ACCOUNT).orElseThrow().state()).isEqualTo(State.RUNNING);
    }

    private void runPending() {
        while (!pending.isEmpty()) {
            pending.removeFirst().run();
        }
    }

    private StockfishGameAnalysisService service(Path directory, FakeEngineProcess engine)
            throws Exception {
        var access = new StockfishEngineAccess(
                StockfishHealthServiceTest.locator(directory), executable -> engine,
                Duration.ofMillis(300));
        return new StockfishGameAnalysisService(
                access, catalog, profiles, versions, pending::add, clock());
    }

    private static FakeEngineProcess levelEngine(String name) {
        return engine(name, StockfishGameAnalysisServiceTest::level);
    }

    /** Every position is dead level with a quiet best move that is never the move played. */
    private static List<String> level(String position) {
        return List.of(
                "info depth 12 seldepth 18 multipv 1 score cp 0 wdl 0 1000 0 nodes 880000 pv a2a3",
                "bestmove a2a3");
    }

    private static FakeEngineProcess engine(String name, Function<String, List<String>> search) {
        String[] position = new String[1];
        return new FakeEngineProcess(command -> {
            if (command.equals("uci")) {
                return List.of("id name " + name, "uciok");
            }
            if (command.equals("isready")) {
                return List.of("readyok");
            }
            if (command.startsWith("position ")) {
                position[0] = command;
                return List.of();
            }
            if (command.startsWith("go ")) {
                return search.apply(position[0]);
            }
            return List.of();
        });
    }

    private static CatalogGame game(CatalogGame.Color ownerColor, String moves, boolean eligible) {
        return new CatalogGame(
                GAME_ID,
                Instant.parse("2026-11-13T10:00:00Z"),
                Instant.parse("2026-11-13T10:10:00Z"),
                CatalogGame.Status.RESIGN,
                CatalogGame.Result.WIN,
                ownerColor,
                1500,
                null,
                new CatalogGame.Opponent(CatalogGame.OpponentKind.USER, "rival", "Rival", 1480, null, null),
                CatalogGame.Speed.BLITZ,
                true,
                eligible ? CatalogGame.Variant.STANDARD : CatalogGame.Variant.CHESS960,
                new CatalogGame.TimeControl(300, 0, null),
                null,
                null,
                moves,
                null,
                List.of(),
                eligible);
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static final class Catalog implements GameLookup {

        private final Map<String, CatalogGame> games = new HashMap<>();

        void add(CatalogGame game) {
            games.put(game.id(), game);
        }

        @Override
        public Optional<CatalogGame> find(String accountId, String gameId) {
            return ACCOUNT.equals(accountId) ? Optional.ofNullable(games.get(gameId)) : Optional.empty();
        }
    }

    private static final class Profiles implements EngineProfileStore {

        EngineProfile profile = PROFILE;

        @Override
        public EngineProfile save(EngineProfile saved) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EngineProfile> latest() {
            return Optional.ofNullable(profile);
        }
    }

    private static final class Versions implements AnalysisVersionStore {

        final List<NewVersion> saved = new ArrayList<>();
        boolean fail;
        boolean error;

        @Override
        public List<Long> saveAll(List<NewVersion> versions) {
            if (error) {
                throw new AssertionError("unexpected");
            }
            if (fail) {
                throw new IllegalStateException("disk full");
            }
            var ids = new ArrayList<Long>();
            for (NewVersion version : versions) {
                saved.add(version);
                ids.add((long) saved.size());
            }
            return ids;
        }
    }
}
