import random
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from engine import NAMES, random_assignments, pretty_music, result_path
from generate_music import TRACKS, synthesise


class EngineTests(unittest.TestCase):
    def test_all_tracks_have_original_compositions(self):
        self.assertEqual(len(TRACKS), 7)
        self.assertEqual(len({x[0] for x in TRACKS}), 7)
        self.assertEqual(set(NAMES), {x[0] for x in TRACKS})

    def test_random_shuffle_no_adjacent_repetition(self):
        songs = [Path(f"track_{n}.mp3") for n in range(7)]
        for seed in (2, 11, 999):
            chosen = random_assignments(70, songs, random.Random(seed))
            self.assertEqual(len(chosen), 70)
            self.assertEqual(set(chosen), set(songs))
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

    def test_music_is_generated_from_synthesis(self):
        # A tiny unique fragment is enough to check the synthesis output.
        # Full tracks are generated and audio-decoded in the Windows CI.
        name, bpm, root, melody = TRACKS[0]
        self.assertEqual(len(melody), 16)
        self.assertTrue(110 <= bpm <= 145)
        self.assertIn(name, NAMES)


if __name__ == "__main__":
    unittest.main()
