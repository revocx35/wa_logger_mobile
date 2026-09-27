#!/usr/bin/env python3
"""End-to-end run on the emulator against the fake WhatsApp Web (app/src/debug/assets/fakewa, debug builds only).

It drives the app like a user through UI Automator and the fake page through Chrome DevTools, so everything
real is exercised: the bridge injected into a real WebView, the message port, ingest, encryption, the
database, media download and decryption, notifications, the foreground service and the UI.

    scripts/emulator.sh start
    ./gradlew :app:assembleDebug            (or :app:assembleE2e for the minified build: E2E_APK=… E2E_PKG=…)
    python3 scripts/e2e.py
Screenshots go to build/e2e/. Fails (exit 1) on the first problem or any crash.
"""
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ui import Ui, UiError  # noqa: E402

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PKG = os.environ.get("E2E_PKG", "io.github.revocx35.walmobile.debug")
APK = os.environ.get("E2E_APK", os.path.join(ROOT, "app/build/outputs/apk/debug/app-debug.apk"))
PASSWORD = "blue kettle 42 test"
NEW_PASSWORD = "green teapot 77 test"
ALI = "905551112233@c.us"
AYSE = "905554445566@c.us"
steps = 0
# The (fake) page is linked and has finished syncing, like WhatsApp Web after a reload.
SYNCED = "typeof fake === 'object' && window.require('WAWebSocketModel').Socket.hasSynced === true"

ui = Ui(pkg=PKG, out=os.path.join(ROOT, "build/e2e"))


def step(msg):
    global steps
    steps += 1
    print(f"[{steps:02d}] {msg}", flush=True)


def page(expr, timeout=60):
    """Evaluates JavaScript in the app's (fake) WhatsApp Web page."""
    env = dict(os.environ, ANDROID_SERIAL=ui.serial, WALM_PKG=PKG)
    deadline = time.time() + timeout
    while True:
        r = subprocess.run(["node", os.path.join(ROOT, "scripts/devtools.mjs"), expr], capture_output=True, text=True, env=env)
        if r.returncode == 0:
            return json.loads(r.stdout)
        if time.time() > deadline:
            raise UiError(f"devtools failed: {r.stderr[-500:]}")
        time.sleep(2)


def wait_page(pred_expr, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if page(pred_expr, timeout=10) is True:
                return
        except UiError:
            pass
        time.sleep(1)
    raise UiError(f"page never satisfied {pred_expr}")


def logcat(*tags):
    return ui.adb("logcat", "-d", *tags, check=False)


def notifications():
    return ui.shell("dumpsys notification --noredact", check=False)


def expect_text(text=None, contains=None, timeout=20):
    return ui.find(text=text, contains=contains, timeout=timeout)


def pixel_at(path, x, y):
    """RGB of a pixel in a PNG screenshot (plain Python, no PIL)."""
    import struct
    import zlib

    with open(path, "rb") as f:
        data = f.read()
    pos, idat, w, h = 8, b"", 0, 0
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", chunk[:10])
            bpp = {2: 3, 6: 4}[ctype]
        elif typ == b"IDAT":
            idat += chunk
        pos += 12 + ln
    raw = zlib.decompress(idat)
    stride = w * bpp
    prev = bytearray(stride)
    for row in range(y + 1):
        f = raw[row * (stride + 1)]
        line = bytearray(raw[row * (stride + 1) + 1:(row + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                line[i] = (line[i] + a) & 255
            elif f == 2:
                line[i] = (line[i] + b) & 255
            elif f == 3:
                line[i] = (line[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        prev = line
    return tuple(prev[x * bpp:x * bpp + 3])


def main():
    step(f"install {os.path.basename(APK)} ({PKG}) with the fake WhatsApp Web")
    ui.adb("uninstall", PKG, check=False)
    ui.adb("install", APK)
    ui.shell(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS", check=False)
    ui.set_prefs(secure_screen=False, fake_wa=True)
    ui.adb("logcat", "-c")
    ui.clear_crash_log()
    ui.start()

    step("set up: password, recovery key")
    expect_text(text="Set up wa_logger", timeout=30)
    ui.type_into("Password", PASSWORD)
    ui.type_into("Confirm password", PASSWORD)
    ui.back()
    ui.tap(text="Continue")
    expect_text(contains="Save your recovery key", timeout=60)
    key = next(t for t in ui.texts() if t and t.count("-") == 12)
    ui.shot("01_recovery_key")
    ui.tap(contains="I have stored my recovery key")
    ui.tap(text="Continue")

    step("link screen: WhatsApp Web loads in the background; QR code from the page")
    expect_text(text="Link WhatsApp")
    expect_text(text="Not linked", timeout=60)
    ui.tap(text="On another phone (QR)")
    ui.find(desc="QR code to link WhatsApp", timeout=30)
    ui.shot("02_link_qr")

    step("link with a pairing code (the fake links 3 s after issuing it)")
    page("fake.seed()")
    ui.tap(text="WhatsApp is on this phone")
    ui.type_into("Phone number", "+90 555 000 00 01")
    ui.back()
    ui.tap(text="Get code")
    expect_text(contains="FAKE", timeout=30)
    ui.shot("03_pairing_code")
    expect_text(text="WhatsApp is linked", timeout=60)
    ui.shot("04_linked")
    ui.tap(text="Complete")

    step("history import: chats and messages, including older ones loaded on demand")
    expect_text(text="Ali Veli", timeout=60)
    expect_text(text="Family")
    ui.shot("05_chats")
    ui.tap(text="Ali Veli")
    expect_text(contains="Geçen hafta ki fotoğrafları attın mı?", timeout=30)

    step("live: text, image, voice note, edit, reaction, delete for everyone")
    ids = page(f"""(async () => {{
        const a = fake.incoming("{ALI}", "Bu mesajı sileceğim 🙈");
        const b = fake.incoming("{ALI}", "Merhaba, yazım hatası yaptım");
        const img = fake.image("{ALI}", "/fakewa/img1.png", "Deniz kenarı");
        const v = fake.voice("{ALI}", "/fakewa/voice.wav");
        await new Promise(r => setTimeout(r, 4000));
        fake.edit(b, "Merhaba, yazım hatasını düzelttim");
        fake.react(a, "{AYSE}", "😮");
        await new Promise(r => setTimeout(r, 2000));
        fake.revoke(a);
        return {{a, b, img, v}};
    }})()""")
    expect_text(contains="Deleted for everyone", timeout=30)
    expect_text(contains="Bu mesajı sileceğim 🙈")
    expect_text(text="😮")
    expect_text(text="Edited")
    expect_text(contains="Merhaba, yazım hatasını düzelttim")
    img = ui.find(desc="Deniz kenarı", timeout=30)
    time.sleep(1)
    shot = ui.shot("06_chat_live")
    x, y = ui.center(img)
    rgb = pixel_at(shot, x, y)
    if abs(rgb[0] - 0) > 12 or abs(rgb[1] - 168) > 12 or abs(rgb[2] - 132) > 12:
        raise UiError(f"downloaded image not shown (pixel {rgb})")

    step("voice note plays (decrypting media source)")
    ui.tap(text="Play")
    time.sleep(2)
    log = logcat()
    if "ExoPlayerImplInternal: Playback error" in log or "PlaybackException" in log:
        raise UiError("voice note playback failed")
    if "AudioTrack" not in log and "getLatency" not in log:
        raise UiError("voice note never started")

    step("deleted-message notification (name only, no content)")
    n = notifications()
    if "A message was deleted" not in n or "Bu mesajı" in n:
        raise UiError("deleted notification missing or leaking content")

    step("Deleted tab and search")
    ui.back()
    ui.tap(text="Deleted")
    expect_text(contains="Bu mesajı sileceğim 🙈", timeout=20)
    ui.shot("07_deleted")
    ui.tap(text="Search")
    field = ui.find(cls="android.widget.EditText", timeout=10)
    ui.tap_xy(*ui.center(field))
    ui.type_text("Bu mesaj")
    ui.key("KEYCODE_ENTER")
    expect_text(contains="Bu mesajı sileceğim", timeout=20)
    ui.shot("08_search")
    ui.back()  # close the keyboard

    step("locked: messages still logged (public key), readable after unlock")
    ui.tap(text="Lock")
    expect_text(text="Unlock", timeout=10)
    page(f"""(async () => {{
        fake.incoming("{AYSE}", "Kilitliyken gelen mesaj");
        const b = fake.incoming("{AYSE}", "Bu da silinecek");
        await new Promise(r => setTimeout(r, 2500));
        fake.revoke(b);
        return 1;
    }})()""")
    time.sleep(3)
    ui.type_into("Password", "wrong password 1")
    ui.key("KEYCODE_ENTER")
    expect_text(text="Wrong password.", timeout=60)
    ui.clear_field("Password")
    ui.type_text(PASSWORD)
    ui.key("KEYCODE_ENTER")
    expect_text(text="Chats", timeout=60)
    ui.tap(text="Chats")
    ui.tap(text="Ayşe Yılmaz", timeout=20)
    expect_text(contains="Kilitliyken gelen mesaj", timeout=20)
    expect_text(contains="Bu da silinecek")
    expect_text(contains="Deleted for everyone")
    ui.back()

    step("process killed by Android: the service restarts and WhatsApp Web reconnects")
    ui.key("KEYCODE_HOME")
    pid = ui.pid()
    ui.shell(f"run-as {PKG} kill -9 {pid}")
    deadline = time.time() + 60
    while time.time() < deadline and (not ui.pid() or ui.pid() == pid):
        time.sleep(1)
    if not ui.pid():
        raise UiError("service did not restart")
    wait_page(SYNCED, timeout=90)
    time.sleep(3)
    page(f'fake.incoming("{ALI}", "Yeniden başladıktan sonra")')
    ui.clear_crash_log()  # the kill above is logged as a crash of the process
    ui.start()
    expect_text(text="Unlock", timeout=30)
    ui.type_into("Password", PASSWORD)
    ui.key("KEYCODE_ENTER")
    expect_text(text="Ali Veli", timeout=60)
    ui.tap(text="Ali Veli")
    expect_text(contains="Yeniden başladıktan sonra", timeout=30)
    ui.back()

    step("WA Web: the live page is shown, and replaced by a fresh background one afterwards")
    ui.tap(text="WA Web")
    ui.tap(text="Show WhatsApp Web")
    expect_text(contains="Fake WhatsApp Web", timeout=30)
    ui.shot("09_wa_web")
    hellos = logcat("-s", "walm:D").count(" hello")
    ui.tap(text="Chats")
    deadline = time.time() + 30
    while logcat("-s", "walm:D").count(" hello") <= hellos:
        if time.time() > deadline:
            raise UiError("page was not replaced after leaving WA Web")
        time.sleep(1)
    wait_page(SYNCED, timeout=60)
    time.sleep(2)

    step("unlinked on the phone: banner and alert notification")
    page("fake.unlink()")
    expect_text(contains="new messages are not being logged", timeout=30)
    if "WhatsApp was unlinked" not in notifications():
        raise UiError("no unlinked alert")
    ui.shot("10_unlinked")

    step("settings: change password, recovery key works, wipe")
    ui.tap(text="Settings")
    ui.type_into("Current password", PASSWORD)
    ui.type_into("New password", NEW_PASSWORD)
    ui.type_into("Confirm new password", NEW_PASSWORD)
    ui.back()
    ui.tap(text="Change password")
    expect_text(text="Password changed.", timeout=60)
    ui.tap(text="Lock")
    ui.tap(text="Forgot your password?")
    ui.type_into("Recovery key", key)
    ui.type_into("New password", PASSWORD)
    ui.type_into("Confirm new password", PASSWORD)
    ui.back()
    ui.tap(text="Reset password")
    expect_text(contains="Save your recovery key", timeout=60)
    ui.tap(contains="I have stored my recovery key")
    ui.tap(text="Continue")
    ui.tap(text="Settings", timeout=30)
    ui.scroll_to(text="Wipe all logged data")
    fields = ui.find_all(cls="android.widget.EditText")  # the Danger zone's password field is the last one
    ui.tap_xy(*ui.center(fields[-1]))
    ui.type_text(PASSWORD)
    ui.back()
    ui.tap(text="Wipe all logged data")
    ui.tap(text="Tap again to wipe everything")
    expect_text(contains="were deleted", timeout=30)
    ui.tap(text="Chats")
    time.sleep(2)
    if ui.exists(text="Ali Veli"):
        raise UiError("wipe left chats behind")

    ui.check_alive()
    print(f"\nOK: {steps} steps, no crashes. Screenshots in build/e2e/")


if __name__ == "__main__":
    try:
        main()
    except (UiError, StopIteration) as e:
        ui.shot("failure")
        print(f"\nFAILED at step {steps}: {e}", file=sys.stderr)
        sys.exit(1)
