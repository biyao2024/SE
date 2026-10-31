"""Opt-in offline camera capture for research only; stores consenting experiment images.

python -m scripts.capture_samples --manifest experiments/pilot/dataset.json
  --subject-id p01 --employee-id 1 --split enrollment --capture-session enroll-day1
  --consent-confirmed
Space captures one frame; Q cancels. This command never marks attendance or calibration.
"""
import argparse
import json
import os
import re
from pathlib import Path
import sys
import time
import uuid

from app.domain import MAX_FRAME_BYTES
from scripts.score_samples import TOKEN


def append_capture(manifest: Path, subject: str, employee: int | None, split: str,
                   capture_session: str, jpeg: bytes) -> Path:
    data = json.loads(manifest.read_text(encoding="utf-8")) if manifest.exists() else {
        "schemaVersion": 1, "datasetId": "pilot-" + uuid.uuid4().hex[:12],
        "consentConfirmed": True, "samples": []}
    if data.get("schemaVersion") != 1 or data.get("consentConfirmed") is not True:
        raise ValueError("已有清单须先确认采集授权和schemaVersion=1")
    for item in data["samples"]:
        if item["subjectId"] == subject and item["employeeId"] != employee:
            raise ValueError("同一参与者不能变更员工编号或已知/未知身份")
        if employee is not None and item["employeeId"] == employee and item["subjectId"] != subject:
            raise ValueError("同一员工编号不能用于不同参与者")
        if item["captureSession"] == capture_session and item["split"] != split:
            raise ValueError("同一采集批次不能跨录入、校准、测试组")
    if split == "enrollment" and (employee is None or sum(item["split"] == split and item["employeeId"] == employee for item in data["samples"]) >= 3):
        raise ValueError("已知人员录入只能有3张；未知人员不能加入人员库")
    if not 0 < len(jpeg) <= MAX_FRAME_BYTES or not jpeg.startswith(b"\xff\xd8"):
        raise ValueError("采集帧须为不超过200KiB的JPEG")
    image_path = manifest.parent / "images" / (uuid.uuid4().hex + ".jpg")
    image_path.parent.mkdir(parents=True, exist_ok=True)
    with image_path.open("xb") as output:
        output.write(jpeg)
    data["samples"].append({"path": image_path.relative_to(manifest.parent).as_posix(), "split": split,
                            "subjectId": subject, "employeeId": employee, "captureSession": capture_session})
    pending = manifest.with_name(manifest.name + "." + uuid.uuid4().hex + ".tmp")
    with pending.open("x", encoding="utf-8") as output:
        json.dump(data, output, ensure_ascii=False, indent=2)
        output.write("\n")
    os.replace(pending, manifest)
    return image_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--subject-id", required=True, help="使用参与者代号，不要填写真实姓名")
    identities = parser.add_mutually_exclusive_group(required=True)
    identities.add_argument("--employee-id", type=int)
    identities.add_argument("--unknown", action="store_true")
    parser.add_argument("--split", choices=["enrollment", "calibration", "test"], required=True)
    parser.add_argument("--capture-session", required=True)
    parser.add_argument("--count", type=int)
    parser.add_argument("--camera-index", type=int, default=0)
    parser.add_argument("--consent-confirmed", action="store_true", required=True,
                        help="确认参与者同意为实验暂存原图，并已约定删除期限")
    args = parser.parse_args()
    count = args.count if args.count is not None else (3 if args.split == "enrollment" else 10)
    if not TOKEN.fullmatch(args.subject_id) or not TOKEN.fullmatch(args.capture_session):
        parser.error("参与者/采集批次代号须为1至64位字母数字/_/-")
    if not 1 <= count <= 100 or (args.split == "enrollment" and (count != 3 or args.unknown)):
        parser.error("每次采集1至100张；录入组仅允许已知人员且每人3张")
    if args.employee_id is not None and not 1 <= args.employee_id < 2**63:
        parser.error("employee-id须为Java员工档案内部正整数ID，不是工号字符串")
    manifest = args.manifest.resolve()
    manifest.parent.mkdir(parents=True, exist_ok=True)
    lock_path = manifest.with_suffix(".capture.lock")
    lock_owned = False
    camera = None
    cv2 = None
    enrolled_before = 0
    try:
        with lock_path.open("x", encoding="ascii") as lock:
            lock.write(str(os.getpid()))
        lock_owned = True
        if manifest.exists():
            existing = json.loads(manifest.read_text(encoding="utf-8"))
            if existing.get("schemaVersion") != 1 or existing.get("consentConfirmed") is not True:
                raise ValueError("已有清单须先确认采集授权和schemaVersion=1")
            if args.split == "enrollment":
                enrolled_before = sum(item["split"] == "enrollment" and item["employeeId"] == args.employee_id
                                      for item in existing["samples"])
                if enrolled_before >= 3:
                    raise ValueError("此员工已有3张录入样本；重新录入请使用新的实验数据集")
                count = 3 - enrolled_before
                if args.count is not None and args.count != count:
                    raise ValueError(f"此员工还需{count}张录入样本，请不指定--count以恢复采集")
        import cv2
        if re.search(r"GUI:\s+NONE", cv2.getBuildInformation()):
            raise ValueError("当前cv2为无GUI构建，不能打开采集窗口；请修复OpenCV安装或使用已有独立JPEG清单")
        camera = cv2.VideoCapture(args.camera_index)
        if not camera.isOpened():
            raise ValueError("无法打开本机摄像头，请检查权限、占用状态及camera-index")
        camera.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        camera.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        print("此命令会保存实验人脸原图。请勿提交到Git/群聊；实验后按约定删除。")
        print("SPACE拍一张，Q退出。录入依次为正脸、轻微左转、轻微右转；其他组请改变距离/光线并独立采集。")
        taken, previous = 0, 0.0
        while taken < count:
            success, frame = camera.read()
            if not success:
                raise ValueError("摄像头读取失败")
            height, width = frame.shape[:2]
            scale = min(1.0, 640 / width, 480 / height)
            if scale < 1:
                frame = cv2.resize(frame, (int(width * scale), int(height * scale)), interpolation=cv2.INTER_AREA)
            preview = frame.copy()
            prompt = f"{args.split}: {taken}/{count} | SPACE capture | Q quit"
            if args.split == "enrollment":
                prompt += " | " + ("FRONT", "SLIGHT LEFT", "SLIGHT RIGHT")[enrolled_before + taken]
            cv2.putText(preview, prompt, (8, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)
            cv2.imshow("Consented offline research capture", preview)
            key = cv2.waitKey(30) & 0xFF
            if key in (ord("q"), 27) or cv2.getWindowProperty("Consented offline research capture", cv2.WND_PROP_VISIBLE) < 1:
                print(f"采集已取消，已保存{taken}张；清单保留当前进度。")
                return 2
            if key != 32 or time.monotonic() - previous < 1:
                continue
            jpeg = None
            for quality in (85, 75, 65, 55, 45):
                encoded, buffer = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, quality])
                if encoded and buffer.size <= MAX_FRAME_BYTES:
                    jpeg = buffer.tobytes()
                    break
            if jpeg is None:
                print("当前帧无法压缩至200KiB，请调整画面重试")
                continue
            append_capture(manifest, args.subject_id, args.employee_id, args.split, args.capture_session, jpeg)
            taken += 1
            previous = time.monotonic()
        print(f"已保存{taken}张至{manifest.parent}；请继续完成其他分组，再运行score_samples。")
        return 0
    except Exception as error:
        print(f"采集未完成：{error}", file=sys.stderr)
        return 2
    finally:
        if camera is not None:
            camera.release()
        if camera is not None and cv2 is not None:
            try:
                cv2.destroyAllWindows()
            except cv2.error:
                pass
        if lock_owned:
            lock_path.unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
