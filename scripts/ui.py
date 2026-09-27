"""Tiny UI Automator driver for the emulator (used by scripts/e2e.py and for manual checks).

    from ui import Ui
    ui = Ui()                       # ANDROID_SERIAL or the first emulator
    ui.tap(text="Continue"); ui.type_into("Password", "secret"); ui.shot("name")
"""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

SDK = os.environ.get("ANDROID_HOME", "/opt/android-sdk")
ADB = os.path.join(SDK, "platform-tools", "adb")


class UiError(Exception):
    pass


class Ui:
    def __init__(self, serial=None, pkg="io.github.revocx35.walmobile.debug", out="build/e2e"):
        self.serial = serial or os.environ.get("ANDROID_SERIAL") or self._first_emulator()
        self.pkg = pkg
        self.out = out
        os.makedirs(out, exist_ok=True)

    def _first_emulator(self):
        out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
        for line in out.splitlines():
            if line.startswith("emulator-") and line.endswith("device"):
                return line.split()[0]
        raise UiError("no emulator running")

    def adb(self, *args, check=True, text=True):
        r = subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, text=text)
        if check and r.returncode != 0:
            raise UiError(f"adb {' '.join(args)}: {r.stderr}")
        return r.stdout

    def shell(self, cmd, check=True):
        return self.adb("shell", cmd, check=check)

    # ---- tree -----------------------------------------------------------------------------------------

    def dump(self):
        out = self.adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        start, end = out.find("<?xml"), out.rfind("</hierarchy>")
        if start >= 0 and end > 0:
            return ET.fromstring(out[start:end + len("</hierarchy>")])
        time.sleep(0.5)
        return ET.fromstring("<hierarchy/>")

    @staticmethod
    def _match(n, text=None, contains=None, desc=None, cls=None):
        t = n.get("text") or ""
        d = n.get("content-desc") or ""
        if text is not None and t != text and d != text:
            return False
        if contains is not None and contains not in t and contains not in d:
            return False
        if desc is not None and d != desc:
            return False
        if cls is not None and n.get("class") != cls:
            return False
        return True

    def find_all(self, root=None, **kw):
        root = root if root is not None else self.dump()
        return [n for n in root.iter("node") if self._match(n, **kw)]

    def find(self, timeout=20, **kw):
        deadline = time.time() + timeout
        while True:
            self.check_alive()
            hits = self.find_all(**kw)
            if hits:
                return hits[0]
            if time.time() > deadline:
                raise UiError(f"not found: {kw}")
            time.sleep(0.5)

    def exists(self, **kw):
        return bool(self.find_all(**kw))

    def texts(self):
        return [n.get("text") or n.get("content-desc") for n in self.dump().iter("node") if (n.get("text") or n.get("content-desc"))]

    @staticmethod
    def center(n):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        return (x1 + x2) // 2, (y1 + y2) // 2

    # ---- actions --------------------------------------------------------------------------------------

    def tap(self, timeout=20, **kw):
        n = self.find(timeout=timeout, **kw)
        x, y = self.center(n)
        self.shell(f"input tap {x} {y}")
        time.sleep(0.4)
        return n

    def tap_xy(self, x, y):
        self.shell(f"input tap {x} {y}")
        time.sleep(0.4)

    def field_after(self, label, timeout=20):
        """The EditText that follows a label text in document order (Compose puts the label above the field)."""
        deadline = time.time() + timeout
        while True:
            root = self.dump()
            seen = False
            for n in root.iter("node"):
                if self._match(n, text=label):
                    seen = True
                elif seen and n.get("class") == "android.widget.EditText":
                    return n
            if time.time() > deadline:
                raise UiError(f"no field after {label!r}")
            time.sleep(0.5)

    def type_into(self, label, value):
        n = self.field_after(label)
        x, y = self.center(n)
        self.shell(f"input tap {x} {y}")
        time.sleep(0.3)
        self.type_text(value)

    def type_text(self, value):
        # `input text` needs spaces as %s and some characters escaped.
        esc = value.replace("%", "\\%").replace(" ", "%s").replace("'", "\\'").replace('"', '\\"').replace("&", "\\&").replace("(", "\\(").replace(")", "\\)").replace("<", "\\<").replace(">", "\\>").replace(";", "\\;").replace("|", "\\|")
        self.shell(f"input text {esc}")
        time.sleep(0.3)

    def key(self, name):
        self.shell(f"input keyevent {name}")
        time.sleep(0.3)

    def back(self):
        self.key("KEYCODE_BACK")

    def swipe_up(self, y1=1800, y2=600):
        self.shell(f"input swipe 540 {y1} 540 {y2} 300")
        time.sleep(0.6)

    def scroll_to(self, max_swipes=12, **kw):
        for _ in range(max_swipes):
            if self.exists(**kw):
                return self.find(timeout=1, **kw)
            self.swipe_up()
        raise UiError(f"not found after scrolling: {kw}")

    def shot(self, name):
        path = os.path.join(self.out, f"{name}.png")
        with open(path, "wb") as f:
            f.write(subprocess.run([ADB, "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout)
        return path

    # ---- app ------------------------------------------------------------------------------------------

    def pid(self):
        return self.shell(f"pidof {self.pkg}", check=False).strip()

    def check_alive(self):
        crash = self.adb("logcat", "-d", "-b", "crash", check=False)
        if f"Process: {self.pkg}" in crash:
            raise UiError("app crashed:\n" + crash[-3000:])

    def clear_crash_log(self):
        self.adb("logcat", "-c", "-b", "crash", check=False)

    def start(self):
        self.shell(f"am start -n {self.pkg}/io.github.revocx35.walmobile.MainActivity")
        time.sleep(1.5)

    def stop(self):
        self.shell(f"am force-stop {self.pkg}")

    def set_prefs(self, **booleans):
        """Writes the app's SharedPreferences while it is stopped (debug builds only: run-as)."""
        items = "".join(f'<boolean name="{k}" value="{str(v).lower()}" />' for k, v in booleans.items())
        xml = f"<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>{items}</map>"
        self.stop()
        subprocess.run([ADB, "-s", self.serial, "shell", f"run-as {self.pkg} sh -c 'mkdir -p shared_prefs && cat > shared_prefs/walm_prefs.xml'"], input=xml, text=True, check=True)
