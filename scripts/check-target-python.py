"""Read-only target environment checks; never downloads models or opens a listener."""
import argparse
import importlib
from importlib import metadata
import json
import os
from pathlib import Path
import platform
import struct
import sys

root = Path(__file__).resolve().parent.parent
target = json.loads((root / "scripts/target-environment.json").read_text(encoding="utf-8"))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--tools-only", action="store_true")
parser.add_argument("--skip-models", action="store_true", help="Dependency installation check only; models prepared separately.")
args = parser.parse_args()
# Import checks must not contact the package update service.
os.environ["NO_ALBUMENTATIONS_UPDATE"] = "1"
if (sys.version_info[:2] != (3, 11) or struct.calcsize("P") != 8
        or sys.platform != "win32" or platform.machine().lower() not in {"amd64", "x86_64"}):
    raise SystemExit("Target requires Python 3.11 Windows x64, not a copied Python 3.12 environment.")
if sys.prefix == sys.base_prefix:
    raise SystemExit("Use algorithm/.venv-runtime; create the virtual environment on this computer.")
print(f"[OK] Python {platform.python_version()} x64 virtual environment: {sys.executable}")
if args.tools_only:
    raise SystemExit(0)
if metadata.version("pip") != target["pipVersion"]:
    raise SystemExit(f"Reproduction installer requires pip {target['pipVersion']}; run install-target-deps.ps1.")

declared = {}
for filename in ("requirements.txt", "requirements-api.txt"):
    for raw in (root / "algorithm" / filename).read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "-r ")):
            continue
        if "==" not in line:
            raise SystemExit(f"Unpinned direct dependency in {filename}: {line}")
        name, version = line.split("==", 1)
        declared[name.split("[", 1)[0].lower()] = version
for name, expected in target["pythonDependencies"].items():
    if declared.get(name.lower()) != expected:
        raise SystemExit(f"Declared dependency {name} must stay at {expected}.")
    installed = metadata.version(name)
    if installed != expected:
        raise SystemExit(f"{name}: expected {expected}, found {installed}.")
try:
    metadata.version("onnxruntime-gpu")
except metadata.PackageNotFoundError:
    pass
else:
    raise SystemExit("Remove onnxruntime-gpu from this CPU environment; use pinned onnxruntime.")
for name in ("fastapi", "starlette", "pydantic", "uvicorn", "multipart", "numpy", "insightface", "onnxruntime", "mediapipe", "cv2", "PIL", "albumentations"):
    importlib.import_module(name)
import onnxruntime
if target["inferenceProvider"] not in onnxruntime.get_available_providers():
    raise SystemExit("ONNX Runtime CPU provider is unavailable.")
print("[OK] Pinned Python direct dependencies import; CPU provider is available.")
if args.skip_models:
    print("Model verification was explicitly skipped for dependency installation.")
    raise SystemExit(0)
sys.path.insert(0, str(root / "algorithm"))
from app.engine import verify_manifest
fingerprint = verify_manifest(root / target["paths"]["models"], "buffalo_l")
print(f"[OK] Model manifest hashes verified: {fingerprint}")
print("Model loading and real camera acceptance remain separate checks.")
