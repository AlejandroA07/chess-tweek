package com.chesstweek.gamereview.internal.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chesstweek.gamereview.EngineBenchmark;
import com.chesstweek.gamereview.EngineProfile;
import com.chesstweek.gamereview.internal.persistence.EngineProfileStore;

/**
 * Measures search speed with the fixed analysis settings and derives node budgets. Budgets are
 * fixed node counts, so later analyses are reproducible regardless of machine load; the targets
 * only decide how many nodes this machine can search in roughly that much time.
 */
@Service
class StockfishBenchmarkService implements EngineBenchmark {

    static final int THREADS = 2;
    static final int HASH_MB = 256;
    static final int MULTI_PV = 3;
    static final long BENCHMARK_NODES = 1_000_000;
    static final int QUICK_TARGET_MS = 750;
    static final int DEEP_TARGET_MS = 3_000;

    /** Fixed positions covering an opening, middlegames, and an endgame. */
    static final List<String> POSITIONS = List.of(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 10",
            "4rrk1/pp1n3p/3q2pQ/2p1pb2/2PP4/2P3N1/P2B2PP/4RRK1 b - - 7 19",
            "r3r1k1/2p2ppp/p1p1bn2/8/1q2P3/2NPQN2/PPP3PP/R4RK1 b - - 2 15",
            "6k1/6p1/6Pp/ppp5/3pn2P/1P3K2/1PP2P2/3N4 b - - 0 1");

    private static final Pattern NODES = Pattern.compile("(?:^| )nodes (\\d{1,15})(?: |$)");
    private static final Duration SETTINGS_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(60);
    private static final long MIN_NODES_PER_SECOND = 10_000;
    private static final long MAX_NODES_PER_SECOND = 1_000_000_000;

    private final StockfishEngineAccess engine;
    private final EngineProfileStore store;
    private final LongSupplier nanoTime;
    private final Clock clock;

    @Autowired
    StockfishBenchmarkService(StockfishEngineAccess engine, EngineProfileStore store) {
        this(engine, store, System::nanoTime, Clock.systemUTC());
    }

    StockfishBenchmarkService(
            StockfishEngineAccess engine,
            EngineProfileStore store,
            LongSupplier nanoTime,
            Clock clock) {
        this.engine = engine;
        this.store = store;
        this.nanoTime = nanoTime;
        this.clock = clock;
    }

    @Override
    public Optional<EngineProfile> currentProfile() {
        return store.latest();
    }

    @Override
    public BenchmarkResult run() {
        var outcome = engine.withReadyEngine(this::measure);
        EngineProfile measured = outcome.value();
        return new BenchmarkResult(
                outcome.status(), measured == null ? null : store.save(measured));
    }

    private EngineProfile measure(UciConnection connection, String engineName)
            throws UciConnection.UciException {
        connection.send("setoption name Threads value " + THREADS);
        connection.send("setoption name Hash value " + HASH_MB);
        connection.send("setoption name MultiPV value " + MULTI_PV);
        connection.send("setoption name UCI_ShowWDL value true");
        connection.send("isready");
        connection.awaitLine("readyok"::equals, line -> {}, SETTINGS_TIMEOUT);

        long totalNodes = 0;
        long totalNanos = 0;
        for (String fen : POSITIONS) {
            connection.send("ucinewgame");
            connection.send("isready");
            connection.awaitLine("readyok"::equals, line -> {}, SETTINGS_TIMEOUT);
            connection.send("position fen " + fen);
            var searched = new long[1];
            long started = nanoTime.getAsLong();
            connection.send("go nodes " + BENCHMARK_NODES);
            connection.awaitLine(line -> line.startsWith("bestmove "), line -> {
                Matcher nodes = NODES.matcher(line);
                if (line.startsWith("info ") && nodes.find()) {
                    searched[0] = Long.parseLong(nodes.group(1));
                }
            }, SEARCH_TIMEOUT);
            long elapsed = nanoTime.getAsLong() - started;
            if (searched[0] <= 0 || elapsed <= 0) {
                return null;
            }
            totalNodes += searched[0];
            totalNanos += elapsed;
        }

        long nodesPerSecond = Math.round(totalNodes / (totalNanos / 1_000_000_000.0));
        if (nodesPerSecond < MIN_NODES_PER_SECOND || nodesPerSecond > MAX_NODES_PER_SECOND) {
            return null;
        }
        return new EngineProfile(
                0,
                engineName,
                THREADS,
                HASH_MB,
                MULTI_PV,
                POSITIONS.size(),
                BENCHMARK_NODES,
                nodesPerSecond,
                QUICK_TARGET_MS,
                DEEP_TARGET_MS,
                budget(nodesPerSecond, QUICK_TARGET_MS),
                budget(nodesPerSecond, DEEP_TARGET_MS),
                clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }

    /** Nodes searchable in {@code targetMs}, rounded to 10,000 and kept within stored bounds. */
    static long budget(long nodesPerSecond, int targetMs) {
        long nodes = Math.round(nodesPerSecond * (targetMs / 1_000.0) / 10_000.0) * 10_000;
        return Math.clamp(nodes, 10_000, 1_000_000_000);
    }
}
