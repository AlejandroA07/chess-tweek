# Stockfish detection and health check

Stage 4 Part 1 finds the local Stockfish binary, confirms that it answers the UCI protocol, and shows the result on the Admin page. No analysis runs yet.

## Detection

`StockfishLocator` checks one location without starting anything:

- **Configured path:** when `chess-tweek.stockfish.path` is set, it must be an absolute path to an executable regular file. Local configuration is trusted, so any location is accepted.
- **Standard path:** otherwise, the standard Homebrew path `/opt/homebrew/bin/stockfish` must resolve, through Homebrew's symbolic link, to an executable regular file inside `/opt/homebrew/Cellar/stockfish`. A link that points anywhere else is refused.

The adapter launches the resolved path. The result is one of these states: not checked, ready, not installed, not executable, unsupported engine, not responding, or busy.

## Health check

`StockfishHealthService` performs a bounded UCI handshake:

1. **Launch:** the binary is started directly, with no shell and no arguments. Its error stream is discarded.
2. **Handshake:** the service sends `uci` and waits up to five seconds for `uciok`, reading the `id name` line on the way.
3. **Identity:** the name must match `Stockfish` followed by up to 48 safe characters. Any other engine is reported as unsupported.
4. **Readiness:** the service sends `isready` and waits up to five seconds for `readyok`.
5. **Shutdown:** the service always sends `quit`, closes the engine's input, and stops the process. It is killed if it has not exited within two seconds.

`UciConnection` reads engine output on a virtual thread so every wait has a deadline. A line longer than 4,096 characters, more than 2,048 unread lines, or the end of output fails the exchange instead of growing memory or blocking.

Only one engine operation runs at a time. A second request while one is running is reported as busy rather than starting another process. The last result is kept in memory, so the Admin page shows it until the application restarts or the binary changes.

## Interface

When Lichess is connected, the Admin page shows a Stockfish panel. It lists status (with text and a status mark), version, and location, and explains each state. When Stockfish is missing, it shows the `brew install stockfish` command. The **Test Engine** button runs the health check. A busy engine or a failed request is reported without losing the last known status.

## Security review

| Check | Verdict |
|---|---|
| Explicit authentication | Pass: both endpoints require the sealed Lichess session; disconnected callers get `401` before the engine is located or started. |
| Mutation protection | Pass: `POST /api/engine/health-checks` requires the same local Origin and refuses `Sec-Fetch-Site: cross-site` before authorization. |
| Command execution | Pass: no shell and no arguments. The executable comes only from local configuration or the validated Homebrew cellar path, never from a request. Only `gamereview.internal.engine` may use `ProcessBuilder`, `Process`, or `Runtime`, enforced by `ArchitectureTest`. |
| Untrusted process output | Pass: bounded line length, a bounded unread-line queue, deadlines on every wait, and a strict engine-name pattern before the name is returned. |
| Resource cleanup | Pass: every check ends the process, including after timeouts, malformed output, and wrong engines. One engine operation at a time. |
| Safe errors | Pass: launch and handshake failures map to fixed states without process or filesystem detail. |
| Response DTOs | Pass: a fixed state string, the local binary path, and the validated engine name only. |
| Output encoding | Pass: the client validates the response and React renders it as text. |

## Next

Part 2 runs the benchmark on the owner's M4 and stores a reproducible analysis profile: 2 threads, 256 MB hash, `MultiPV=3`, WDL output, and quick and deep node budgets.
