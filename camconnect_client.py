import os
import time
import json
import queue
import threading
import tkinter as tk
from tkinter import messagebox
import customtkinter as ctk
import cv2
import numpy as np
import PIL.Image
import PIL.ImageTk
import requests

# Settings file matching the original PC Client settings
SETTINGS_FILE = "camconnect_settings.json"

# Set appearance mode and color theme for CustomTkinter
ctk.set_appearance_mode("Dark")
ctk.set_default_color_theme("blue")

class CamConnectClientApp(ctk.CTk):
    def __init__(self):
        super().__init__()
        
        # Configure Window (Compact, DroidCam-style vertical remote format)
        self.title("CamConnect Controller")
        self.geometry("420x720")
        self.minsize(400, 680)
        self.resizable(True, True)
        
        # Threading and State Variables
        self.running = False
        self.thread = None
        self.frame_lock = threading.Lock()
        self.latest_frame = None
        self.current_frame = None
        self.processed_frame = None
        self.new_frame_available = False
        
        # Camera server states
        self.current_zoom = 1.0
        
        # Queue for thread-safe UI updates (e.g. status changes)
        self.status_queue = queue.Queue()
        
        # Build UI layout
        self.setup_ui()
        
        # Load last used settings
        self.load_settings()
        
        # Start periodic GUI updates
        self.update_gui_frame()
        self.check_status_queue()
        
        # Set protocol for clean application shutdown
        self.protocol("WM_DELETE_WINDOW", self.on_closing)

    def setup_ui(self):
        # Configure main grid (1 Column, multiple rows stacked)
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(2, weight=1) # Video stream area expands
        
        # ==========================================
        # 1. HEADER TITLE
        # ==========================================
        self.logo_label = ctk.CTkLabel(
            self, 
            text="📐 CamConnect Client", 
            font=ctk.CTkFont(family="Inter", size=20, weight="bold"),
            text_color="#BB86FC"
        )
        self.logo_label.grid(row=0, column=0, padx=15, pady=(15, 2), sticky="w")
        
        self.sub_logo = ctk.CTkLabel(
            self,
            text="Mobile camera controller & receiver",
            font=ctk.CTkFont(family="Inter", size=11, weight="normal"),
            text_color="gray"
        )
        self.sub_logo.grid(row=1, column=0, padx=15, pady=(0, 10), sticky="w")

        # ==========================================
        # 2. CONNECTION SETUP CARD
        # ==========================================
        self.conn_frame = ctk.CTkFrame(self)
        self.conn_frame.grid(row=2, column=0, padx=15, pady=5, sticky="nsew")
        self.conn_frame.columnconfigure(1, weight=1)
        self.conn_frame.rowconfigure(4, weight=1)
        
        # IP Address
        self.ip_label = ctk.CTkLabel(self.conn_frame, text="Phone IP:", font=ctk.CTkFont(family="Inter", size=12))
        self.ip_label.grid(row=0, column=0, padx=(15, 5), pady=(10, 5), sticky="w")
        self.ip_entry = ctk.CTkEntry(self.conn_frame, placeholder_text="192.168.0.101")
        self.ip_entry.grid(row=0, column=1, padx=(5, 15), pady=(10, 5), sticky="ew")
        
        # Port
        self.port_label = ctk.CTkLabel(self.conn_frame, text="Port:", font=ctk.CTkFont(family="Inter", size=12))
        self.port_label.grid(row=1, column=0, padx=(15, 5), pady=5, sticky="w")
        self.port_entry = ctk.CTkEntry(self.conn_frame, placeholder_text="8080")
        self.port_entry.grid(row=1, column=1, padx=(5, 15), pady=5, sticky="ew")
        
        # Security PIN
        self.pin_label = ctk.CTkLabel(self.conn_frame, text="PIN (Optional):", font=ctk.CTkFont(family="Inter", size=12))
        self.pin_label.grid(row=2, column=0, padx=(15, 5), pady=5, sticky="w")
        
        self.pin_input_container = ctk.CTkFrame(self.conn_frame, fg_color="transparent")
        self.pin_input_container.grid(row=2, column=1, padx=(5, 15), pady=5, sticky="ew")
        self.pin_input_container.columnconfigure(0, weight=1)
        
        self.pin_entry = ctk.CTkEntry(self.pin_input_container, placeholder_text="Enter PIN if required", show="*")
        self.pin_entry.grid(row=0, column=0, sticky="ew")
        
        self.show_pin_var = ctk.StringVar(value="off")
        self.show_pin_cb = ctk.CTkCheckBox(
            self.pin_input_container, 
            text="Show", 
            width=50,
            variable=self.show_pin_var, 
            onvalue="on", 
            offvalue="off", 
            command=self.toggle_pin_visibility, 
            font=ctk.CTkFont(family="Inter", size=11)
        )
        self.show_pin_cb.grid(row=0, column=1, padx=(5, 0))
        
        # Connect & Disconnect Buttons
        self.btn_container = ctk.CTkFrame(self.conn_frame, fg_color="transparent")
        self.btn_container.grid(row=3, column=0, columnspan=2, padx=15, pady=(10, 10), sticky="ew")
        self.btn_container.columnconfigure(0, weight=1)
        self.btn_container.columnconfigure(1, weight=1)
        
        self.connect_btn = ctk.CTkButton(
            self.btn_container, 
            text="Connect", 
            command=self.start_connection, 
            fg_color="#6200EE", 
            hover_color="#3700B3",
            font=ctk.CTkFont(family="Inter", weight="bold")
        )
        self.connect_btn.grid(row=0, column=0, padx=(0, 5), sticky="ew")
        
        self.disconnect_btn = ctk.CTkButton(
            self.btn_container, 
            text="Disconnect", 
            command=self.stop_connection, 
            fg_color="#CF6679", 
            hover_color="#B00020", 
            text_color="#000000",
            state="disabled",
            font=ctk.CTkFont(family="Inter", weight="bold")
        )
        self.disconnect_btn.grid(row=0, column=1, padx=(5, 0), sticky="ew")

        # ==========================================
        # 3. VIDEO CONTAINER & FEED
        # ==========================================
        self.video_container = ctk.CTkFrame(self, fg_color="#0a0a0a", corner_radius=8)
        self.video_container.grid(row=3, column=0, padx=15, pady=5, sticky="nsew")
        self.video_container.grid_columnconfigure(0, weight=1)
        self.video_container.grid_rowconfigure(0, weight=1)
        
        # Placeholder view (displayed when disconnected)
        self.placeholder_frame = ctk.CTkFrame(self.video_container, fg_color="transparent")
        self.placeholder_frame.grid(row=0, column=0, sticky="nsew")
        self.placeholder_frame.grid_columnconfigure(0, weight=1)
        self.placeholder_frame.grid_rowconfigure(0, weight=1)
        
        self.placeholder_label = ctk.CTkLabel(
            self.placeholder_frame, 
            text="Disconnected\nEnter phone connection details above\nand click 'Connect'", 
            font=ctk.CTkFont(family="Inter", size=13, weight="bold"),
            text_color="#555555"
        )
        self.placeholder_label.grid(row=0, column=0, padx=20, pady=20)
        
        # High performance raw image label (Hidden initially)
        self.video_label = tk.Label(self.video_container, bg="#0a0a0a")
        # Do not grid initially

        # ==========================================
        # 4. STREAM STATUS ROW
        # ==========================================
        self.status_frame = ctk.CTkFrame(self, height=35)
        self.status_frame.grid(row=4, column=0, padx=15, pady=5, sticky="ew")
        
        self.status_led = tk.Canvas(self.status_frame, width=10, height=10, highlightthickness=0)
        self.status_led.pack(side="left", padx=(15, 8), pady=12)
        self.led_circle = self.status_led.create_oval(1, 1, 9, 9, fill="#555555", outline="")
        
        self.status_text_label = ctk.CTkLabel(
            self.status_frame, 
            text="Offline", 
            font=ctk.CTkFont(family="Inter", weight="bold", size=12)
        )
        self.status_text_label.pack(side="left", pady=5)
        
        # Dynamic canvas coloring callback
        self.update_idletasks()
        self.update_canvas_bg()

        # ==========================================
        # 5. CAMERA REMOTE CONTROLS CARD
        # ==========================================
        self.remote_frame = ctk.CTkFrame(self)
        self.remote_frame.grid(row=5, column=0, padx=15, pady=5, sticky="ew")
        self.remote_frame.columnconfigure((0, 1, 2, 3), weight=1)
        
        self.remote_title = ctk.CTkLabel(
            self.remote_frame, 
            text="PHONE CAMERA REMOTE CONTROLS", 
            font=ctk.CTkFont(family="Inter", size=10, weight="bold"), 
            text_color="#BB86FC"
        )
        self.remote_title.grid(row=0, column=0, columnspan=4, padx=15, pady=(8, 4), sticky="w")
        
        # Remote control buttons (Disabled by default until connected)
        self.flash_btn = ctk.CTkButton(
            self.remote_frame, text="⚡ Flash", command=lambda: self.send_action("toggle-flash"), 
            height=32, state="disabled", fg_color="#2b2b2b", hover_color="#3a3a3a"
        )
        self.flash_btn.grid(row=1, column=0, padx=5, pady=(2, 10), sticky="ew")
        
        self.flip_btn = ctk.CTkButton(
            self.remote_frame, text="🔄 Flip Cam", command=lambda: self.send_action("switch-camera"), 
            height=32, state="disabled", fg_color="#2b2b2b", hover_color="#3a3a3a"
        )
        self.flip_btn.grid(row=1, column=1, padx=5, pady=(2, 10), sticky="ew")
        
        self.zoom_in_btn = ctk.CTkButton(
            self.remote_frame, text="🔍 Zoom +", command=lambda: self.adjust_zoom(0.2), 
            height=32, state="disabled", fg_color="#2b2b2b", hover_color="#3a3a3a"
        )
        self.zoom_in_btn.grid(row=1, column=2, padx=5, pady=(2, 10), sticky="ew")
        
        self.zoom_out_btn = ctk.CTkButton(
            self.remote_frame, text="🔍 Zoom -", command=lambda: self.adjust_zoom(-0.2), 
            height=32, state="disabled", fg_color="#2b2b2b", hover_color="#3a3a3a"
        )
        self.zoom_out_btn.grid(row=1, column=3, padx=5, pady=(2, 10), sticky="ew")

        # ==========================================
        # 6. LOCAL PREVIEW CONTROLS CARD
        # ==========================================
        self.local_frame = ctk.CTkFrame(self)
        self.local_frame.grid(row=6, column=0, padx=15, pady=5, sticky="ew")
        self.local_frame.columnconfigure((0, 1), weight=1)
        
        # Rotation
        self.rot_label = ctk.CTkLabel(self.local_frame, text="Local Rotate:", font=ctk.CTkFont(family="Inter", size=11))
        self.rot_label.grid(row=0, column=0, padx=(15, 5), pady=(8, 2), sticky="w")
        self.rotation_var = ctk.StringVar(value="0°")
        self.rot_menu = ctk.CTkOptionMenu(
            self.local_frame, values=["0°", "90°", "180°", "270°"], variable=self.rotation_var, height=28
        )
        self.rot_menu.grid(row=0, column=1, padx=(5, 15), pady=(8, 2), sticky="ew")
        
        # Aspect Ratio
        self.aspect_label = ctk.CTkLabel(self.local_frame, text="Local Crop:", font=ctk.CTkFont(family="Inter", size=11))
        self.aspect_label.grid(row=1, column=0, padx=(15, 5), pady=(2, 8), sticky="w")
        self.aspect_ratio_var = ctk.StringVar(value="Source")
        self.aspect_menu = ctk.CTkOptionMenu(
            self.local_frame, values=["Source", "16:9", "9:16", "4:3", "1:1"], variable=self.aspect_ratio_var, height=28
        )
        self.aspect_menu.grid(row=1, column=1, padx=(5, 15), pady=(2, 8), sticky="ew")

        # ==========================================
        # 7. SNAPSHOT BUTTON & TOAST NOTIFICATION
        # ==========================================
        self.snapshot_btn = ctk.CTkButton(
            self, 
            text="📸 TAKE SNAPSHOT (সেভ করুন)", 
            command=self.take_snapshot, 
            fg_color="#2b712b", 
            hover_color="#1b4d1b", 
            state="disabled",
            height=38,
            font=ctk.CTkFont(family="Inter", weight="bold")
        )
        self.snapshot_btn.grid(row=7, column=0, padx=15, pady=(10, 5), sticky="ew")
        
        # Toast notifications overlay
        self.notification_label = ctk.CTkLabel(
            self, 
            text="", 
            font=ctk.CTkFont(family="Inter", size=12, weight="bold"),
            text_color="#2ecc71"
        )
        self.notification_label.grid(row=8, column=0, padx=15, pady=(0, 10))

    def update_canvas_bg(self):
        bg_color = self.status_frame.cget("fg_color")
        if isinstance(bg_color, (list, tuple)):
            bg_color = bg_color[1] if ctk.get_appearance_mode() == "Dark" else bg_color[0]
        self.status_led.configure(bg=bg_color)

    def load_settings(self):
        default_ip = "192.168.0.101"
        default_port = "8080"
        default_pin = ""
        
        if os.path.exists(SETTINGS_FILE):
            try:
                with open(SETTINGS_FILE, "r") as f:
                    data = json.load(f)
                    default_ip = data.get("ip", default_ip)
                    default_port = data.get("port", default_port)
                    default_pin = data.get("pin", default_pin)
            except Exception:
                pass
                
        self.ip_entry.insert(0, default_ip)
        self.port_entry.insert(0, default_port)
        self.pin_entry.insert(0, default_pin)

    def save_settings(self, ip, port, pin):
        try:
            with open(SETTINGS_FILE, "w") as f:
                json.dump({"ip": ip, "port": port, "pin": pin}, f)
        except Exception:
            pass

    def toggle_pin_visibility(self):
        if self.show_pin_var.get() == "on":
            self.pin_entry.configure(show="")
        else:
            self.pin_entry.configure(show="*")

    def show_notification(self, text, color="#2ecc71", duration=2500):
        self.notification_label.configure(text=text, text_color=color)
        self.after(duration, lambda: self.notification_label.configure(text=""))

    def start_connection(self):
        ip = self.ip_entry.get().strip()
        port = self.port_entry.get().strip()
        pin = self.pin_entry.get().strip()
        
        if not ip or not port:
            messagebox.showerror("Error", "Please enter a valid Phone IP address and Port.")
            return
            
        # Save connection details to settings file
        self.save_settings(ip, port, pin)
        
        self.running = True
        self.set_ui_state("connecting")
        self.update_status("Connecting...", "#d4af37")
        
        # Display video placeholder grid, hide placeholder text
        self.placeholder_frame.grid_forget()
        self.video_label.grid(row=0, column=0, sticky="nsew")
        
        # Start background stream reading thread
        self.thread = threading.Thread(target=self.stream_reader, args=(ip, port, pin))
        self.thread.daemon = True
        self.thread.start()

    def stop_connection(self):
        self.running = False
        
        # Reset state & frames
        with self.frame_lock:
            self.latest_frame = None
            self.new_frame_available = False
        self.current_frame = None
        self.processed_frame = None
        
        # Clear video display label photo reference to free memory
        self.video_label.configure(image="")
        self.video_label.grid_forget()
        self.placeholder_frame.grid(row=0, column=0, sticky="nsew")
        
        # Reset UI controls
        self.set_ui_state("disconnected")
        self.update_status("Offline", "#555555")

    def set_ui_state(self, state):
        if state == "disconnected":
            self.connect_btn.configure(state="normal")
            self.disconnect_btn.configure(state="disabled")
            
            # Disable remote camera controller buttons
            self.flash_btn.configure(state="disabled")
            self.flip_btn.configure(state="disabled")
            self.zoom_in_btn.configure(state="disabled")
            self.zoom_out_btn.configure(state="disabled")
            self.snapshot_btn.configure(state="disabled")
            
            self.ip_entry.configure(state="normal")
            self.port_entry.configure(state="normal")
            self.pin_entry.configure(state="normal")
        elif state == "connecting":
            self.connect_btn.configure(state="disabled")
            self.disconnect_btn.configure(state="normal")
            
            self.flash_btn.configure(state="disabled")
            self.flip_btn.configure(state="disabled")
            self.zoom_in_btn.configure(state="disabled")
            self.zoom_out_btn.configure(state="disabled")
            self.snapshot_btn.configure(state="disabled")
            
            self.ip_entry.configure(state="disabled")
            self.port_entry.configure(state="disabled")
            self.pin_entry.configure(state="disabled")
        elif state == "connected":
            self.connect_btn.configure(state="disabled")
            self.disconnect_btn.configure(state="normal")
            
            # Enable remote camera controller buttons
            self.flash_btn.configure(state="normal")
            self.flip_btn.configure(state="normal")
            self.zoom_in_btn.configure(state="normal")
            self.zoom_out_btn.configure(state="normal")
            self.snapshot_btn.configure(state="normal")
            
            self.ip_entry.configure(state="disabled")
            self.port_entry.configure(state="disabled")
            self.pin_entry.configure(state="disabled")

    def update_status(self, text, color):
        self.status_text_label.configure(text=text)
        self.status_led.itemconfig(self.led_circle, fill=color)

    def set_status_threadsafe(self, text, color, state):
        self.status_queue.put((text, color, state))

    def check_status_queue(self):
        while not self.status_queue.empty():
            try:
                text, color, state = self.status_queue.get_nowait()
                self.update_status(text, color)
                self.set_ui_state(state)
            except Exception:
                pass
        self.after(100, self.check_status_queue)

    def send_action(self, action, value=None):
        ip = self.ip_entry.get().strip()
        port = self.port_entry.get().strip()
        pin = self.pin_entry.get().strip()
        
        url = f"http://{ip}:{port}/action/{action}"
        params = {}
        if pin:
            params["pin"] = pin
        if value is not None:
            params["value"] = str(value)
            
        def action_worker():
            try:
                response = requests.get(url, params=params, timeout=2.0)
                if response.status_code == 200:
                    self.show_notification(f"Command '{action}' sent successfully!")
                else:
                    self.show_notification(f"Failed to control phone camera ({response.status_code})", "#e74c3c")
            except Exception as e:
                self.show_notification(f"Remote control failed", "#e74c3c")
                
        threading.Thread(target=action_worker, daemon=True).start()

    def adjust_zoom(self, step):
        self.current_zoom = round(max(1.0, min(5.0, self.current_zoom + step)), 1)
        self.send_action("zoom", self.current_zoom)
        self.show_notification(f"Zoom level: {self.current_zoom}x")

    def stream_reader(self, ip, port, pin):
        url = f"http://{ip}:{port}/video"
        if pin:
            url += f"?pin={pin}"
            
        try:
            with requests.get(url, stream=True, timeout=5.0) as response:
                if response.status_code == 401:
                    self.set_status_threadsafe("Error: Invalid PIN (401)", "#e74c3c", "disconnected")
                    return
                elif response.status_code != 200:
                    self.set_status_threadsafe(f"Error Code: {response.status_code}", "#e74c3c", "disconnected")
                    return
                
                self.set_status_threadsafe("Connected", "#2ecc71", "connected")
                
                bytes_buffer = bytes()
                for chunk in response.iter_content(chunk_size=8192):
                    if not self.running:
                        break
                    
                    bytes_buffer += chunk
                    
                    while True:
                        a = bytes_buffer.find(b'\xff\xd8')
                        if a == -1:
                            break
                        
                        b = bytes_buffer.find(b'\xff\xd9', a)
                        if b == -1:
                            bytes_buffer = bytes_buffer[a:]
                            break
                        
                        jpg = bytes_buffer[a:b+2]
                        bytes_buffer = bytes_buffer[b+2:]
                        
                        frame = cv2.imdecode(np.frombuffer(jpg, dtype=np.uint8), cv2.IMREAD_COLOR)
                        if frame is not None:
                            with self.frame_lock:
                                self.latest_frame = frame
                                self.new_frame_available = True
                                
        except requests.exceptions.Timeout:
            self.set_status_threadsafe("Error: Connection Timeout", "#e74c3c", "disconnected")
        except requests.exceptions.ConnectionError:
            self.set_status_threadsafe("Error: Server Offline / Bad IP", "#e74c3c", "disconnected")
        except Exception as e:
            self.set_status_threadsafe(f"Disconnected: {str(e)[:25]}", "#e74c3c", "disconnected")
        finally:
            self.running = False

    def update_gui_frame(self):
        frame = None
        if self.running or self.latest_frame is not None:
            with self.frame_lock:
                if self.new_frame_available and self.latest_frame is not None:
                    self.current_frame = self.latest_frame.copy()
                    self.new_frame_available = False
            
            frame = self.current_frame
            
        if frame is not None:
            # 1. Apply Rotation
            rot = self.rotation_var.get()
            if rot == "90°":
                frame = cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)
            elif rot == "180°":
                frame = cv2.rotate(frame, cv2.ROTATE_180)
            elif rot == "270°":
                frame = cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)
            
            # 2. Apply Aspect Ratio Crop
            aspect = self.aspect_ratio_var.get()
            if aspect != "Source":
                h, w = frame.shape[:2]
                target_ratio = None
                
                if aspect == "16:9":
                    target_ratio = 16.0 / 9.0
                elif aspect == "9:16":
                    target_ratio = 9.0 / 16.0
                elif aspect == "4:3":
                    target_ratio = 4.0 / 3.0
                elif aspect == "1:1":
                    target_ratio = 1.0
                    
                if target_ratio:
                    current_ratio = w / h
                    if current_ratio > target_ratio:
                        new_w = int(h * target_ratio)
                        start_x = (w - new_w) // 2
                        frame = frame[:, start_x : start_x + new_w]
                    else:
                        new_h = int(w / target_ratio)
                        start_y = (h - new_h) // 2
                        frame = frame[start_y : start_y + new_h, :]
                        
            self.processed_frame = frame.copy()
            
            # 3. Dynamic Scaling to Fit Video Container Area
            dw = self.video_container.winfo_width()
            dh = self.video_container.winfo_height()
            
            dw = max(50, dw - 10)
            dh = max(50, dh - 10)
            
            fh, fw = frame.shape[:2]
            if fw > 0 and fh > 0:
                scale_w = dw / fw
                scale_h = dh / fh
                scale = min(scale_w, scale_h)
                
                new_w = int(fw * scale)
                new_h = int(fh * scale)
                
                if new_w > 0 and new_h > 0:
                    frame_resized = cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_AREA)
                    frame_rgb = cv2.cvtColor(frame_resized, cv2.COLOR_BGR2RGB)
                    pil_img = PIL.Image.fromarray(frame_rgb)
                    
                    self.tk_image = PIL.ImageTk.PhotoImage(image=pil_img)
                    self.video_label.configure(image=self.tk_image)
                    
        self.after(30, self.update_gui_frame)

    def take_snapshot(self):
        if self.processed_frame is None:
            self.show_notification("No video frame to capture!", "#e74c3c")
            return
            
        try:
            desktop_dir = os.path.join(os.path.expanduser("~"), "Desktop")
            if not os.path.exists(desktop_dir):
                desktop_dir = os.path.expanduser("~")
                
            timestamp = time.strftime("%Y%m%d_%H%M%S")
            filename = f"CamConnect_Snapshot_{timestamp}.png"
            filepath = os.path.join(desktop_dir, filename)
            
            success = cv2.imwrite(filepath, self.processed_frame)
            
            if success:
                self.show_notification(f"Snapshot saved: {filename}", "#2ecc71")
            else:
                self.show_notification("Failed to save snapshot file", "#e74c3c")
        except Exception as e:
            self.show_notification(f"Error: {str(e)[:30]}", "#e74c3c")

    def on_closing(self):
        self.running = False
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)
        self.destroy()

if __name__ == "__main__":
    app = CamConnectClientApp()
    app.mainloop()
