"""Record locally verified model files; this is integrity bookkeeping, not provenance attestation."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def write_manifest(root: Path, name: str) -> Path:
    root = root.resolve()
    model_root = root / "insightface" / "models" / name
    required = [model_root / file for file in ("det_10g.onnx", "w600k_r50.onnx", "1k3d68.onnx")]
    required.append(root / "face_landmarker.task")
    if not all(path.is_file() for path in required):
        raise ValueError("缺少模型文件，请先按 Readme 下载并解压完整 buffalo_l 模型及 face_landmarker.task")
    paths = sorted(set(required + list(model_root.glob("*.onnx"))))
    digests = {}
    for path in paths:
        if not path.resolve().is_relative_to(root):
            raise ValueError("模型路径不得指向模型目录之外")
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        digests[path.relative_to(root).as_posix()] = digest.hexdigest()
    manifest = {"modelName": name, "recordedAt": datetime.now(timezone.utc).isoformat(),
                "sources": ["https://github.com/deepinsight/insightface/releases/tag/v0.7",
                            "https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker"],
                "sha256": digests,
                "notice": "Locally computed hashes detect subsequent change; independently verify downloads and license before registration."}
    output = root / "manifest.json"
    output.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, default=Path("models"))
    parser.add_argument("--model-name", default="buffalo_l", choices=["buffalo_l"])
    args = parser.parse_args()
    try:
        print(write_manifest(args.model_dir, args.model_name))
    except ValueError as error:
        parser.exit(2, f"{error}\n")
