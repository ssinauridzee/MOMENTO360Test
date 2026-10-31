"""MOMENTO Batch SlowMo — Windows desktop application for GoPro videos.

Simple, offline, no login, no API keys, standard-library UI. FFmpeg is bundled
inside the Windows executable and runs solely on the user's computer.
"""
from __future__ import annotations

import csv
import datetime as dt
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import webbrowser

from processor import (
    Cancelled, SlowSegment, SUPPORTED_EXTENSIONS, choose_output_name,
    ffmpeg_path, parse_seconds, parse_segments, plan_timeline, format_segments,
    probe_duration, render, result_duration, validate_duration,
)

BG = '#0C1627'
PANEL = '#12223B'
PANEL2 = '#1C3150'
TEXT = '#F0F5FF'
MUTED = '#9FB1CA'
ACCENT = '#00C8B1'
ACCENT_HOVER = '#00A894'
WARN = '#F9BD70'
RED = '#E97887'
FONT = 'Segoe UI'


class VideoItem:
    def __init__(self, path: Path, settings: tuple):
        self.path = path
        self.settings = settings   # (start, slow final seconds, speed, tail override, extras)
        self.segments = ''
        self.duration: float | None = None
        self.tail_seconds: float | None = None
        self.expected_duration: float | None = None
        self.slow_actual_seconds: float | None = None
        self.output: Path | None = None
        self.output_duration: float | None = None
        self.status = 'ხანგრძლივობის შემოწმება…'
        self.error = ''


class SlowMoApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title('MOMENTO — Batch SlowMo')
        self.root.configure(bg=BG)
        self.root.geometry('1220x790')
        self.root.minsize(950, 660)
        self.items: dict[str, VideoItem] = {}
        self.events: queue.Queue[tuple] = queue.Queue()
        self.busy = False
        self.cancelled = threading.Event()
        self.completed = 0
        self.failed = 0
        self.current_file = ''
        self.interval_var = tk.StringVar(value='3-5@0.25')
        self.slow_start_var = tk.StringVar(value='3')
        self.slow_length_var = tk.StringVar(value='2')
        self.slow_speed_var = tk.StringVar(value='0.25× — 4-ჯერ ნელა')
        self.extra_intervals_var = tk.StringVar(value='')
        self.tail_mode_var = tk.StringVar(value='auto')
        self.tail_seconds_var = tk.StringVar(value='')
        self.preview_var = tk.StringVar(value='3–5 წმ → 8 წმ ნელი ვიდეო')
        self.output_var = tk.StringVar(value=str(Path.home() / 'Videos' / 'MOMENTO_MERIA_READY'))
        self.fps_var = tk.StringVar(value='60')
        self.quality_var = tk.StringVar(value='მაღალი (CRF 20)')
        self.status_var = tk.StringVar(value='მზადაა • დაამატე GoPro ვიდეოები')
        self.file_progress_var = tk.DoubleVar(value=0)
        self.global_progress_var = tk.DoubleVar(value=0)
        self._settings_dirty = False
        self._syncing_fields = False
        self._build_ui()
        for field in (self.slow_start_var, self.slow_length_var,
                      self.slow_speed_var, self.extra_intervals_var,
                      self.tail_mode_var, self.tail_seconds_var):
            field.trace_add('write', self._on_settings_edited)
        self._update_segment_preview()
        self.root.after(80, self._poll)
        self.root.protocol('WM_DELETE_WINDOW', self._on_close)

    def _style(self):
        st = ttk.Style()
        try:
            st.theme_use('clam')
        except tk.TclError:
            pass
        st.configure('Momento.Treeview', background=PANEL, foreground=TEXT,
                     fieldbackground=PANEL, rowheight=33, borderwidth=0,
                     font=(FONT, 10))
        st.configure('Momento.Treeview.Heading', background=PANEL2, foreground=TEXT,
                     font=(FONT, 10, 'bold'), padding=(8, 9), relief='flat')
        st.map('Momento.Treeview', background=[('selected', '#2B6482')],
               foreground=[('selected', TEXT)])
        # Always style Tk's builtin progressbar layout; a new custom name
        # without a layout crashed the app on Windows (TclError).
        st.configure('Horizontal.TProgressbar', background=ACCENT, troughcolor=PANEL2,
                     bordercolor=BG, lightcolor=ACCENT, darkcolor=ACCENT, thickness=13)
        st.configure('Momento.TCombobox', fieldbackground='#243852',
                     background='#243852', foreground=TEXT, padding=6)

    def _label(self, parent, text, size=10, color=TEXT, bold=False, **pack):
        w = tk.Label(parent, text=text, bg=parent['bg'], fg=color,
                     font=(FONT, size, 'bold' if bold else 'normal'),
                     justify='left', anchor='w', wraplength=720)
        w.pack(**({'anchor': 'w'} | pack))
        return w

    def _button(self, parent, text, command, *, prominent=False, danger=False, side=None, padx=13, pady=10):
        fg = '#082B28' if prominent else TEXT
        bg = ACCENT if prominent else ('#5C2B44' if danger else PANEL2)
        b = tk.Button(parent, text=text, command=command, bg=bg, fg=fg,
                      activebackground=ACCENT_HOVER if prominent else '#2E5073',
                      activeforeground=TEXT if not prominent else '#082B28',
                      relief='flat', bd=0, cursor='hand2', font=(FONT, 10, 'bold'),
                      padx=padx, pady=pady)
        b.pack(side=side or tk.LEFT, padx=(0, 8), pady=4)
        return b

    def _entry(self, parent, variable, width=None):
        e = tk.Entry(parent, textvariable=variable, bg='#233A59', fg=TEXT,
                     insertbackground=TEXT, relief='flat', bd=0,
                     highlightbackground='#385678', highlightthickness=1,
                     font=(FONT, 11))
        e.pack(fill=tk.X, ipady=8, pady=(5, 10))
        return e

    def _build_ui(self):
        self._style()
        header = tk.Frame(self.root, bg=BG, padx=27, pady=16)
        header.pack(fill=tk.X)
        self._label(header, 'MOMENTO  /  BATCH SLOWMO', 24, TEXT, True)
        self._label(header, 'GoPro-ს ვიდეოები → შენელება → ერთი საქაღალდე → ერთი Google Drive ბმული',
                    11, MUTED)
        body = tk.Frame(self.root, bg=BG, padx=20)
        body.pack(fill=tk.BOTH, expand=True)
        left = tk.Frame(body, bg=PANEL, padx=16, pady=15)
        left.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(0, 11))
        right_outer = tk.Frame(body, bg=PANEL, width=350)
        right_outer.pack(side=tk.RIGHT, fill=tk.Y)
        right_outer.pack_propagate(False)
        right_scrollbar = ttk.Scrollbar(right_outer, orient='vertical')
        right_scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        right_canvas = tk.Canvas(right_outer, bg=PANEL, highlightthickness=0,
                                 yscrollcommand=right_scrollbar.set)
        right_canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        right_scrollbar.configure(command=right_canvas.yview)
        right = tk.Frame(right_canvas, bg=PANEL, padx=17, pady=16)
        right_window = right_canvas.create_window((0, 0), window=right, anchor='nw')
        right.bind('<Configure>', lambda e: right_canvas.configure(
            scrollregion=right_canvas.bbox('all')))
        right_canvas.bind('<Configure>', lambda e: right_canvas.itemconfigure(
            right_window, width=e.width))

        self._label(left, '1. ვიდეოების სია', 15, TEXT, True)
        toolbar = tk.Frame(left, bg=PANEL)
        toolbar.pack(fill=tk.X, pady=(7, 10))
        self._button(toolbar, '+ ვიდეოების დამატება', self.add_files, prominent=True)
        self._button(toolbar, '+ საქაღალდე', self.add_folder)
        self._button(toolbar, 'წაშლა სიიდან', self.remove_selected)
        cols = ('filename', 'duration', 'segments', 'status')
        frame = tk.Frame(left, bg=PANEL)
        frame.pack(fill=tk.BOTH, expand=True)
        self.tree = ttk.Treeview(frame, columns=cols, show='headings',
                                 style='Momento.Treeview', selectmode='extended')
        self.tree.heading('filename', text='ფაილი')
        self.tree.heading('duration', text='ორიგინალი')
        self.tree.heading('segments', text='შენელების ინტერვალები')
        self.tree.heading('status', text='მდგომარეობა')
        self.tree.column('filename', minwidth=130, width=180, anchor='w', stretch=True)
        self.tree.column('duration', minwidth=68, width=86, anchor='center', stretch=False)
        self.tree.column('segments', minwidth=110, width=150, anchor='w')
        self.tree.column('status', minwidth=110, width=146, anchor='w')
        scroll = ttk.Scrollbar(frame, orient='vertical', command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.tree.bind('<<TreeviewSelect>>', self._select_item)
        self.tree.bind('<Double-1>', self._select_item)
        self.count_label = self._label(left, 'სულ: 0 ვიდეო', 10, MUTED)

        self._label(right, '2. შენელების პარამეტრები', 14, TEXT, True)
        self._label(right, 'აირჩიე ზუსტად როდის და რამდენ ხანს', 10, MUTED,
                    pady=(10, 6))
        time_row = tk.Frame(right, bg=PANEL)
        time_row.pack(fill=tk.X, pady=(2, 0))
        start_col = tk.Frame(time_row, bg=PANEL)
        start_col.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 5))
        length_col = tk.Frame(time_row, bg=PANEL)
        length_col.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(5, 0))
        self._label(start_col, 'რომელი წამიდან?', 10, TEXT, True)
        self._entry(start_col, self.slow_start_var)
        self._label(length_col, 'რამდენი წამი?', 10, TEXT, True)
        self._entry(length_col, self.slow_length_var)
        self._label(right, 'რა სიჩქარით შენელდეს?', 10, TEXT, True, pady=(3, 4))
        speed_menu = ttk.Combobox(right, textvariable=self.slow_speed_var,
            values=['0.5× — 2-ჯერ ნელა', '0.25× — 4-ჯერ ნელა',
                    '0.125× — 8-ჯერ ნელა'], state='readonly')
        speed_menu.pack(fill=tk.X, pady=(0, 9))
        self.preview_label = self._label(right, '', 10, ACCENT, True, pady=(0, 9))
        self.preview_label.configure(textvariable=self.preview_var, wraplength=290)
        self._label(right, '3. ბოლოს ჩვეულებრივად რამდენი წამი?', 11, TEXT, True,
                    pady=(12, 5))
        auto_radio = tk.Radiobutton(
            right, text='ავტომატურად გამოთვალე (Android-ის მსგავსად)',
            variable=self.tail_mode_var, value='auto', bg=PANEL, fg=TEXT,
            selectcolor=PANEL2, activebackground=PANEL, activeforeground=TEXT,
            font=(FONT, 9), anchor='w')
        auto_radio.pack(fill=tk.X)
        manual_radio = tk.Radiobutton(
            right, text='მე მივუთითებ ბოლო ჩვეულებრივ წამებს',
            variable=self.tail_mode_var, value='manual', bg=PANEL, fg=TEXT,
            selectcolor=PANEL2, activebackground=PANEL, activeforeground=TEXT,
            font=(FONT, 9), anchor='w')
        manual_radio.pack(fill=tk.X, pady=(0, 3))
        self.tail_entry = self._entry(right, self.tail_seconds_var)
        self._label(right, 'ხელით მითითებისას შენელების ხანგრძლივობა '
                    'ავტომატურად მოერგება ვიდეოს, რომ ბოლო წამები აუცილებლად '
                    'ჩვეულებრივად გაგრძელდეს.', 9, MUTED, pady=(0, 9))
        self.timeline_preview = self._label(right,
            'ჯერ დაამატე GoPro-ს MP4, რომ დაითვალოს დასაწყისი / '
            'შენელება / დასასრული.', 10, ACCENT, True, pady=(1, 9))
        self.timeline_preview.configure(wraplength=300)
        self._label(right, 'დამატებითი მონაკვეთები (სურვილისამებრ)', 9, MUTED)
        self._entry(right, self.extra_intervals_var)
        self._label(right, 'მაგალითად: 8-9@0.5', 9, MUTED, pady=(0, 9))
        timing_buttons = tk.Frame(right, bg=PANEL)
        timing_buttons.pack(fill=tk.X)
        self._button(timing_buttons, 'მონიშნულებზე', self.apply_selected, padx=11, pady=9)
        self._button(timing_buttons, 'ყველა ვიდეოზე', self.apply_all, prominent=True, padx=11, pady=9)

        self._label(right, 'საბოლოო ვიდეოს FPS', 10, MUTED, False, pady=(15, 4))
        fps = ttk.Combobox(right, textvariable=self.fps_var, values=['30', '60'], state='readonly')
        fps.pack(fill=tk.X, pady=(0, 10))
        self._label(right, 'ხარისხი', 10, MUTED, False, pady=(4, 4))
        quality = ttk.Combobox(right, textvariable=self.quality_var,
                               values=['მაქსიმალური (CRF 18)', 'მაღალი (CRF 20)',
                                       'კომპაქტური (CRF 23)'], state='readonly')
        quality.pack(fill=tk.X, pady=(0, 14))
        self._label(right, '3. მზა ვიდეოების საქაღალდე', 14, TEXT, True, pady=(7, 4))
        self._entry(right, self.output_var)
        out_buttons = tk.Frame(right, bg=PANEL)
        out_buttons.pack(fill=tk.X)
        self._button(out_buttons, 'საქაღალდის არჩევა', self.choose_output, pady=8)
        self._button(out_buttons, 'გახსნა', self.open_output, pady=8)

        self._label(right, '4. Google Drive', 14, TEXT, True, pady=(18, 4))
        self._label(right, 'პროგრამა ვიდეოებს ლოკალურად ქმნის. მერე ატვირთე მთელი საქაღალდე Drive-ში და მერიას ერთი საქაღალდის Viewer ბმული მიეცი.',
                    10, MUTED)
        drive = tk.Frame(right, bg=PANEL)
        drive.pack(fill=tk.X, pady=(7, 0))
        self._button(drive, 'Google Drive-ის გახსნა', self.open_drive, pady=9)

        footer = tk.Frame(self.root, bg=BG, padx=27, pady=16)
        footer.pack(fill=tk.X)
        self._label(footer, '5. ავტომატური დამუშავება', 13, TEXT, True)
        info = tk.Frame(footer, bg=BG)
        info.pack(fill=tk.X, pady=5)
        self.start_button = self._button(info, '▶ ყველას დამუშავება', self.start, prominent=True, padx=24, pady=12)
        self.stop_button = self._button(info, '■ შეჩერება', self.stop, danger=True, padx=20, pady=12)
        self.stop_button.config(state=tk.DISABLED)
        self.progress_text = self._label(footer, 'ვიდეო: 0% • ყველა: 0%', 10, MUTED)
        self.progressbar = ttk.Progressbar(footer, variable=self.file_progress_var,
                                            style='Horizontal.TProgressbar', orient=tk.HORIZONTAL, maximum=100)
        self.progressbar.pack(fill=tk.X, pady=(4, 6))
        self.totalbar = ttk.Progressbar(footer, variable=self.global_progress_var,
                                       style='Horizontal.TProgressbar', orient=tk.HORIZONTAL, maximum=100)
        self.totalbar.pack(fill=tk.X, pady=(0, 4))
        self.message = tk.Label(footer, textvariable=self.status_var, bg=BG,
                                fg=ACCENT, font=(FONT, 10), anchor='w', justify='left')
        self.message.pack(fill=tk.X)

    def _current_spec(self) -> str:
        """Create source-timeline intervals from three visible user controls."""
        start = parse_seconds(self.slow_start_var.get())
        length = parse_seconds(self.slow_length_var.get())
        if length <= 0:
            raise ValueError('შენელების ხანგრძლივობა 0-ზე მეტი უნდა იყოს.')
        speed_text = self.slow_speed_var.get().split('×')[0].strip()
        speed = parse_seconds(speed_text)
        if speed not in (0.5, 0.25, 0.125):
            raise ValueError('აირჩიე 0.5×, 0.25× ან 0.125×.')
        spec = f'{start:g}-{start + length:g}@{speed:g}'
        extra = self.extra_intervals_var.get().strip()
        if extra:
            spec += '; ' + extra
        parse_segments(spec)
        return spec

    def _on_settings_edited(self, *_):
        self._update_segment_preview()
        if not self._syncing_fields:
            self._settings_dirty = True

    def _update_segment_preview(self, *_):
        try:
            spec = self._current_spec()
            first = SlowSegment(
                parse_seconds(self.slow_start_var.get()),
                parse_seconds(self.slow_start_var.get()) +
                parse_seconds(self.slow_length_var.get()),
                parse_seconds(self.slow_speed_var.get().split('×')[0].strip()))
            self.preview_var.set(
                f'✓ ორიგინალის {first.start:g}–{first.end:g} წმ '
                f'→ მზაში {first.original_seconds / first.speed:g} წმ')
            self.interval_var.set(spec)
        except ValueError as exc:
            self.preview_var.set('⚠ ' + str(exc))

    def _show_settings_for_spec(self, spec: str):
        """Restore a video's saved settings when user selects its row."""
        self._syncing_fields = True
        try:
            segments = parse_segments(spec)
            first = segments[0]
            self.slow_start_var.set(f'{first.start:g}')
            self.slow_length_var.set(f'{first.original_seconds:g}')
            speed_names = {
                0.5: '0.5× — 2-ჯერ ნელა',
                0.25: '0.25× — 4-ჯერ ნელა',
                0.125: '0.125× — 8-ჯერ ნელა',
            }
            self.slow_speed_var.set(speed_names[first.speed])
            self.extra_intervals_var.set('; '.join(
                f'{item.start:g}-{item.end:g}@{item.speed:g}'
                for item in segments[1:]))
        except (ValueError, KeyError):
            pass
        finally:
            self._syncing_fields = False
            self._settings_dirty = False

    def add_files(self):
        if self.busy:
            return
        paths = filedialog.askopenfilenames(title='აირჩიე GoPro MP4 ვიდეოები',
                    filetypes=[('ვიდეოები', '*.mp4 *.mov *.m4v *.avi *.mkv'), ('ყველა ფაილი', '*.*')])
        self._add_paths([Path(p) for p in paths])

    def add_folder(self):
        if self.busy:
            return
        folder = filedialog.askdirectory(title='აირჩიე GoPro ვიდეოების საქაღალდე')
        if folder:
            self._add_paths(sorted(p for p in Path(folder).iterdir()
                                   if p.is_file() and p.suffix.lower() in SUPPORTED_EXTENSIONS))

    def _add_paths(self, paths: list[Path]):
        new = []
        try:
            spec = self._current_spec()
        except ValueError as e:
            messagebox.showerror('შენელების პარამეტრები', str(e))
            return
        for path in paths:
            if path.suffix.lower() not in SUPPORTED_EXTENSIONS or not path.is_file():
                continue
            key = str(path.resolve()).lower()
            if key in self.items:
                continue
            item = VideoItem(path.resolve(), spec)
            self.items[key] = item
            self.tree.insert('', 'end', iid=key,
                             values=(path.name, '...', item.segments, item.status))
            new.append((key, path))
        self.count_label.config(text=f'სულ: {len(self.items)} ვიდეო')
        if new:
            self.status_var.set(f'დაემატა {len(new)} ვიდეო. პარამეტრები შეგიძლია ყველას ერთად მიუთითო.')
            threading.Thread(target=self._probe_items, args=(new,), daemon=True).start()

    def _probe_items(self, items):
        ffmpeg = ffmpeg_path()
        for key, path in items:
            try:
                duration = probe_duration(ffmpeg, path)
                self.events.put(('probed', key, duration))
            except Exception as e:
                self.events.put(('probe_error', key, str(e)))

    def remove_selected(self):
        if self.busy:
            return
        for key in self.tree.selection():
            self.tree.delete(key)
            self.items.pop(key, None)
        self.count_label.config(text=f'სულ: {len(self.items)} ვიდეო')

    def _select_item(self, event=None):
        selected = self.tree.selection()
        if len(selected) == 1 and selected[0] in self.items:
            self._show_settings_for_spec(self.items[selected[0]].segments)

    def _apply(self, keys):
        if self.busy:
            return
        try:
            spec = self._current_spec()
            segments = parse_segments(spec)
            for key in keys:
                item = self.items[key]
                if item.duration is not None:
                    validate_duration(segments, item.duration)
        except ValueError as e:
            messagebox.showerror('შენელების შეცდომა', str(e))
            return
        for key in keys:
            self.items[key].segments = spec
            self._refresh_row(key)
        self._settings_dirty = False
        self.status_var.set(f'შენელების პარამეტრები მიენიჭა {len(keys)} ვიდეოს.')

    def apply_selected(self):
        selection = [key for key in self.tree.selection() if key in self.items]
        if not selection:
            messagebox.showinfo('მონიშვნა', 'ჯერ სიაში მონიშნე ერთი ან რამდენიმე ვიდეო.')
            return
        self._apply(selection)

    def apply_all(self):
        if self.items:
            self._apply(list(self.items))

    def choose_output(self):
        if self.busy:
            return
        folder = filedialog.askdirectory(title='აირჩიე მზა MP4-ების საქაღალდე')
        if folder:
            self.output_var.set(folder)

    def open_output(self):
        path = Path(self.output_var.get()).expanduser()
        try:
            path.mkdir(parents=True, exist_ok=True)
            if os.name == 'nt':
                os.startfile(str(path))
            elif sys.platform == 'darwin':
                subprocess.Popen(['open', str(path)])
            else:
                subprocess.Popen(['xdg-open', str(path)])
        except OSError as e:
            messagebox.showerror('საქაღალდის შეცდომა', str(e))

    def open_drive(self):
        webbrowser.open('https://drive.google.com/drive/u/0/my-drive')

    def _refresh_row(self, key: str):
        if key not in self.items or not self.tree.exists(key):
            return
        item = self.items[key]
        self.tree.item(key, values=(item.path.name,
            f'{item.duration:.2f} წმ' if item.duration else '...',
            item.segments, item.status))

    def start(self):
        if self.busy:
            return
        if not self.items:
            messagebox.showinfo('ვიდეოების დამატება', 'ჯერ აირჩიე GoPro ვიდეოები.')
            return
        if self._settings_dirty:
            try:
                self._current_spec()
            except ValueError as exc:
                messagebox.showerror('შენელების პარამეტრები', str(exc))
                return
            if not messagebox.askyesno(
                    'შენელების პარამეტრები',
                    'შენელების წამები ან სიჩქარე შეცვალე, მაგრამ ღილაკით ჯერ '
                    'ვიდეოებზე არ მიგინიჭებია.\\n\\n'
                    'ახლანდელი პარამეტრები ყველა ვიდეოზე გამოვიყენო?'):
                return
            self._apply(list(self.items))
            if self._settings_dirty:
                return
        try:
            out = Path(self.output_var.get()).expanduser().resolve()
            out.mkdir(parents=True, exist_ok=True)
            if not out.is_dir():
                raise ValueError('საქაღალდე მიუწვდომელია.')
            specs = {key: parse_segments(item.segments) for key, item in self.items.items()}
            for key, segments in specs.items():
                item = self.items[key]
                if item.duration:
                    validate_duration(segments, item.duration)
            fps = int(self.fps_var.get())
            crf = int(self.quality_var.get().split('CRF ')[-1].rstrip(')'))
        except (ValueError, OSError) as e:
            messagebox.showerror('პარამეტრების შეცდომა', str(e))
            return
        self.busy = True
        self.cancelled.clear()
        self.completed = self.failed = 0
        self.file_progress_var.set(0)
        self.global_progress_var.set(0)
        self.start_button.config(state=tk.DISABLED)
        self.stop_button.config(state=tk.NORMAL)
        batch = list(self.items.items())
        self.status_var.set(f'იწყება {len(batch)} ვიდეოს დამუშავება...')
        threading.Thread(target=self._process, args=(batch, specs, out, fps, crf),
                         daemon=True, name='MOMENTO-VideoWorker').start()

    def _process(self, batch, specs, out, fps, crf):
        rows = []
        occupied: set[str] = set()
        ffmpeg = ffmpeg_path()
        count = len(batch)
        for i, (key, item) in enumerate(batch):
            if self.cancelled.is_set():
                break
            self.events.put(('status', key, 'მუშავდება…'))
            output = choose_output_name(out, item.path, occupied)
            try:
                def on_progress(percent, *, file_key=key, index=i):
                    self.events.put(('progress', file_key, index, count, percent))
                finished, original_seconds, output_seconds = render(
                    item.path, output, specs[key], ffmpeg=ffmpeg, fps=fps,
                    crf=crf, cancelled=self.cancelled, progress=on_progress)
                self.events.put(('done_file', key, str(finished), original_seconds, output_seconds))
                status = 'დასრულდა'
                error = ''
            except Cancelled:
                self.events.put(('status', key, 'შეჩერებულია'))
                break
            except Exception as e:
                status = 'შეცდომა'
                error = str(e)
                self.events.put(('error_file', key, error))
                original_seconds = item.duration or 0
                output_seconds = 0
            rows.append({
                'ფაილი': item.path.name, 'ორიგინალი (წმ)': f'{original_seconds:.2f}',
                'შენელების ინტერვალები': item.segments,
                'მზა ვიდეო (წმ)': f'{output_seconds:.2f}',
                'მდგომარეობა': status,
                'გამოსავალი': str(output) if status == 'დასრულდა' else '',
                'შეცდომა': error,
            })
        report = out / 'MOMENTO_ვიდეოების_სია.csv'
        try:
            with report.open('w', encoding='utf-8-sig', newline='') as f:
                writer = csv.DictWriter(f, fieldnames=list(rows[0]) if rows else [
                    'ფაილი','ორიგინალი (წმ)','შენელების ინტერვალები',
                    'მზა ვიდეო (წმ)','მდგომარეობა','გამოსავალი','შეცდომა'])
                writer.writeheader()
                writer.writerows(rows)
        except OSError as e:
            self.events.put(('warning', f'CSV-ის შენახვა ვერ მოხერხდა: {e}'))
        self.events.put(('finished', self.cancelled.is_set(), out))

    def stop(self):
        if self.busy:
            self.cancelled.set()
            self.status_var.set('მიმდინარე ვიდეო ჩერდება. დასრულებული MP4-ები შენარჩუნდება.')
            self.stop_button.config(state=tk.DISABLED)

    def _poll(self):
        try:
            while True:
                event = self.events.get_nowait()
                kind = event[0]
                if kind == 'probed':
                    _, key, duration = event
                    if key in self.items:
                        self.items[key].duration = duration
                        self._refresh_row(key)
                elif kind == 'probe_error':
                    _, key, err = event
                    if key in self.items:
                        self.items[key].status = 'შემოწმების შეცდომა'
                        self.items[key].error = err
                        self._refresh_row(key)
                elif kind == 'status':
                    _, key, status = event
                    if key in self.items:
                        self.items[key].status = status
                        self._refresh_row(key)
                elif kind == 'progress':
                    _, key, index, count, percent = event
                    self.file_progress_var.set(percent)
                    self.global_progress_var.set((index + percent / 100) * 100 / count)
                    self.progress_text.config(text=f'ვიდეო {index+1}/{count}: {percent:.0f}% • სულ: {(index + percent/100)*100/count:.0f}%')
                    if key in self.items:
                        self.items[key].status = f'{percent:.0f}%'
                        self._refresh_row(key)
                elif kind == 'done_file':
                    _, key, output, orig, result_secs = event
                    self.completed += 1
                    if key in self.items:
                        self.items[key].output = Path(output)
                        self.items[key].output_duration = result_secs
                        self.items[key].status = '✓ მზადაა'
                        self._refresh_row(key)
                elif kind == 'error_file':
                    _, key, err = event
                    self.failed += 1
                    if key in self.items:
                        self.items[key].status = '✕ შეცდომა'
                        self.items[key].error = err
                        self._refresh_row(key)
                        self.status_var.set(f'⚠ {self.items[key].path.name}: {err[:170]}')
                elif kind == 'warning':
                    self.status_var.set(event[1])
                elif kind == 'finished':
                    self.busy = False
                    self.start_button.config(state=tk.NORMAL)
                    self.stop_button.config(state=tk.DISABLED)
                    _, was_cancelled, out = event
                    total = len(self.items)
                    note = ('გაჩერებულია.' if was_cancelled else 'დამთავრდა.') + \
                           f' მზადაა: {self.completed}; შეცდომა: {self.failed}; სულ: {total}.'
                    self.status_var.set(note)
                    if not was_cancelled:
                        self.global_progress_var.set(100)
                    messagebox.showinfo('MOMENTO — შედეგი',
                        note + '\n\nმზა ვიდეოები და MOMENTO_ვიდეოების_სია.csv ერთ საქაღალდეშია.'
                        '\nGoogle Drive-ზე გააზიარე სწორედ ეს საქაღალდე.')
        except queue.Empty:
            pass
        finally:
            self.root.after(80, self._poll)

    def _on_close(self):
        if self.busy:
            messagebox.showwarning('მიმდინარე დამუშავება',
                 'ჯერ დააჭირე „შეჩერება“-ს და დაელოდე მიმდინარე ფაილის დასრულებას.')
            return
        self.root.destroy()


def _verify_from_commandline(args: list[str]) -> int:
    if len(args) != 2:
        return 2
    try:
        render(Path(args[0]), Path(args[1]), [SlowSegment(1, 2, 0.5)])
        return 0 if Path(args[1]).is_file() else 1
    except Exception:
        return 1


def _ui_selftest() -> int:
    """Headless-command-mode health check: actually builds the Tk GUI on Windows.

    Prevents another release whose CLI video processing works but whose
    progressbar/style fails before the window becomes visible.
    """
    root = None
    try:
        root = tk.Tk()
        root.withdraw()
        app = SlowMoApp(root)
        root.update_idletasks()
        assert app.progressbar.winfo_exists()
        assert app.totalbar.winfo_exists()
        assert app._current_spec() == '3-5@0.25'
        app.slow_start_var.set('5')
        app.slow_length_var.set('3')
        app.slow_speed_var.set('0.5× — 2-ჯერ ნელა')
        assert app._current_spec() == '5-8@0.5'
        app.extra_intervals_var.set('9-10@0.25')
        assert app._current_spec() == '5-8@0.5; 9-10@0.25'
        if sys.stdout is not None:
            print('MOMENTO UI startup and three editing controls passed')
        return 0
    except Exception:
        import traceback
        if sys.stderr is not None:
            traceback.print_exc()
        return 1
    finally:
        if root is not None:
            root.destroy()


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == '--ui-selftest':
        return _ui_selftest()
    if len(sys.argv) > 1 and sys.argv[1] == '--verify':
        return _verify_from_commandline(sys.argv[2:])
    root = tk.Tk()
    try:
        root.tk.call('tk', 'scaling', 1.15)
    except tk.TclError:
        pass
    SlowMoApp(root)
    root.mainloop()
    return 0


if __name__ == '__main__':
    sys.exit(main())