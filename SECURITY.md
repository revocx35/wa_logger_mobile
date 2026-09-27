# Security

wa_logger mobile stores private messages, so the design starts from what could go wrong. Details of the mechanisms
are in [ARCHITECTURE.md](ARCHITECTURE.md) §3–§7.

## Threats considered

| Threat | Protection |
|---|---|
| Someone gets the app's files (rooted phone, forensic copy of a locked phone, a leaked backup) | Messages, names, thumbnails and media are encrypted under keys only the owner's password or recovery key unlock. The logger holds only the public key. Android backups and device-to-device transfer are disabled. |
| Someone picks up the unlocked phone | The app locks itself after it leaves the screen (default 5 min) and needs the password or a strong biometric. Screenshots and app-switcher previews are blocked by default. |
| Password guessing in the app | scrypt (32 MiB) per try, and after 5 wrong tries waits of 30 s doubling to 1 h, stored in the database (a restart doesn't reset it). |
| Malicious message content (HTML/scripts in text, crafted files) | Text is never rendered as HTML (WhatsApp formatting is parsed into styled text, links only for http/https). Media is shown only through the image decoder and the media player; other files open only when the owner chooses an app. |
| The WhatsApp Web page (or script injected into it) talking to the app | The bridge port is available only to the main frame of `https://web.whatsapp.com`; messages are size-limited and parsed as data. The page can't navigate elsewhere, open windows, download, pick files or get permissions. |
| Other apps on the phone | No exported components except the launcher activity and the boot receiver (which only starts the service); no content provider except a non-exported FileProvider with per-file read grants; no local network server. |
| Network attackers | HTTPS only, system CAs only (user-added CAs are not trusted). |
| Fingerprint unlock abuse | The biometric copy of the key is usable only right after a strong biometric check and is invalidated when fingerprints are added or removed. |

## Accepted / out of scope

- A fully compromised, unlocked phone (root malware while the app is unlocked can read memory).
- **The WhatsApp Web session itself** lives in the app's WebView storage, like on a computer. Anyone who can read the
  app's private files (root) could use it. Unlink the device in WhatsApp if the phone is lost.
- Metadata is stored in plain text: WhatsApp ids (which contain phone numbers), timestamps, message types, flags and
  media sizes. Use the phone's own encryption and screen lock.
- WhatsApp's Terms of Service: this is an unofficial client; the account risk is the owner's decision.

## Reporting

Please open a private security advisory on GitHub (Security → Report a vulnerability) rather than a public issue.
