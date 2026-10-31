import unittest

from scripts.calibrate import Sample, calibrate, metrics, wilson


class CalibrationTests(unittest.TestCase):
    def test_test_data_cannot_change_selected_threshold(self):
        calibration = [Sample("a", "calibration", "1", "1", 0.8, 0.3, False, "test-model"),
                       Sample("b", "calibration", "UNKNOWN", "1", 0.4, 0.1, False, "test-model")]
        test_a = [Sample("c", "test", "1", "1", 0.9, 0.3, False, "test-model"),
                  Sample("d", "test", "UNKNOWN", "1", 0.2, 0.1, False, "test-model")]
        test_b = [Sample("c", "test", "1", "2", 0.99, 0.9, False, "test-model"),
                  Sample("d", "test", "UNKNOWN", "1", 0.99, 0.9, False, "test-model")]
        first, second = calibrate(calibration + test_a, 0, 0), calibrate(calibration + test_b, 0, 0)
        self.assertEqual((first["threshold"], first["margin"]), (second["threshold"], second["margin"]))
        self.assertNotEqual(first["test"], second["test"])

    def test_denominators_and_small_sample_uncertainty(self):
        samples = [Sample("a", "test", "1", "2", 0.9, 0.4, False, "test-model"),
                   Sample("b", "test", "1", "1", 0.1, 0.1, False, "test-model"),
                   Sample("c", "test", "UNKNOWN", "1", 0.1, 0.1, False, "test-model")]
        result = metrics(samples, 0.5, 0.1)
        self.assertEqual(result["wrongIdentityAcceptanceRate"], 0.5)
        self.assertEqual(result["knownRejectionRate"], 0.5)
        self.assertEqual(result["unknownAcceptanceRate"], 0)
        self.assertGreater(wilson(0, 10)[1], 0.2)


if __name__ == "__main__":
    unittest.main()
