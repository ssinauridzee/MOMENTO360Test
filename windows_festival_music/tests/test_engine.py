import random
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from engine import NAMES, random_assignments, pretty_music, result_path
from catalog import TRACKS, STYLE_LABELS


class EngineTests(unittest.TestCase):
    def test_all_tracks_have_original_compositions(self):
        self.assertEqual(len(TRACKS), 60)
        self.assertEqual(len({x.name for x in TRACKS}), 60)
        self.assertEqual(set(NAMES), {x.name for x in TRACKS})

    def test_random_shuffle_no_adjacent_repetition(self):
        songs = [Path(f"{x.name}.mp3") for x in TRACKS]
        for seed in (2, 11, 999):
            chosen = random_assignments(47, songs, random.Random(seed))
            self.assertEqual(len(chosen), 47)
            self.assertEqual(len(set(chosen)), 47)
            self.assertTrue(all(a != b for a, b in zip(chosen, chosen[1:])))

    def test_one_song_can_repeat(self):
        chosen = random_assignments(4, [Path("one.mp3")])
        self.assertEqual(chosen, [Path("one.mp3")] * 4)

    def test_random_names_do_not_clobber_existing_files(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            source = folder / "GX0001.mp4"
            occupied = set()
            first = result_path(folder, source, occupied)
            second = result_path(folder, source, occupied)
            self.assertNotEqual(first, second)
            self.assertIn("MOMENTO_FESTIVAL", first.name)

    def test_60_happy_compositions_have_distinct_melodies(self):
        self.assertEqual(len(STYLE_LABELS), 5)
        self.assertEqual(len({t.motif for t in TRACKS}), 60)
        self.assertEqual(len({t.name for t in TRACKS}), 60)
        self.assertTrue(all(len(t.motif) == 16 for t in TRACKS))
        self.assertTrue(all(116 <= t.bpm <= 142 for t in TRACKS))
        self.assertEqual({t.style for t in TRACKS},
                         {prefix for prefix, _, _, _ in STYLE_LABELS})

    def test_47_videos_have_47_different_tracks(self):
        music = [Path(f"{t.name}.mp3") for t in TRACKS]
        assigned = random_assignments(47, music, random.Random(40))
        self.assertEqual(len(set(assigned)), 47)



if __name__ == "__main__":
    unittest.main()
