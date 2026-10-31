import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

from app.domain import Rejected
from app.engine import FaceEngine, verify_manifest, validate_runtime_models, validate_pose, validate_blendshapes
from app.settings import Settings
from scripts.model_manifest import write_manifest


class EngineFailClosedTests(unittest.TestCase):
    def test_invalid_enrollment_threshold_cannot_disable_identity_checks(self):
        for value in (float("nan"), float("inf"), 0, -1, 1.1):
            with self.subTest(value=value), self.assertRaises(ValueError):
                Settings("test-key", Path("models"), enrollment_threshold=value)

    def test_nonfinite_pose_and_expression_cannot_pass(self):
        for pose in ([float("nan"), 0, 0], [0, float("inf"), 0], [], None, [31, 0, 0]):
            with self.subTest(pose=pose), self.assertRaises(Rejected):
                validate_pose(pose)
        validate_pose([0, 20, 0])
        for value in (float("nan"), float("inf"), -0.1, 1.1):
            with self.subTest(blendshape=value), self.assertRaises(Rejected):
                validate_blendshapes({"eyeBlinkLeft": value, "eyeBlinkRight": 0, "jawOpen": 0})

    def test_models_must_include_recognition_pose_and_cpu_only(self):
        def model(providers):
            return SimpleNamespace(session=SimpleNamespace(get_providers=lambda: providers))
        models = {name: model(["CPUExecutionProvider"]) for name in ("detection", "recognition", "landmark_3d_68")}
        self.assertEqual(len(validate_runtime_models(SimpleNamespace(models=models))), 3)
        models["recognition"] = model(["CUDAExecutionProvider", "CPUExecutionProvider"])
        with self.assertRaises(RuntimeError):
            validate_runtime_models(SimpleNamespace(models=models))
        del models["recognition"]
        with self.assertRaises(RuntimeError):
            validate_runtime_models(SimpleNamespace(models=models))

    def test_missing_models_are_unavailable_and_cannot_observe(self):
        settings = Settings("test-internal-key-that-is-more-than-32-characters", Path("absent-test-model-directory"))
        engine = FaceEngine(settings)
        engine.load()
        self.assertFalse(engine.ready)
        self.assertEqual(engine.model_version, "unavailable")
        self.assertIn("manifest.json", engine.detail)
        with self.assertRaises(Rejected) as context:
            engine.observe(b"not-a-face")
        self.assertEqual(context.exception.status, 503)

    def test_missing_key_is_unavailable(self):
        engine = FaceEngine(Settings("", Path("models")))
        engine.load()
        self.assertFalse(engine.ready)
        self.assertIn("INTERNAL_API_KEY", engine.detail)

    def test_model_manifest_detects_change_before_loading_any_runtime(self):
        workspace = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix="manifest-test-", dir=workspace) as temporary:
            root = Path(temporary)
            self.assertTrue(root.resolve().is_relative_to(workspace))
            model = root / "insightface/models/buffalo_l"
            model.mkdir(parents=True)
            for name in ("det_10g.onnx", "w600k_r50.onnx", "1k3d68.onnx"):
                (model / name).write_bytes(b"test fixture, not an actual model")
            (root / "face_landmarker.task").write_bytes(b"test fixture, not an actual model")
            write_manifest(root, "buffalo_l")
            self.assertTrue(verify_manifest(root, "buffalo_l").startswith("buffalo_l-"))
            (model / "det_10g.onnx").write_bytes(b"tampered fixture")
            with self.assertRaises(RuntimeError):
                verify_manifest(root, "buffalo_l")


if __name__ == "__main__":
    unittest.main()
