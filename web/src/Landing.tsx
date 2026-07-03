import { useEffect, useState } from "react";
import {
  Camera, VideoCamera, DeviceMobile, Monitor, WifiHigh, Lightning,
  SlidersHorizontal, PictureInPicture, BatteryFull, Feather, GithubLogo,
  CaretDown, Record, ArrowsClockwise, FlipHorizontal, Sun, Moon,
  MagnifyingGlassPlus, Flashlight, WaveSine, ArrowRight, DownloadSimple,
  IconContext,
} from "@phosphor-icons/react";

const nav = [
  { label: "Features", href: "#features" },
  { label: "Setup guide", href: "#setup" },
  { label: "Specs", href: "#specs" },
  { label: "FAQ", href: "#faq" },
  { label: "GitHub", href: "https://github.com/rahadalways/camconnect" },
];

const features = [
  {
    icon: SlidersHorizontal,
    title: "A control room on your PC",
    body: "Rotate, mirror, flip, brightness, contrast, zoom, torch, camera switch, stream quality, everything adjustable from the desktop, live, mid-call. The phone stays on its stand; you never touch it.",
    tags: ["Rotate", "Mirror", "Brightness", "Zoom", "Torch", "Quality"],
    wide: true,
  },
  {
    icon: VideoCamera,
    title: "A real virtual webcam",
    body: "Fonocam registers as a genuine camera on Windows. Open Zoom, Google Meet, Discord, Teams or OBS and simply pick it from the camera list, no plugins, no capture-card tricks.",
    tags: ["Zoom", "Meet", "Discord", "Teams", "OBS"],
    wide: true,
  },
  {
    icon: Lightning,
    title: "Zero-typing setup",
    body: "Press Start on the phone and it appears in the desktop app by itself. No IP addresses, no ports, no pairing codes.",
  },
  {
    icon: WifiHigh,
    title: "WiFi or USB",
    body: "Wireless freedom around the room, or a cable for rock-solid low-latency video that charges the phone while you stream.",
  },
  {
    icon: Record,
    title: "Record on both ends",
    body: "The PC records the processed stream; the phone records a smooth hardware-encoded backup up to 4K with audio, immune to WiFi hiccups.",
  },
  {
    icon: PictureInPicture,
    title: "Floating facecam",
    body: "Pop the preview into a rounded always-on-top mini window. Drag it to a corner, scroll to resize, perfect for screen recordings.",
  },
  {
    icon: BatteryFull,
    title: "Runs in the background",
    body: "Leave the app or let the screen dim, streaming continues as a background service with a quiet notification and a battery saver.",
  },
  {
    icon: Feather,
    title: "Light and honest",
    body: "13 MB on your phone. No ads, no account, no tracking, no watermark. The full source code is public on GitHub.",
  },
];

const setupSteps = [
  { letter: "A", title: "Install the Windows app", body: "Download Fonocam-Setup.exe below and run it. If SmartScreen asks, choose More info → Run anyway, the app isn't code-signed yet, and the source is public." },
  { letter: "B", title: "Install the OBS driver (once)", body: "Fonocam uses the OBS Virtual Camera driver to appear as a real webcam. Install OBS Studio from obsproject.com once, you never need to open it." },
  { letter: "C", title: "Install the Android app", body: "Download Fonocam.apk on your phone and install it. Android will ask to allow installs from your browser, allow it, this is normal for apps outside the Play Store." },
  { letter: "D", title: "Press Start on the phone", body: "Open Fonocam, allow the camera and microphone, and tap the big ▶ start button. The status chip switches to WAITING FOR PC." },
  { letter: "E", title: "Connect from the desktop", body: "Open Fonocam on the PC, your phone is already in the device list (same WiFi). Click Connect. Live preview appears with FPS and resolution." },
  { letter: "F", title: "Start the virtual webcam", body: "Press Start virtual webcam. In Zoom, Meet, Discord or OBS, pick the camera named OBS Virtual Camera. You're live." },
  { letter: "G", title: "Optional: go USB", body: "Enable Developer options → USB debugging on the phone, plug in the cable and press USB in the desktop app, lower latency, zero WiFi drops, and the phone charges." },
  { letter: "H", title: "Optional: tune and record", body: "Use the VideoCamera and Phone tabs to frame and tune the image, hit ● to record on the PC, or the phone's record button for a 4K backup with sound." },
];

const specs = [
  ["Streaming", "MJPEG over HTTP · 480p / 720p / 1080p · up to 30 fps"],
  ["Discovery", "Automatic on the local network (UDP beacon, port 4748)"],
  ["Connections", "WiFi · USB (adb port-forward)"],
  ["Virtual camera", "OBS Virtual Camera driver · Fit / Fill framing · 30 or 60 fps output"],
  ["Recording", "PC: processed stream (MP4) · Phone: hardware-encoded up to 4K + audio"],
  ["Requirements", "Windows 10/11 · Android 7.0+ · same network or a USB cable"],
  ["Size", "Android app ~13 MB · Windows app ~72 MB"],
  ["Privacy", "No account · no cloud · video never leaves your network"],
];

const compare = [
  ["Image quality", "Your phone's real camera sensor", "Small plastic lens, soft 720p"],
  ["Low light", "Phone-grade processing", "Grainy"],
  ["Price", "Free", "$20 to $40"],
  ["Wireless", "Yes, place it anywhere", "Cable to the monitor"],
  ["Desktop controls", "Rotate, tune, zoom, record", "Usually none"],
  ["Backup recording", "Up to 4K with audio", "No"],
];

const faqs = [
  { q: "Is Fonocam really free?", a: "Yes, completely free, no ads, no account, no premium tier. The full source code is public on GitHub and every release is compiled publicly on GitHub Actions." },
  { q: "Do I need OBS Studio?", a: "You install it once so Windows registers the OBS Virtual Camera driver. After that you never need to open OBS, Fonocam pipes video into the driver on its own." },
  { q: "Does it work over USB?", a: "Yes. Enable USB debugging on the phone, plug in the cable and press USB in the desktop app. You get lower latency, zero WiFi drops, and the phone charges while streaming." },
  { q: "The video lags on WiFi. What can I do?", a: "Use 5 GHz WiFi, drop resolution to 720p, close bandwidth-heavy apps, or switch to USB for guaranteed low latency." },
  { q: "Does it keep working when I leave the app or the screen dims?", a: "Yes. Streaming continues as an Android background service with a quiet notification and a built-in battery saver." },
  { q: "Can it record video with sound?", a: "The PC records the processed stream to MP4. The phone can record a hardware-encoded backup up to 4K with audio, independent of the WiFi connection." },
  { q: "How do updates work?", a: "New releases are published on GitHub. The desktop app checks for updates on launch; the APK you install manually." },
  { q: "Why does Windows say 'unknown publisher'?", a: "The app isn't code-signed yet. Choose More info → Run anyway. The source is public, you can inspect or build it yourself." },
];

export default function Landing() {
  const [openFaq, setOpenFaq] = useState<number | null>(0);
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <IconContext.Provider value={{ weight: "duotone", size: 20 }}>
    <div className="min-h-screen text-foreground overflow-x-hidden">

      {/* NAV */}
      <header className="sticky top-0 z-40 border-b border-border/60 bg-background/80 backdrop-blur-xl">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3 sm:px-6 lg:px-8">
          <a href="#" className="flex items-center gap-2 font-display text-lg font-bold">
            <span className="icon-premium h-9 w-9">
              <Camera className="h-4 w-4" weight="fill" />
            </span>
            Fonocam
          </a>
          <nav className="hidden items-center gap-7 text-sm text-muted-foreground md:flex">
            {nav.map((n) => (
              <a key={n.label} href={n.href} className="transition-colors hover:text-foreground">{n.label}</a>
            ))}
          </nav>
          <div className="flex items-center gap-2">
            <ThemeToggle />
            <a href="#download" className="hidden rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground transition-transform hover:scale-[1.02] sm:inline-flex">
              Download free
            </a>
            <button
              aria-label="Menu"
              onClick={() => setMenuOpen((v) => !v)}
              className="grid h-9 w-9 place-items-center rounded-lg border border-border md:hidden"
            >
              <div className="flex flex-col gap-1">
                <span className="block h-0.5 w-4 bg-foreground" />
                <span className="block h-0.5 w-4 bg-foreground" />
              </div>
            </button>
          </div>
        </div>
        {menuOpen && (
          <div className="border-t border-border md:hidden">
            <nav className="mx-auto flex max-w-7xl flex-col px-4 py-3 text-sm">
              {nav.map((n) => (
                <a key={n.label} href={n.href} onClick={() => setMenuOpen(false)} className="border-b border-border/50 py-3 text-muted-foreground last:border-0">
                  {n.label}
                </a>
              ))}
              <a href="#download" onClick={() => setMenuOpen(false)} className="mt-3 rounded-lg bg-primary px-4 py-2.5 text-center text-sm font-semibold text-primary-foreground">
                Download free
              </a>
            </nav>
          </div>
        )}
      </header>

      {/* HERO */}
      <section className="px-4 pt-8 sm:px-6 sm:pt-14 lg:px-8">
        <div className="mx-auto max-w-6xl">
          <div className="corner-frame relative overflow-hidden rounded-3xl border border-border/60 bg-card/40 px-4 py-12 sm:px-10 sm:py-20 grid-bg">
            <span className="corner-bl" />
            <span className="corner-br" />

            {/* status chips */}
            <div className="mb-8 flex items-center justify-between text-[10px] font-mono tracking-widest text-muted-foreground sm:text-xs">
              <span>CAM 01 · PHONE</span>
              <span className="inline-flex items-center gap-1.5 rounded-full bg-destructive/20 px-2.5 py-1 text-destructive-foreground">
                <span className="h-1.5 w-1.5 rounded-full bg-red-500 animate-pulse" />
                <span className="text-red-300">LIVE</span>
              </span>
              <span className="text-emerald-400">PC CONNECTED</span>
            </div>

            <div className="mx-auto max-w-3xl text-center">
              <h1 className="font-display text-4xl font-bold leading-[1.05] tracking-tight sm:text-6xl md:text-7xl">
                Your phone is<br />
                the <span className="text-primary font-serif italic">camera.</span>
              </h1>
              <p className="mx-auto mt-6 max-w-xl text-base text-muted-foreground sm:text-lg">
                Fonocam turns your Android phone into a premium PC webcam over WiFi or USB, sharper than any budget webcam, with every control on your desktop.
              </p>

              <div className="mt-8 flex flex-col items-stretch justify-center gap-3 sm:flex-row sm:items-center">
                <a href="#download" className="inline-flex items-center justify-center gap-2 rounded-xl bg-primary px-6 py-3.5 font-semibold text-primary-foreground shadow-lg shadow-primary/20 transition-transform hover:scale-[1.02]">
                  <DownloadSimple className="h-4 w-4" /> Download for Windows
                </a>
                <a href="#download" className="inline-flex items-center justify-center gap-2 rounded-xl border border-border bg-card/60 px-6 py-3.5 font-semibold text-foreground transition-colors hover:bg-card">
                  <DeviceMobile className="h-4 w-4" /> Get the Android app
                </a>
              </div>

              <p className="mt-6 font-mono text-[10px] tracking-widest text-muted-foreground sm:text-xs">
                FREE · OPEN SOURCE · NO ACCOUNT · WINDOWS 10/11 · ANDROID 7+
              </p>
            </div>

            <div className="mt-10 flex items-center justify-between text-[10px] font-mono tracking-widest text-muted-foreground sm:text-xs">
              <span>1280×720 · 30FPS</span>
              <span>WIFI / USB</span>
            </div>
          </div>

          {/* Flow diagram */}
          <div className="mt-10 flex flex-col items-stretch justify-center gap-3 sm:flex-row sm:items-center sm:gap-2">
            <FlowNode icon={<DeviceMobile className="h-4 w-4 text-sky-400" />} title="Your phone" sub="CAMERA + APP" />
            <FlowConnector label="WIFI / USB" />
            <FlowNode icon={<Monitor className="h-4 w-4 text-primary" />} title="Fonocam Desktop" sub="PREVIEW + CONTROL" />
            <FlowConnector label="VIRTUAL CAM" />
            <FlowNode icon={<VideoCamera className="h-4 w-4 text-emerald-400" />} title="Zoom · Meet · OBS" sub="ANY VIDEO APP" />
          </div>
        </div>
      </section>

      {/* FEATURES */}
      <section id="features" className="px-4 py-20 sm:px-6 sm:py-28 lg:px-8">
        <div className="mx-auto max-w-6xl">
          <SectionEyebrow>Features</SectionEyebrow>
          <h2 className="mt-3 max-w-3xl font-display text-3xl font-bold sm:text-5xl">
            Everything a webcam should be
          </h2>
          <p className="mt-4 max-w-2xl text-muted-foreground">
            Your phone already has a better sensor than a $100 webcam. Fonocam puts it to work, and gives you a control room on the desktop.
          </p>

          <div className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-6">
            {features.map((f, i) => (
              <div
                key={f.title}
                className={`rounded-2xl border border-border/70 bg-card/60 p-6 transition-colors hover:border-primary/40 hover:bg-card ${
                  i < 2 ? "lg:col-span-3" : "lg:col-span-2"
                }`}
              >
                <div className="icon-premium-lg h-11 w-11">
                  <f.icon className="h-5 w-5" />
                </div>
                <h3 className="mt-5 font-display text-lg font-semibold">{f.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{f.body}</p>
                {f.tags && (
                  <div className="mt-4 flex flex-wrap gap-1.5">
                    {f.tags.map((t) => (
                      <span key={t} className="rounded-md border border-border px-2 py-1 font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                        {t}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>

          {/* Control strip visual */}
          <div className="mt-10 rounded-2xl border border-border/70 bg-card/50 p-4 sm:p-6">
            <div className="mb-4 flex items-center justify-between">
              <span className="font-mono text-[10px] tracking-widest text-muted-foreground">DESKTOP CONTROLS</span>
              <span className="inline-flex items-center gap-1 font-mono text-[10px] text-emerald-400"><WaveSine className="h-3 w-3" /> LIVE 30 FPS</span>
            </div>
            <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
              {[
                { i: ArrowsClockwise, l: "Rotate" }, { i: FlipHorizontal, l: "Mirror" },
                { i: Sun, l: "Bright" }, { i: MagnifyingGlassPlus, l: "Zoom" },
                { i: Flashlight, l: "Torch" }, { i: SlidersHorizontal, l: "Quality" },
              ].map(({ i: Ic, l }) => (
                <div key={l} className="flex flex-col items-center gap-2 rounded-xl border border-border bg-background/40 p-3">
                  <Ic className="h-5 w-5 text-primary" />
                  <span className="text-xs">{l}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* SETUP */}
      <section id="setup" className="border-t border-border/50 px-4 py-20 sm:px-6 sm:py-28 lg:px-8">
        <div className="mx-auto max-w-6xl">
          <SectionEyebrow>Setup guide</SectionEyebrow>
          <h2 className="mt-3 max-w-3xl font-display text-3xl font-bold sm:text-5xl">
            From download to live, A to Z
          </h2>
          <p className="mt-4 max-w-2xl text-muted-foreground">
            One-time setup takes about three minutes. After that it's two taps: Start on the phone, Connect on the PC.
          </p>

          <div className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {setupSteps.map((s) => (
              <div key={s.letter} className="relative rounded-2xl border border-border/70 bg-card/60 p-6">
                <span className="absolute right-5 top-5 font-display text-xl font-bold text-primary/70">{s.letter}</span>
                <h3 className="pr-8 font-display text-base font-semibold">{s.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{s.body}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* SPECS */}
      <section id="specs" className="border-t border-border/50 px-4 py-20 sm:px-6 sm:py-28 lg:px-8">
        <div className="mx-auto max-w-5xl">
          <SectionEyebrow>Specs</SectionEyebrow>
          <h2 className="mt-3 font-display text-3xl font-bold sm:text-5xl">Under the hood</h2>

          <div className="mt-10 overflow-hidden rounded-2xl border border-border/70">
            {specs.map(([k, v], i) => (
              <div
                key={k}
                className={`grid grid-cols-1 gap-1 px-5 py-4 sm:grid-cols-[200px_1fr] sm:gap-6 sm:px-6 sm:py-5 ${
                  i % 2 === 0 ? "bg-card/40" : "bg-card/20"
                }`}
              >
                <span className="font-mono text-[11px] uppercase tracking-widest text-muted-foreground">{k}</span>
                <span className="text-sm">{v}</span>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* COMPARE */}
      <section className="border-t border-border/50 px-4 py-20 sm:px-6 sm:py-28 lg:px-8">
        <div className="mx-auto max-w-5xl">
          <SectionEyebrow>Compare</SectionEyebrow>
          <h2 className="mt-3 font-display text-3xl font-bold sm:text-5xl">Why not just buy a webcam?</h2>

          <div className="mt-10 overflow-hidden rounded-2xl border border-border/70">
            <div className="hidden grid-cols-[1.2fr_1.4fr_1.4fr] bg-card/60 px-6 py-4 font-mono text-[10px] uppercase tracking-widest text-muted-foreground sm:grid">
              <span />
              <span className="text-primary">Fonocam + your phone</span>
              <span>Budget webcam ($20 to $40)</span>
            </div>
            {compare.map(([k, a, b], i) => (
              <div
                key={k}
                className={`grid grid-cols-1 gap-3 px-5 py-5 sm:grid-cols-[1.2fr_1.4fr_1.4fr] sm:gap-6 sm:px-6 ${
                  i % 2 === 0 ? "bg-card/30" : "bg-card/10"
                }`}
              >
                <span className="font-display font-semibold">{k}</span>
                <span className="flex items-start gap-2 text-sm">
                  <span className="mt-1 inline-block h-1.5 w-1.5 shrink-0 rounded-full bg-primary" />
                  <span>{a}</span>
                </span>
                <span className="flex items-start gap-2 text-sm text-muted-foreground">
                  <span className="mt-1 inline-block h-1.5 w-1.5 shrink-0 rounded-full bg-muted-foreground/50" />
                  <span>{b}</span>
                </span>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* DOWNLOAD */}
      <section id="download" className="px-4 py-20 sm:px-6 sm:py-28 lg:px-8">
        <div className="mx-auto max-w-5xl">
          <div className="corner-frame relative overflow-hidden rounded-3xl border border-border/70 bg-card/50 p-8 sm:p-14 grid-bg">
            <span className="corner-bl" />
            <span className="corner-br" />
            <SectionEyebrow>Download</SectionEyebrow>
            <h2 className="mt-3 font-display text-3xl font-bold sm:text-5xl">Get Fonocam, free</h2>

            <div className="mt-8 grid gap-4 md:grid-cols-2">
              <a
                href="https://github.com/rahadalways/camconnect/releases/latest/download/Fonocam-Setup.exe"
                className="group relative flex items-center gap-4 overflow-hidden rounded-2xl bg-primary p-5 text-primary-foreground shadow-[0_10px_40px_-12px_color-mix(in_oklab,var(--color-primary)_55%,transparent)] transition-all hover:-translate-y-0.5 hover:shadow-[0_20px_50px_-12px_color-mix(in_oklab,var(--color-primary)_65%,transparent)] sm:p-6"
              >
                <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-primary-foreground/15 ring-1 ring-inset ring-primary-foreground/25">
                  <DownloadSimple className="h-5 w-5" weight="duotone" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-[11px] font-medium uppercase tracking-[0.14em] opacity-80">Windows 10 / 11</span>
                  <span className="mt-0.5 block font-display text-base font-semibold leading-tight sm:text-lg">Fonocam for Windows</span>
                </span>
                <ArrowRight className="h-5 w-5 shrink-0 transition-transform group-hover:translate-x-1" weight="bold" />
              </a>

              <a
                href="https://github.com/rahadalways/camconnect/releases/latest/download/Fonocam.apk"
                className="group relative flex items-center gap-4 overflow-hidden rounded-2xl border border-border bg-background/60 p-5 backdrop-blur transition-all hover:-translate-y-0.5 hover:border-primary/50 hover:bg-background/80 sm:p-6"
              >
                <span className="icon-premium flex h-11 w-11 shrink-0 items-center justify-center rounded-xl">
                  <DeviceMobile className="h-5 w-5 text-primary" weight="duotone" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-[11px] font-medium uppercase tracking-[0.14em] text-muted-foreground">Android 7.0+</span>
                  <span className="mt-0.5 block font-display text-base font-semibold leading-tight sm:text-lg">Fonocam for Android <span className="text-muted-foreground">(.apk)</span></span>
                </span>
                <ArrowRight className="h-5 w-5 shrink-0 text-muted-foreground transition-all group-hover:translate-x-1 group-hover:text-primary" weight="bold" />
              </a>
            </div>

            <p className="mt-6 max-w-2xl text-sm text-muted-foreground">
              Windows installer with Start Menu shortcut and uninstaller. Prefer no installer? A portable exe is on the releases page. All builds are compiled publicly on GitHub Actions from the open source code.
            </p>

          </div>
        </div>
      </section>

      {/* FAQ */}
      <section id="faq" className="border-t border-border/50 px-4 py-20 sm:px-6 sm:py-28 lg:px-8">
        <div className="mx-auto max-w-3xl">
          <SectionEyebrow>FAQ</SectionEyebrow>
          <h2 className="mt-3 font-display text-3xl font-bold sm:text-5xl">Questions, answered</h2>

          <div className="mt-10 divide-y divide-border/60 overflow-hidden rounded-2xl border border-border/60 bg-card/30">
            {faqs.map((f, i) => {
              const open = openFaq === i;
              return (
                <button
                  key={f.q}
                  onClick={() => setOpenFaq(open ? null : i)}
                  className="w-full text-left"
                >
                  <div className="flex items-center justify-between gap-4 px-5 py-5 sm:px-6">
                    <span className="font-display text-base font-semibold">{f.q}</span>
                    <CaretDown className={`h-5 w-5 shrink-0 text-muted-foreground transition-transform ${open ? "rotate-180 text-primary" : ""}`} />
                  </div>
                  {open && (
                    <div className="px-5 pb-5 text-sm leading-relaxed text-muted-foreground sm:px-6">
                      {f.a}
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        </div>
      </section>

      {/* FOOTER */}
      <footer className="border-t border-border/50 px-4 py-14 sm:px-6 lg:px-8">
        <div className="mx-auto grid max-w-6xl gap-10 sm:grid-cols-[1.4fr_1fr_1fr]">
          <div>
            <a href="#" className="flex items-center gap-2 font-display text-lg font-bold">
              <span className="icon-premium h-9 w-9">
                <Camera className="h-4 w-4" weight="fill" />
              </span>
              Fonocam
            </a>
            <p className="mt-4 max-w-sm text-sm text-muted-foreground">
              Turn your Android phone into a premium PC webcam over WiFi or USB. Free, open source, built by Rahad.
            </p>
          </div>
          <div>
            <div className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">Product</div>
            <ul className="mt-4 space-y-2 text-sm">
              <li><a href="#features" className="hover:text-primary">Features</a></li>
              <li><a href="#setup" className="hover:text-primary">Setup guide</a></li>
              <li><a href="#specs" className="hover:text-primary">Specs</a></li>
              <li><a href="#faq" className="hover:text-primary">FAQ</a></li>
            </ul>
          </div>
          <div>
            <div className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">Project</div>
            <ul className="mt-4 space-y-2 text-sm">
              <li><a href="https://github.com/rahadalways/camconnect" target="_blank" rel="noopener" className="inline-flex items-center gap-2 hover:text-primary"><GithubLogo className="h-4 w-4" /> Source on GitHub</a></li>
              <li><a href="https://github.com/rahadalways/camconnect/releases" target="_blank" rel="noopener" className="hover:text-primary">All releases</a></li>
              <li><a href="https://github.com/rahadalways/camconnect/issues" target="_blank" rel="noopener" className="hover:text-primary">Report an issue</a></li>
            </ul>
          </div>
        </div>
        <div className="mx-auto mt-12 max-w-6xl border-t border-border/50 pt-6 text-center font-mono text-[10px] tracking-widest text-muted-foreground">
          © 2026 FONOCAM · MADE WITH 📱 + 💻 · YOUR VIDEO NEVER LEAVES YOUR NETWORK
        </div>
      </footer>
    </div>
    </IconContext.Provider>
  );
}

function SectionEyebrow({ children }: { children: React.ReactNode }) {
  return (
    <div className="inline-flex items-center gap-2 font-mono text-[10px] uppercase tracking-[0.25em] text-primary">
      <span className="h-px w-6 bg-primary" />
      {children}
    </div>
  );
}

function FlowNode({ icon, title, sub }: { icon: React.ReactNode; title: string; sub: string }) {
  return (
    <div className="flex min-w-0 items-center gap-3 rounded-xl border border-border bg-card/60 px-4 py-3">
      <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-background">{icon}</span>
      <div className="min-w-0">
        <div className="truncate text-sm font-semibold">{title}</div>
        <div className="truncate font-mono text-[9px] uppercase tracking-widest text-muted-foreground">{sub}</div>
      </div>
    </div>
  );
}

function FlowConnector({ label }: { label: string }) {
  return (
    <div className="flex items-center justify-center gap-2 font-mono text-[9px] uppercase tracking-widest text-muted-foreground sm:flex-col sm:gap-1">
      <span className="hidden h-px w-6 border-t border-dashed border-border sm:block" />
      <span className="sm:rotate-0">{label}</span>
      <span className="hidden h-px w-6 border-t border-dashed border-border sm:block" />
    </div>
  );
}

function ThemeToggle() {
  const [isLight, setIsLight] = useState(false);
  useEffect(() => {
    setIsLight(document.documentElement.classList.contains("light"));
  }, []);
  const toggle = () => {
    const next = !isLight;
    setIsLight(next);
    document.documentElement.classList.toggle("light", next);
    try { localStorage.setItem("theme", next ? "light" : "dark"); } catch {}
  };
  return (
    <button
      aria-label="Toggle theme"
      onClick={toggle}
      className="grid h-9 w-9 place-items-center rounded-lg border border-border bg-card/60 text-foreground transition-colors hover:border-primary/40 hover:text-primary"
    >
      {isLight ? <Moon className="h-4 w-4" /> : <Sun className="h-4 w-4" />}
    </button>
  );
}
