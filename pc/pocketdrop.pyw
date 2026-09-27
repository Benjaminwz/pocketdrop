# -*- coding: utf-8 -*-
"""
口袋快傳（電腦版）
跟手機上的「口袋快傳」App 在同一個 Wi-Fi 下互傳檔案和文字：
  手機 → 電腦：存到「接收資料夾」（預設 下載\\PocketDrop）
  電腦 → 手機：檔案拖進視窗排隊，手機 App 開著就會自動收下
約定很單純：手機用 UDP 廣播 "POCKETDROP?" 找電腦，之後全部走 HTTP（見 Handler）。
第一次連線時電腦會問要不要允許那支手機，允許後發一把鑰匙（key）給它，之後每個請求都要帶。
"""
import http.server
import ipaddress
import json
import re
import os
import queue
import secrets
import socket
import subprocess
import sys
import threading
import time
import tkinter as tk
import urllib.parse
import urllib.request
from tkinter import filedialog, messagebox, ttk

IS_WINDOWS = sys.platform == "win32"
if IS_WINDOWS:
    import ctypes

# 用 pythonw 開的時候沒有主控台，有東西想印出來會出錯，先導到空的地方
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w")
if sys.stderr is None:
    sys.stderr = open(os.devnull, "w")

try:
    from tkinterdnd2 import DND_FILES, TkinterDnD
except Exception:  # 沒有拖放套件也能用，只是只能按按鈕選檔案
    TkinterDnD = None

try:
    import qrcode
except Exception:
    qrcode = None

APP_NAME = "口袋快傳"
APP_ID = "PocketDrop.Desktop"
HTTP_PORT = int(os.environ.get("POCKETDROP_PORT", "47850"))  # 改埠號只給測試用，手機 App 固定連 47850
UDP_PORT = 47852
# 測試用：設成 127.0.0.1 就只聽本機，不會跳防火牆
BIND_HOST = os.environ.get("POCKETDROP_BIND", "0.0.0.0")
ONLINE_SECONDS = 40  # 手機每 25 秒會來問一次，超過這個時間沒來就當作離線

FROZEN = getattr(sys, "frozen", False)
BASE_DIR = os.path.dirname(os.path.abspath(sys.executable if FROZEN else __file__))
RES_DIR = getattr(sys, "_MEIPASS", BASE_DIR)
# 手機安裝檔：優先用程式旁邊的，沒有的話用打包進 exe 裡的
APK_PATH = next((p for p in (os.path.join(BASE_DIR, "PocketDrop.apk"), os.path.join(RES_DIR, "PocketDrop.apk"))
                 if os.path.exists(p)), os.path.join(BASE_DIR, "PocketDrop.apk"))

if IS_WINDOWS:
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass
    try:
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(APP_ID)
    except Exception:
        pass

# 螢幕縮放比例（175% = 1.75）。字型用「點」會自己縮放，像素值要自己乘
UI_SCALE = 1.0


def px(*values):
    scaled = tuple(round(v * UI_SCALE) for v in values)
    return scaled[0] if len(scaled) == 1 else scaled


# 配色：深藍＋寶藍
BG = "#f4f6fc"
CARD = "#ffffff"
TEXT = "#1f2a3d"
MUTED = "#6b7688"
BORDER = "#dde2f1"
ACCENT = "#3558d4"
ACCENT_DARK = "#2542ad"
ACCENT_LIGHT = "#b9c7f4"
ACCENT_SOFT = "#eef2ff"
FIELD = "#f6f8fe"
LOG_BG = "#f7f9fc"
OK_COLOR = "#22a35a"
WARN_COLOR = "#c98a00"
BAD_COLOR = "#d64545"
HEAD_BG = "#1a2656"
HEAD_TEXT = "#c1cbee"
HEAD_MUTED = "#8793c4"
HEAD_ONLINE = "#7be0a4"
FONT = "Microsoft JhengHei UI" if IS_WINDOWS else "PingFang TC" if sys.platform == "darwin" else "Noto Sans CJK TC"


# ---------------------------------------------------------------- 小工具

def fmt_size(n):
    if n is None or n < 0:
        return "?"
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


_RESERVED = {"CON", "PRN", "AUX", "NUL"} | {f"COM{i}" for i in range(1, 10)} | {f"LPT{i}" for i in range(1, 10)}


def safe_name(name):
    """手機傳來的檔名整理成 Windows 能用的樣子，而且不能跑出接收資料夾。"""
    name = os.path.basename(str(name).replace("\\", "/"))
    name = "".join("_" if (c in '<>:"/\\|?*' or ord(c) < 32) else c for c in name).strip().rstrip(". ")
    if not name:
        name = "未命名"
    if name.split(".")[0].upper() in _RESERVED:
        name = "_" + name
    if len(name) > 180:
        root, ext = os.path.splitext(name)
        name = root[:180 - len(ext)] + ext
    return name


def lan_ip():
    """這台電腦在區網裡的 IP（不會真的連出去，只是問系統會走哪張網卡）。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def open_path(path):
    if IS_WINDOWS:
        os.startfile(path)
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path])


def reveal_path(path):
    """在檔案總管裡打開所在資料夾並選好這個檔案。"""
    if IS_WINDOWS:
        subprocess.Popen(["explorer", "/select,", os.path.normpath(path)])
    elif sys.platform == "darwin":
        subprocess.Popen(["open", "-R", path])
    else:
        open_path(os.path.dirname(path))


# 手機開「USB 網路共用」時，Windows 會多一張這種名字的網卡（刻意不用 "USB" 這種寬鬆的字，
# 不然 USB 轉乙太網路的網卡也會被當成手機，整個區網的人都能自動配對）
USB_ADAPTER = re.compile(r"Remote NDIS|RNDIS|UsbNcm|NCM Host|Android", re.I)
_usb_lock = threading.Lock()
_usb_cache = {"time": 0.0, "nets": []}


def usb_networks(max_age=5.0):
    """USB 網路共用網卡的網段清單（只有 Windows 查得到；其他系統回傳空的，就是一律要按允許）。"""
    if not IS_WINDOWS:
        return []
    with _usb_lock:
        if time.time() - _usb_cache["time"] < max_age:
            return _usb_cache["nets"]
        nets = []
        cmd = ("[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-CimInstance Win32_NetworkAdapterConfiguration "
               "-Filter 'IPEnabled=true' | Select-Object Description,IPAddress,IPSubnet | ConvertTo-Json -Compress")
        try:
            out = subprocess.run(["powershell", "-NoProfile", "-Command", cmd], capture_output=True, timeout=10,
                                 creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout
            data = json.loads(out.decode("utf-8", "replace").strip() or "[]")
            for adapter in data if isinstance(data, list) else [data]:
                if not USB_ADAPTER.search(adapter.get("Description") or ""):
                    continue
                ips, masks = adapter.get("IPAddress") or [], adapter.get("IPSubnet") or []
                ips, masks = ([ips] if isinstance(ips, str) else ips), ([masks] if isinstance(masks, str) else masks)
                for ip, mask in zip(ips, masks):
                    if "." in ip and "." in mask:
                        nets.append(ipaddress.ip_network(f"{ip}/{mask}", strict=False))
        except Exception:
            pass
        _usb_cache.update(time=time.time(), nets=nets)
        return nets


def is_usb_peer(ip):
    """這個連線是不是從 USB 線（手機的 USB 網路共用）進來的。剛插線時快取可能還沒更新，找不到就再查一次。"""
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return any(addr in n for n in usb_networks()) or any(addr in n for n in usb_networks(max_age=0))


# ---------------------------------------------------------------- 設定

def config_path():
    custom = os.environ.get("POCKETDROP_CONFIG")
    if custom:
        return custom
    if IS_WINDOWS:
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
    elif sys.platform == "darwin":
        base = os.path.expanduser("~/Library/Application Support")
    else:
        base = os.path.expanduser("~/.config")
    folder = os.path.join(base, "PocketDrop")
    os.makedirs(folder, exist_ok=True)
    return os.path.join(folder, "config.json")


class Config:
    """設定存在 %APPDATA%\\PocketDrop（不放程式旁邊，分享程式時才不會把配對鑰匙一起送出去）。"""

    def __init__(self):
        self.path = config_path()
        self.lock = threading.Lock()
        try:
            with open(self.path, encoding="utf-8-sig") as f:
                data = json.load(f)
        except (OSError, ValueError):
            data = {}
        self.data = {
            "pc_id": data.get("pc_id") or secrets.token_hex(8),
            "recv_dir": data.get("recv_dir") or os.path.join(os.path.expanduser("~"), "Downloads", "PocketDrop"),
            "auto_copy": data.get("auto_copy", True),
            "devices": data.get("devices") or {},  # 手機 id -> {"name", "key"}
        }
        self.save()

    def save(self):
        with self.lock:
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.data, f, ensure_ascii=False, indent=2)
            os.replace(tmp, self.path)


# ---------------------------------------------------------------- 網路這一側

class Hub:
    """HTTP 伺服器、UDP 回應、要給手機的東西排隊都在這裡。跟畫面只透過 events 佇列溝通。"""

    def __init__(self, cfg):
        self.cfg = cfg
        self.events = queue.Queue()
        self.cond = threading.Condition()
        self.outbox = []  # 等手機來拿的東西
        self.next_id = secrets.randbelow(900000) * 1000 + 1
        self.next_tid = 1
        self.seen = {}  # 手機 id -> 最後一次來的時間
        self.pair_lock = threading.Lock()  # 一次只問一支手機
        self.file_lock = threading.Lock()
        self.pc_name = socket.gethostname() or "電腦"

    def emit(self, *event):
        self.events.put(event)

    def new_tid(self):
        with self.file_lock:
            self.next_tid += 1
            return self.next_tid

    # ---- 配對
    def find_device(self, key):
        if not key:
            return None, None
        for did, dev in list(self.cfg.data["devices"].items()):
            if secrets.compare_digest(str(dev.get("key", "")).encode(), key.encode("utf-8", "replace")):
                self.touch(did)
                return did, dev
        return None, None

    def touch(self, did):
        self.seen[did] = time.time()

    def ask_pair(self, name):
        answer = {"ok": False}
        done = threading.Event()
        self.emit("pair", name, answer, done)
        done.wait(120)
        return answer["ok"]

    def add_device(self, did, name):
        with self.cfg.lock:
            self.cfg.data["devices"][did] = {"name": name, "key": secrets.token_urlsafe(24)}
        self.cfg.save()
        return self.cfg.data["devices"][did]

    def online_names(self):
        now = time.time()
        devs = self.cfg.data["devices"]
        return [devs[d].get("name", "手機") for d, t in list(self.seen.items()) if now - t < ONLINE_SECONDS and d in devs]

    # ---- 電腦 → 手機的排隊
    def add_files(self, files):
        added = []
        with self.cond:
            for path, name in files:
                try:
                    size = os.path.getsize(path)
                except OSError:
                    continue
                item = {"id": self.next_id, "type": "file", "name": name.replace("\\", "/"), "size": size, "path": path}
                self.next_id += 1
                self.outbox.append(item)
                added.append(item)
            self.cond.notify_all()
        return added

    def add_text(self, text):
        with self.cond:
            item = {"id": self.next_id, "type": "text", "text": text}
            self.next_id += 1
            self.outbox.append(item)
            self.cond.notify_all()
        return item

    def wait_items(self, wait):
        """手機來問「有東西給我嗎」：有就馬上回，沒有就等一下（最多 wait 秒），有新東西時立刻回。"""
        deadline = time.time() + wait
        with self.cond:
            while not self.outbox:
                left = deadline - time.time()
                if left <= 0:
                    break
                self.cond.wait(left)
            return [{k: v for k, v in it.items() if k != "path"} for it in self.outbox[:50]]

    def get_item(self, item_id):
        with self.cond:
            return next((it for it in self.outbox if it["id"] == item_id), None)

    def finish_item(self, item_id, ok):
        with self.cond:
            item = self.get_item(item_id)
            if item:
                self.outbox.remove(item)
        if item:
            self.emit("delivered", item, ok)

    def pending_count(self):
        with self.cond:
            return len(self.outbox)

    def clear_outbox(self):
        with self.cond:
            n = len(self.outbox)
            self.outbox.clear()
        return n

    # ---- 手機 → 電腦
    def reserve(self, name):
        """在接收資料夾找一個不會撞名的檔名，先建立 .part 占位（同名檔案同時傳也不會互蓋）。"""
        folder = self.cfg.data["recv_dir"]
        os.makedirs(folder, exist_ok=True)
        root, ext = os.path.splitext(name)
        n = 1
        with self.file_lock:
            path = os.path.join(folder, name)
            while os.path.exists(path) or os.path.exists(path + ".part"):
                path = os.path.join(folder, f"{root} ({n}){ext}")
                n += 1
            open(path + ".part", "wb").close()
        return path


PAGE = """<!doctype html><html lang="zh-Hant"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>口袋快傳</title>
<style>body{font-family:system-ui,sans-serif;background:#f4f6fc;color:#1f2a3d;margin:0;padding:20px}
.card{background:#fff;border-radius:18px;padding:26px;max-width:420px;margin:30px auto;box-shadow:0 2px 14px rgba(26,38,86,.08);text-align:center}
h1{margin:4px 0;color:#1a2656}p{color:#6b7688}
a.btn{display:block;background:#3558d4;color:#fff;text-decoration:none;padding:16px;border-radius:12px;font-size:18px;font-weight:bold;margin:22px 0}
ol{text-align:left;line-height:1.9;color:#1f2a3d;padding-left:22px}</style></head>
<body><div class="card"><h1>口袋快傳</h1><p>手機跟電腦互傳檔案</p>%BODY%</div></body></html>"""

PAGE_OK = """<a class="btn" href="/PocketDrop.apk">下載 Android App</a>
<ol><li>下載完打開 <b>PocketDrop.apk</b></li>
<li>手機問要不要允許安裝，選「允許／設定 → 允許這個來源」</li>
<li>裝好打開口袋快傳，再到電腦上按「允許」就連上了</li></ol>"""

PAGE_MISSING = "<p>電腦上找不到 PocketDrop.apk，請把它放在口袋快傳程式的資料夾裡。</p>"


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "PocketDrop/1.0"
    timeout = 120
    hub = None  # 啟動時設定

    def log_message(self, fmt, *args):
        pass

    # ---- 小工具
    def reply(self, code, obj=None, body=None, ctype="application/json; charset=utf-8", close=False):
        if body is None:
            body = json.dumps(obj if obj is not None else {}, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        if close:
            self.send_header("Connection", "close")
            self.close_connection = True
        self.end_headers()
        self.wfile.write(body)

    def deny(self, code, msg):
        # 沒把 body 讀完就回絕：連線得關掉，不然剩下的 body 會被當成下一個請求
        self.reply(code, {"ok": False, "error": msg}, close=True)

    def parse(self):
        u = urllib.parse.urlsplit(self.path)
        self.route = u.path
        self.q = {k: v[-1] for k, v in urllib.parse.parse_qs(u.query).items()}

    def device(self):
        return self.hub.find_device(self.headers.get("X-Key", ""))

    def body_chunks(self, bufsize=1 << 20):
        """一塊一塊讀請求內容；手機不知道檔案大小時會用 chunked 分段傳。"""
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            while True:
                line = self.rfile.readline(65537)
                size = int(line.split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    while self.rfile.readline(65537) not in (b"\r\n", b"\n", b""):
                        pass
                    return
                left = size
                while left:
                    data = self.rfile.read(min(left, bufsize))
                    if not data:
                        raise ConnectionError("連線中斷")
                    left -= len(data)
                    yield data
                self.rfile.readline()
        else:
            left = int(self.headers.get("Content-Length") or 0)
            while left > 0:
                data = self.rfile.read(min(left, bufsize))
                if not data:
                    raise ConnectionError("連線中斷")
                left -= len(data)
                yield data

    def read_body(self, limit):
        data = b""
        for chunk in self.body_chunks():
            data += chunk
            if len(data) > limit:
                raise ValueError("太大")
        return data

    # ---- GET
    def do_GET(self):
        self.parse()
        try:
            if self.route == "/":
                html = PAGE.replace("%BODY%", PAGE_OK if os.path.exists(APK_PATH) else PAGE_MISSING)
                return self.reply(200, body=html.encode("utf-8"), ctype="text/html; charset=utf-8")
            if self.route == "/PocketDrop.apk":
                return self.send_apk()
            did, dev = self.device()
            if not did:
                return self.deny(401, "not paired")
            if self.route == "/api/ping":
                return self.reply(200, {"ok": True, "name": self.hub.pc_name, "id": self.hub.cfg.data["pc_id"]})
            if self.route == "/api/poll":
                try:
                    wait = max(0.0, min(float(self.q.get("wait", "25")), 30.0))
                except ValueError:
                    wait = 25.0
                items = self.hub.wait_items(wait)
                self.hub.touch(did)
                return self.reply(200, {"items": items})
            if self.route == "/api/file":
                return self.send_item_file(did)
            self.deny(404, "not found")
        except Exception:
            self.close_connection = True

    def send_apk(self):
        try:
            f = open(APK_PATH, "rb")
        except OSError:
            return self.deny(404, "no apk")
        with f:
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", 'attachment; filename="PocketDrop.apk"')
            self.send_header("Content-Length", str(os.fstat(f.fileno()).st_size))
            self.end_headers()
            while True:
                chunk = f.read(1 << 20)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def send_item_file(self, did):
        try:
            item = self.hub.get_item(int(self.q.get("id", "0")))
        except ValueError:
            item = None
        if not item or item["type"] != "file":
            return self.deny(404, "gone")
        try:
            f = open(item["path"], "rb")
        except OSError:
            return self.deny(404, "gone")
        with f:
            size = os.fstat(f.fileno()).st_size
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            tid = self.hub.new_tid()
            sent, last = 0, 0.0
            self.hub.emit("xfer", tid, "out", item["name"], 0, size, "run")
            try:
                while True:
                    chunk = f.read(1 << 20)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    sent += len(chunk)
                    now = time.time()
                    if now - last > 0.2:
                        last = now
                        self.hub.touch(did)
                        self.hub.emit("xfer", tid, "out", item["name"], sent, size, "run")
            finally:
                self.hub.emit("xfer", tid, "out", item["name"], sent, size, "end")

    # ---- POST
    def do_POST(self):
        self.parse()
        try:
            if self.route.startswith("/local/"):
                return self.local()
            if self.route == "/api/hello":
                return self.hello()
            did, dev = self.device()
            if not did:
                return self.deny(401, "not paired")
            if self.route == "/api/upload":
                return self.upload(did)
            if self.route == "/api/text":
                text = self.read_body(2_000_000).decode("utf-8", "replace")
                self.hub.emit("text_in", dev.get("name", "手機"), text)
                return self.reply(200, {"ok": True})
            if self.route == "/api/done":
                self.read_body(1024)
                try:
                    item_id = int(self.q.get("id", "0"))
                except ValueError:
                    item_id = 0
                self.hub.finish_item(item_id, self.q.get("ok", "1") == "1")
                return self.reply(200, {"ok": True})
            self.deny(404, "not found")
        except Exception:
            self.close_connection = True

    def hello(self):
        """手機來打招呼：認識的手機直接給鑰匙；新手機要電腦這邊按「允許」，
        但如果是用 USB 線插在這台電腦上（走 USB 網路共用進來的），代表人就在電腦前面，直接配對。"""
        self.read_body(4096)
        did = self.headers.get("X-Device-Id", "").strip()[:64]
        name = urllib.parse.unquote_plus(self.headers.get("X-Device-Name", "")).strip()[:40] or "手機"
        if not did:
            return self.deny(400, "no id")
        devices = self.hub.cfg.data["devices"]
        dev = devices.get(did)
        if dev is None:
            if not self.hub.pair_lock.acquire(timeout=2):
                return self.reply(409, {"ok": False, "error": "busy"})
            try:
                dev = devices.get(did)  # 等的時候可能已經允許過了
                if dev is None:
                    if is_usb_peer(self.client_address[0]):
                        dev = self.hub.add_device(did, name)
                        self.hub.emit("log", "✓", f"「{name}」用 USB 線接上，已自動配對（之後改用 Wi-Fi 也會自動連）", "ok")
                    elif not self.hub.ask_pair(name):
                        return self.reply(403, {"ok": False, "error": "denied"})
                    else:
                        dev = self.hub.add_device(did, name)
            finally:
                self.hub.pair_lock.release()
        elif dev.get("name") != name:
            dev["name"] = name
            self.hub.cfg.save()
        self.hub.touch(did)
        self.reply(200, {"ok": True, "key": dev["key"], "name": self.hub.pc_name, "id": self.hub.cfg.data["pc_id"]})

    def upload(self, did):
        name = safe_name(self.q.get("name", ""))
        try:
            total = int(self.q.get("size", "-1"))
        except ValueError:
            total = -1
        path = self.hub.reserve(name)
        part = path + ".part"
        shown = os.path.basename(path)
        tid = self.hub.new_tid()
        done, last, ok = 0, 0.0, False
        self.hub.emit("xfer", tid, "in", shown, 0, total, "run")
        try:
            with open(part, "wb") as f:
                for chunk in self.body_chunks():
                    f.write(chunk)
                    done += len(chunk)
                    now = time.time()
                    if now - last > 0.2:
                        last = now
                        self.hub.touch(did)
                        self.hub.emit("xfer", tid, "in", shown, done, total, "run")
            if total >= 0 and done != total:
                raise ConnectionError("檔案沒收完整")
            os.replace(part, path)
            ok = True
        finally:
            if not ok:
                self.close_connection = True
                try:
                    os.remove(part)
                except OSError:
                    pass
            self.hub.emit("xfer", tid, "in", shown, done, total, "ok" if ok else "fail", path)
        self.reply(200, {"ok": True, "name": shown})

    def local(self):
        """同一台電腦上又開了一次口袋快傳（例如從「傳送到」選單），把工作交給已經開著的這個。"""
        if self.client_address[0] not in ("127.0.0.1", "::1"):
            return self.deny(403, "local only")
        data = self.read_body(4_000_000)
        if self.route == "/local/send":
            paths = json.loads(data.decode("utf-8") or "{}").get("paths", [])
            self.hub.emit("local_send", paths)
        else:
            self.hub.emit("show")
        self.reply(200, {"ok": True})


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    # Windows 的 SO_REUSEADDR 會讓兩個程式搶同一個埠，要關掉，才能用埠號判斷是不是已經開著
    allow_reuse_address = not IS_WINDOWS

    def server_bind(self):
        if IS_WINDOWS and hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()


def udp_loop(hub):
    """手機在 Wi-Fi 裡喊「POCKETDROP?」，這裡回答自己的名字，手機就知道電腦在哪。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.bind(("" if BIND_HOST == "0.0.0.0" else BIND_HOST, UDP_PORT))
    except OSError:
        hub.emit("log", "", "自動搜尋用的連接埠被占用了，手機可能要手動輸入 IP", "bad")
        return
    while True:
        try:
            data, addr = s.recvfrom(2048)
            if data.startswith(b"POCKETDROP?"):
                reply = {"app": "pocketdrop", "id": hub.cfg.data["pc_id"], "name": hub.pc_name, "port": HTTP_PORT}
                s.sendto(json.dumps(reply, ensure_ascii=False).encode("utf-8"), addr)
        except OSError:
            pass  # Windows 上對方關掉時 UDP 會收到一個「連線被重設」，忽略就好


# ---------------------------------------------------------------- 畫面

class FlatButton(tk.Label):
    """用 Label 做的扁平按鈕（tk.Button 在 Mac 上不能換顏色）。"""

    def __init__(self, parent, text, command, primary=True, small=False):
        self.colors = (ACCENT, ACCENT_DARK, "#ffffff") if primary else (ACCENT_SOFT, "#dfe6ff", ACCENT)
        super().__init__(parent, text=text, bg=self.colors[0], fg=self.colors[2], cursor="hand2",
                         font=(FONT, 9 if small else 10, "bold"), padx=px(12 if small else 16), pady=px(5 if small else 8))
        self.command = command
        self.bind("<Enter>", lambda e: self.configure(bg=self.colors[1]))
        self.bind("<Leave>", lambda e: self.configure(bg=self.colors[0]))
        self.bind("<Button-1>", lambda e: self.command())


class App:
    def __init__(self, root, hub, dnd_ok):
        global UI_SCALE
        self.root = root
        self.hub = hub
        self.cfg = hub.cfg
        self.dnd_ok = dnd_ok
        UI_SCALE = max(1.0, root.winfo_fpixels("1i") / 96.0)
        self.active = {}  # 正在傳的：tid -> [方向, 名稱, 已傳, 總共, 開始時間]
        self.link_count = 0
        self.drop_hover = False
        self.ip = lan_ip()
        self.ip_checked = time.time()

        root.title(APP_NAME)
        root.configure(bg=BG)
        root.geometry("%dx%d" % px(500, 720))
        root.minsize(*px(420, 560))
        ico = os.path.join(RES_DIR, "icon.ico")
        if IS_WINDOWS and os.path.exists(ico):
            try:
                root.iconbitmap(ico)
            except tk.TclError:
                pass
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.build()
        self.pump()
        self.tick()

    # ---- 版面
    def build(self):
        root = self.root
        head = tk.Frame(root, bg=HEAD_BG)
        head.pack(fill="x")
        inner = tk.Frame(head, bg=HEAD_BG)
        inner.pack(fill="x", padx=px(18), pady=px(14))
        self.avatar = None
        avatar_path = os.path.join(RES_DIR, "icon-96.png" if UI_SCALE >= 1.5 else "icon-48.png")
        if os.path.exists(avatar_path):
            try:
                self.avatar = tk.PhotoImage(file=avatar_path)
                tk.Label(inner, image=self.avatar, bg=HEAD_BG).pack(side="left", padx=(0, px(12)))
            except tk.TclError:
                pass
        texts = tk.Frame(inner, bg=HEAD_BG)
        texts.pack(side="left", fill="x", expand=True)
        tk.Label(texts, text=APP_NAME, bg=HEAD_BG, fg="#ffffff", font=(FONT, 16, "bold")).pack(anchor="w")
        self.status = tk.Label(texts, text="", bg=HEAD_BG, fg=HEAD_TEXT, font=(FONT, 10), anchor="w", justify="left")
        self.status.pack(anchor="w", fill="x")
        self.ip_label = tk.Label(texts, text="", bg=HEAD_BG, fg=HEAD_MUTED, font=(FONT, 9), anchor="w")
        self.ip_label.pack(anchor="w")

        body = tk.Frame(root, bg=BG)
        body.pack(fill="both", expand=True, padx=px(14), pady=px(12))

        # 傳到手機
        card, box = self.card(body, "傳到手機")
        card.pack(fill="x")
        self.drop = tk.Canvas(box, height=px(112), bg=CARD, highlightthickness=0, cursor="hand2")
        self.drop.pack(fill="x")
        self.drop.bind("<Configure>", lambda e: self.draw_drop())
        self.drop.bind("<Button-1>", lambda e: self.choose_files())
        row = tk.Frame(box, bg=CARD)
        row.pack(fill="x", pady=(px(10), 0))
        # 按鈕要先放，不然輸入框預設的寬度會把它擠出視窗
        FlatButton(row, "傳文字", self.send_text).pack(side="right", padx=(px(10), 0), anchor="n")
        self.text_box = tk.Text(row, width=10, height=3, wrap="word", font=(FONT, 10), bg=FIELD, fg=TEXT, relief="flat",
                                highlightthickness=1, highlightbackground=BORDER, highlightcolor=ACCENT,
                                padx=px(8), pady=px(6), insertbackground=TEXT)
        self.text_box.pack(side="left", fill="x", expand=True)
        self.text_box.bind("<Control-Return>", lambda e: (self.send_text(), "break")[1])

        # 紀錄
        card, box = self.card(body, "紀錄")
        card.pack(fill="both", expand=True, pady=(px(12), 0))
        self.prog = tk.Frame(box, bg=CARD)
        self.prog_label = tk.Label(self.prog, text="", bg=CARD, fg=TEXT, font=(FONT, 9), anchor="w")
        self.prog_label.pack(fill="x")
        self.prog_bar = ttk.Progressbar(self.prog, mode="determinate", maximum=1000)
        self.prog_bar.pack(fill="x", pady=(px(4), px(8)))
        log_frame = tk.Frame(box, bg=LOG_BG, highlightthickness=1, highlightbackground=BORDER)
        log_frame.pack(fill="both", expand=True)
        self.log = tk.Text(log_frame, height=8, wrap="word", font=(FONT, 10), bg=LOG_BG, fg=TEXT, relief="flat",
                           padx=px(10), pady=px(8), state="disabled", cursor="arrow")
        bar = ttk.Scrollbar(log_frame, command=self.log.yview)
        self.log.configure(yscrollcommand=bar.set)
        bar.pack(side="right", fill="y")
        self.log.pack(side="left", fill="both", expand=True)
        self.log.tag_configure("time", foreground=MUTED, font=(FONT, 9))
        self.log.tag_configure("muted", foreground=MUTED)
        self.log.tag_configure("ok", foreground=OK_COLOR)
        self.log.tag_configure("warn", foreground=WARN_COLOR)
        self.log.tag_configure("bad", foreground=BAD_COLOR)
        self.log.tag_configure("quote", foreground=TEXT, background="#eef2fb", lmargin1=px(16), lmargin2=px(16))
        self.log.tag_configure("link", foreground=ACCENT, underline=True)
        self.log.tag_bind("link", "<Enter>", lambda e: self.log.configure(cursor="hand2"))
        self.log.tag_bind("link", "<Leave>", lambda e: self.log.configure(cursor="arrow"))

        # 下方：接收資料夾、其他
        bottom = tk.Frame(body, bg=BG)
        bottom.pack(fill="x", pady=(px(10), 0))
        FlatButton(bottom, "打開接收資料夾", self.open_recv, primary=False, small=True).pack(side="left")
        FlatButton(bottom, "更改…", self.change_recv, primary=False, small=True).pack(side="left", padx=(px(6), 0))
        FlatButton(bottom, "手機安裝 App", self.show_install, primary=False, small=True).pack(side="right")
        bottom2 = tk.Frame(body, bg=BG)
        bottom2.pack(fill="x", pady=(px(6), 0))
        self.recv_label = tk.Label(bottom2, text="", bg=BG, fg=MUTED, font=(FONT, 9), anchor="w")
        self.recv_label.pack(side="left", fill="x", expand=True)
        self.update_recv_label()
        bottom3 = tk.Frame(body, bg=BG)
        bottom3.pack(fill="x", pady=(px(2), 0))
        self.auto_copy = tk.BooleanVar(value=bool(self.cfg.data.get("auto_copy", True)))
        tk.Checkbutton(bottom3, text="收到文字自動複製", variable=self.auto_copy, command=self.save_auto_copy,
                       bg=BG, fg=MUTED, activebackground=BG, font=(FONT, 9), selectcolor=CARD).pack(side="left")
        unpair = tk.Label(bottom3, text="管理配對", bg=BG, fg=ACCENT, font=(FONT, 9, "underline"), cursor="hand2")
        unpair.pack(side="right")
        unpair.bind("<Button-1>", lambda e: self.manage_pairs())

        if self.dnd_ok:
            for w in (self.drop, self.log):
                w.drop_target_register(DND_FILES)
                w.dnd_bind("<<Drop>>", self.on_drop)
            self.drop.dnd_bind("<<DropEnter>>", lambda e: self.set_hover(True))
            self.drop.dnd_bind("<<DropLeave>>", lambda e: self.set_hover(False))

        self.log_line("", "開好了。手機打開口袋快傳 App 就會自動連上；還沒裝的話按右下角「手機安裝 App」。", "muted")

    def card(self, parent, title):
        outer = tk.Frame(parent, bg=CARD, highlightthickness=1, highlightbackground=BORDER)
        tk.Label(outer, text=title, bg=CARD, fg=TEXT, font=(FONT, 11, "bold")).pack(anchor="w", padx=px(14), pady=(px(10), px(6)))
        inner = tk.Frame(outer, bg=CARD)
        inner.pack(fill="both", expand=True, padx=px(14), pady=(0, px(14)))
        return outer, inner

    def draw_drop(self):
        c = self.drop
        c.delete("all")
        w, h = c.winfo_width(), c.winfo_height()
        m = px(2)
        c.create_rectangle(m, m, w - m, h - m, outline=ACCENT if self.drop_hover else ACCENT_LIGHT,
                           dash=(4, 3), fill=ACCENT_SOFT if self.drop_hover else "#fafbff")
        main = "把檔案或資料夾拖到這裡" if self.dnd_ok else "點這裡選擇檔案"
        c.create_text(w / 2, h / 2 - px(12), text=main, font=(FONT, 12, "bold"), fill=ACCENT)
        sub = "也可以點一下選檔案・手機 App 開著就會自動收下" if self.dnd_ok else "手機 App 開著就會自動收下"
        c.create_text(w / 2, h / 2 + px(14), text=sub, font=(FONT, 9), fill=MUTED)

    def set_hover(self, on):
        self.drop_hover = on
        self.draw_drop()

    # ---- 紀錄
    def log_line(self, icon, text, tag=None, links=()):
        """加一行紀錄。links = [(文字, 點了要做的事)]，會接在後面變成可以點的字。"""
        log = self.log
        log.configure(state="normal")
        log.insert("end", time.strftime("%H:%M  "), "time")
        log.insert("end", (icon + " " if icon else "") + text, tag or ())
        for label, action in links:
            self.link_count += 1
            name = f"link{self.link_count}"
            log.insert("end", "  ")
            log.insert("end", label, ("link", name))
            log.tag_bind(name, "<Button-1>", lambda e, a=action: a())
        log.insert("end", "\n")
        if int(log.index("end-1c").split(".")[0]) > 600:
            log.delete("1.0", "100.0")
        log.configure(state="disabled")
        log.see("end")

    def log_quote(self, text):
        log = self.log
        log.configure(state="normal")
        shown = text if len(text) <= 600 else text[:600] + "…"
        log.insert("end", shown + "\n", "quote")
        log.configure(state="disabled")
        log.see("end")

    # ---- 事件（網路那邊丟過來的）
    def pump(self):
        try:
            while True:
                event = self.hub.events.get_nowait()
                try:
                    self.handle(event)
                except Exception as e:  # 單一事件出錯不要讓整個畫面停掉
                    self.log_line("✗", f"內部錯誤：{e}", "bad")
        except queue.Empty:
            pass
        self.root.after(100, self.pump)

    def handle(self, event):
        kind = event[0]
        if kind == "xfer":
            self.on_xfer(*event[1:])
        elif kind == "text_in":
            self.on_text_in(*event[1:])
        elif kind == "delivered":
            item, ok = event[1], event[2]
            label = "文字" if item["type"] == "text" else item["name"]
            if ok:
                self.log_line("✓", f"手機已收到：{label}", "ok")
            else:
                self.log_line("✗", f"手機沒收到：{label}", "bad")
        elif kind == "pair":
            self.on_pair(*event[1:])
        elif kind == "local_send":
            self.show_window()
            self.queue_paths(event[1])
        elif kind == "show":
            self.show_window()
        elif kind == "log":
            self.log_line(*event[1:])

    def on_xfer(self, tid, direction, name, done, total, state, path=None):
        now = time.time()
        if state == "run":
            if tid not in self.active:
                self.active[tid] = [direction, name, done, total, now]
            else:
                self.active[tid][2] = done
        else:
            self.active.pop(tid, None)
            if direction == "in":
                if state == "ok":
                    self.log_line("↓", f"收到：{name}（{fmt_size(done)}）", "ok",
                                  links=[("打開", lambda p=path: self.safe_open(p)),
                                         ("在資料夾中顯示", lambda p=path: self.safe_reveal(p))])
                else:
                    self.log_line("✗", f"沒收完：{name}（傳到一半斷掉了）", "bad")
        self.update_progress()

    def update_progress(self):
        if not self.active:
            self.prog.pack_forget()
            return
        tid = max(self.active)
        direction, name, done, total, t0 = self.active[tid]
        speed = done / max(time.time() - t0, 0.001)
        verb = "↓ 從手機接收" if direction == "in" else "↑ 傳到手機"
        extra = f"（還有 {len(self.active) - 1} 個）" if len(self.active) > 1 else ""
        if total and total > 0:
            pct = min(done * 100 // total, 100)
            text = f"{verb}：{name}   {pct}%   {fmt_size(done)} / {fmt_size(total)}   {fmt_size(speed)}/s {extra}"
            self.prog_bar.configure(value=done * 1000 // total)
        else:
            text = f"{verb}：{name}   {fmt_size(done)}   {fmt_size(speed)}/s {extra}"
            self.prog_bar.configure(value=0)
        self.prog_label.configure(text=text)
        if not self.prog.winfo_ismapped():
            self.prog.pack(fill="x", before=self.log.master)

    def on_text_in(self, dev_name, text):
        copied = False
        if self.auto_copy.get():
            self.copy(text)
            copied = True
        self.log_line("↓", f"{dev_name} 傳來文字" + ("（已複製）" if copied else ""), "ok",
                      links=[("複製", lambda t=text: (self.copy(t), self.flash_status("已複製")))])
        self.log_quote(text)

    def on_pair(self, name, answer, done):
        self.show_window()
        ok = messagebox.askyesno(
            APP_NAME,
            f"手機「{name}」想連到這台電腦。\n\n允許之後，這支手機就能跟電腦互傳檔案，之後不用再問。\n要允許嗎？",
            parent=self.root)
        answer["ok"] = ok
        done.set()
        if ok:
            self.log_line("✓", f"已允許「{name}」連線", "ok")
        else:
            self.log_line("", f"拒絕了「{name}」", "muted")

    # ---- 按鈕
    def choose_files(self):
        paths = filedialog.askopenfilenames(parent=self.root, title="選擇要傳到手機的檔案")
        if paths:
            self.queue_paths(paths)

    def on_drop(self, event):
        self.set_hover(False)
        self.queue_paths(self.root.tk.splitlist(event.data))
        return "copy"

    def queue_paths(self, paths):
        files = []
        for p in paths:
            p = os.path.abspath(p)
            if os.path.isdir(p):
                base = os.path.dirname(p.rstrip("\\/"))
                for folder, _dirs, names in os.walk(p):
                    for n in sorted(names):
                        full = os.path.join(folder, n)
                        files.append((full, os.path.relpath(full, base)))
            elif os.path.isfile(p):
                files.append((p, os.path.basename(p)))
        if not files:
            return
        items = self.hub.add_files(files)
        if len(items) <= 5:
            for it in items:
                self.log_line("↑", f"排隊傳到手機：{it['name']}（{fmt_size(it['size'])}）")
        else:
            total = sum(it["size"] for it in items)
            self.log_line("↑", f"排隊傳到手機：{len(items)} 個檔案，共 {fmt_size(total)}")
        if not self.hub.online_names():
            self.log_line("", "手機還沒連上：打開手機上的口袋快傳就會自動收下", "warn")

    def send_text(self):
        text = self.text_box.get("1.0", "end-1c")
        if not text.strip():
            return
        self.hub.add_text(text)
        self.text_box.delete("1.0", "end")
        self.log_line("↑", "排隊傳文字到手機")
        self.log_quote(text)
        if not self.hub.online_names():
            self.log_line("", "手機還沒連上：打開手機上的口袋快傳就會自動收下", "warn")

    def copy(self, text):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)

    def safe_open(self, path):
        try:
            open_path(path)
        except OSError:
            messagebox.showwarning(APP_NAME, "打不開這個檔案（可能被移走或刪掉了）。", parent=self.root)

    def safe_reveal(self, path):
        try:
            reveal_path(path)
        except OSError:
            pass

    def open_recv(self):
        folder = self.cfg.data["recv_dir"]
        os.makedirs(folder, exist_ok=True)
        open_path(folder)

    def change_recv(self):
        folder = filedialog.askdirectory(parent=self.root, title="手機傳來的檔案要存在哪裡？",
                                         initialdir=self.cfg.data["recv_dir"])
        if folder:
            self.cfg.data["recv_dir"] = os.path.normpath(folder)
            self.cfg.save()
            self.update_recv_label()

    def update_recv_label(self):
        folder = self.cfg.data["recv_dir"]
        if len(folder) > 48:
            folder = "…" + folder[-47:]
        self.recv_label.configure(text=f"手機傳來的檔案存在：{folder}")

    def save_auto_copy(self):
        self.cfg.data["auto_copy"] = bool(self.auto_copy.get())
        self.cfg.save()

    def manage_pairs(self):
        devs = self.cfg.data["devices"]
        if not devs:
            messagebox.showinfo(APP_NAME, "目前還沒有配對過的手機。", parent=self.root)
            return
        names = "、".join(d.get("name", "手機") for d in devs.values())
        if messagebox.askyesno(APP_NAME, f"已配對的手機：{names}\n\n要全部取消配對嗎？\n之後手機要連線時，電腦會再問一次。",
                               parent=self.root):
            with self.cfg.lock:
                devs.clear()
            self.cfg.save()
            self.hub.seen.clear()
            self.log_line("", "已取消所有手機的配對", "muted")

    def show_install(self):
        url = f"http://{lan_ip()}:{HTTP_PORT}/"
        win = tk.Toplevel(self.root)
        win.title("手機安裝 App")
        win.configure(bg=CARD)
        win.transient(self.root)
        win.resizable(False, False)
        box = tk.Frame(win, bg=CARD)
        box.pack(padx=px(24), pady=px(20))
        tk.Label(box, text="用手機相機掃這個 QR code", bg=CARD, fg=TEXT, font=(FONT, 13, "bold")).pack()
        if qrcode:
            qr = qrcode.QRCode(border=2, error_correction=qrcode.constants.ERROR_CORRECT_M)
            qr.add_data(url)
            qr.make(fit=True)
            matrix = qr.get_matrix()
            cell = px(6)
            size = cell * len(matrix)
            canvas = tk.Canvas(box, width=size, height=size, bg="#ffffff", highlightthickness=0)
            canvas.pack(pady=px(12))
            for y, line in enumerate(matrix):
                for x, on in enumerate(line):
                    if on:
                        canvas.create_rectangle(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, fill=HEAD_BG, width=0)
        tk.Label(box, text=f"或在手機瀏覽器輸入：{url}", bg=CARD, fg=ACCENT, font=(FONT, 10)).pack()
        steps = ("1. 手機跟電腦要連同一個 Wi-Fi\n"
                 "2. 打開網頁後按「下載 Android App」，下載完打開 PocketDrop.apk\n"
                 "3. 手機問要不要允許安裝，選「允許」\n"
                 "4. 裝好打開口袋快傳，電腦這邊按「允許」就連上了")
        tk.Label(box, text=steps, bg=CARD, fg=MUTED, font=(FONT, 9), justify="left").pack(pady=(px(12), 0), anchor="w")
        if not os.path.exists(APK_PATH):
            tk.Label(box, text="注意：程式資料夾裡找不到 PocketDrop.apk", bg=CARD, fg=BAD_COLOR, font=(FONT, 9)).pack(pady=(px(8), 0))
        FlatButton(box, "關閉", win.destroy).pack(pady=(px(14), 0))
        # 放在主視窗正中間
        win.update_idletasks()
        x = self.root.winfo_rootx() + (self.root.winfo_width() - win.winfo_reqwidth()) // 2
        y = self.root.winfo_rooty() + max(0, (self.root.winfo_height() - win.winfo_reqheight()) // 3)
        win.geometry(f"+{max(0, x)}+{max(0, y)}")

    def show_window(self):
        root = self.root
        root.deiconify()
        root.lift()
        root.attributes("-topmost", True)
        root.after(400, lambda: root.attributes("-topmost", False))
        root.focus_force()

    def flash_status(self, text):
        self.status.configure(text=text, fg=HEAD_ONLINE)
        self.root.after(1200, self.refresh_status)

    # ---- 定時更新
    def tick(self):
        self.refresh_status()
        if self.active:
            self.update_progress()
        if time.time() - self.ip_checked > 5:
            self.ip = lan_ip()
            self.ip_checked = time.time()
        self.ip_label.configure(text=f"本機 IP：{self.ip}（手機找不到電腦時，在 App 裡輸入這個）")
        self.root.after(1000, self.tick)

    def refresh_status(self):
        names = self.hub.online_names()
        pending = self.hub.pending_count()
        if names:
            text, color = "● 已連線：" + "、".join(dict.fromkeys(names)), HEAD_ONLINE
        else:
            text, color = "○ 等待手機連線（手機打開口袋快傳就會自動連上）", HEAD_TEXT
        if pending:
            text += f"　・{pending} 個等手機接收"
        self.status.configure(text=text, fg=color)

    def on_close(self):
        pending = self.hub.pending_count()
        busy = len(self.active)
        if pending or busy:
            msg = f"還有 {pending + busy} 個東西沒傳完，關掉就會取消。\n確定要關掉嗎？"
            if not messagebox.askyesno(APP_NAME, msg, parent=self.root):
                return
        self.root.destroy()


# ---------------------------------------------------------------- 啟動

def hand_over(paths):
    """已經有一個口袋快傳開著：把要傳的檔案交給它（或叫它跳到最前面）。成功就回 True。"""
    route = "/local/send" if paths else "/local/show"
    data = json.dumps({"paths": paths}).encode("utf-8")
    req = urllib.request.Request(f"http://127.0.0.1:{HTTP_PORT}{route}", data=data, method="POST",
                                 headers={"Content-Type": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=3) as r:
            return r.status == 200
    except Exception:
        return False


def main():
    paths = [os.path.abspath(a) for a in sys.argv[1:] if os.path.exists(a)]
    cfg = Config()
    hub = Hub(cfg)
    Handler.hub = hub
    try:
        server = Server((BIND_HOST, HTTP_PORT), Handler)
    except OSError:
        if hand_over(paths):
            return
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror(APP_NAME, f"連接埠 {HTTP_PORT} 被別的程式占用了，口袋快傳沒辦法開始。")
        return
    threading.Thread(target=server.serve_forever, daemon=True).start()
    threading.Thread(target=udp_loop, args=(hub,), daemon=True).start()

    root, dnd_ok = None, False
    if TkinterDnD is not None:
        try:
            root = TkinterDnD.Tk()
            dnd_ok = True
        except Exception:
            root = None
    if root is None:
        root = tk.Tk()
    app = App(root, hub, dnd_ok)
    if paths:
        app.queue_paths(paths)
    root.mainloop()


if __name__ == "__main__":
    main()
