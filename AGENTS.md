# AGENTS.md — notes for AI coding agents

The full agent notes live in [CLAUDE.md](CLAUDE.md) (rules, commands, environment gotchas) and the design in
[ARCHITECTURE.md](ARCHITECTURE.md) and [SECURITY.md](SECURITY.md). Read them before changing anything. In short:

- **Build/test:** `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` ·
  `node --test bridge-test/*.test.mjs` · emulator end-to-end `scripts/e2e.py` (fake WhatsApp Web, debug/e2e builds).
- **Where things are:** page bridge `app/src/main/assets/walm-bridge.js`, engine `app/.../engine/` (WaEngine,
  Ingest, MediaQueue, LocalSender for sending), encrypted store `store/`, crypto `core/crypto/`, UI `ui/`.
- **Never:** log message content, attach the engine's WebView outside borrowView/returnView, run two WhatsApp Web
  WebViews, ship the fake WhatsApp page in release, edit old DB migrations, commit the signing key.
- **Calls** are impossible in Android WebView (no SharedArrayBuffer; ARCHITECTURE §11).
