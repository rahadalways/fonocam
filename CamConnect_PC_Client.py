import os
import sys
import time
import json
import threading
import urllib.request
import urllib.parse
import cv2
import numpy as np
import tkinter as tk
from tkinter import ttk, messagebox

# Settings file to remember IP, Port and PIN
SETTINGS_FILE = "camconnect_settings.json"

class CamConnectClient:
    def __init__(self, root):
        self.root = root
        self.root.title("CamConnect - HD PC Webcam Client")
        self.root.geometry("450x380")
        self.root.configure(bg="#121212")
        self.root.resizable(False, False)

        # Style configurations
        self.style = ttk.Style()
        self.style.theme_use('clam')
        
        # Dark theme styling
        self.style.configure('TLabel', background='#121212', foreground='#E0E0E0', font=('Arial', 10))
        self.style.configure('TEntry', fieldbackground='#1E1E1E', foreground='#FFFFFF', insertcolor='#FFFFFF', bordercolor='#333333')
        self.style.configure('Action.TButton', background='#6200EE', foreground='#FFFFFF', bordercolor='#6200EE', font=('Arial', 11, 'bold'))
        self.style.map('Action.TButton', background=[('active', '#3700B3')])
        self.style.configure('Reset.TButton', background='#CF6679', foreground='#000000', font=('Arial', 9))
        self.style.map('Reset.TButton', background=[('active', '#B00020')])

        # Header Title
        title_label = tk.Label(
            root, 
            text="📐 CamConnect", 
            fg="#BB86FC", 
            bg="#121212", 
            font=('Arial', 20, 'bold'),
            pady=10
        )
        title_label.pack()

        subtitle_label = tk.Label(
            root, 
            text="High-Resolution, Zero-Lag Android Live Stream", 
            fg="#9E9E9E", 
            bg="#121212", 
            font=('Arial', 9, 'italic')
        )
        subtitle_label.pack()

        # Frame for controls
        frame = tk.Frame(root, bg="#1E1E1E", bd=2, relief="groove", padx=20, pady=20)
        frame.pack(pady=15, padx=20, fill="both", expand=True)

        # IP Address
        lbl_ip = ttk.Label(frame, text="Phone IP Address (ফোনের আইপি):")
        lbl_ip.grid(row=0, column=0, sticky="w", pady=5)
        self.ent_ip = ttk.Entry(frame, width=28, font=('Arial', 10))
        self.ent_ip.grid(row=0, column=1, pady=5, padx=10)

        # Port
        lbl_port = ttk.Label(frame, text="Server Port (পোর্ট):")
        lbl_port.grid(row=1, column=0, sticky="w", pady=5)
        self.ent_port = ttk.Entry(frame, width=28, font=('Arial', 10))
        self.ent_port.grid(row=1, column=1, pady=5, padx=10)

        # PIN (Optional)
        lbl_pin = ttk.Label(frame, text="Security PIN (পিন - ঐচ্ছিক):")
        lbl_pin.grid(row=2, column=0, sticky="w", pady=5)
        self.ent_pin = ttk.Entry(frame, width=28, font=('Arial', 10), show="*")
        self.ent_pin.grid(row=2, column=1, pady=5, padx=10)

        # Load Saved Settings
        self.load_settings()

        # Connect Button
        btn_connect = ttk.Button(
            root, 
            text="🎥 START FULL HD WEBCAM (ক্যামেরা চালু করুন)", 
            style='Action.TButton',
            command=self.start_streaming_thread
        )
        btn_connect.pack(pady=10, fill="x", padx=20)

        # Instructions/Footer
        footer_label = tk.Label(
            root,
            text="Shortcuts in Camera: [R] Rotate | [S] Snapshot | [Q] Quit\nক্যামেরা উইন্ডো সিলেক্ট থাকা অবস্থায় ঘুরানোর জন্য R চাপুন, স্ক্রিনশটের জন্য S",
            fg="#03DAC6",
            bg="#121212",
            font=('Arial', 8, 'bold'),
            pady=5
        )
        footer_label.pack()

        self.streaming = False

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
                
        self.ent_ip.insert(0, default_ip)
        self.ent_port.insert(0, default_port)
        self.ent_pin.insert(0, default_pin)

    def save_settings(self, ip, port, pin):
        try:
            with open(SETTINGS_FILE, "w") as f:
                json.dump({"ip": ip, "port": port, "pin": pin}, f)
        except Exception:
            pass

    def start_streaming_thread(self):
        if self.streaming:
            messagebox.showinfo("Already Running", "Webcam stream is already running.")
            return
            
        ip = self.ent_ip.get().strip()
        port = self.ent_port.get().strip()
        pin = self.ent_pin.get().strip()

        if not ip or not port:
            messagebox.showerror("Error", "Please enter a valid Phone IP address and Port.")
            return

        # Save settings for next time
        self.save_settings(ip, port, pin)

        # Build stream URL
        query = f"?pin={pin}" if pin else ""
        self.stream_url = f"http://{ip}:{port}/video{query}"
        
        self.streaming = True
        threading.Thread(target=self.run_stream_viewer, daemon=True).start()

    def run_stream_viewer(self):
        print(f"Connecting to CamConnect Stream: {self.stream_url}")
        try:
            # Request setup with timeout
            req = urllib.request.Request(self.stream_url)
            stream = urllib.request.urlopen(req, timeout=5)
        except Exception as e:
            self.streaming = False
            self.root.after(0, lambda: messagebox.showerror("Connection Failed", 
                f"Could not connect to CamConnect server.\n\nMake sure:\n1. Your phone's 'Start Phone Server' is ON\n2. Phone and PC are on the same Wi-Fi\n3. The IP address is correct.\n\nError: {str(e)}"))
            return

        bytes_buffer = bytes()
        rotation_angle = 0  # 0, 90, 180, 270
        
        cv2.namedWindow("CamConnect WebCam Feed", cv2.WINDOW_NORMAL)
        cv2.resizeWindow("CamConnect WebCam Feed", 1280, 720)

        # Main fast streaming decoder loop
        while self.streaming:
            try:
                chunk = stream.read(4096)
                if not chunk:
                    break
                bytes_buffer += chunk
                
                # Look for JPEG boundary
                a = bytes_buffer.find(b'\xff\xd8') # JPEG Start
                b = bytes_buffer.find(b'\xff\xd9') # JPEG End
                
                if a != -1 and b != -1 and b > a:
                    jpg = bytes_buffer[a:b+2]
                    bytes_buffer = bytes_buffer[b+2:]
                    
                    # High-speed decode to raw matrix
                    frame = cv2.imdecode(np.frombuffer(jpg, dtype=np.uint8), cv2.IMREAD_COLOR)
                    
                    if frame is not None:
                        # Apply live PC rotation
                        if rotation_angle == 90:
                            frame = cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)
                        elif rotation_angle == 180:
                            frame = cv2.rotate(frame, cv2.ROTATE_180)
                        elif rotation_angle == 270:
                            frame = cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)
                        
                        # Add a clean text indicator in corner
                        h, w = frame.shape[:2]
                        cv2.putText(
                            frame, 
                            f"HD LIVE | {w}x{h} | R to Rotate", 
                            (15, 30), 
                            cv2.FONT_HERSHEY_SIMPLEX, 
                            0.7, 
                            (0, 255, 3), 
                            2, 
                            cv2.LINE_AA
                        )
                        
                        cv2.imshow("CamConnect WebCam Feed", frame)
                    
                    # Check keyboard shortcuts in open window
                    key = cv2.waitKey(1) & 0xFF
                    if key == ord('q') or key == 27: # 'q' or 'ESC' to exit
                        break
                    elif key == ord('r'): # 'r' to rotate
                        rotation_angle = (rotation_angle + 90) % 360
                    elif key == ord('s'): # 's' to take snapshot
                        timestamp = int(time.time())
                        filename = f"Snapshot_{timestamp}.jpg"
                        cv2.imwrite(filename, frame)
                        print(f"Snapshot saved to PC as {filename}")
                        # Draw visual confirmation
                        confirm_text = "SNAPSHOT SAVED!"
                        cv2.putText(frame, confirm_text, (w//2 - 120, h//2), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 127, 255), 3, cv2.LINE_AA)
                        cv2.imshow("CamConnect WebCam Feed", frame)
                        cv2.waitKey(400)
                        
            except Exception as e:
                print(f"Stream decode error: {e}")
                break

        # Cleanup
        self.streaming = False
        cv2.destroyAllWindows()
        try:
            stream.close()
        except Exception:
            pass

if __name__ == "__main__":
    root = tk.Tk()
    app = CamConnectClient(root)
    root.mainloop()
