package com.chesstweek.gamereview.internal.engine;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import io.github.wolfraam.chessgame.ChessGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chesstweek.gamereview.EngineBenchmark;
import com.chesstweek.gamereview.EngineProfile;
import com.chesstweek.gamereview.EngineStatus;
import com.chesstweek.gamereview.internal.persistence.EngineProfileStore;

import static org.assertj.core.api.Assertions.assertThat;

class StockfishBenchmarkServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T09:00:00.123456Z");

    private final AtomicLong nanos = new AtomicLong();
    private final InMemoryStore store = new InMemoryStore();

    @Test
    void measuresTheFixedPositionsWithAnalysisSettingsAndStoresAProfile(@TempDir Path directory)
            throws Exception {
        var engine = benchmarkEngine(command -> {
            nanos.addAndGet(1_000_000_000L);
            return List.of(
                    "info depth 10 seldepth 14 multipv 1 score cp 20 wdl 50 900 50 nodes 400000 nps 900000",
                    "info depth 18 seldepth 30 multipv 1 score cp 27 wdl 52 942 6 nodes 1000000 nps 1000000 time 1000 pv e2e4 e7e5",
                    "bestmove e2e4 ponder e7e5");
        });

        EngineBenchmark.BenchmarkResult result = service(directory, engine).run();

        assertThat(result.engine().state()).isEqualTo(EngineStatus.State.READY);
        EngineProfile profile = result.profile();
        assertThat(profile.id()).isEqualTo(1);
        assertThat(profile.engineName()).isEqualTo("Stockfish 19");
        assertThat(profile.threads()).isEqualTo(2);
        assertThat(profile.hashMb()).isEqualTo(256);
        assertThat(profile.multiPv()).isEqualTo(3);
        assertThat(profile.benchmarkPositions()).isEqualTo(6);
        assertThat(profile.benchmarkNodesPerPosition()).isEqualTo(1_000_000);
        assertThat(profile.nodesPerSecond()).isEqualTo(1_000_000);
        assertThat(profile.quickTargetMs()).isEqualTo(750);
        assertThat(profile.deepTargetMs()).isEqualTo(3_000);
        assertThat(profile.quickNodes()).isEqualTo(750_000);
        assertThat(profile.deepNodes()).isEqualTo(3_000_000);
        assertThat(profile.createdAt()).isEqualTo(Instant.parse("2026-11-14T09:00:00.123Z"));
        assertThat(store.saved).containsExactly(profile);

        List<String> commands = engine.received();
        assertThat(commands).containsSubsequence(
                "uci",
                "isready",
                "setoption name Threads value 2",
                "setoption name Hash value 256",
                "setoption name MultiPV value 3",
                "setoption name UCI_ShowWDL value true",
                "isready");
        assertThat(commands.stream().filter(command -> command.startsWith("position fen ")))
                .hasSize(6);
        assertThat(commands.stream().filter("go nodes 1000000"::equals)).hasSize(6);
        assertThat(commands.getLast()).isEqualTo("quit");
        assertThat(engine.stopped()).isTrue();
    }

    @Test
    void storesNothingWhenStockfishIsUnavailable(@TempDir Path directory) {
        var access = new StockfishEngineAccess(
                new StockfishLocator(
                        null,
                        directory.resolve("bin/stockfish"),
                        directory.resolve("Cellar/stockfish")),
                executable -> {
                    throw new AssertionError("must not launch");
                },
                Duration.ofMillis(300));

        var result = new StockfishBenchmarkService(access, store, nanos::get, clock()).run();

        assertThat(result.engine().state()).isEqualTo(EngineStatus.State.NOT_INSTALLED);
        assertThat(result.profile()).isNull();
        assertThat(store.saved).isEmpty();
    }

    @Test
    void storesNothingWhenSearchesReportNoNodes(@TempDir Path directory) throws Exception {
        var engine = benchmarkEngine(command -> {
            nanos.addAndGet(1_000_000_000L);
            return List.of("info string NNUE evaluation enabled", "bestmove e2e4");
        });

        var result = service(directory, engine).run();

        assertThat(result.engine().state()).isEqualTo(EngineStatus.State.READY);
        assertThat(result.profile()).isNull();
        assertThat(store.saved).isEmpty();
        assertThat(engine.stopped()).isTrue();
    }

    @Test
    void storesNothingForAnImplausibleSpeed(@TempDir Path directory) throws Exception {
        var engine = benchmarkEngine(command -> {
            nanos.addAndGet(1);
            return List.of("info depth 1 nodes 1000000", "bestmove e2e4");
        });

        assertThat(service(directory, engine).run().profile()).isNull();
        assertThat(store.saved).isEmpty();
    }

    @Test
    void storesNothingWhenASearchNeverFinishes(@TempDir Path directory) throws Exception {
        var engine = benchmarkEngine(command -> List.of("info depth 1 nodes 1000"));
        var access = new StockfishEngineAccess(
                StockfishHealthServiceTest.locator(directory), executable -> engine,
                Duration.ofMillis(300));
        var service = new StockfishBenchmarkService(access, store, nanos::get, clock());

        // The search timeout is long; stopping the fake ends its output, which fails the wait.
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            engine.stop(Duration.ZERO);
        });

        var result = service.run();

        assertThat(result.profile()).isNull();
        assertThat(store.saved).isEmpty();
    }

    @Test
    void returnsTheLatestStoredProfile(@TempDir Path directory) throws Exception {
        assertThat(service(directory, benchmarkEngine(command -> List.of())).currentProfile())
                .isEmpty();
    }

    @Test
    void derivesRoundedBoundedBudgets() {
        assertThat(StockfishBenchmarkService.budget(1_066_879, 750)).isEqualTo(800_000);
        assertThat(StockfishBenchmarkService.budget(1_066_879, 3_000)).isEqualTo(3_200_000);
        assertThat(StockfishBenchmarkService.budget(10_000, 750)).isEqualTo(10_000);
        assertThat(StockfishBenchmarkService.budget(1_000_000_000, 3_000)).isEqualTo(1_000_000_000);
    }

    @Test
    void benchmarkPositionsAreLegalWithMovesToSearch() {
        for (String fen : StockfishBenchmarkService.POSITIONS) {
            assertThat(new ChessGame(fen).getLegalMoves()).as(fen).isNotEmpty();
        }
    }

    private StockfishBenchmarkService service(Path directory, FakeEngineProcess engine)
            throws Exception {
        var access = new StockfishEngineAccess(
                StockfishHealthServiceTest.locator(directory), executable -> engine,
                Duration.ofMillis(300));
        return new StockfishBenchmarkService(access, store, nanos::get, clock());
    }

    private static FakeEngineProcess benchmarkEngine(Function<String, List<String>> search) {
        return new FakeEngineProcess(command -> {
            if (command.equals("uci")) {
                return List.of("id name Stockfish 19", "uciok");
            }
            if (command.equals("isready")) {
                return List.of("readyok");
            }
            if (command.startsWith("go ")) {
                return search.apply(command);
            }
            return List.of();
        });
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static final class InMemoryStore implements EngineProfileStore {

        private final List<EngineProfile> saved = new ArrayList<>();

        @Override
        public EngineProfile save(EngineProfile profile) {
            EngineProfile stored = profile.withId(saved.size() + 1);
            saved.add(stored);
            return stored;
        }

        @Override
        public Optional<EngineProfile> latest() {
            return saved.isEmpty() ? Optional.empty() : Optional.of(saved.getLast());
        }
    }
}
