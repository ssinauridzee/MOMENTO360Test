import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from processor import SlowSegment, filter_graph, parse_segments, result_duration, validate_duration


class SlowMoUnitTests(unittest.TestCase):
    def test_intervals_and_multiple_speeds(self):
        self.assertEqual(parse_segments('3-5@0.25; 8-9@0.5'),
            [SlowSegment(3,5,.25), SlowSegment(8,9,.5)])
        self.assertAlmostEqual(result_duration(10, parse_segments('3-5@0.25; 8-9@0.5')),17)

    def test_minute_notation(self):
        self.assertEqual(parse_segments('0:03-0:05@0,25')[0], SlowSegment(3,5,.25))

    def test_non_overlapping(self):
        with self.assertRaises(ValueError):
            parse_segments('2-5@0.25; 4-8@0.5')

    def test_bounds(self):
        with self.assertRaises(ValueError):
            validate_duration(parse_segments('4-8@0.25'),5)

    def test_filter(self):
        s = filter_graph(parse_segments('2-4@0.25; 5-6@0.5'),8)
        self.assertIn('split=5',s)
        self.assertIn('concat=n=5:v=1:a=0',s)
        self.assertIn('fps=60',s)
        self.assertIn('setpts=(PTS-STARTPTS)/0.25',s)


if __name__ == '__main__':
    unittest.main()