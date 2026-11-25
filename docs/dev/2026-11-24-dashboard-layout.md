# Dashboard layout refresh

The Dashboard places Chess.com ratings to the left of Lichess ratings on wide screens and stacks them on narrower screens. Rating cards use a compact two-column layout. Correspondence and the bot record are no longer displayed; the existing rating-context API and stored game data remain unchanged.

Lichess connection controls are on Admin, while a disconnected Dashboard still offers connection and recovery. The header no longer polls or displays backend health. The desktop sidebar is 208px wide. The existing compact navigation remains available on narrow screens.

The rating panels now show only each site's heading, an Open Games link, and four cards containing the mode and rating. Lichess uses current session ratings; Chess.com uses the latest rating in stored games, which can be older than the current Chess.com rating. The Chess.com importer has no Classical category, so that card says Not available when no Classical rating exists. Rating histories remain stored but are no longer displayed on the Dashboard.
