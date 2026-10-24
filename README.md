# Kite Client

Android client for viewing a Zerodha/Kite portfolio. Built with Jetpack Compose, MVI, Hilt, coroutines, Retrofit, and decimal monetary calculations. Open the project in Android Studio and run `app`. **Explore a demo portfolio** works without configuration; demo prices are explicitly labeled sample data.

## Architecture

The current app uses one Gradle module with clear package boundaries:

- `domain`: pure Kotlin models, repository contracts, and use cases. No Android, UI, or networking imports.
- `data`: Kite response models and mapping, Retrofit APIs, repository implementations, a Keystore-backed encrypted session store, and the demo data source.
- `presentation`: immutable state, user intents, mutations, a pure reducer, a Hilt ViewModel, and stateless Compose screens. State flows down; intents flow up. Browser launches use a separate, single-consumer effect channel.
- `di`: composition root. Hilt binds implementations to domain interfaces and provides transport/configuration/use cases.

The ViewModel depends on use cases rather than Retrofit or repository implementations. Presentation owns UI state, repositories own data access, and models own portfolio calculations. New features should introduce their own contracts, use cases, state, and screens rather than expanding the portfolio screen. Domain interfaces can be extracted into a JVM module when the project grows.

## Login setup

Both debug and release builds exchange Kite login tokens directly from Android. No backend, Node.js installation, credential vault, or device provisioning is required.

Configure the existing, Git-ignored `local.properties`, preserving `sdk.dir`:

```properties
kite.apiKey=YOUR_PUBLIC_KITE_API_KEY
kite.apiSecret=YOUR_KITE_API_SECRET
kite.redirectUrl=http://127.0.0.1:8000/api/kite/callback
```

Set `kite.redirectUrl` to the exact URL registered for your Kite application. Rebuild after changing credentials or the redirect URL. Missing credentials disable live login; demo mode remains available.

Gradle embeds the API key and secret into the APK through BuildConfig. Git exclusion protects the local file from commits, but anyone with the APK may recover the secret. Keep these personal builds private. A publicly distributed app should use a backend that holds the secret.

Install with `./gradlew :app:installDebug`, then tap **Connect with Zerodha**. The embedded login captures the registered callback, checks its origin and random state, and exchanges the one-time request token over HTTPS. Loopback callbacks are intercepted locally without starting a server. The app does not read login-page passwords or TOTP values or install a JavaScript bridge. Signing out removes the session token; reinstalling retains login configuration because it is included in the build.

## Portfolio behavior

- Holdings can be sorted by name, total profit, daily profit, profit percentage, daily percentage change, invested value, current value, or combined quantity. Names default to A–Z and numeric fields to highest first; the direction button reverses either order. Percentages use invested cost and previous-close value respectively, with unavailable percentages always last. Sorting includes pledged shares, compares unrounded values, works with search, and keeps separate choices for holdings and positions across refresh and saved-state restoration. Positions support name, supplied total P&L, and signed net quantity.
- Holdings and net positions load concurrently as one snapshot. Positions use Kite's supplied P&L, which accounts for products, multipliers, and closed positions.
- Holdings totals include settled plus T1 quantities. `used_quantity` is not subtracted a second time because Kite already reduces the remaining quantity after a sale. See [Kite's quantity explanation](https://kite.trade/forum/discussion/comment/52974/) and [T1 guidance](https://kite.trade/forum/discussion/13001/t1-t2-and-realized-qty).
- Total holdings return and day change are calculated with `BigDecimal`; positions P&L is displayed separately.
- Equity pledges parse Kite's `collateral_quantity` and `collateral_type`. Rows and holding details show pledged quantities; the pledge summary totals only reported quantities and estimates their market value at LTP before any haircut. Missing pledge fields remain unknown, rather than becoming zero. Every holding valuation uses one combined quantity: non-pledged settled shares plus T1 shares plus reported `collateral_quantity`. Invested cost is the reported average price times combined quantity; current value is LTP times combined quantity; P&L is current minus invested; day change is (LTP minus previous close) times combined quantity. Portfolio totals and percentages aggregate these combined values. The pledge summary is a breakdown already included in those totals and is never added a second time. Unknown pledge quantities contribute no invented shares; the UI retains their missing-data status. No free/sellable quantity or post-haircut margin is inferred. Kite may omit back-office pledge records, so this summary is not a replacement for the full Zerodha Console pledge record. See [Kite's pledge-field guidance](https://kite.trade/forum/discussion/15157/fields-missing-in-the-kite-java-sdk-responses) and [back-office limitation](https://kite.trade/forum/discussion/15738/pledged-details-via-kite-connect).
- Refresh failures retain the prior snapshot and its timestamp. Empty results and search with no matches have distinct states.
- HTTP 401/403 clears the cached and encrypted session and returns to sign-in. Access tokens, account identity, API-key binding, and expiry are stored encrypted with AES-256-GCM under an Android Keystore key in private no-backup storage. Startup restores a valid session before showing login and reloads the portfolio. A network outage preserves the restored session and offers retry. The local expiry follows the next 6 AM Asia/Kolkata boundary, and Kite may revoke the token earlier. Logout removes persisted credentials before remote revocation. Rotation retains the ViewModel and a pending browser callback waits for session restoration instead of being dropped. An existing memory-only session from an older build cannot be migrated; sign in once after installing this update.
- Logout attempts Kite session revocation and always clears local credentials, including when offline. A failed remote revocation is reported.
- Prices refresh on request. Streaming quotes, order placement, and scanners are outside this version. Options payoff analysis is available in its separate section below.

## Options analysis

The separate **Options** destination follows the web workspace with a charcoal/mint theme, two underlined tabs, compact market controls, and responsive two/four-column metric grids. **Manual payoff** is available from the overflow menu. Market data loads on entry; index/expiry changes reload automatically.

- **Option chain** fetches Kite's NFO contracts and NSE index instruments for NIFTY, BANKNIFTY, FINNIFTY and MIDCPNIFTY. Select an available expiry and refresh broker quotes. Prices prefer valid bid/ask midpoints, with last-trade fallback. The chain keeps strikes centred between horizontally scrolling call/put columns, highlights ATM and ITM, and shows LTP with daily change, IV, delta, volume, OI and previous-session OI change. Tap a row for bid/ask, price source and quote timestamps. An interactive call/put histogram switches between OI and change in OI. Instrument catalogs are cached in memory for the day. Full-chain quotes are batched below the broker's 500-instrument limit and paced at one request per second.
- **Straddle & strangle** uses selected chain contracts, buy/sell, lots, a weekday-close date slider at 15:30 IST, and a ±10-point IV slider. Results recalculate automatically off the UI thread. New strangles default to approximately 20-delta OTM legs; new positions default to sell, matching the web workspace. Black-76 reprices both legs before expiry; expiry uses intrinsic value. Quantity is lots × broker contract lot size. Results show premium, maximum expiry profit/loss, expiry and target-date breakevens, P&L at unchanged spot, touch-inspectable target-date and dashed expiry curves on a stable expiry-based axis, shaded ±1σ/±2σ bands, model chance of profit, the central approximately 68% index range, fifth-percentile P&L and range probabilities with movement from spot, probability bars and the spot-containing range highlighted. Missing leg IV falls back to ATM IV and is labelled. Missing ATM IV or unusable prices block simulation.
- **Manual payoff** retains the independent multi-leg expiry builder and synthetic example strategies.

The pure Kotlin engine lives in `feature/options/domain/MarketAnalysis.kt`; broker access in `feature/options/data/OptionMarketRepository.kt`; and separate state, ViewModel and Compose screens in `feature/options/presentation/MarketOptions*`. It uses the same 6.5% rate, parity-implied forward, Black-76 IV solver and lognormal probability assumptions as the web app's `options.py` and `strategy.js`. Simulator settings survive saved-state restoration. Switching accounts clears market results and cancels outstanding loads.

PCR and max pain use **all listed contracts**, independently of the displayed strike window. Missing OI makes these aggregate results unavailable. Other figures include broker-supplied India VIX, index daily change, volume PCR, and 20-session sample realised volatility (21 completed daily closes), ATM call-plus-put premium movement around the implied forward, separate IV-based 1σ movement, OI walls and approximately 25-delta put-minus-call skew. Max pain and OI walls are positioning references, not predictions. Stale last-only zero-volume quotes are excluded from IV inference.

Index history and visible-window previous-session OI load progressively with historical requests paced below three per second. Previous OI must match the last completed index session, including holidays; older contract candles are not silently substituted. Unavailable history stays explicitly unavailable and does not prevent chain prices or simulation. Historical market data is cached only in memory, without access tokens. More strikes trigger history loading for the expanded window.

Portfolio demo mode uses explicitly labelled synthetic chain data with unit lot size 1 and makes no broker requests. Live market-data and historical access require the relevant Kite account permissions. Broker timestamps are displayed; quotes can be delayed or from a closed market. Session-hours status uses weekday/time rules and does not infer exchange holidays. Prices refresh on request or every 30 seconds while the chain view is resumed and its auto-refresh setting is enabled. Automatic refresh pauses in the background, in other views, while a load/history request is running, and in demo mode.

Probabilities are model estimates rather than measured future success rates. The IV slider changes repricing, while the probability distribution retains the snapshot's ATM IV. “Worst 5%” is the fifth-percentile P&L threshold, not maximum loss. Outcome-table tail P&L ranges are sampled through ±6σ and are not tail loss bounds. Selling can have unlimited losses. Fees, taxes, margin and slippage are excluded. No orders are placed. See the official [quote/instrument](https://kite.trade/docs/connect/v3/market-quotes/) and [historical](https://kite.trade/docs/connect/v3/historical/) API contracts.

## Scanners

The **Scanners** tab runs all eight Signals strategies independently on Android: SMA crossover, 52-week breakout with volume, RSI pullback in trend, MACD crossover, Supertrend flip, relative strength vs Nifty 50, F&O OI buildup, and expected move vs realised volatility. No web-app server or scanner backend is used. Live scans require a connected Kite session and the relevant historical/derivative market-data permissions. The current Nifty 100 constituents load from the public Nifty Indices CSV; derivatives come from Kite's current instrument catalog. Missing source data produces an explicit failure or skipped-stock explanation rather than a substitute universe.

The new `feature/scanners/` package follows MVVM and Clean Architecture:

- `domain/`: framework-independent models, parameter validation, repository interfaces, the `RunScanner`, `LoadScanHistory`, and `SaveScan` use cases, and pure technical/derivative calculations. No Android, networking, storage, or presentation dependencies.
- `data/`: direct Kite APIs and mapping, a synthetic demo source, and an account-specific local history repository implementing the domain contracts.
- `presentation/`: immutable `ScannersState`, a Hilt `ScannersViewModel` depending only on domain use cases, and Compose library/settings/results/history views.
- `di/`: Hilt bindings and use-case construction. The host activity supplies account context and navigation without scanner business logic.

Scanner settings are validated before requests, including integer periods and relationships between thresholds. Technical scans exclude today's daily candle until 4 PM IST. Crossover scanners report the latest event in the selected calendar-day lookback; RSI and relative-strength scanners report current setups and their onset. Relative strength aligns stock and benchmark dates before computing returns. Breakouts compare against the previous 252 sessions and previous volume averages. RSI and Supertrend use seeded Wilder smoothing. Supported strategies include 5/10/20-session historical win rates, mean returns, and sample counts, with today's-membership and overlapping-signal limitations disclosed.

OI scans combine all currently live futures maturities, carry unchanged OI over missing contract sessions, and exclude history before the newest contract's listing. Live quotes are marked provisional and are added only when the preceding candle matches the quote's previous close. Missing OI remains unavailable. Expected-move scans use the nearest eligible paired expiry, valid ATM bid/ask midpoints, parity-implied forward and Black-76 IV at a 6.5% rate; compare against sample log-return RV annualised over 252 sessions; and report past move breaches and median absolute moves. The expiry horizon uses weekdays and remaining session time, excluding exchange holidays from its assumptions.

Historical and quote request pacing is shared with Options. Completed daily history is cached in memory with a bounded cache; current-session futures history is fetched afresh. Scan results (never credentials) are saved atomically under app-private no-backup storage, isolated by account and demo/live mode. The latest 100 runs per account are retained. History survives process restart and is available offline. A history-write failure leaves the current result visible with a warning. Scans run while the app process remains alive; keep the app open to finish. Cancellation discards the unfinished run. Switching accounts cancels work and clears visible results.

Demo scans run the same engines against explicitly labelled synthetic prices, OI and option books without broker or constituent-source requests. Local history is independent of the web app's saved runs. No orders are placed.

Tests include frozen Python/web parity fixtures for six technical strategies and their outcome samples, OI rollover and missing-data checks, Black pricing/IV recovery, straddle spread validation, all-eight demo isolation, settings validation, account changes, and on-device navigation, filtering and history persistence. The parity fixture uses synthetic daily candles and benchmark prices; it contains no account data.

## Validation

Use JDK 21 and the SDK configured by Android Studio:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

Device tests can perform a fresh install that removes saved broker sessions. Login configuration is included in the build and requires no provisioning. The login-to-demo test skips when an existing broker session is present, rather than signing the account out. Options UI tests use isolated synthetic data and exercise automatic repricing, strangle defaults, date/IV sliders, chart inspection and the manual-builder menu.

Unit tests cover session restoration, expiry, logout removal, offline/revoked startup behavior, API-key binding, the direct Kite token checksum, missing local credentials, callback validation, monetary calculations, settled/T1 mapping, state reset, retained snapshots, request authorization, session expiry, demo isolation, and offline logout. Device tests cover encrypted session restoration, logout deletion and tamper rejection, demo entry, search, details, positions, and logout. Live end-to-end login requires the account holder to complete Kite authentication and is not verified by demo tests.


## Local credentials

`local.properties` holds the API key, API secret, redirect URL, and Android SDK location and is excluded by the root `.gitignore`. Keep real values out of source files, documentation, and shared build logs. Session access tokens remain encrypted in private no-backup app storage using Android Keystore.
