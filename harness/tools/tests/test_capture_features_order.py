import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).parents[1] / "capture-features-order.py"
SPEC = importlib.util.spec_from_file_location("capture_features_order", SCRIPT)
CAPTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAPTURE)


class CapturePlanTests(unittest.TestCase):
    def test_36_square_is_tiled_within_forceload_limit(self):
        batches = CAPTURE.plan_batches(-18, -18, 36, 36)

        covered = set()
        for batch in batches:
            self.assertLessEqual(len(batch.required), 256)
            self.assertTrue(covered.isdisjoint(batch.required))
            covered.update(batch.required)

        expected = {(x, z) for x in range(-18, 18) for z in range(-18, 18)}
        self.assertEqual(covered, expected)
        self.assertGreaterEqual(CAPTURE.scored_interior_count(expected, 2), 1000)

    def test_forceload_commands_use_chunk_aligned_block_corners(self):
        batch = CAPTURE.plan_batches(-3, 5, 2, 3)[0]

        self.assertEqual(batch.add_command, "forceload add -48 80 -32 112")
        self.assertEqual(batch.remove_command, "forceload remove -48 80 -32 112")

    def test_rejects_a_window_without_a_ring_two_interior(self):
        origins = {(x, z) for x in range(4) for z in range(4)}
        self.assertEqual(CAPTURE.scored_interior_count(origins, 2), 0)

    def test_legacy_targets_still_wait_for_their_dependency_ring(self):
        batches = CAPTURE.legacy_batches()

        self.assertEqual([batch.add_command for batch in batches], CAPTURE.COMMANDS)
        self.assertEqual(len(batches[0].required), 9)
        self.assertEqual(batches[0].required,
                         frozenset((x, z) for x in range(-7, -4) for z in range(-7, -4)))


if __name__ == "__main__":
    unittest.main()
