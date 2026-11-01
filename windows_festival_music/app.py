"""MOMENTO Festival Music — Windows desktop application.
Feed already-edited GoPro MP4s. Strip ALL original sound, randomly mix the
seven original festive backing tracks (or user-supplied licensed music).
No login, no internet, no extra software installation. FFmpeg bundled.
"""
from __future__ import annotations
import csv
from pathlib import Path
import os
import queue
import sys
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import webbrowser

from engine import (
    ALLOWED_VIDEOS, ALLOWED_MUSIC, NAMES, Stopped, duration_seconds,
    ffmpeg_path, original_tracks, pretty_music, random_assignments,
    replace_audio, result_path
)

BG = "#0B1426"
PANEL = "#14233D"
PANEL_LIGHT = "#213856"
TEXT = "#F4F7FF"
MUTED = "#9DB5D0"
ACCENT = "#00C9AE"
FONT = "Segoe UI"


def pretty_sec(value: float | None) -> str:
    if value is None:
        return "..."
    return f"{value:.1f} წმ"


class VideoJob:
    def __init__(self, path: Path):
        self.path = path
        self.duration: float | None = None
        self.music: Path | None = None
        self.status = "მოწმდება…"
        self.output: Path | None = None


class FestivalApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("MOMENTO Happy Music 60 — თერჯოლობა")
        root.geometry("1240x790")
        root.minsize(940, 640)
        root.configure(bg=BG)
        self.jobs: dict[str, VideoJob] = {}
        self.extra_music: dict[str, Path] = {}
        self.event_queue: queue.Queue = queue.Queue()
        self.use_originals = tk.BooleanVar(value=True)
        self.music_volume = tk.IntVar(value=82)
        self.fade = tk.DoubleVar(value=.7)
        self.folder = tk.StringVar(value=str(Path.home() / "Videos" / "MOMENTO_TERJOLOBA_MUSIC_READY"))
        self.status = tk.StringVar(value="მზადაა • დაამატე დამუშავებული GoPro MP4 ვიდეოები")
        self.progress = tk.DoubleVar(value=0)
        self.total_progress = tk.DoubleVar(value=0)
        self.running = False
        self.stop_event = threading.Event()
        self.song_list: list[Path] = []
        self._build()
        self._refresh_music()
        self.root.after(100, self._poll)
        self.root.protocol("WM_DELETE_WINDOW", self._close)

    def _label(self, parent, text, size=10, color=TEXT, bold=False, **kwargs):
        label = tk.Label(parent, text=text, fg=color, bg=parent["bg"],
                         font=(FONT, size, "bold" if bold else "normal"),
                         anchor="w", justify="left", wraplength=700)
        label.pack(anchor="w", **kwargs)
        return label

    def _btn(self, parent, title, action, prime=False):
        button = tk.Button(parent, text=title, command=action,
                           font=(FONT, 10, "bold"), cursor="hand2",
                           bg=ACCENT if prime else PANEL_LIGHT,
                           fg="#092A28" if prime else TEXT,
                           activebackground="#10AD99" if prime else "#315478",
                           activeforeground=TEXT, relief="flat", borderwidth=0,
                           padx=13, pady=10)
        button.pack(side=tk.LEFT, padx=(0, 7), pady=3)
        return button

    def _build(self):
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("Festival.Treeview", background=PANEL, foreground=TEXT,
                        fieldbackground=PANEL, rowheight=32, borderwidth=0,
                        font=(FONT, 9))
        style.configure("Festival.Treeview.Heading", background=PANEL_LIGHT,
                        foreground=TEXT, font=(FONT, 9, "bold"), padding=(7, 10))
        style.map("Festival.Treeview",
                  background=[("selected", "#315F84")],
                  foreground=[("selected", TEXT)])
        # This is the built-in ttk layout, tested on real Windows; DO NOT invent
        # a Custom.TProgressbar without a matching theme layout.
        style.configure("Horizontal.TProgressbar", background=ACCENT,
                        troughcolor=PANEL_LIGHT, bordercolor=BG, thickness=12)

        header = tk.Frame(self.root, bg=BG, padx=23, pady=15)
        header.pack(fill=tk.X)
        self._label(header, "MOMENTO  /  HAPPY MUSIC 60", 23, TEXT, True)
        self._label(header, "თერჯოლობა • 60 განსხვავებული მხიარული მუსიკა • 47 ვიდეოზე გამეორების გარეშე",
                    11, MUTED)

        body = tk.Frame(self.root, bg=BG, padx=16)
        body.pack(fill=tk.BOTH, expand=True)
        left = tk.Frame(body, bg=PANEL, padx=15, pady=13)
        left.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(0, 8))
        right = tk.Frame(body, bg=PANEL, padx=15, pady=13, width=420)
        right.pack(side=tk.RIGHT, fill=tk.Y)
        right.pack_propagate(False)

        self._label(left, "1. უკვე დამუშავებული ვიდეოები", 15, TEXT, True)
        actions = tk.Frame(left, bg=PANEL)
        actions.pack(fill=tk.X, pady=(7, 8))
        self._btn(actions, "+ MP4 ვიდეოები", self.add_videos, True)
        self._btn(actions, "+ საქაღალდე", self.add_folder)
        self._btn(actions, "ამოშლა", self.remove_videos)
        cols = ("video", "length", "music", "state")
        table_wrap = tk.Frame(left, bg=PANEL)
        table_wrap.pack(fill=tk.BOTH, expand=True)
        self.table = ttk.Treeview(table_wrap, columns=cols, show="headings",
                                  selectmode="extended", style="Festival.Treeview")
        for col, title in (("video", "ვიდეო"), ("length", "დრო"),
                           ("music", "შემთხვევითი მუსიკა"), ("state", "სტატუსი")):
            self.table.heading(col, text=title)
        self.table.column("video", minwidth=155, width=200)
        self.table.column("length", minwidth=60, width=74, stretch=False, anchor="center")
        self.table.column("music", minwidth=130, width=190)
        self.table.column("state", minwidth=80, width=106, stretch=False)
        scroll = ttk.Scrollbar(table_wrap, orient=tk.VERTICAL, command=self.table.yview)
        self.table.configure(yscrollcommand=scroll.set)
        self.table.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.file_count = self._label(left, "ვიდეო: 0 • ორიგინალ MP4-ებს არ შევცვლი", 10, MUTED,
                                       pady=(6, 4))
        shuffle_actions = tk.Frame(left, bg=PANEL)
        shuffle_actions.pack(fill=tk.X, pady=(2, 0))
        self._btn(shuffle_actions, "🔀 მუსიკის შემთხვევითი გადანაწილება", self.shuffle, True)

        self._label(right, "2. მუსიკის არჩევა", 15, TEXT, True)
        self._label(right, "MOMENTO-ს 60 ახალი ჰეფი / დენსი / ფესტივალური მელოდია",
                    10, MUTED, pady=(5, 2))
        toggle = tk.Checkbutton(right, text="ჩაშენებული ორიგინალი მუსიკების გამოყენება",
                                variable=self.use_originals, command=self._refresh_music,
                                bg=PANEL, fg=TEXT, activebackground=PANEL,
                                activeforeground=TEXT, selectcolor=PANEL_LIGHT,
                                font=(FONT, 10))
        toggle.pack(anchor="w", pady=(2, 7))
        list_frame = tk.Frame(right, bg=PANEL)
        list_frame.pack(fill=tk.BOTH, expand=True)
        self.music_list = tk.Listbox(list_frame, font=(FONT, 10),
                                     bg=PANEL_LIGHT, fg=TEXT,
                                     selectbackground="#285F7E",
                                     highlightthickness=0, relief=tk.FLAT, height=8)
        mscroll = ttk.Scrollbar(list_frame, orient=tk.VERTICAL,
                                command=self.music_list.yview)
        self.music_list.configure(yscrollcommand=mscroll.set)
        self.music_list.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        mscroll.pack(side=tk.RIGHT, fill=tk.Y)
        mbuttons = tk.Frame(right, bg=PANEL)
        mbuttons.pack(fill=tk.X, pady=(5, 0))
        self._btn(mbuttons, "+ საკუთარი MP3", self.add_music)
        self._btn(mbuttons, "▶ მოსმენა", self.play_music)
        self._btn(mbuttons, "ამოშლა", self.remove_music)
        self.music_count = self._label(right, "60 სხვადასხვა მუსიკა", 10, MUTED)
        self._label(right,
                     "ახალი მხიარული პოპი, დენსი, ზაფხულის რიტმები, "
                     "ბრასის და დისკოს ჟღერადობით. საკუთარი მუსიკის დამატებისას "
                    "შეამოწმე გამოყენების ლიცენზია.",
                    9, MUTED, pady=(5, 8))

        self._label(right, "3. მუსიკის ხმა", 12, TEXT, True)
        volume = tk.Scale(right, from_=15, to=125, variable=self.music_volume,
                          resolution=5, orient=tk.HORIZONTAL, bg=PANEL, fg=TEXT,
                          troughcolor=PANEL_LIGHT, highlightthickness=0,
                          activebackground=ACCENT, showvalue=True, length=300)
        volume.pack(fill=tk.X)
        self._label(right, "ვიდეოს ორიგინალი ხმა 100%-ით წაიშლება.", 9, ACCENT)
        self._label(right, "დასასრულის რბილი მიწყნარება (წამები)", 10, MUTED,
                    pady=(9, 2))
        fade_row = tk.Frame(right, bg=PANEL)
        fade_row.pack(fill=tk.X)
        fade_choices = ttk.Combobox(fade_row, textvariable=self.fade,
                         values=[.0, .3, .5, .7, 1.0, 1.5, 2.0],
                         state="readonly", width=9)
        fade_choices.pack(side=tk.LEFT)

        self._label(right, "4. მზა ვიდეოების შენახვა", 12, TEXT, True,
                    pady=(13, 3))
        out_entry = tk.Entry(right, textvariable=self.folder,
                             bg=PANEL_LIGHT, fg=TEXT, insertbackground=TEXT,
                             relief=tk.FLAT, font=(FONT, 10))
        out_entry.pack(fill=tk.X, ipady=8)
        output_actions = tk.Frame(right, bg=PANEL)
        output_actions.pack(fill=tk.X, pady=(4, 0))
        self._btn(output_actions, "საქაღალდის არჩევა", self.choose_folder)
        self._btn(output_actions, "გახსნა", self.open_folder)
        self._label(right, "ყველა მზა ვიდეო შეინახება MP4 ფორმატში, "
                    "ვიდეოს ხელახალი კოდირების გარეშე.", 9, MUTED)

        footer = tk.Frame(self.root, bg=BG, padx=23, pady=13)
        footer.pack(fill=tk.X)
        toolbar = tk.Frame(footer, bg=BG)
        toolbar.pack(fill=tk.X)
        self.go_button = self._btn(toolbar, "▶ ყველა ვიდეოს მუსიკის დადება",
                                   self.start, True)
        self.cancel_button = self._btn(toolbar, "■ შეჩერება", self.stop)
        self.cancel_button.config(state=tk.DISABLED)
        self.bar = ttk.Progressbar(footer, variable=self.progress,
                                   maximum=100, orient=tk.HORIZONTAL,
                                   style="Horizontal.TProgressbar")
        self.bar.pack(fill=tk.X, pady=(8, 2))
        self.totalbar = ttk.Progressbar(footer, variable=self.total_progress,
                                        maximum=100, orient=tk.HORIZONTAL,
                                        style="Horizontal.TProgressbar")
        self.totalbar.pack(fill=tk.X, pady=(3, 3))
        status = tk.Label(footer, textvariable=self.status, bg=BG, fg=ACCENT,
                          anchor="w", justify=tk.LEFT, font=(FONT, 10))
        status.pack(fill=tk.X)

    def _row(self, job: VideoJob):
        music = pretty_music(job.music) if job.music else "—"
        return (job.path.name, pretty_sec(job.duration), music, job.status)

    def _refresh_row(self, key):
        if key in self.jobs and self.table.exists(key):
            self.table.item(key, values=self._row(self.jobs[key]))

    def _refresh_music(self):
        self.song_list = (
            original_tracks() if self.use_originals.get() else []
        ) + list(self.extra_music.values())
        self.music_list.delete(0, tk.END)
        for song in self.song_list:
            prefix = "♫ " if song.stem in NAMES else "♪ "
            self.music_list.insert(tk.END, prefix + pretty_music(song))
        self.music_count.config(
            text=f"ხელმისაწვდომია {len(self.song_list)} განსხვავებული მუსიკა • "
                 f"47 ვიდეოსთვის გამეორება არ იქნება"
                 if len(self.song_list)>=47 else
                 f"ხელმისაწვდომია {len(self.song_list)} მუსიკა")
        if not self.running and self.jobs and self.song_list:
            self.shuffle()

    def add_videos(self):
        if self.running:
            return
        files = filedialog.askopenfilenames(
            title="აირჩიე დამუშავებული MOMENTO/GoPro MP4 ვიდეოები",
            filetypes=[("MP4 ვიდეოები", "*.mp4 *.mov *.m4v *.mkv *.avi"),
                       ("ყველა ფაილი", "*.*")])
        self._add_videos([Path(x) for x in files])

    def add_folder(self):
        if self.running:
            return
        value = filedialog.askdirectory(title="აირჩიე ვიდეოების საქაღალდე")
        if value:
            root = Path(value)
            self._add_videos(sorted(x for x in root.iterdir() if
                                    x.is_file() and x.suffix.lower() in ALLOWED_VIDEOS))

    def _add_videos(self, paths):
        new = []
        for path in paths:
            if not path.is_file() or path.suffix.lower() not in ALLOWED_VIDEOS:
                continue
            key = str(path.resolve()).lower()
            if key in self.jobs:
                continue
            item = VideoJob(path.resolve())
            self.jobs[key] = item
            self.table.insert("", tk.END, iid=key, values=self._row(item))
            new.append((key, item.path))
        self.file_count.config(text=f"ვიდეო: {len(self.jobs)} • ორიგინალები დაცულია")
        if new:
            self.shuffle()
            threading.Thread(target=self._probe, args=(new,), daemon=True).start()

    def _probe(self, new):
        exe = ffmpeg_path()
        for key, path in new:
            try:
                seconds = duration_seconds(exe, path, True)
                self.event_queue.put(("duration", key, seconds))
            except Exception as exc:
                self.event_queue.put(("error", key, str(exc)))

    def remove_videos(self):
        if self.running:
            return
        for key in self.table.selection():
            self.table.delete(key)
            self.jobs.pop(key, None)
        self.file_count.config(text=f"ვიდეო: {len(self.jobs)} • ორიგინალები დაცულია")
        self.shuffle()

    def add_music(self):
        if self.running:
            return
        files = filedialog.askopenfilenames(
            title="დაამატე მხოლოდ ის მუსიკა, რომლის გამოყენების უფლება გაქვს",
            filetypes=[("მუსიკა", "*.mp3 *.wav *.m4a *.aac *.ogg *.flac"),
                       ("ყველა ფაილი", "*.*")])
        for raw in files:
            file = Path(raw)
            if file.is_file() and file.suffix.lower() in ALLOWED_MUSIC:
                self.extra_music[str(file.resolve()).lower()] = file.resolve()
        self._refresh_music()

    def remove_music(self):
        if self.running:
            return
        indices = self.music_list.curselection()
        if not indices:
            return
        for i in indices:
            if 0 <= i < len(self.song_list):
                file = self.song_list[i]
                if file.stem in NAMES and file in original_tracks():
                    messagebox.showinfo("ჩაშენებული მუსიკა",
                        "ჩაშენებული ტრეკების გამოსართავად მოხსენი ზემოთ მონიშვნა.")
                    continue
                self.extra_music.pop(str(file.resolve()).lower(), None)
        self._refresh_music()

    def play_music(self):
        indices = self.music_list.curselection()
        if not indices:
            messagebox.showinfo("მუსიკის მოსმენა", "ჯერ აირჩიე მუსიკა სიიდან.")
            return
        song = self.song_list[indices[0]]
        try:
            if os.name == "nt":
                os.startfile(str(song))
            else:
                messagebox.showinfo("მუსიკის მოსმენა", str(song))
        except OSError as exc:
            messagebox.showerror("მოსმენა", str(exc))

    def shuffle(self):
        if self.running or not self.jobs:
            return
        if not self.song_list:
            for key, job in self.jobs.items():
                job.music = None
                self._refresh_row(key)
            self.status.set("მუსიკა არ არის არჩეული. ჩართე ჩაშენებული ტრეკები ან დაამატე MP3.")
            return
        allocation = random_assignments(len(self.jobs), self.song_list)
        for (key, job), song in zip(self.jobs.items(), allocation):
            job.music = song
            self._refresh_row(key)
        distinct=len({str(x) for x in allocation})
        self.status.set(
            f"🔀 {len(self.jobs)} ვიდეო → {distinct} განსხვავებული მუსიკა; "
            + ("გამეორება არ არის." if distinct==len(self.jobs)
               else "ყველაზე განსხვავებული შედეგისთვის დაამატე მეტი მუსიკა.")
        )

    def choose_folder(self):
        if self.running:
            return
        value = filedialog.askdirectory(title="აირჩიე მზა ვიდეოების საქაღალდე")
        if value:
            self.folder.set(value)

    def open_folder(self):
        target = Path(self.folder.get()).expanduser()
        try:
            target.mkdir(parents=True, exist_ok=True)
            if os.name == "nt":
                os.startfile(str(target))
            else:
                webbrowser.open(target.as_uri())
        except Exception as exc:
            messagebox.showerror("საქაღალდე", str(exc))

    def start(self):
        if self.running:
            return
        if not self.jobs:
            messagebox.showinfo("ვიდეოები", "ჯერ დაამატე MP4 ვიდეოები.")
            return
        if not self.song_list:
            messagebox.showerror("მუსიკა", "მინიმუმ ერთი მუსიკა აუცილებელია.")
            return
        if len(self.jobs)<=len(self.song_list) and len({
                str(job.music) for job in self.jobs.values() if job.music
            }) != len(self.jobs):
            self.shuffle()  # No duplicate music when enough unique tracks exist.
        if any(job.duration is None or job.music is None for job in self.jobs.values()):
            messagebox.showerror("დამუშავება", "ყველა ვიდეოს ხანგრძლივობის შემოწმებას დაელოდე.")
            return
        try:
            out = Path(self.folder.get()).expanduser().resolve()
            out.mkdir(parents=True, exist_ok=True)
            volume = self.music_volume.get() / 100.0
            fade = self.fade.get()
            if not 0 < volume <= 2.0 or not 0 <= fade <= 2:
                raise ValueError("მუსიკის ხმა ან fade-ის დრო არასწორია.")
        except (OSError, ValueError) as exc:
            messagebox.showerror("შენახვა", str(exc))
            return
        self.stop_event.clear()
        self.running = True
        self.progress.set(0)
        self.total_progress.set(0)
        self.go_button.configure(state=tk.DISABLED)
        self.cancel_button.configure(state=tk.NORMAL)
        batch = [(key, job) for key, job in self.jobs.items()]
        self.status.set(f"▶ იწყება {len(batch)} ვიდეოზე მუსიკის დადება…")
        threading.Thread(target=self._work, args=(batch, out, volume, fade),
                         name="MOMENTO-Festival-Worker", daemon=True).start()

    def _work(self, batch, out, volume, fade):
        used = set()
        records = []
        exe = ffmpeg_path()
        for i, (key, job) in enumerate(batch):
            if self.stop_event.is_set():
                break
            target = result_path(out, job.path, used)
            self.event_queue.put(("state", key, "მუშავდება…"))
            try:
                def tick(percent, k=key, index=i):
                    self.event_queue.put(("progress", k, index, len(batch), percent))
                result, seconds = replace_audio(
                    job.path, job.music, target, ffmpeg=exe, duration=job.duration,
                    music_volume=volume, fade_seconds=fade,
                    stopped=self.stop_event, progress=tick)
                self.event_queue.put(("ok", key, result))
                records.append([job.path.name, pretty_sec(seconds),
                                pretty_music(job.music), job.music.name,
                                str(result), "მზადაა"])
            except Stopped:
                self.event_queue.put(("state", key, "შეჩერებულია"))
                break
            except Exception as exc:
                self.event_queue.put(("error", key, str(exc)))
                records.append([job.path.name, pretty_sec(job.duration),
                                pretty_music(job.music), job.music.name, "", str(exc)])
        if records:
            report = out / "MOMENTO_TERJOLOBA_MUSIC_REPORT.csv"
            try:
                with report.open("w", encoding="utf-8-sig", newline="") as target:
                    writer = csv.writer(target)
                    writer.writerow(["ორიგინალი MP4", "ხანგრძლივობა",
                                     "გამოყენებული მუსიკა", "აუდიო ფაილი",
                                     "მზა MP4", "შედეგი"])
                    writer.writerows(records)
            except OSError as exc:
                self.event_queue.put(("notice", f"CSV ვერ შეინახა: {exc}"))
        self.event_queue.put(("finished", self.stop_event.is_set(), out))

    def stop(self):
        if not self.running:
            return
        self.stop_event.set()
        self.cancel_button.configure(state=tk.DISABLED)
        self.status.set("დამუშავება ჩერდება. მზა MP4-ები შენარჩუნდება.")

    def _poll(self):
        try:
            while True:
                event = self.event_queue.get_nowait()
                tag = event[0]
                if tag == "duration":
                    _, key, seconds = event
                    if key in self.jobs:
                        self.jobs[key].duration = seconds
                        self.jobs[key].status = "მზადაა"
                        self._refresh_row(key)
                elif tag == "state":
                    _, key, state = event
                    if key in self.jobs:
                        self.jobs[key].status = state
                        self._refresh_row(key)
                elif tag == "error":
                    _, key, reason = event
                    if key in self.jobs:
                        self.jobs[key].status = "⚠ შეცდომა"
                        self._refresh_row(key)
                        self.status.set(f"⚠ {self.jobs[key].path.name}: {reason[:170]}")
                elif tag == "progress":
                    _, key, index, count, fraction = event
                    self.progress.set(fraction)
                    self.total_progress.set(100 * (index + fraction / 100) / count)
                    self.status.set(
                        f"♫ ვიდეო {index+1}/{count}: {fraction:.0f}% • სულ "
                        f"{(index + fraction/100) * 100 / count:.0f}%")
                elif tag == "ok":
                    _, key, dest = event
                    if key in self.jobs:
                        self.jobs[key].output = dest
                        self.jobs[key].status = "✓ დასრულდა"
                        self._refresh_row(key)
                elif tag == "notice":
                    self.status.set(event[1])
                elif tag == "finished":
                    self.running = False
                    self.go_button.configure(state=tk.NORMAL)
                    self.cancel_button.configure(state=tk.DISABLED)
                    _, cancelled, out = event
                    done = sum(1 for job in self.jobs.values() if job.output is not None)
                    self.status.set(
                        f"დასრულდა: {done}/{len(self.jobs)} ვიდეო. "
                        f"შედეგი: {out}")
                    if not cancelled:
                        self.total_progress.set(100)
                    messagebox.showinfo(
                        "MOMENTO Festival Music",
                        f"მზა ვიდეოები: {done}/{len(self.jobs)}\n\n"
                        f"ფაილები შენახულია:\n{out}\n\n"
                        "ვიდეოს ორიგინალი ხმა წაშლილია, "
                        "ახალი მუსიკა კი MP4-შია დამატებული.")
        except queue.Empty:
            pass
        finally:
            self.root.after(100, self._poll)

    def _close(self):
        if self.running:
            messagebox.showwarning("დამუშავება",
                                   "ჯერ დააჭირე შეჩერებას, მერე დახურე პროგრამა.")
            return
        self.root.destroy()


def ui_test():
    root = None
    try:
        root = tk.Tk()
        root.withdraw()
        app = FestivalApp(root)
        root.update_idletasks()
        assert app.bar.winfo_exists()
        assert app.totalbar.winfo_exists()
        assert len(app.song_list) == 60, len(app.song_list)
        assert all(x.is_file() for x in app.song_list)
        assert all(x.stat().st_size > 50000 for x in app.song_list)
        app.jobs["sample"] = VideoJob(Path("sample.mp4"))
        app.jobs["sample"].duration = 12.0
        app.table.insert("", "end", iid="sample", values=app._row(app.jobs["sample"]))
        app.shuffle()
        assert app.jobs["sample"].music is not None
        app._refresh_row("sample")
        # Verify 47 distinct tracks are available for all 47 edited videos.
        choices = random_assignments(47, app.song_list)
        assert len(choices) == 47 and len(set(choices)) == 47
        return 0
    except Exception:
        import traceback
        if sys.stderr is not None:
            traceback.print_exc()
        return 1
    finally:
        if root:
            root.destroy()


def verify_video(args):
    if len(args) != 3:
        return 2
    video, music, output = map(Path, args)
    try:
        finished, video_length = replace_audio(video, music, output)
        result_seconds = duration_seconds(ffmpeg_path(), finished)
        if abs(result_seconds - video_length) > .45:
            return 3
        log = __import__("subprocess").run(
            [ffmpeg_path(), "-hide_banner", "-nostdin", "-i", str(finished)],
            capture_output=True, text=True, timeout=35, errors="replace").stderr
        if "Audio: aac" not in log or "Video:" not in log:
            return 4
        return 0
    except Exception:
        import traceback
        if sys.stderr is not None:
            traceback.print_exc()
        return 1


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--ui-test":
        return ui_test()
    if len(sys.argv) > 1 and sys.argv[1] == "--verify":
        return verify_video(sys.argv[2:])
    root = tk.Tk()
    FestivalApp(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
