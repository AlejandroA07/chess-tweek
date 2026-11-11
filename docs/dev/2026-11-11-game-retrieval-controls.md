# Manual game retrieval controls

Stage 3 Part 4 connects the existing protected retrieval endpoint to the Games interface without adding filters, selection, or analysis.

The connected Games view exposes a non-modal right-side **Get Lichess Games** panel. It accepts an integer from 1 through 50 and an optional before-date, identifies the connected account without exposing its token, and states that retrieval never starts analysis. Submission shows bounded progress, disables duplicate submission, reports a validated success result, and refreshes the account-scoped catalog. Rate limits expose only the safe retry delay; busy, disconnected, malformed, and unavailable outcomes remain recoverable without changing the visible library.

The current backend reports requested, received, and rejected counts. It does not distinguish newly inserted, updated, and duplicate records, so the interface does not invent that breakdown.

## Security review

- Authentication and object authorization: pass; the unchanged endpoint derives account identity and credentials only from the sealed session.
- Input validation: pass; native controls and client bounds improve feedback, while the server remains authoritative for count and date validation.
- Secret/data exposure: pass; the browser receives only bounded summary counts and safe fixed errors.
- Injection and transport: pass; JSON uses fixed fields, the same-origin cookie policy remains unchanged, and no raw HTML is rendered.
- Abuse resistance: pass; the server still permits one retrieval at a time, caps batches at 50, and preserves the bounded Retry-After value.
- Denied path: pass; existing endpoint tests cover disconnected and cross-origin callers, while the interface invalidates shared session state on `not_connected`.

The next independent part adds catalog filters. Selection remains a separate part after filtered catalog behavior is stable.
