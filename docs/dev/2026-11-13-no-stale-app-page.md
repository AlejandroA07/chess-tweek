# No stale application page

During the Stage 3 live check, the browser kept showing an older Dashboard after the application was rebuilt and restarted.

## Cause

The packaged `index.html` was served without `Cache-Control`, and with a `Last-Modified` date of 1 February 1980. Every build gives packaged files that same fixed date.

- With no explicit caching instruction, browsers guess how long a page stays valid from its age. A page that seems unchanged since 1980 is reused for a very long time.
- When the browser did check with the server, the date never changed, so the server confirmed the old copy as current.

The browser therefore kept running the first build it had cached. After the Part 9 rating-order change, that old page rejected the new four-speed rating response. The Dashboard then showed the old card layout and no history line.

## Fix

`StaticResourceCaching` sets how the browser may cache each kind of file:

- `/` and `/index.html` are served with `Cache-Control: no-store` and no `Last-Modified`, so every load fetches the current page.
- Built files under `/assets/` have content hashes in their names. They are served with a one-year `immutable` cache, because a new build always produces new names.
- API responses keep their existing `no-store` headers.

`StaticResourceCachingTest` starts the real server and checks these headers against the built interface.

A browser that already holds the old page needs one forced reload (Cmd+Shift+R) after this fix. Later updates appear on a normal reload.
