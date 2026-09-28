# wa_logger mobile — Architecture

> Source of truth for design decisions. Read this and [CLAUDE.md](CLAUDE.md) before changing anything; if you change
> a decision, update this file in the same commit.

## 1. What it is

A standalone Android app that does what the [wa_logger](https://github.com/revocx35/wa_logger) server does, on the
phone: it runs WhatsApp Web in a WebView that is never shown, records every message, edit, reaction and media file
of the owner's account, encrypted at rest, keeps messages that are later deleted, and shows everything in a
read-only, WhatsApp-like UI (wa_logger's web design, via wa_logger's Android client, which this app is forked from).

Out of scope on purpose: sending messages, several accounts, view-once media (placeholder only).

## 2. Map of the code

```
core/ (pure Kotlin/JVM, unit-tested without Android)
  Models.kt             the UI's DTOs (same as wa_logger's shared/api.d.ts)
  Errors.kt             ApiException (codes: locked, invalid_credentials, rate_limited, validation, conflict, not_found, unavailable)
  WaText.kt, Format.kt  WhatsApp text formatting and dates (ports of the web UI's)
  crypto/               Primitives (AES-GCM, HKDF, P-256 ECDH), Scrypt, Keyring (envelope encryption), MediaCrypto (chunked files)
  wa/Mapper.kt          serialized WhatsApp message → storage record (port of server wa/mapper.ts, same tests)
app/src/main/
  assets/walm-bridge.js the page bridge (§4)
  engine/               WaEngine (WebView + state machine), PageBridge (RPC), Ingest, Sync, MediaQueue, Avatars,
                        WaService (foreground service), BootReceiver, Notifier
  store/                Db (schema), Repo (queries), Presenter (rows → DTOs), LocalApi (the UI's API), MediaFiles, SettingsStore
  security/             Vault (owner keys, unlock, recovery, throttle), Biometric
  media/                MediaAccess (decrypting Coil fetcher + ExoPlayer data source), MediaOps, MediaViewer, AudioController
  data/                 AppGraph (singletons), AppController (route guard, auto-lock), Session, Prefs, Diagnostics
  ui/                   Compose screens (from wa_logger's android_client): auth, link, chats, deleted, search, WA Web, settings
app/src/debug/assets/fakewa/   fake WhatsApp Web for emulator end-to-end tests (debug and e2e builds only, §9)
bridge-test/            Node tests of walm-bridge.js against fake WhatsApp Web modules
scripts/                e2e.py (emulator end-to-end), ui.py (UI Automator driver), devtools.mjs, emulator.sh
```

## 3. Runtime: why the WebView is never on screen

* `WaService` is a foreground service (`specialUse`, permanent low-priority notification). It keeps the process alive
  in the background and owns nothing but the notification and an optional partial wake lock; `WaEngine` (a process
  singleton in `AppGraph`) owns the WebView. `START_STICKY` + `BootReceiver` (boot, app update) restart it.
* **Chromium freezes hidden pages.** A WebView that was attached to a window and detached again is "hidden", and
  Chromium sends the page `freeze` about 60 s later: no JavaScript runs, WhatsApp Web stops receiving. Measured on the
  API 37 emulator: `freeze` exactly 60 s after detaching; forcing window visibility afterwards did not help.
* **A WebView that was never attached stays "visible"** and keeps running, also with the screen off and in forced
  deep Doze (network works because the process has a foreground service). So the engine's WebView is created with a
  `MutableContextWrapper(applicationContext)` and never attached.
* To show the page (WA Web screen, "Trouble? Show WhatsApp Web itself"), the engine *lends* its WebView
  (`borrowView`, swaps the wrapper's base context to the activity so IME and popups work, and lays the page out at
  960 px so the desktop layout fits). When the screen closes (`returnView`), that WebView is destroyed and a fresh,
  never-attached one loads WhatsApp Web again (a few seconds; WhatsApp Web restores its session from its own storage).
  Two WebViews must never run WhatsApp Web at the same time (the second tab takes the session over).
* `setRendererPriorityPolicy(IMPORTANT, false)` keeps the renderer at the app's priority. `onRenderProcessGone` →
  destroy and recreate with backoff.
* WebView settings: JavaScript + DOM storage; no file/content access; no multiple windows; downloads ignored; file
  chooser returns nothing; permission requests denied; navigation only to `https://web.whatsapp.com` (other links
  open in the browser only when tapped while the page is shown). User agent: the WebView's own Chrome version as
  desktop Linux Chrome, plus matching Client Hints metadata (`mobile=false`), because WhatsApp Web serves the full
  site only to desktop browsers.
* The phone lists the linked device as Chrome on Linux (from the user agent). The whatsapp-web.js device-name
  override (`WAWebMiscBrowserUtils.info`) no longer exists on WhatsApp Web 2.3000.10486x (the info moved into the
  function module `WAWebBrowserInfo`); the bridge still applies it where present.
* Requires `DOCUMENT_START_SCRIPT` and `WEB_MESSAGE_LISTENER` (WebView ~90+); otherwise the status asks to update
  Android System WebView.

## 4. The page bridge (`walm-bridge.js`)

Injected at document start into `https://web.whatsapp.com` (main frame only) with
`WebViewCompat.addDocumentStartJavaScript`. It talks to the app only through `WalBridge`, the port from
`WebViewCompat.addWebMessageListener` restricted to that origin. Adapted from whatsapp-web.js 1.34.7 (Client.js
`inject`/`attachEventListeners`, Injected/Utils.js, AuthStore.js; Apache-2.0, see NOTICE) and the server's
`wa/pageapi.ts` / `wa/media.ts`. The facts in wa_logger's `docs/wwebjs-notes.md` apply.

* WhatsApp Web replaces the global timer functions; the bridge keeps the native ones (`T`).
* Boot: poll until `window.require` and `window.Debug.VERSION` exist → `start()`: device name, `wa` message, socket
  state listener, QR (`Conn.ref` + noise/identity/ADV keys, prefixed with `https://wa.me/settings/linked_devices#`
  exactly like WhatsApp Web's own QR — verified identical on the emulator), offline progress, logout.
* Ready: on `Socket` `change:hasSynced` (or already synced, or 60 s after `CONNECTED` as a fallback — the server met a
  stalled "ready") → `authenticated`, install hooks, `ready {me}`, then "unavailable" presence now and every 4 min
  (the hidden page counts as visible and would otherwise show the owner online).
* Events (`{t:'ev'}`): `message` (Msg `add` with `isNewMsg`; ciphertext first, then again once decrypted, placeholder
  resend enabled), `revoke` (`change:type` → revoked; `before` from a 200-entry cache of recently seen messages),
  `revoke_me`, `edit` (texts omitted when they are thumbnails: `blob:true`), `ack`, `reactions` (observing
  `WAWebAddonReactionTableMode.reactionTableMode.bulkUpsert`), `chat_removed`, `chat_archived`.
* Serialization: `ser()` picks the fields the mapper reads from `message.serialize()`, JIDs as strings, keys as
  `MsgKey.toString()` (`<fromMe>_<remote>_<id>[_<participant>][_<self>]`), no binary fields.
* Commands (`{id, op, args}` → `{t:'res', id, ok, data|error}`): `ping`, `status`, `listChats`, `chatInfo`,
  `contactInfo` (LID → phone), `fetchMessages` (loadEarlierMsgs, notifications skipped), `profilePicUrl`,
  `mediaPrepare`/`mediaSlice`/`mediaRelease` (download + decrypt in the page, base64 slices of 2 MiB),
  `logout`, `refreshQr`, `pairingCode`, `cancelPairing`, `setViewport`. Every WhatsApp call is raced against a
  timer (`within`, which always clears it).

## 5. Engine (`engine/`)

* `PageBridge`: messages arrive on the main thread and are parsed in order on one background coroutine; `res`
  completes the pending `call` (timeouts per op), everything else goes to `WaEngine.onPageMessage`. A new `hello`
  (page load) fails all pending calls.
* `WaEngine` state machine (`WaStatus`, same states as the server): `starting` → `qr` → `authenticating` →
  `syncing`/`ready`; `disconnected` after an unlink (kept until relinked, raises the alert); `error` with reload
  backoff 5 s → 60 s (network callback retries at once). Watchdog: `ping` every 60 s, two failures or no WhatsApp
  Web after 3 min → reload.
* Ingest runs on one serial queue (`work` channel on a single IO thread) in WhatsApp's event order; page reads happen
  outside it. Ports of the server's modules: `Ingest` (idempotent upserts, revoke/edit/reaction/ack/placeholder
  logic), `Sync` (first history import, catch-up history passes during the first 3 h, reconcile every 10 min),
  `MediaQueue` (live before history, 2 at a time, 4 attempts, backoff 5 s/30 s/2 min/10 min, 180 s prepare timeout,
  pauses under 1 GB free), `Avatars` (URL from the page, downloaded natively over HTTPS from `*.whatsapp.net`,
  ≤ 2 MiB, every 30 min for chats not checked for 3 days).
* `Notifier`: status notification (always), "WhatsApp was unlinked" alert, and "A message was deleted in <chat>"
  (batched per chat for 3 s; the chat name only when the app is unlocked; no content; private on the lock screen).

## 6. Encryption and unlock (`core/crypto`, `security/`)

Same envelope design as the server (its ARCHITECTURE.md §6), with changes for the phone:

* Owner key pair: **P-256** (X25519 is only in the platform from API 33). DEK wrap: ephemeral P-256 ECDH →
  `HKDF(shared, ephPub‖ownerPub, "walm/v1/dek")` → AES-256-GCM. Public keys are X.509-encoded.
* Password KDF: **scrypt N=2^15, r=8, p=1** (32 MiB; pure Kotlin, checked against RFC 7914 and Node), then
  `HKDF(master, salt, "walm/v1/auth" | "walm/v1/kek")`. `pw_log_n` is stored per owner. Release build on the
  emulator: well under a second (debuggable builds take 2–5 s, so don't judge it on a debug build).
* Recovery key: 32 random bytes, Crockford base32 in groups of 4; wraps a second copy of the private key.
  Recovery sets a new password and a new recovery key and turns fingerprint unlock off.
* Fingerprint unlock (Android 11+): a third copy of the private key, AES-GCM under an Android Keystore key that needs
  `BIOMETRIC_STRONG` per use and is invalidated by new enrollments; the unwrapped key is proven against the public key
  before use.
* Field format `0x01 ‖ keyId(u32be) ‖ nonce ‖ ct ‖ tag`, AAD `walm/v1|<table>|<column>|<rowId>|<header>`; media files
  `WAL1` chunked format (64 KiB chunks, final flag, index in nonce and AAD) under `no_backup/media/<xx>/<random>.bin`.
* `Vault` states: Loading → NoOwner → Locked ⇄ Unlocked. `DataWriter` (public key, current DEK, rotated daily) exists
  whenever an owner exists, so logging works while locked; `DataReader` (private key, cached DEKs) only while
  unlocked. Failed unlocks are charged in the owner row *before* scrypt: after 5, waits of 30 s doubling to 1 h.
  Auto-lock after the app leaves the screen (default 5 min). ContentValues keep references: key material is wiped only
  after `updateOwner` wrote it (a test caught the opposite order storing zeros).
* After password changes, recovery and key rotation the WAL is checkpointed (`secure_delete=ON` zeroes freed pages).

## 7. Data (`store/`)

SQLite `databases/walm.db` (WAL, foreign keys, `secure_delete`), schema of the server minus sessions/TOTP (see
`Db.kt`; migrations append-only): `owner`, `audit_log`, `app_state` (settings JSON, `onboarding_complete`, `wa_me`,
`initial_sync_done`, …), `data_keys`, `media`, `chats`, `contacts`, `messages`, `message_edits`, `reactions`.
`Presenter` and `LocalApi` produce exactly the server's DTOs (paging cursors `<ts>_<rowid>`, around/after pages,
deleted feed, bounded decrypt-and-scan search), so the UI code needed almost no changes. Media URLs are
`walm://media/<id>`, served by `MediaAccess` to Coil (in-memory only) and ExoPlayer (chunk-wise decryption,
seeking). "Save" and "Open with" decrypt to the chosen file / a cache copy deleted at next start.

## 8. UI

The screens of wa_logger's Android client (Compose, the web's CSS tokens and icons, 900 dp two-pane breakpoint),
with the server parts replaced: setup (password) → recovery key → link (phone-number code or native QR, live page as
fallback) → main. "Log out" became "Lock". Settings: password, unlock (fingerprint, auto-lock), recovery key,
logging, WhatsApp, background (battery optimization, notifications, wake lock, autostart, deleted notices),
storage, security log, wipe, app. `FLAG_SECURE` by default. Notification taps open WA Web or Deleted.

## 9. Testing

* `:core:test` — crypto vectors and tampering, chunked media, mapper (the server's fixtures), WaText/Format.
* `:app:testDebugUnitTest` — Robolectric with real SQLite: ingest (the server's scenarios), vault (throttle,
  recovery, password change), local API (paging, feed, search, wipe, media files), screenshot renders (Roborazzi).
* `node --test bridge-test/*.test.mjs` — the bridge against fake WhatsApp Web modules (QR payload, pairing, ready,
  serialization, revoke/edit/ack/reactions, ciphertext, page API, media slices, presence).
* `scripts/e2e.py` — emulator, UI Automator + DevTools, against the fake WhatsApp Web (`fake_wa` debug preference;
  only `debug` and `e2e` builds contain it and honor it, `BuildConfig.FAKE_WA`): 14 steps from setup to wipe, including
  process kill, lock, notifications and the live page hand-off. `e2e` = the release build (R8) + fake page, debuggable.
* Real WhatsApp Web on the emulator was verified up to linking: module access, states, QR payload identical to the
  page's own, background survival. Linked behavior relies on the same WhatsApp Web internals the wa_logger server
  uses in production.

## 10. Build and release

Kotlin 2.4, AGP 9.4, Gradle 9.8, compile/target SDK 37, min SDK 26. Package `io.github.revocx35.walmobile`
(`.debug`, `.e2e` suffixes). R8 on for release (shrink/optimize, no obfuscation). CI (`.github/workflows/ci.yml`):
bridge tests; core + app unit tests, lint, debug and e2e builds. Releases are manual: bump `versionCode`/
`versionName`, add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (≤ 500 characters), run the e2e
script on the `e2e` build, tag `vX.Y.Z`, `assembleRelease` with `keystore.properties` (never committed; location in
Claude's project memory) from a clean checkout of the tag, `gh release create vX.Y.Z` with the APK named
`wa_logger_mobile-X.Y.Z.apk` and the certificate SHA-256.

**F-Droid** (fdroiddata `metadata/io.github.revocx35.walmobile.yml`; license GPL-3.0-or-later, anti-feature
NonFreeNet): F-Droid builds each new `v*` tag (`UpdateCheckMode: Tags`) with Debian's OpenJDK 21 and publishes the
GitHub APK (`Binaries`, `AllowedAPKSigningKeys`) only if its own unsigned build is identical to it apart from the
signature, so users can move between F-Droid and GitHub without reinstalling (which would delete the log). Keep the
build reproducible: no dependency-info block and no VCS info in the APK (both off in `app/build.gradle.kts`), no
build timestamps, JDK 21, and build the release APK from the tag with no local changes. Check before uploading:
build the tag again in another directory without `keystore.properties` (`--no-build-cache`), then
`apksigcopier compare <signed.apk> --unsigned <unsigned.apk>`. The store listing (texts, icon, screenshots, which
the README also shows) is `fastlane/metadata/android/en-US/`.
