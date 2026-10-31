"""Load verified local CPU models and optionally exercise a real JPEG through both models.

python -m scripts.diagnose_models --model-dir models --image consenting-sample.jpg
No camera access, model downloads, embeddings, or successful attendance records are produced.
"""
import argparse
import hashlib
import json
import math
import platform
import secrets
import sys
import time
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

from app.domain import MAX_FRAME_BYTES, Rejected
from app.engine import FaceEngine
from app.settings import Settings


def diagnose(model_dir: Path, image: Path | None = None) -> dict:
    packages = {}
    for package in ("insightface", "onnxruntime", "mediapipe", "numpy", "opencv-contrib-python", "Pillow"):
        try:
            packages[package] = version(package)
        except PackageNotFoundError:
            packages[package] = "not installed"
    report = {"python": platform.python_version(), "platform": platform.platform(),
              "machine": platform.machine(), "pointerBits": 64 if sys.maxsize > 2**32 else 32,
              "packages": packages, "ready": False, "scope": "local model diagnostic; no accuracy or liveness certification"}
    settings = Settings(secrets.token_urlsafe(32), model_dir.resolve())
    engine = FaceEngine(settings)
    started = time.perf_counter()
    try:
        engine.load()
        report.update(ready=engine.ready, detail=engine.detail, modelVersion=engine.model_version,
                      providers=engine.runtime_providers, loadSeconds=round(time.perf_counter() - started, 3))
        if not engine.ready or image is None:
            return report
        with image.open("rb") as source:
            jpeg = source.read(MAX_FRAME_BYTES + 1)
        started = time.perf_counter()
        try:
            observation = engine.observe(jpeg, True)
            report["image"] = {"sha256": hashlib.sha256(jpeg).hexdigest(), "status": "INFERENCE_OK",
                               "embeddingDimensions": len(observation.embedding),
                               "embeddingNorm": math.sqrt(sum(value * value for value in observation.embedding)),
                               "eyeBlinkLeft": observation.eye_left, "eyeBlinkRight": observation.eye_right,
                               "jawOpen": observation.mouth, "inferenceSeconds": round(time.perf_counter() - started, 3)}
        except Rejected as error:
            report["image"] = {"status": "REJECTED", "code": error.code, "message": error.message}
        return report
    finally:
        engine.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, default=Path("models"))
    parser.add_argument("--image", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = diagnose(args.model_dir, args.image)
        encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            # Diagnostic reports are evidence; never overwrite another run accidentally.
            with args.output.open("x", encoding="utf-8") as output:
                output.write(encoded)
        print(encoded)
        return 0 if report["ready"] and report.get("image", {}).get("status", "INFERENCE_OK") == "INFERENCE_OK" else 2
    except (OSError, ValueError) as error:
        print(f"诊断未完成：{error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
