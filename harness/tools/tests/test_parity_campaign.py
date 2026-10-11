import importlib.util
from pathlib import Path
import random
import unittest


SCRIPT = Path(__file__).parents[1] / "parity-campaign.py"
SPEC = importlib.util.spec_from_file_location("parity_campaign", SCRIPT)
CAMPAIGN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAMPAIGN)

# Vanilla world border radius 29,999,984 blocks, in chunks.
BORDER_CHUNKS = 29_999_984 // 16


class PlanTests(unittest.TestCase):
    def test_plan_starts_at_origin_and_stays_inside_border(self):
        for seed in range(50):
            plans = CAMPAIGN.plan_windows(20, 64, random.Random(seed))
            self.assertEqual(plans[0], (-32, -32, True))
            for x, z, startup in plans:
                for c in (x, z, x + 63, z + 63):
                    self.assertLess(abs(c), BORDER_CHUNKS)
                # only origin windows need startup chunks traced
                self.assertEqual(startup, (x, z) == (-32, -32))

    def test_plan_reaches_far_distances(self):
        plans = CAMPAIGN.plan_windows(20, 64, random.Random(7))
        far = max(max(abs(x), abs(z)) for x, z, _ in plans)
        self.assertGreater(far, 60_000)

    def test_verdict_regexes_match_real_output(self):
        strict = ("TIER_R ring=2 scoredChunks=9529 exact=9529 (100,00%) cells=936738816 "
                  "diffs=0 parity=100,000000%")
        m = CAMPAIGN.TIER_R_RE.search(strict)
        self.assertEqual(m.groups(), ("9529", "9529", "936738816", "0"))
        live = "LiveStructuresTraceCheck: PASSED (9529 chunks, 936738816 cells)"
        self.assertEqual(CAMPAIGN.LIVE_RE.search(live).groups(), ("9529", "936738816"))


if __name__ == "__main__":
    unittest.main()
