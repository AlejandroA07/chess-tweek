# Engine benchmark and analysis profile

Stage 4 Part 2 measures Stockfish on this Mac with the fixed analysis settings and stores an immutable analysis profile. Later analysis versions will reference the profile they used.

## Settings and budgets

The profile fixes these settings: 2 threads, 256 MB hash, `MultiPV=3`, and `UCI_ShowWDL=true`.

1. **Benchmark:** six fixed positions (an opening, three middlegames, an early opening, and an endgame) are each searched for exactly 1,000,000 nodes. The positions are tested as legal.
2. **Speed:** search speed is total nodes divided by measured wall time.
3. **Budgets:** the speed is turned into two fixed node budgets:
   - quick: nodes searchable in about 750 ms
   - deep: nodes searchable in about 3,000 ms
4. **Rounding:** both budgets are rounded to 10,000 nodes and kept within stored bounds.

Analysis will search by node count, not by time, so results are reproducible regardless of what else the machine is doing.

The targets come from measurement on the owner's M4. With these settings, Stockfish 19 searched about 1.05–1.13 million nodes per second. A typical 40-move side needs about 80 quick searches plus deeper re-searches for the few positions with large losses. That comes to roughly 1.8 minutes for one side and 2.6 minutes for both sides, within the planned 1–2 and 2–4 minute targets. The real benchmark completes in about 6 seconds.

A speed outside 10,000–1,000,000,000 nodes per second, a search that reports no nodes, or a search that does not finish within 60 seconds fails the benchmark. In that case no profile is saved.

## Engine access

`StockfishEngineAccess` is now the single way to run Stockfish. It:

- locates the binary
- allows one engine operation at a time
- completes the bounded handshake
- runs the task
- always stops the process

The health check and the benchmark both use it, so a benchmark and a health check can never run at the same time.

## Storage

Migration `V4__game_review_engine_profile.sql` adds `game_review_engine_profile`. Each benchmark inserts a new row, and existing rows never change. The current profile is the newest row. The table belongs to `game-review` and is not account-scoped, because it describes this machine. `CHECK` constraints enforce every bound, including that the deep budget is at least the quick budget.

## Interface

The Admin Stockfish panel has an Analysis profile section. It shows:

- the measured speed
- the quick and deep budgets, in nodes and approximate seconds
- the settings, date, and engine version

When none exists, the section explains the benchmark. **Run Benchmark** runs it, and Test Engine is disabled while it runs. When Stockfish has changed version since the stored benchmark, the section shows a warning asking for a new benchmark. A failed or busy benchmark keeps the previous profile visible.

## Security review

| Check | Verdict |
|---|---|
| Explicit authentication | Pass: `POST /api/engine/benchmarks` and the profile in `GET /api/engine` require the sealed Lichess session; denied callers never reach the engine or the database. |
| Mutation protection | Pass: the benchmark endpoint requires the same local Origin before authorization. |
| Command execution | Pass: unchanged; UCI commands are fixed strings and constant FENs, never request data. |
| Untrusted process output | Pass: node counts are parsed from a strict pattern with at most 15 digits, and the derived speed must fall within fixed bounds. |
| Resource limits | Pass: fixed node counts, a 60-second limit per search, one engine operation at a time, and process shutdown after every run. |
| Parameterized data access | Pass: the profile insert and read use constant SQL with named parameters. |
| Safe errors | Pass: `engine_busy`, `engine_not_ready`, `benchmark_failed`, and `engine_profile_unavailable` disclose no process or database detail. |
| Output encoding | Pass: the client validates every profile bound and React renders values as text. |

## Next

Part 3 implements the analysis rules against a fake engine. That covers expected score from win/draw/loss output, loss thresholds, the second pass, and game phases.
