import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from processor import SlowSegment, filter_graph, parse_segments, result_duration, validate_duration, plan_timeline, format_segments


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

    def test_android_playback_seconds_and_auto_normal_outro(self):
        # 12s source: 5s normal + 3.5s of original played at 0.5x
        # for 7s + 3.5s ending at normal speed = 15.5s final.
        p = plan_timeline(12, 5, 7, .5)
        self.assertAlmostEqual(p.intro, 5)
        self.assertAlmostEqual(p.slow_source_seconds, 3.5)
        self.assertAlmostEqual(p.slow_playback_seconds, 7)
        self.assertAlmostEqual(p.outro, 3.5)
        self.assertAlmostEqual(p.final_seconds, 15.5)
        self.assertEqual(format_segments(p.segments), '5-8.5@0.5')
        g = filter_graph(list(p.segments), 12, fps=60)
        self.assertIn('trim=start=8.500000:end=12.000000,setpts=PTS-STARTPTS',g)

    def test_manual_exact_last_normal_seconds_recalculates_slow(self):
        p=plan_timeline(12,5,7,.5,tail_seconds=2)
        self.assertAlmostEqual(p.slow_source_seconds,5)
        self.assertAlmostEqual(p.slow_playback_seconds,10)
        self.assertAlmostEqual(p.outro,2)
        self.assertAlmostEqual(p.final_seconds,17)
        g=filter_graph(list(p.segments),12)
        self.assertIn('trim=start=10.000000:end=12.000000,setpts=PTS-STARTPTS',g)

    def test_manual_tail_is_not_possible_when_source_too_short(self):
        with self.assertRaises(ValueError):
            plan_timeline(6,5,7,.5,tail_seconds=2)

    def test_auto_slow_duration_must_fit_video(self):
        with self.assertRaises(ValueError):
            plan_timeline(7,5,7,.5)

    def test_no_unexpected_trimming(self):
        for source in (11,12,15,24):
            p=plan_timeline(source,5,7,.5)
            self.assertAlmostEqual(p.intro+p.slow_source_seconds+p.outro,source)
            self.assertGreaterEqual(p.outro,0)



if __name__ == '__main__':
    unittest.main()