"""
CamConnect Desktop - use your phone as a real PC webcam.

Connects to the CamConnect Android app (MJPEG over HTTP), shows a live
preview, and outputs the video as a virtual webcam that Zoom / Meet /
Discord / OBS can use.

Requires: customtkinter, opencv-python, pyvirtualcam, pillow, numpy
Virtual webcam needs the OBS Virtual Camera driver (install OBS Studio once).
"""

import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime

import cv2
import numpy as np
import customtkinter as ctk
from PIL import Image
from tkinter import messagebox

SETTINGS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "camconnect_settings.json")

# ---------- palette (matches the design blueprint) ----------
BG      = "#14181d"
PANEL   = "#1c2229"
PANEL2  = "#232b34"
LINE    = "#2e3742"
TEXT    = "#e8ecef"
MUTED   = "#97a3ad"
ACCENT  = "#f2a93b"
ACCENT_D= "#c8871f"
LIVE    = "#e5484d"
OK      = "#4cc38a"

VCAM_SIZES = {"480p": (640, 480), "720p": (1280, 720), "1080p": (1920, 1080)}
DISCOVERY_PORT = 4748
SEARCHING = "🔍  Searching for phones…"


def find_adb():
    """Locate adb.exe in PATH or in the default Android SDK folder."""
    path = shutil.which("adb")
    if path:
        return path
    guess = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    if os.path.isfile(guess):
        return guess
    return None


class CamConnectApp(ctk.CTk):
    def __init__(self):
        super().__init__()
        self.title("CamConnect Desktop")
        self.geometry("1080x640")
        self.minsize(940, 560)
        self.configure(fg_color=BG)
        ctk.set_appearance_mode("dark")

        # ---- state ----
        self.connected = False
        self.stop_stream = threading.Event()
        self.frame_lock = threading.Lock()
        self.frame = None              # latest processed BGR frame
        self.fps = 0.0
        self.rotation = 0              # user-chosen extra rotation: 0 / 90 / 180 / 270
        self.auto_rotation = 0         # phone sensor rotation, from /status
        self.mirror = False
        self.flip_v = False
        self.brightness = 0            # -100 .. 100
        self.contrast = 1.0            # 0.4 .. 2.2
        self.vcam_running = False
        self.vcam_thread = None
        self.recording = False
        self.video_writer = None
        self.record_path = None
        self.torch_on = False

        self.settings = self.load_settings()

        # auto-discovery: phones broadcasting on the WiFi
        self.devices = {}            # ip -> {name, port, seen}
        self._device_map = {}        # menu label -> (ip, port)
        self._autofilled_ip = None

        self.build_ui()
        threading.Thread(target=self.discovery_loop, daemon=True).start()
        self.after(1000, self.refresh_devices)
        self.after(33, self.update_preview)
        self.protocol("WM_DELETE_WINDOW", self.on_close)

    # ------------------------------------------------ discovery
    def discovery_loop(self):
        """Listen for CamConnect phones announcing themselves on the WiFi."""
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("", DISCOVERY_PORT))
            sock.settimeout(1.0)
        except Exception:
            return
        while True:
            try:
                data, addr = sock.recvfrom(1024)
                info = json.loads(data.decode("utf-8", "ignore"))
                if info.get("app") == "camconnect":
                    self.devices[addr[0]] = {
                        "name": str(info.get("name", "Phone"))[:40],
                        "port": int(info.get("port", 8080)),
                        "seen": time.time(),
                    }
            except Exception:
                pass

    def refresh_devices(self):
        now = time.time()
        self.devices = {ip: d for ip, d in self.devices.items() if now - d["seen"] < 8}
        self._device_map = {
            f"📱  {d['name']}  ·  {ip}": (ip, d["port"])
            for ip, d in sorted(self.devices.items())
        }
        labels = list(self._device_map.keys())
        if labels:
            self.device_menu.configure(values=labels)
            if self.device_menu.get() == SEARCHING:
                self.device_menu.set(labels[0])
            # zero-typing: fill the fields automatically unless the user
            # typed their own IP
            ip_now = self.ip_entry.get().strip()
            if not self.connected and (not ip_now or ip_now == self._autofilled_ip):
                first_ip, first_port = self._device_map[labels[0]]
                if ip_now != first_ip:
                    self.ip_entry.delete(0, "end")
                    self.ip_entry.insert(0, first_ip)
                    self.port_entry.delete(0, "end")
                    self.port_entry.insert(0, str(first_port))
                self._autofilled_ip = first_ip
        else:
            self.device_menu.configure(values=[SEARCHING])
            self.device_menu.set(SEARCHING)
        self.after(1000, self.refresh_devices)

    def on_device_selected(self, label):
        ip, port = self._device_map.get(label, (None, None))
        if ip:
            self.ip_entry.delete(0, "end")
            self.ip_entry.insert(0, ip)
            self.port_entry.delete(0, "end")
            self.port_entry.insert(0, str(port))
            self._autofilled_ip = ip

    # ------------------------------------------------ settings
    def load_settings(self):
        try:
            with open(SETTINGS_FILE, "r") as f:
                return json.load(f)
        except Exception:
            return {}

    def save_settings(self):
        data = {
            "ip": self.ip_entry.get().strip(),
            "port": self.port_entry.get().strip(),
            "pin": self.pin_entry.get().strip(),
        }
        try:
            with open(SETTINGS_FILE, "w") as f:
                json.dump(data, f, indent=2)
        except Exception:
            pass

    # ------------------------------------------------ ui
    def build_ui(self):
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(1, weight=1)

        # ---- top bar ----
        top = ctk.CTkFrame(self, fg_color=PANEL, corner_radius=0, height=54, border_width=0)
        top.grid(row=0, column=0, columnspan=2, sticky="ew")
        top.grid_propagate(False)
        ctk.CTkLabel(top, text="  📸  CamConnect", font=ctk.CTkFont("Segoe UI", 19, "bold"),
                     text_color=TEXT).pack(side="left", padx=14, pady=10)
        self.status_chip = ctk.CTkLabel(top, text="●  Not connected", font=ctk.CTkFont("Consolas", 13),
                                        text_color=MUTED)
        self.status_chip.pack(side="right", padx=18)

        # ---- main area ----
        main = ctk.CTkFrame(self, fg_color=BG, corner_radius=0)
        main.grid(row=1, column=0, sticky="nsew", padx=14, pady=14)
        main.grid_columnconfigure(0, weight=1)
        main.grid_columnconfigure(1, weight=0)
        main.grid_rowconfigure(0, weight=1)

        # ---- left: preview ----
        left = ctk.CTkFrame(main, fg_color=PANEL, corner_radius=14, border_width=1, border_color=LINE)
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 14))
        left.grid_rowconfigure(1, weight=1)
        left.grid_columnconfigure(0, weight=1)

        self.stream_info = ctk.CTkLabel(left, text="—", font=ctk.CTkFont("Consolas", 12),
                                        text_color=MUTED, anchor="w")
        self.stream_info.grid(row=0, column=0, sticky="ew", padx=16, pady=(10, 0))

        self.preview = ctk.CTkLabel(left, text="Open CamConnect on your phone and press Start —\nit will appear in the list on the right.",
                                    font=ctk.CTkFont("Segoe UI", 15), text_color=MUTED,
                                    fg_color="#0b0e11", corner_radius=10)
        self.preview.grid(row=1, column=0, sticky="nsew", padx=12, pady=10)

        self.vcam_btn = ctk.CTkButton(left, text="▶   Start Virtual Webcam",
                                      font=ctk.CTkFont("Segoe UI", 15, "bold"),
                                      fg_color=ACCENT, hover_color=ACCENT_D, text_color="#14181d",
                                      height=44, corner_radius=10, command=self.toggle_vcam)
        self.vcam_btn.grid(row=2, column=0, sticky="ew", padx=12, pady=(0, 12))

        # ---- right: controls ----
        right = ctk.CTkScrollableFrame(main, fg_color=PANEL, corner_radius=14,
                                       border_width=1, border_color=LINE, width=370)
        right.grid(row=0, column=1, sticky="nsew")

        def group(title):
            ctk.CTkLabel(right, text=title.upper(), font=ctk.CTkFont("Consolas", 11, "bold"),
                         text_color=MUTED, anchor="w").pack(fill="x", padx=16, pady=(14, 4))
            f = ctk.CTkFrame(right, fg_color="transparent")
            f.pack(fill="x", padx=12)
            return f

        # -- connect --
        g = group("Connect")
        self.device_menu = ctk.CTkOptionMenu(
            g, values=[SEARCHING], command=self.on_device_selected,
            fg_color=PANEL2, button_color=PANEL2, button_hover_color=LINE,
            dropdown_fg_color=PANEL2, dropdown_hover_color=LINE,
            text_color=TEXT, height=34, corner_radius=8,
            font=ctk.CTkFont("Segoe UI", 12))
        self.device_menu.set(SEARCHING)
        self.device_menu.pack(fill="x", pady=(0, 6))

        row1 = ctk.CTkFrame(g, fg_color="transparent"); row1.pack(fill="x", pady=2)
        self.ip_entry = ctk.CTkEntry(row1, placeholder_text="Phone IP  (192.168.x.x)",
                                     fg_color=PANEL2, border_color=LINE, height=34)
        self.ip_entry.pack(side="left", fill="x", expand=True, padx=(0, 6))
        self.port_entry = ctk.CTkEntry(row1, placeholder_text="Port", width=70,
                                       fg_color=PANEL2, border_color=LINE, height=34)
        self.port_entry.pack(side="left")

        row2 = ctk.CTkFrame(g, fg_color="transparent"); row2.pack(fill="x", pady=(6, 2))
        self.pin_entry = ctk.CTkEntry(row2, placeholder_text="PIN (shown on the phone)",
                                      fg_color=PANEL2, border_color=LINE, height=34)
        self.pin_entry.pack(side="left", fill="x", expand=True)

        if self.settings.get("ip"):   self.ip_entry.insert(0, self.settings["ip"])
        if self.settings.get("port"): self.port_entry.insert(0, str(self.settings["port"]))
        else:                         self.port_entry.insert(0, "8080")
        if self.settings.get("pin"):  self.pin_entry.insert(0, str(self.settings["pin"]))

        row3 = ctk.CTkFrame(g, fg_color="transparent"); row3.pack(fill="x", pady=(8, 2))
        self.connect_btn = ctk.CTkButton(row3, text="Connect (WiFi)", height=36, corner_radius=8,
                                         fg_color=ACCENT, hover_color=ACCENT_D, text_color="#14181d",
                                         font=ctk.CTkFont("Segoe UI", 13, "bold"),
                                         command=self.toggle_connect)
        self.connect_btn.pack(side="left", fill="x", expand=True, padx=(0, 6))
        self.usb_btn = ctk.CTkButton(row3, text="🔌 USB", width=90, height=36, corner_radius=8,
                                     fg_color=PANEL2, hover_color=LINE, border_width=1,
                                     border_color=LINE, text_color=TEXT,
                                     command=self.connect_usb)
        self.usb_btn.pack(side="left")

        # -- virtual cam output --
        g = group("Virtual Webcam Output")
        row = ctk.CTkFrame(g, fg_color="transparent"); row.pack(fill="x", pady=2)
        self.res_seg = ctk.CTkSegmentedButton(row, values=["480p", "720p", "1080p"],
                                              fg_color=PANEL2, selected_color=ACCENT,
                                              selected_hover_color=ACCENT_D,
                                              unselected_color=PANEL2, unselected_hover_color=LINE,
                                              text_color=TEXT)
        self.res_seg.set("720p")
        self.res_seg.pack(side="left", fill="x", expand=True, padx=(0, 6))
        self.fps_seg = ctk.CTkSegmentedButton(row, values=["30", "60"], width=90,
                                              fg_color=PANEL2, selected_color=ACCENT,
                                              selected_hover_color=ACCENT_D,
                                              unselected_color=PANEL2, unselected_hover_color=LINE,
                                              text_color=TEXT)
        self.fps_seg.set("30")
        self.fps_seg.pack(side="left")

        # -- transform --
        g = group("Transform")
        row = ctk.CTkFrame(g, fg_color="transparent"); row.pack(fill="x", pady=2)
        self.rot_btn = self.tool_btn(row, "↻ Rotate 0°", self.cycle_rotation)
        self.mirror_btn = self.tool_btn(row, "⇋ Mirror", self.toggle_mirror)
        self.flip_btn = self.tool_btn(row, "⇅ Flip", self.toggle_flip)

        # -- image --
        g = group("Image")
        self.bright_slider = self.slider(g, "☀  Brightness", -100, 100, 0, self.on_bright)
        self.contrast_slider = self.slider(g, "◐  Contrast", 40, 220, 100, self.on_contrast)

        # -- phone camera (remote control) --
        g = group("Phone Camera")
        row = ctk.CTkFrame(g, fg_color="transparent"); row.pack(fill="x", pady=2)
        self.torch_btn = self.tool_btn(row, "🔦 Torch", self.toggle_torch)
        self.tool_btn(row, "🔄 Switch Cam", lambda: self.phone_action("switch-camera"))
        self.zoom_slider = self.slider(g, "🔍  Zoom", 10, 80, 10, self.on_zoom)
        row = ctk.CTkFrame(g, fg_color="transparent"); row.pack(fill="x", pady=(4, 2))
        ctk.CTkLabel(row, text="Quality", font=ctk.CTkFont("Segoe UI", 12),
                     text_color=MUTED, width=60, anchor="w").pack(side="left", padx=(4, 6))
        self.q_seg = ctk.CTkSegmentedButton(row, values=["Low", "Medium", "High"],
                                            fg_color=PANEL2, selected_color=ACCENT,
                                            selected_hover_color=ACCENT_D,
                                            unselected_color=PANEL2, unselected_hover_color=LINE,
                                            text_color=TEXT, command=self.on_quality)
        self.q_seg.set("Medium")
        self.q_seg.pack(side="left", fill="x", expand=True)

        # -- capture --
        g = group("Capture")
        row = ctk.CTkFrame(g, fg_color="transparent"); row.pack(fill="x", pady=(2, 14))
        self.rec_btn = self.tool_btn(row, "⏺ Record", self.toggle_record)
        self.tool_btn(row, "📷 Snapshot", self.snapshot)

    def tool_btn(self, parent, text, cmd):
        b = ctk.CTkButton(parent, text=text, height=34, corner_radius=8,
                          fg_color=PANEL2, hover_color=LINE, border_width=1, border_color=LINE,
                          text_color=TEXT, font=ctk.CTkFont("Segoe UI", 12),
                          command=cmd)
        b.pack(side="left", fill="x", expand=True, padx=2)
        return b

    def set_active(self, btn, active):
        btn.configure(fg_color=ACCENT if active else PANEL2,
                      text_color="#14181d" if active else TEXT,
                      border_color=ACCENT if active else LINE)

    def slider(self, parent, label, lo, hi, init, cmd):
        row = ctk.CTkFrame(parent, fg_color="transparent"); row.pack(fill="x", pady=3)
        ctk.CTkLabel(row, text=label, font=ctk.CTkFont("Segoe UI", 12),
                     text_color=MUTED, width=100, anchor="w").pack(side="left", padx=(4, 6))
        s = ctk.CTkSlider(row, from_=lo, to=hi, command=cmd,
                          progress_color=ACCENT, button_color="#ffffff",
                          button_hover_color=ACCENT, fg_color=PANEL2, height=16)
        s.set(init)
        s.pack(side="left", fill="x", expand=True)
        return s

    # ------------------------------------------------ connect / stream
    def base_url(self):
        ip = self.ip_entry.get().strip()
        port = self.port_entry.get().strip() or "8080"
        return f"http://{ip}:{port}"

    def auth_query(self):
        pin = self.pin_entry.get().strip()
        return f"pin={urllib.parse.quote(pin)}" if pin else ""

    def toggle_connect(self):
        if self.connected:
            self.disconnect()
        else:
            self.connect()

    def connect(self):
        ip = self.ip_entry.get().strip()
        if not ip:
            messagebox.showwarning("CamConnect",
                                   "Enter the phone's IP address first, or wait for your\n"
                                   "phone to appear in the detected-devices list.")
            return
        self.save_settings()
        self.stop_stream.clear()
        self.connect_btn.configure(text="Connecting…", state="disabled")
        threading.Thread(target=self.stream_loop, daemon=True).start()

    def disconnect(self):
        self.stop_stream.set()
        if self.vcam_running:
            self.toggle_vcam()
        if self.recording:
            self.toggle_record()
        self.connected = False
        self.connect_btn.configure(text="Connect (WiFi)", state="normal",
                                   fg_color=ACCENT, text_color="#14181d")
        self.status_chip.configure(text="●  Not connected", text_color=MUTED)
        with self.frame_lock:
            self.frame = None

    def stream_loop(self):
        url = f"{self.base_url()}/video"
        q = self.auth_query()
        if q:
            url += "?" + q
        try:
            stream = urllib.request.urlopen(url, timeout=6)
        except Exception:
            self.after(0, self.on_stream_failed)
            return

        self.connected = True
        self.after(0, self.on_stream_started)
        threading.Thread(target=self.status_loop, daemon=True).start()

        # Parse the MJPEG stream by hand and always decode only the NEWEST
        # complete JPEG in the buffer — old frames are dropped, so the video
        # never builds up lag when the network hiccups.
        buf = b""
        last = time.time()
        n = 0
        try:
            while not self.stop_stream.is_set():
                # read1 = whatever bytes are available right now (no waiting
                # to fill the whole buffer) — keeps latency at zero
                chunk = stream.read1(65536)
                if not chunk:
                    break
                buf += chunk
                end = buf.rfind(b"\xff\xd9")
                if end == -1:
                    if len(buf) > 8_000_000:
                        buf = buf[-1_000_000:]
                    continue
                start = buf.rfind(b"\xff\xd8", 0, end)
                if start == -1:
                    buf = buf[end + 2:]
                    continue
                jpg = buf[start:end + 2]
                buf = buf[end + 2:]
                frame = cv2.imdecode(np.frombuffer(jpg, np.uint8), cv2.IMREAD_COLOR)
                if frame is None:
                    continue
                frame = self.process(frame)
                with self.frame_lock:
                    self.frame = frame
                    if self.recording and self.video_writer is not None:
                        self.video_writer.write(frame)
                n += 1
                now = time.time()
                if now - last >= 1.0:
                    self.fps = n / (now - last)
                    n = 0
                    last = now
        except Exception:
            pass
        try:
            stream.close()
        except Exception:
            pass
        if not self.stop_stream.is_set():
            # dropped unexpectedly
            self.after(0, self.on_stream_dropped)

    def status_loop(self):
        """Poll the phone's /status: picks up sensor rotation (and future state)."""
        while self.connected and not self.stop_stream.is_set():
            try:
                url = f"{self.base_url()}/status"
                q = self.auth_query()
                if q:
                    url += "?" + q
                data = json.loads(urllib.request.urlopen(url, timeout=4).read().decode())
                self.auto_rotation = int(data.get("rotation", 0)) % 360
            except Exception:
                pass
            time.sleep(2)

    def on_stream_started(self):
        self.connect_btn.configure(text="Disconnect", state="normal",
                                   fg_color=PANEL2, text_color=TEXT)
        self.status_chip.configure(text="●  Connected", text_color=OK)

    def on_stream_failed(self):
        self.connect_btn.configure(text="Connect (WiFi)", state="normal")
        self.status_chip.configure(text="●  Connection failed", text_color=LIVE)
        messagebox.showerror("CamConnect",
                             "Could not connect to the phone.\n\n"
                             "Please check:\n"
                             "  1. The phone app is streaming (press ▶ Start on the phone)\n"
                             "  2. Phone and PC are on the same WiFi (or the USB cable is plugged in)\n"
                             "  3. IP, port and PIN are correct")

    def on_stream_dropped(self):
        self.disconnect()
        self.status_chip.configure(text="●  Stream dropped", text_color=LIVE)

    # ------------------------------------------------ frame processing
    def process(self, frame):
        total_rotation = (self.auto_rotation + self.rotation) % 360
        if total_rotation == 90:
            frame = cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)
        elif total_rotation == 180:
            frame = cv2.rotate(frame, cv2.ROTATE_180)
        elif total_rotation == 270:
            frame = cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)
        if self.mirror:
            frame = cv2.flip(frame, 1)
        if self.flip_v:
            frame = cv2.flip(frame, 0)
        if self.brightness != 0 or self.contrast != 1.0:
            frame = cv2.convertScaleAbs(frame, alpha=self.contrast, beta=self.brightness)
        return frame

    # ------------------------------------------------ preview loop
    def update_preview(self):
        with self.frame_lock:
            frame = None if self.frame is None else self.frame.copy()
        if frame is not None:
            h, w = frame.shape[:2]
            pw = max(self.preview.winfo_width(), 200)
            ph = max(self.preview.winfo_height(), 150)
            scale = min(pw / w, ph / h)
            disp = cv2.resize(frame, (max(1, int(w * scale)), max(1, int(h * scale))))
            rgb = cv2.cvtColor(disp, cv2.COLOR_BGR2RGB)
            img = ctk.CTkImage(Image.fromarray(rgb), size=(disp.shape[1], disp.shape[0]))
            self.preview.configure(image=img, text="")
            self.preview._image_ref = img  # keep reference

            bits = [f"● LIVE · {w}x{h} · {self.fps:.0f} fps"]
            if self.vcam_running:
                bits.append("VCAM ON")
            if self.recording:
                bits.append("REC ●")
            self.stream_info.configure(text="   ".join(bits),
                                       text_color=LIVE if self.recording else MUTED)
        else:
            self.stream_info.configure(text="—", text_color=MUTED)
            if not self.connected:
                self.preview.configure(image=None, text="Open CamConnect on your phone and press Start —\nit will appear in the list on the right.")
                self.preview._image_ref = None
        self.after(33, self.update_preview)

    # ------------------------------------------------ virtual webcam
    def toggle_vcam(self):
        if self.vcam_running:
            self.vcam_running = False
            self.vcam_btn.configure(text="▶   Start Virtual Webcam",
                                    fg_color=ACCENT, text_color="#14181d")
            return
        if not self.connected:
            messagebox.showwarning("CamConnect", "Connect to the phone first.")
            return
        self.vcam_running = True
        self.vcam_btn.configure(text="■   Stop Virtual Webcam",
                                fg_color=LIVE, text_color="#ffffff")
        self.vcam_thread = threading.Thread(target=self.vcam_loop, daemon=True)
        self.vcam_thread.start()

    def vcam_loop(self):
        import pyvirtualcam
        w, h = VCAM_SIZES[self.res_seg.get()]
        fps = int(self.fps_seg.get())
        try:
            cam = pyvirtualcam.Camera(width=w, height=h, fps=fps, print_fps=False)
        except Exception as e:
            self.vcam_running = False
            self.after(0, lambda: (
                self.vcam_btn.configure(text="▶   Start Virtual Webcam",
                                        fg_color=ACCENT, text_color="#14181d"),
                messagebox.showerror(
                    "Virtual Webcam",
                    "Could not start the virtual webcam.\n\n"
                    "This feature needs the OBS Virtual Camera driver.\n"
                    "Install OBS Studio (free) from obsproject.com,\n"
                    "then try again.\n\n"
                    f"Error: {e}")))
            return
        canvas = np.zeros((h, w, 3), dtype=np.uint8)
        with cam:
            while self.vcam_running and self.connected:
                with self.frame_lock:
                    frame = None if self.frame is None else self.frame.copy()
                if frame is not None:
                    fh, fw = frame.shape[:2]
                    scale = min(w / fw, h / fh)
                    nw, nh = max(1, int(fw * scale)), max(1, int(fh * scale))
                    resized = cv2.resize(frame, (nw, nh))
                    canvas[:] = 0
                    x, y = (w - nw) // 2, (h - nh) // 2
                    canvas[y:y + nh, x:x + nw] = resized
                    cam.send(cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB))
                cam.sleep_until_next_frame()

    # ------------------------------------------------ usb connect
    def connect_usb(self):
        adb = find_adb()
        if not adb:
            messagebox.showerror(
                "USB Connect",
                "adb was not found on this PC.\n\n"
                "USB mode needs Android platform-tools (adb).\n"
                "Download them from:\n"
                "developer.android.com/tools/releases/platform-tools\n"
                "and extract to %LOCALAPPDATA%\\Android\\Sdk\\platform-tools")
            return
        port = self.port_entry.get().strip() or "8080"
        try:
            out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=15).stdout
            devices = [l for l in out.strip().splitlines()[1:] if l.strip().endswith("device")]
            if not devices:
                messagebox.showwarning(
                    "USB Connect",
                    "No phone was found over USB.\n\n"
                    "  1. Plug the phone in with a USB cable\n"
                    "  2. Enable Developer Options → USB debugging on the phone\n"
                    "  3. Tap 'Allow' when the USB-debugging prompt appears")
                return
            fwd = subprocess.run([adb, "forward", f"tcp:{port}", f"tcp:{port}"],
                                 capture_output=True, text=True, timeout=15)
            if fwd.returncode != 0:
                messagebox.showerror("USB Connect",
                                     f"Could not set up the USB tunnel:\n{fwd.stderr.strip()}")
                return
        except Exception as e:
            messagebox.showerror("USB Connect", f"Failed to run adb:\n{e}")
            return
        # tunnel ready — connect over localhost
        self.ip_entry.delete(0, "end")
        self.ip_entry.insert(0, "127.0.0.1")
        if self.connected:
            self.disconnect()
        self.connect()

    # ------------------------------------------------ transform / image handlers
    def cycle_rotation(self):
        self.rotation = (self.rotation + 90) % 360
        self.rot_btn.configure(text=f"↻ Rotate {self.rotation}°")
        self.set_active(self.rot_btn, self.rotation != 0)

    def toggle_mirror(self):
        self.mirror = not self.mirror
        self.set_active(self.mirror_btn, self.mirror)

    def toggle_flip(self):
        self.flip_v = not self.flip_v
        self.set_active(self.flip_btn, self.flip_v)

    def on_bright(self, v):
        self.brightness = int(v)

    def on_contrast(self, v):
        self.contrast = float(v) / 100.0

    # ------------------------------------------------ phone remote control
    def phone_action(self, name, value=None):
        def run():
            try:
                url = f"{self.base_url()}/action/{name}"
                params = []
                if value is not None:
                    params.append("value=" + urllib.parse.quote(str(value)))
                q = self.auth_query()
                if q:
                    params.append(q)
                if params:
                    url += "?" + "&".join(params)
                urllib.request.urlopen(url, timeout=5).read()
            except Exception:
                pass
        threading.Thread(target=run, daemon=True).start()

    def toggle_torch(self):
        self.torch_on = not self.torch_on
        self.set_active(self.torch_btn, self.torch_on)
        self.phone_action("toggle-flash")

    def on_zoom(self, v):
        self.phone_action("zoom", round(float(v) / 10.0, 1))

    def on_quality(self, choice):
        self.phone_action("quality", {"Low": 40, "Medium": 70, "High": 95}[choice])

    # ------------------------------------------------ capture
    def toggle_record(self):
        if self.recording:
            self.recording = False
            with self.frame_lock:
                if self.video_writer is not None:
                    self.video_writer.release()
                    self.video_writer = None
            self.set_active(self.rec_btn, False)
            self.rec_btn.configure(text="⏺ Record")
            if self.record_path:
                messagebox.showinfo("CamConnect", f"Video saved:\n{self.record_path}")
            return
        with self.frame_lock:
            frame = self.frame
            if frame is None:
                messagebox.showwarning("CamConnect", "Connect first, then record.")
                return
            h, w = frame.shape[:2]
            folder = os.path.join(os.path.expanduser("~"), "Videos", "CamConnect")
            os.makedirs(folder, exist_ok=True)
            self.record_path = os.path.join(folder, datetime.now().strftime("CamConnect_%Y%m%d_%H%M%S.mp4"))
            fourcc = cv2.VideoWriter_fourcc(*"mp4v")
            self.video_writer = cv2.VideoWriter(self.record_path, fourcc, max(self.fps, 10.0), (w, h))
        self.recording = True
        self.set_active(self.rec_btn, True)
        self.rec_btn.configure(text="⏹ Stop Rec")

    def snapshot(self):
        with self.frame_lock:
            frame = None if self.frame is None else self.frame.copy()
        if frame is None:
            messagebox.showwarning("CamConnect", "Connect first, then take a snapshot.")
            return
        folder = os.path.join(os.path.expanduser("~"), "Pictures", "CamConnect")
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, datetime.now().strftime("CamConnect_%Y%m%d_%H%M%S.jpg"))
        cv2.imwrite(path, frame)
        messagebox.showinfo("CamConnect", f"Snapshot saved:\n{path}")

    # ------------------------------------------------ close
    def on_close(self):
        self.stop_stream.set()
        self.vcam_running = False
        self.recording = False
        with self.frame_lock:
            if self.video_writer is not None:
                self.video_writer.release()
        self.save_settings()
        self.destroy()


if __name__ == "__main__":
    app = CamConnectApp()
    app.mainloop()
