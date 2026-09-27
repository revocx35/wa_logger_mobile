# wa_logger mobile — notes for Claude sessions

Read first, every session: [ARCHITECTURE.md](ARCHITECTURE.md) (design source of truth) and [SECURITY.md](SECURITY.md).
The server version is https://github.com/revocx35/wa_logger (local checkout `/root/wa_logger`); its
`docs/wwebjs-notes.md` explains the WhatsApp Web internals this app relies on.

## Rules
- Security first: this app stores private messages. Never log message content, names, numbers or keys (debug logs
  in WaEngine print message *types* only). Never render message text as HTML. Keep FLAG_SECURE default on, backups off,
  cleartext off.
- The engine's WebView must never be attached to a window except through `borrowView`/`returnView` (Chromium
  freezes a page 60 s after its WebView is detached; ARCHITECTURE §3). Never run two WebViews with WhatsApp Web.
- WhatsApp Web replaces `setTimeout`/`setInterval`: the bridge uses the saved natives (`T`). Every awaited
  WhatsApp call goes through `within()` (which clears its timer).
- `core/Models.kt` mirrors wa_logger's `shared/api.d.ts`; `core/wa/Mapper.kt` and `engine/Ingest.kt` port the
  server's `wa/mapper.ts` and `wa/ingest.ts`. Keep them in step when porting fixes from the server.
- DB migrations in `store/Db.kt` are append-only; encrypted fields are AAD-bound to their row id.
- ContentValues/bind args keep array references: wipe key material only after the write.
- All DB/crypto work off the main thread (`LocalApi` and the engine use Dispatchers.IO / the ingest queue).
- The fake WhatsApp Web (`app/src/debug/assets/fakewa`) exists only in debug/e2e builds and only works when
  `BuildConfig.FAKE_WA`; it must never reach release.
- Commits end with the Co-Authored-By trailer. CI must stay green.

## Commands
- JVM + Robolectric tests, lint, APK: `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- Bridge: `node --test bridge-test/*.test.mjs` (a directory argument doesn't work with this Node).
- Emulator e2e (no WhatsApp account needed): `scripts/emulator.sh start`, then `./gradlew :app:assembleDebug` and
  `python3 scripts/e2e.py`; for the minified build `./gradlew :app:assembleE2e` and
  `E2E_APK=app/build/outputs/apk/e2e/app-e2e.apk E2E_PKG=io.github.revocx35.walmobile.e2e python3 scripts/e2e.py`.
- Inspect the page of a debug build: `ANDROID_SERIAL=emulator-5554 node scripts/devtools.mjs '<js expression>'`
  (state only, never read messages). The fake page is driven the same way (`fake.link()`, `fake.incoming(...)`).
- Screenshots of a debug build: FLAG_SECURE blocks them; `ui.set_prefs(secure_screen=False, ...)` in scripts/ui.py.
- Release: ARCHITECTURE §10. Signing key location is in Claude's project memory (not in the repo).

## Environment gotchas (this LXC)
- 16 GB RAM, 3 cores, ~6 GB free disk. The only AVD is the NPM Mobile project's `npm_test` (API 37); a second AVD did
  not fit on the disk. It may already be running (`adb devices`); don't disturb the NPM app on it.
- `adb shell input text` can't type non-ASCII; the e2e script uses ASCII input.
- UI Automator dumps take ~2 s: don't time app operations through them.
- Debuggable builds run scrypt ~10× slower than release (2–5 s vs. well under 1 s).

## Known limits / ideas
- Real WhatsApp was verified up to linking (QR payload identical to WhatsApp Web's own); linked behavior follows the
  server's production-tested code paths. When the owner reports a problem, ask for Settings → App → Crash reports
  and the security log first.
- Possible next steps: Wi-Fi-only media option, export/backup of the encrypted log, a baseline profile for scrypt.
