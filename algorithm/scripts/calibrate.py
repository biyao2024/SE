"""Fit retrieval thresholds on calibration data and report untouched test results.

CSV columns: sample_id,split,truth_id,candidate_id,score,runner_up_score,model_version
split is calibration or test; truth_id UNKNOWN denotes a person outside the gallery.
An empty runner_up_score denotes a one-person gallery. sample_id should be the source
capture's SHA-256; never include images or feature templates in this CSV.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


@dataclass(frozen=True)
class Sample:
    sample_id: str
    split: str
    truth: str
    candidate: str
    score: float
    margin: float
    singleton: bool
    model_version: str
    eligible: bool = True
    rejection_code: str = ""


def read_samples(path: Path) -> list[Sample]:
    samples, seen = [], set()
    with path.open(newline="", encoding="utf-8-sig") as source:
        for number, row in enumerate(csv.DictReader(source), 2):
            identity = row["sample_id"].strip()
            if not identity or identity in seen:
                raise ValueError(f"第{number}行 sample_id 为空或重复；校准与测试不得复用同一采集")
            seen.add(identity)
            if row["split"] not in {"calibration", "test"}:
                raise ValueError(f"第{number}行 split 无效")
            status = row.get("status", "SCORED")
            if status not in {"SCORED", "REJECTED"}:
                raise ValueError(f"第{number}行 status 无效")
            eligible = status == "SCORED"
            if eligible:
                score = float(row["score"])
                singleton = not row["runner_up_score"].strip()
                runner = -1.0 if singleton else float(row["runner_up_score"])
                if not math.isfinite(score) or not math.isfinite(runner) or not -1 <= score <= 1 or not -1 <= runner <= score:
                    raise ValueError(f"第{number}行相似度无效")
                if not row["candidate_id"]:
                    raise ValueError(f"第{number}行缺少检索候选")
            else:
                if any(row[field].strip() for field in ("score", "runner_up_score", "candidate_id")) or not row.get("rejection_code"):
                    raise ValueError(f"第{number}行拒绝样本不得填写虚构分数，且必须有拒绝原因")
                score, runner, singleton = -1.0, -1.0, False
            if not row["truth_id"] or not row["model_version"]:
                raise ValueError(f"第{number}行身份标签或模型版本缺失")
            samples.append(Sample(identity, row["split"], row["truth_id"], row["candidate_id"], score,
                                  2.0 if singleton else score - runner, singleton, row["model_version"],
                                  eligible, row.get("rejection_code", "")))
    if len({sample.model_version for sample in samples}) != 1:
        raise ValueError("一份校准数据必须来自同一模型版本")
    for split in ("calibration", "test"):
        selected = [sample for sample in samples if sample.split == split]
        if not any(s.truth == "UNKNOWN" for s in selected) or not any(s.truth != "UNKNOWN" for s in selected):
            raise ValueError(f"{split} 必须各自包含已录入及未录入人员样本")
    return samples


def wilson(successes: int, count: int) -> list[float]:
    if not count:
        return [0.0, 1.0]
    z = 1.95996398454
    p = successes / count
    denominator = 1 + z * z / count
    center = (p + z * z / (2 * count)) / denominator
    radius = z * math.sqrt(p * (1 - p) / count + z * z / (4 * count * count)) / denominator
    return [max(0.0, center - radius), min(1.0, center + radius)]


def metrics(samples: list[Sample], threshold: float, margin: float) -> dict:
    known = unknown = correct = wrong = unknown_accepted = known_rejected = 0
    for sample in samples:
        accepted = sample.eligible and sample.score >= threshold and (sample.singleton or sample.margin >= margin)
        if sample.truth == "UNKNOWN":
            unknown += 1
            unknown_accepted += int(accepted)
        else:
            known += 1
            correct += int(accepted and sample.candidate == sample.truth)
            wrong += int(accepted and sample.candidate != sample.truth)
            known_rejected += int(not accepted)
    return {"knownSamples": known, "unknownSamples": unknown,
            "qualityRejectedSamples": sum(not sample.eligible for sample in samples),
            "correctIdentificationRate": correct / known if known else None,
            "wrongIdentityAcceptanceRate": wrong / known if known else None,
            "unknownAcceptanceRate": unknown_accepted / unknown if unknown else None,
            "knownRejectionRate": known_rejected / known if known else None,
            "unknownAcceptance95PercentCI": wilson(unknown_accepted, unknown),
            "wrongIdentity95PercentCI": wilson(wrong, known)}


def calibrate(samples: list[Sample], target_unknown: float, target_wrong: float) -> dict:
    calibration = [sample for sample in samples if sample.split == "calibration"]
    test = [sample for sample in samples if sample.split == "test"]
    if not any(sample.eligible and sample.truth != "UNKNOWN" for sample in calibration):
        raise ValueError("校准组没有可检索的已知人员样本，请先解决采集质量问题")
    if not any(sample.eligible and sample.truth == "UNKNOWN" for sample in calibration):
        raise ValueError("校准组没有可检索的未知人员样本，不能仅凭质量拒绝选择识别阈值")
    feasible = []
    for threshold_step in range(20, 96):
        for margin_step in range(0, 31):
            threshold, margin = threshold_step / 100, margin_step / 100
            result = metrics(calibration, threshold, margin)
            if result["unknownAcceptanceRate"] <= target_unknown and result["wrongIdentityAcceptanceRate"] <= target_wrong:
                feasible.append((result["correctIdentificationRate"], threshold, margin, result))
    if not feasible:
        raise ValueError("预设搜索范围内没有满足错误接受率约束的参数；请改善样本/模型并重新独立校准")
    # Prefer more conservative parameters among equally accurate calibration choices.
    _, threshold, margin, result = max(feasible, key=lambda item: item[:3])
    if result["correctIdentificationRate"] <= 0:
        raise ValueError("满足错误接受约束的参数无法正确识别任何已知人员，不生成可用参数")
    return {"createdAt": datetime.now(timezone.utc).isoformat(), "modelVersion": samples[0].model_version,
            "threshold": threshold, "margin": margin,
            "targets": {"maxUnknownAcceptance": target_unknown, "maxWrongIdentityAcceptance": target_wrong},
            "calibration": result, "test": metrics(test, threshold, margin),
            "limitations": ["This measures single-frame retrieval including quality rejections, not multi-frame or liveness performance.",
                            "Repeated frames from the same participant are correlated; Wilson intervals assume independent trials and may be optimistic.",
                            "Targets constrain observed calibration rates, not the upper confidence bound.",
                            "Zero observed errors with a small sample does not establish a zero population error rate.",
                            "Enrollment consistency and duplicate thresholds require separate labeled-pair calibration."]}


def verify_run_report(path: Path, samples_path: Path, samples: list[Sample]) -> dict:
    report = json.loads(path.read_text(encoding="utf-8-sig"))
    if report.get("scoresSha256") != hashlib.sha256(samples_path.read_bytes()).hexdigest():
        raise ValueError("scores.csv 与评分运行报告的SHA-256不一致，可能被手动修改或混用了批次")
    if report.get("modelVersion") != samples[0].model_version:
        raise ValueError("评分运行报告模型版本与CSV不一致")
    if set(report.get("enrollmentSampleIds", [])) & {sample.sample_id for sample in samples}:
        raise ValueError("评分数据包含录入图像，不能用于独立校准/测试")
    if not report.get("gallerySha256") or not report.get("manifestSha256"):
        raise ValueError("评分运行报告缺少固定人员库或数据清单指纹")
    return {key: report[key] for key in ("datasetId", "manifestSha256", "gallerySha256", "scoresSha256")}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--run-report", type=Path, help="score_samples生成的run.json，用于验证分数及固定人员库出处")
    parser.add_argument("--max-unknown-acceptance", type=float, default=0.01)
    parser.add_argument("--max-wrong-identity", type=float, default=0.01)
    args = parser.parse_args()
    try:
        if not 0 <= args.max_unknown_acceptance <= 1 or not 0 <= args.max_wrong_identity <= 1:
            raise ValueError("错误接受率约束必须在0至1之间")
        samples = read_samples(args.samples)
        provenance = verify_run_report(args.run_report, args.samples, samples) if args.run_report else None
        report = calibrate(samples, args.max_unknown_acceptance, args.max_wrong_identity)
        report["sourceRun"] = provenance
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"已保存 {args.output}；threshold={report['threshold']:.2f}, margin={report['margin']:.2f}")
    except (ValueError, KeyError, OSError) as error:
        parser.exit(2, f"校准失败: {error}\n")
