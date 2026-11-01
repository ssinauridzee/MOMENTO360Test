"""Catalog for 60 DIFFERENT original, upbeat festival music compositions.
Names, tonalities, melodic hooks, tempos and arrangements are deterministically
generated, rather than renaming the same recording or pitch-shifting one track.
"""
from __future__ import annotations
from collections import namedtuple
import random

Track = namedtuple("Track", "name bpm root motif style variant progression")

STYLE_LABELS = (
    ("Happy_Pop", "ჰეფი პოპი", 124, 139),
    ("Party_Dance", "მხიარული დენსი", 126, 142),
    ("Summer_Bounce", "ზაფხულის მხიარული რიტმი", 116, 133),
    ("Festive_Brass", "სადღესასწაულო ბრასი", 120, 140),
    ("Disco_Fun", "სადისკო გართობა", 118, 135),
)

TITLE_ENDINGS = (
    "მზიანი ზეიმი", "მხიარული ქალაქი", "სიცილის რიტმი", "ცეკვის დრო",
    "ფერადი დღე", "მეგობრების წრე", "ღიმილის ტალღა", "ფესტივალის ცეცხლი",
    "ხალისიანი ნაბიჯები", "სიხარულის საათი", "მეგობრული წვეულება",
    "უდარდელი ფინალი",
)

# These progressions are original arrangements of conventional functional
# harmonic vocabulary, not copied melodies or recordings.
PROGRESSIONS = (
    (0, 4, 5, 3),  # I V vi IV
    (0, 3, 4, 0),  # I IV V I
    (5, 3, 0, 4),  # vi IV I V
    (0, 1, 3, 4),  # I ii IV V
    (0, 5, 3, 4),  # I vi IV V
    (3, 4, 0, 5),  # IV V I vi
    (0, 4, 3, 4),  # I V IV V
    (0, 3, 0, 4),  # I IV I V
    (5, 4, 3, 0),  # vi V IV I
    (0, 1, 4, 0),  # I ii V I
)
CHORD_TONES = (
    (0, 2, 4),   # I
    (1, 3, 5),   # ii
    (2, 4, 6),   # iii
    (3, 5, 7),   # IV
    (4, 6, 8),   # V
    (5, 7, 9),   # vi
)
# Major scale offsets across 3 octaves.
MAJOR = tuple((0, 2, 4, 5, 7, 9, 11)[i % 7] + 12*(i // 7)
              for i in range(28))


def make_tracks() -> tuple[Track, ...]:
    entries = []
    for group, (prefix, label, low, high) in enumerate(STYLE_LABELS):
        for number in range(12):
            seed = 240117 + group*1409 + number*683
            r = random.Random(seed)
            tempo = low + (number*7 + group*3) % (high-low+1)
            base = (60, 62, 65, 67, 69, 64, 63, 66, 70, 61, 68, 71)[(number + 3*group) % 12]
            progress = PROGRESSIONS[(number + group*3) % len(PROGRESSIONS)]
            # Write a unique 16-eighth-note hook for each composition. On strong
            # notes, choose consonant major-chord tones; vary syncopated pickups.
            hook = []
            for step in range(16):
                if step % 2 == 0:
                    triad = CHORD_TONES[progress[(step // 4) % 4]]
                    degree = r.choice(triad) + (7 if r.random() < .32 else 0)
                else:
                    previous = hook[-1]
                    degree = max(0, min(15, previous + r.choice((-2,-1,0,1,2))))
                hook.append(degree)
            name = f"Terjoloba_{prefix}_{number+1:02d}"
            entries.append(Track(name,tempo,base,tuple(hook),
                                 prefix,number,progress))
    return tuple(entries)


TRACKS = make_tracks()
NAMES = {
    spec.name: f"თერჯოლობა • {label} • {TITLE_ENDINGS[spec.variant]}"
    for prefix,label,*_ in STYLE_LABELS
    for spec in TRACKS if spec.style == prefix
}
assert len(TRACKS) == 60
assert len(NAMES) == 60
assert len({t.motif for t in TRACKS}) == 60
