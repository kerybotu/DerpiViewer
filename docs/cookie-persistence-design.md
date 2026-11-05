# Cookie Persistence Design

## Intent

Prepare an isolated, encrypted storage boundary for Derpibooru site cookies that may support future native interactions. The current login remains a manual official-site WebView login followed by the existing API Key import flow.

## Goals and limits

- Keep API Key and site cookies in separate stores with separate Android Keystore aliases.
- Use Android Keystore AES-GCM encryption for an opaque, current-site cookie snapshot.
- Require an explicit authorization value before writing; the feature remains disabled by default.
- Support read, replace/update, clear, and synchronization hooks without automatically reading or persisting WebView cookies.
- Provide a typed future consent event as the single entry point for enabling persistence after an explicit user choice.
- Preserve the current `CookieManager`/`SharedCookieJar` runtime bridge.
- Add no consent prompt and make no login-flow changes in this version.

## Approaches

1. Add an isolated store and synchronization boundary with an explicit authorization gate. This keeps runtime cookies transient from the app's perspective and minimizes regression risk.
2. Add an isolated store and synchronization boundary plus a typed future consent event. The event is not emitted by current login or challenge paths, but gives the future consent UI one clear integration point. **Selected.**
3. Persist the WebView cookie snapshot automatically and add opt-out later. This violates the current no-unauthorized-persistence requirement and is rejected.

## Implemented boundary

- `SiteCookieStore` uses the dedicated `site_cookie_credentials` preferences file and `derpiviewer_site_cookie_v1` Android Keystore alias. It stores AES/GCM/NoPadding ciphertext and IV, authenticates the fixed official-site identifier as AAD, limits snapshots to 64 KiB, and replaces the stored snapshot in one preference commit.
- `OfficialSiteCookieSnapshot.capture` validates the exact canonical HTTPS Derpibooru origin before reading the WebView CookieManager at that origin. Empty snapshots remove old ciphertext so expired cookies are not reused.
- Writes require the module-internal `CookiePersistenceConsentEvent.Granted`; missing authorization is rejected, and `Declined` clears the dedicated store and Keystore key, reporting cleanup failure. `syncFromWebView` checks authorization before reading CookieManager. Corrupt or unauthentic ciphertext is cleared and returns no cookie.
- Snapshot construction and store APIs are module-internal. Snapshots carry the canonical source origin, and the store validates that origin and cookie-pair syntax before persistence; malformed or foreign-origin snapshots clear existing data rather than replacing it.
- `CookiePersistencePolicy` keeps consent UI and native interactions disabled. No current production code emits a grant or calls the store. The synchronization API is reserved for a future consent UI and is not connected to `LoginActivity`, `SharedCookieJar`, or `ChallengeCoordinator`.
- Instrumentation tests cover encryption/decryption, rejected writes, replacement, clear/decline, tamper cleanup, official-origin validation, and the unchanged transient `CookieManager`/`SharedCookieJar` bridge using synthetic data.
- README documents the separate API Key and Cookie purposes, current manual login flow, disabled persistence, explicit future consent, encryption, clearing, and possible limits on future native interactions.

## Acceptance and open constraint

Existing API Key import remains unchanged. With no authorization event, no cookie ciphertext is written. Authorized test snapshots can be decrypted, replaced, and cleared. Existing WebView/OkHttp runtime cookie sharing remains unchanged. The current version shows no cookie consent UI and performs no background persistence.

The existing `CookieManager` is a WebView-managed site session store and may retain site cookies according to Android WebView behavior. This implementation will not add another persistence path or claim to disable WebView's own session persistence.
