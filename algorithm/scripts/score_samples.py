"""Generate genuine retrieval scores from a consented, independently captured dataset.

Run as: python -m scripts.score_samples --manifest experiments/dataset.json
        --output-dir experiments/run-001 --model-dir models
There is no simulated engine or synthetic-score CLI option.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import re
import secrets
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from app.domain import Gallery, MAX_FRAME_BYTES, Rejected, cosine
from app.engine import FaceEngine
from app.settings import Settings

TOKEN = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
FIELDS = ["sample_id", "split", "truth_id", "candidate_id", "score", "runner_up_score",
          "model_version", "status", "rejection_code"]
SAMPLE_REJECTIONS = {"INVALID_IMAGE", "INVALID_IMAGE_SIZE", "NO_FACE", "MULTIPLE_FACES", "LOW_QUALITY"}


@dataclass(frozen=True)
class Capture:
    path: Path
    split: str
    subject_id: str
    employee_id: int | None
    capture_session: str
    sample_id: str
    pixel_digest: str


@dataclass(frozen=True)
class Dataset:
    dataset_id: str
    manifest_digest: str
    captures: tuple[Capture, ...]


def pixel_fingerprint(content: bytes) -> str:
    """Detect identical decoded pixels even when JPEG metadata differs; not near duplicates."""
    from PIL import Image
    try:
        with Image.open(io.BytesIO(content)) as image:
            width, height = image.size
            if image.format != "JPEG" or not 160 <= width <= 1920 or not 120 <= height <= 1080:
                raise ValueError("样本必须为160×120至1920×1080的JPEG图像")
            # Match FaceEngine's ignore-orientation policy. Camera captures normally have no EXIF.
            rgb = image.convert("RGB")
            digest = hashlib.sha256(f"{width}x{height}:RGB:".encode())
            digest.update(rgb.tobytes())
            return digest.hexdigest()
    except (OSError, Image.DecompressionBombError) as error:
        raise ValueError("样本不是完整的JPEG图像") from error


def load_dataset(manifest: Path, fingerprint=pixel_fingerprint) -> Dataset:
    manifest = manifest.resolve()
    if manifest.stat().st_size > 4 * 1024 * 1024:
        raise ValueError("样本清单不能超过4MiB")
    with manifest.open("rb") as stream:
        source = stream.read(4 * 1024 * 1024 + 1)
    if len(source) > 4 * 1024 * 1024:
        raise ValueError("样本清单不能超过4MiB")
    data = json.loads(source.decode("utf-8-sig"))
    if not isinstance(data, dict) or data.get("schemaVersion") != 1 or data.get("consentConfirmed") is not True:
        raise ValueError("清单须为schemaVersion=1，并明确consentConfirmed=true")
    if not isinstance(data.get("datasetId"), str) or not TOKEN.fullmatch(data["datasetId"]):
        raise ValueError("datasetId须为1至64位字母数字/_/-，请勿使用真实姓名")
    samples = data.get("samples")
    if not isinstance(samples, list) or not 1 <= len(samples) <= 10000:
        raise ValueError("samples须包含1至10000个采集条目")
    root = manifest.parent
    captures, byte_seen, pixel_seen = [], set(), set()
    subject_ids, employee_ids, capture_sessions = {}, {}, {}
    for number, item in enumerate(samples, 1):
        if not isinstance(item, dict):
            raise ValueError(f"第{number}个采集条目格式错误")
        split = item.get("split")
        subject = item.get("subjectId")
        employee = item.get("employeeId")
        capture_session = item.get("captureSession")
        if split not in {"enrollment", "calibration", "test"}:
            raise ValueError(f"第{number}个条目的split无效")
        if not isinstance(subject, str) or not TOKEN.fullmatch(subject):
            raise ValueError(f"第{number}个条目subjectId须使用字母数字/_/-的参与者代号")
        if not isinstance(capture_session, str) or not TOKEN.fullmatch(capture_session):
            raise ValueError(f"第{number}个条目缺少合法captureSession采集批次代号")
        if employee is not None and (type(employee) is not int or not 1 <= employee < 2**63):
            raise ValueError("employeeId必须为正整数或null（未知人员）")
        if subject in subject_ids and subject_ids[subject] != employee:
            raise ValueError("同一subjectId不能切换员工编号或同时标成已知和未知人员")
        if employee is not None and employee in employee_ids and employee_ids[employee] != subject:
            raise ValueError("同一employeeId不能对应多个参与者代号")
        if capture_session in capture_sessions and capture_sessions[capture_session] != split:
            raise ValueError("同一采集批次不能跨enrollment/calibration/test，必须独立重新采集")
        subject_ids[subject], capture_sessions[capture_session] = employee, split
        if employee is not None:
            employee_ids[employee] = subject
        relative = item.get("path")
        if not isinstance(relative, str) or Path(relative).is_absolute():
            raise ValueError("path必须相对于样本清单所在目录")
        path = (root / relative).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            raise ValueError("样本文件缺失或路径超出清单目录")
        if not 0 < path.stat().st_size <= MAX_FRAME_BYTES:
            raise ValueError(f"第{number}个图像必须在1至200KiB以内，须与在线采集格式一致")
        with path.open("rb") as stream:
            content = stream.read(MAX_FRAME_BYTES + 1)
        if len(content) > MAX_FRAME_BYTES:
            raise ValueError("读取期间文件大小发生变化")
        digest = hashlib.sha256(content).hexdigest()
        pixel_digest = fingerprint(content)
        if digest in byte_seen or pixel_digest in pixel_seen:
            raise ValueError("发现重复图像或相同解码像素；包括改名、改元数据及跨组复用")
        byte_seen.add(digest)
        pixel_seen.add(pixel_digest)
        captures.append(Capture(path, split, subject, employee, capture_session, digest, pixel_digest))
    enrolled = {}
    for capture in captures:
        if capture.split == "enrollment":
            if capture.employee_id is None:
                raise ValueError("未知人员不能出现在enrollment组")
            enrolled[capture.employee_id] = enrolled.get(capture.employee_id, 0) + 1
    if not 1 <= len(enrolled) <= 100 or any(count != 3 for count in enrolled.values()):
        raise ValueError("须录入1至100人，且每人恰好3张独立图像")
    for split in ("calibration", "test"):
        selected = [capture for capture in captures if capture.split == split]
        known = {capture.employee_id for capture in selected if capture.employee_id is not None}
        if known != set(enrolled) or not any(capture.employee_id is None for capture in selected):
            raise ValueError(f"{split}必须覆盖全部已录入人员，并包含未录入人员")
    return Dataset(data["datasetId"], hashlib.sha256(source).hexdigest(), tuple(captures))


def read_capture(capture: Capture) -> bytes:
    with capture.path.open("rb") as source:
        content = source.read(MAX_FRAME_BYTES + 1)
    if hashlib.sha256(content).hexdigest() != capture.sample_id:
        raise ValueError("采集文件在预检查后发生变化，请停止修改并重新生成实验")
    return content


def score_dataset(dataset: Dataset, engine) -> tuple[list[dict], dict]:
    if not engine.ready:
        raise ValueError("模型未就绪；不能生成识别分数")
    templates, enrollment_ids = {}, []
    for capture in dataset.captures:
        if capture.split != "enrollment":
            continue
        try:
            observation = engine.observe(read_capture(capture), False)
        except Rejected as error:
            raise ValueError(f"录入样本{capture.sample_id[:12]}失败：{error.code}，请重新采集；未生成评分结果") from error
        templates.setdefault(capture.employee_id, []).append(observation.embedding)
        enrollment_ids.append(capture.sample_id)
    for vectors in templates.values():
        if any(cosine(vectors[i], vectors[j]) < engine.settings.enrollment_threshold
               for i in range(3) for j in range(i + 1, 3)):
            raise ValueError("录入图像未通过与在线服务相同的同人一致性检查")
    gallery = Gallery.build(1, [{"employeeId": identity, "templates": templates[identity]} for identity in sorted(templates)])
    for identity, vectors in gallery.employees:
        for vector in vectors:
            duplicate = gallery.search(vector, exclude=identity)
            if duplicate and duplicate.score >= engine.settings.duplicate_threshold:
                raise ValueError("录入图像疑似重复绑定，须核查样本标签；不会静默修改人员库")
    gallery_hash = hashlib.sha256(json.dumps(gallery.employees, separators=(",", ":")).encode()).hexdigest()
    rows = []
    for capture in dataset.captures:
        if capture.split == "enrollment":
            continue
        row = {"sample_id": capture.sample_id, "split": capture.split,
               "truth_id": "UNKNOWN" if capture.employee_id is None else str(capture.employee_id),
               "candidate_id": "", "score": "", "runner_up_score": "", "model_version": engine.model_version,
               "status": "REJECTED", "rejection_code": ""}
        try:
            observation = engine.observe(read_capture(capture), False)
            match = gallery.search(observation.embedding)
            row.update(status="SCORED", candidate_id=str(match.employee_id), score=match.score,
                       runner_up_score="" if match.candidate_count == 1 else max(-1.0, match.score - match.margin))
        except Rejected as error:
            if error.code not in SAMPLE_REJECTIONS:
                raise ValueError(f"算法运行故障{error.code}，本批次中止，不把运行故障算作正常拒绝") from error
            row["rejection_code"] = error.code
        rows.append(row)
    report = {"schemaVersion": 1, "createdAt": datetime.now(timezone.utc).isoformat(),
              "datasetId": dataset.dataset_id, "manifestSha256": dataset.manifest_digest,
              "modelVersion": engine.model_version, "gallerySha256": gallery_hash,
              "enrollmentSampleIds": sorted(enrollment_ids), "enrolledPeople": len(templates),
              "enrollmentThreshold": engine.settings.enrollment_threshold,
              "duplicateThreshold": engine.settings.duplicate_threshold,
              "counts": {split: {"attempts": sum(row["split"] == split for row in rows),
                                 "qualityRejected": sum(row["split"] == split and row["status"] == "REJECTED" for row in rows),
                                 "knownPeople": len(templates),
                                 "unknownPeople": len({capture.subject_id for capture in dataset.captures
                                                       if capture.split == split and capture.employee_id is None})}
                         for split in ("calibration", "test")},
              "scope": "Real image quality and single-frame retrieval against one frozen enrollment gallery; no liveness verdict.",
              "limitations": ["Hashes detect identical files/pixels, not recaptured or recompressed near-duplicates.",
                              "Capture-session separation and truth labels rely on honest dataset curation.",
                              "This file does not confirm calibration or modify business settings."]}
    return rows, report


def write_results(output: Path, rows: list[dict], report: dict) -> None:
    # A new directory prevents mixing results with a previous experiment or overwriting evidence.
    output.mkdir(parents=True, exist_ok=False)
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=FIELDS)
    writer.writeheader()
    writer.writerows(rows)
    csv_bytes = stream.getvalue().encode("utf-8")
    (output / "scores.csv").write_bytes(csv_bytes)
    report = {**report, "scoresSha256": hashlib.sha256(csv_bytes).hexdigest()}
    (output / "run.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--model-dir", type=Path, default=Path("models"))
    args = parser.parse_args()
    engine = None
    try:
        if args.output_dir.exists():
            raise ValueError("输出目录已存在，请为本次实验选择新的目录")
        dataset = load_dataset(args.manifest)
        configured = Settings.from_environment()
        # Local offline inference has no listening endpoint. A transient key meets the shared
        # engine's configuration validation and is never printed, persisted or sent anywhere.
        settings = Settings(secrets.token_urlsafe(32), args.model_dir.resolve(), configured.insightface_model,
                            configured.enrollment_threshold, configured.duplicate_threshold)
        engine = FaceEngine(settings)
        engine.load()
        if not engine.ready:
            raise ValueError(engine.detail)
        rows, report = score_dataset(dataset, engine)
        write_results(args.output_dir, rows, report)
        print(f"已生成真实评分：{args.output_dir / 'scores.csv'}；共{len(rows)}个评估样本，拒绝样本保留")
        return 0
    except (ValueError, KeyError, OSError) as error:
        print(f"实验未完成：{error}", file=sys.stderr)
        return 2
    finally:
        if engine is not None:
            engine.close()


if __name__ == "__main__":
    raise SystemExit(main())
