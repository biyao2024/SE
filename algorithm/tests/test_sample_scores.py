"""Dataset/score bookkeeping tests use explicit test doubles, not real face experiments."""
import csv
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

from app.domain import Observation, Rejected
from app.settings import Settings
from scripts.calibrate import calibrate, metrics, read_samples, verify_run_report
from scripts.capture_samples import append_capture
from scripts.score_samples import load_dataset, pixel_fingerprint, read_capture, score_dataset, write_results
from tests.test_domain import vector


class FixtureEngine:
    ready = True
    model_version = "explicit-test-double"
    settings = Settings("unused-key", Path("unused-models"))

    def observe(self, content, require_landmarks=False):
        if b"quality-reject" in content:
            raise Rejected("LOW_QUALITY", "test fixture rejection")
        if b"runtime-failure" in content:
            raise Rejected("MODEL_UNAVAILABLE", "test fixture unavailable", 503)
        return Observation(vector(1 if b"unknown" in content else 0))


class SampleScoreTests(unittest.TestCase):
    @unittest.skipUnless(importlib.util.find_spec("PIL"), "Pillow is required for real JPEG duplicate validation")
    def test_jpeg_metadata_does_not_evade_pixel_duplicate_detection(self):
        from PIL import Image
        pixels = Image.new("RGB", (160, 120), (30, 90, 150))
        first, second = io.BytesIO(), io.BytesIO()
        metadata = Image.Exif()
        metadata[270] = "metadata-only change"
        pixels.save(first, format="JPEG", quality=80)
        pixels.save(second, format="JPEG", quality=80, exif=metadata)
        self.assertNotEqual(hashlib.sha256(first.getvalue()).digest(), hashlib.sha256(second.getvalue()).digest())
        self.assertEqual(pixel_fingerprint(first.getvalue()), pixel_fingerprint(second.getvalue()))
        too_small = io.BytesIO()
        Image.new("RGB", (16, 16)).save(too_small, format="JPEG")
        with self.assertRaises(ValueError):
            pixel_fingerprint(too_small.getvalue())

    def setUp(self):
        workspace = Path(__file__).resolve().parents[1]
        self.temporary = tempfile.TemporaryDirectory(prefix="dataset-test-", dir=workspace)
        self.root = Path(self.temporary.name)
        self.assertTrue(self.root.resolve().is_relative_to(workspace))
        self.addCleanup(self.temporary.cleanup)
        self.path = self.root / "dataset.json"
        self.data = {"schemaVersion": 1, "datasetId": "fixture", "consentConfirmed": True, "samples": []}
        for i, (split, employee) in enumerate([("enrollment", 1)] * 3 + [("calibration", 1), ("calibration", None), ("test", 1), ("test", None)]):
            name = f"image-{i}.jpg"
            (self.root / name).write_bytes(f"fixture-{i}-{'known' if employee else 'unknown'}".encode())
            self.data["samples"].append({"path": name, "split": split, "employeeId": employee,
                                         "subjectId": "p01" if employee else "u01", "captureSession": split + "-capture"})

    def load(self, fingerprint=lambda content: hashlib.sha256(content).hexdigest()):
        self.path.write_text(json.dumps(self.data), encoding="utf-8")
        return load_dataset(self.path, fingerprint=fingerprint)

    def test_score_pipeline_contract_and_preserved_quality_rejection(self):
        (self.root / "image-5.jpg").write_bytes(b"unique-quality-reject-known")
        rows, report = score_dataset(self.load(), FixtureEngine())
        self.assertEqual(len(rows), 4)
        self.assertEqual(rows[0]["score"], 1.0)
        self.assertEqual(rows[1]["truth_id"], "UNKNOWN")
        self.assertEqual(rows[1]["score"], 0.0)
        self.assertEqual(rows[2]["status"], "REJECTED")
        self.assertEqual(rows[2]["score"], "")
        output = self.root / "run"
        write_results(output, rows, report)
        samples = read_samples(output / "scores.csv")
        measured = metrics([sample for sample in samples if sample.split == "test"], 0.5, 0.1)
        self.assertEqual(measured["knownRejectionRate"], 1.0)
        self.assertEqual(measured["qualityRejectedSamples"], 1)
        self.assertEqual(measured["unknownAcceptanceRate"], 0.0)
        self.assertEqual(verify_run_report(output / "run.json", output / "scores.csv", samples)["gallerySha256"], report["gallerySha256"])
        with self.assertRaises(FileExistsError):
            write_results(output, rows, report)

    def test_duplicate_bytes_and_decoded_pixels_rejected(self):
        original = (self.root / "image-5.jpg").read_bytes()
        (self.root / "image-5.jpg").write_bytes((self.root / "image-0.jpg").read_bytes())
        with self.assertRaisesRegex(ValueError, "重复"):
            self.load()
        (self.root / "image-5.jpg").write_bytes(original)
        with self.assertRaisesRegex(ValueError, "重复"):
            self.load(fingerprint=lambda content: "same-decoded-pixels")

    def test_capture_session_cannot_cross_groups(self):
        self.data["samples"][3]["captureSession"] = "enrollment-capture"
        with self.assertRaisesRegex(ValueError, "采集批次"):
            self.load()

    def test_unknown_label_cannot_reclassify_enrolled_subject(self):
        self.data["samples"][4]["subjectId"] = "p01"
        with self.assertRaisesRegex(ValueError, "同一subjectId"):
            self.load()

    def test_exact_three_enrollment_and_each_split_coverage(self):
        self.data["samples"].pop(0)
        with self.assertRaisesRegex(ValueError, "恰好3张"):
            self.load()

    def test_test_group_requires_unknown_people(self):
        self.data["samples"].pop()
        with self.assertRaisesRegex(ValueError, "test必须"):
            self.load()

    def test_consent_required_and_path_cannot_escape_dataset(self):
        self.data["consentConfirmed"] = False
        with self.assertRaisesRegex(ValueError, "consentConfirmed"):
            self.load()
        self.data["consentConfirmed"] = True
        self.data["samples"][0]["path"] = "../outside.jpg"
        with self.assertRaisesRegex(ValueError, "超出清单目录"):
            self.load()

    def test_mutation_after_preflight_and_runtime_failure_abort_batch(self):
        dataset = self.load()
        (self.root / "image-0.jpg").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "发生变化"):
            read_capture(dataset.captures[0])
        (self.root / "image-3.jpg").write_bytes(b"runtime-failure")
        with self.assertRaisesRegex(ValueError, "运行故障"):
            score_dataset(self.load(), FixtureEngine())

    def test_enrollment_quality_failure_aborts_instead_of_replacing_template(self):
        (self.root / "image-1.jpg").write_bytes(b"quality-reject-enrollment")
        with self.assertRaisesRegex(ValueError, "录入样本"):
            score_dataset(self.load(), FixtureEngine())

    def test_modified_scores_cannot_claim_original_run_provenance(self):
        rows, report = score_dataset(self.load(), FixtureEngine())
        output = self.root / "run"
        write_results(output, rows, report)
        samples = read_samples(output / "scores.csv")
        with (output / "scores.csv").open("a") as stream:
            stream.write("\n")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            verify_run_report(output / "run.json", output / "scores.csv", samples)

    def test_rejected_rows_cannot_include_fabricated_scores(self):
        (self.root / "image-3.jpg").write_bytes(b"quality-reject-calibration")
        rows, report = score_dataset(self.load(), FixtureEngine())
        rows[0]["score"] = 0.99
        output = self.root / "run"
        write_results(output, rows, report)
        with self.assertRaisesRegex(ValueError, "虚构分数"):
            read_samples(output / "scores.csv")

    def test_all_known_calibration_rejected_cannot_return_usable_parameters(self):
        (self.root / "image-3.jpg").write_bytes(b"quality-reject-calibration")
        rows, report = score_dataset(self.load(), FixtureEngine())
        output = self.root / "run"
        write_results(output, rows, report)
        with self.assertRaisesRegex(ValueError, "采集质量"):
            calibrate(read_samples(output / "scores.csv"), 0.01, 0.01)

    def test_all_unknown_calibration_quality_rejected_does_not_establish_threshold(self):
        (self.root / "image-4.jpg").write_bytes(b"quality-reject-unknown")
        rows, report = score_dataset(self.load(), FixtureEngine())
        output = self.root / "run"
        write_results(output, rows, report)
        with self.assertRaisesRegex(ValueError, "未知人员"):
            calibrate(read_samples(output / "scores.csv"), 0.01, 0.01)

    def test_capture_append_does_not_overwrite_and_blocks_fourth_enrollment(self):
        self.load()
        before = self.path.read_bytes()
        with self.assertRaisesRegex(ValueError, "只能有3张"):
            append_capture(self.path, "p01", 1, "enrollment", "enrollment-capture", b"\xff\xd8fixture")
        self.assertEqual(self.path.read_bytes(), before)
        first = append_capture(self.path, "u01", None, "test", "test-capture", b"\xff\xd8fixture-a")
        second = append_capture(self.path, "u01", None, "test", "test-capture", b"\xff\xd8fixture-b")
        self.assertNotEqual(first, second)
        self.assertEqual(first.read_bytes(), b"\xff\xd8fixture-a")
        self.assertEqual(len(json.loads(self.path.read_text())["samples"]), 9)


if __name__ == "__main__":
    unittest.main()
