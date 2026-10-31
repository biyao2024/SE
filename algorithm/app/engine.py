"""Only the real InsightFace/MediaPipe path is available in production."""
from __future__ import annotations

import hashlib
import io
import json
import math
import threading
from importlib.metadata import version
from pathlib import Path

from .domain import MAX_FRAME_BYTES, Observation, Rejected, normalize
from .settings import Settings


def verify_manifest(root: Path, model_name: str) -> str:
    manifest_path = root / "manifest.json"
    if not manifest_path.is_file():
        raise RuntimeError("缺少模型清单 models/manifest.json，请按算法 Readme 准备模型")
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    entries = data.get("sha256", {})
    required = {"face_landmarker.task",
                f"insightface/models/{model_name}/det_10g.onnx",
                f"insightface/models/{model_name}/w600k_r50.onnx",
                f"insightface/models/{model_name}/1k3d68.onnx"}
    required.update(p.relative_to(root).as_posix() for p in (root / "insightface" / "models" / model_name).glob("*.onnx"))
    if data.get("modelName") != model_name or not required.issubset(entries):
        raise RuntimeError("模型清单缺少所需文件或模型名称不符")
    for relative in sorted(required):
        path = (root / relative).resolve()
        if not path.is_relative_to(root.resolve()) or not path.is_file():
            raise RuntimeError("模型文件缺失或路径无效")
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != entries[relative]:
            raise RuntimeError("模型 SHA-256 校验失败，请重新确认下载来源并生成清单")
    fingerprint = hashlib.sha256(json.dumps({key: entries[key] for key in sorted(required)}, sort_keys=True).encode()).hexdigest()[:16]
    return f"{model_name}-{fingerprint}-policy2"


def validate_runtime_models(face) -> dict[str, list[str]]:
    required = {"detection", "recognition", "landmark_3d_68"}
    if not required.issubset(face.models):
        raise RuntimeError("模型包未加载完整的检测、识别及3D姿态模块")
    providers = {name: face.models[name].session.get_providers() for name in sorted(required)}
    if any(value != ["CPUExecutionProvider"] for value in providers.values()):
        raise RuntimeError("当前适配器要求所有InsightFace模块仅使用CPUExecutionProvider")
    return providers


def validate_pose(pose) -> None:
    if pose is None or len(pose) != 3 or not all(math.isfinite(float(v)) for v in pose):
        raise Rejected("LOW_QUALITY", "无法得到有效人脸姿态，请重新采集")
    if any(abs(float(value)) > limit for value, limit in zip(pose, (30, 35, 25), strict=True)):
        raise Rejected("LOW_QUALITY", "人脸角度过大，请正对镜头或轻微转头")


def validate_blendshapes(blend: dict[str, float]) -> tuple[float, float, float]:
    names = ("eyeBlinkLeft", "eyeBlinkRight", "jawOpen")
    if not set(names).issubset(blend):
        raise Rejected("MODEL_UNAVAILABLE", "动作模型输出不完整", 503)
    values = tuple(blend[name] for name in names)
    if not all(math.isfinite(value) and 0 <= value <= 1 for value in values):
        raise Rejected("MODEL_UNAVAILABLE", "动作模型输出数值无效", 503)
    return values


class FaceEngine:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.ready = False
        self.detail = "模型尚未加载"
        self.model_version = "unavailable"
        self.lock = threading.Lock()
        self.face = None
        self.landmarker = None
        self.runtime_providers = {}

    def load(self) -> None:
        with self.lock:
            if self.ready:
                return
            try:
                if len(self.settings.internal_api_key) < 32:
                    raise RuntimeError("INTERNAL_API_KEY 必须至少32字符")
                fingerprint = verify_manifest(self.settings.model_dir, self.settings.insightface_model)
                import cv2
                import mediapipe as mp
                import numpy as np
                from PIL import Image
                from .cpu_runtime import make_cpu_face_analysis
                self.cv2, self.mp, self.np, self.Image = cv2, mp, np, Image
                # A complete verified local directory prevents InsightFace's download fallback.
                self.face = make_cpu_face_analysis(
                    self.settings.model_dir / "insightface" / "models" / self.settings.insightface_model,
                    self.settings.inference_threads)
                self.face.prepare(ctx_id=-1, det_size=(640, 640), det_thresh=0.6)
                self.runtime_providers = validate_runtime_models(self.face)
                options = mp.tasks.vision.FaceLandmarkerOptions(
                    # MediaPipe's native file loader may reject Windows Unicode paths.
                    # Python reads the already verified local file as bytes instead.
                    base_options=mp.tasks.BaseOptions(model_asset_buffer=(self.settings.model_dir / "face_landmarker.task").read_bytes(),
                                                      delegate=mp.tasks.BaseOptions.Delegate.CPU),
                    running_mode=mp.tasks.vision.RunningMode.IMAGE,
                    num_faces=2, output_face_blendshapes=True,
                    min_face_detection_confidence=0.6, min_face_presence_confidence=0.6)
                # IMAGE mode intentionally avoids shared video tracking across simultaneous sessions.
                self.landmarker = mp.tasks.vision.FaceLandmarker.create_from_options(options)
                self.model_version = f"{fingerprint}-if{version('insightface')}-mp{version('mediapipe')}"
                self.ready, self.detail = True, "CPU模型已加载，等待人员库同步"
            except Exception as error:
                self.ready = False
                if isinstance(error, (RuntimeError, ModuleNotFoundError)):
                    self.detail = str(error)[:240]
                else:
                    self.detail = f"模型初始化失败 ({type(error).__name__})，请核对模型、依赖和运行环境"

    def observe(self, jpeg: bytes, require_landmarks: bool = True) -> Observation:
        with self.lock:
            if not self.ready:
                raise Rejected("MODEL_UNAVAILABLE", "算法服务不可用，请联系管理员", 503)
            if not jpeg or len(jpeg) > MAX_FRAME_BYTES or not jpeg.startswith(b"\xff\xd8"):
                raise Rejected("INVALID_IMAGE", "仅支持不超过200KB的JPEG图像")
            try:
                # Read header and reject oversized dimensions before allocating the decoded image.
                with self.Image.open(io.BytesIO(jpeg)) as header:
                    width, height = header.size
                    if header.format != "JPEG" or width < 160 or height < 120 or width > 1920 or height > 1080 or width * height > 1920 * 1080:
                        raise Rejected("INVALID_IMAGE_SIZE", "图像尺寸须在160×120至1920×1080之间")
                    header.verify()
                # Browser frames have no orientation tag; ignore EXIF consistently with header
                # dimensions so uploaded diagnostic images cannot silently rotate after validation.
                frame = self.cv2.imdecode(self.np.frombuffer(jpeg, dtype=self.np.uint8),
                                          self.cv2.IMREAD_COLOR | self.cv2.IMREAD_IGNORE_ORIENTATION)
            except Rejected:
                raise
            except Exception:
                raise Rejected("INVALID_IMAGE", "JPEG图像损坏或无法解码") from None
            if frame is None or frame.shape[:2] != (height, width):
                raise Rejected("INVALID_IMAGE", "JPEG图像无法解码")
            faces = self.face.get(frame)
            if not faces:
                raise Rejected("NO_FACE", "未检测到人脸")
            if len(faces) != 1:
                raise Rejected("MULTIPLE_FACES", "多人入镜，请保持单人画面")
            face = faces[0]
            x1, y1, x2, y2 = [int(value) for value in face.bbox]
            if min(x2 - x1, y2 - y1) < 96 or x1 < 0 or y1 < 0 or x2 > width or y2 > height:
                raise Rejected("LOW_QUALITY", "人脸过小或未完整入镜，请调整距离")
            roi = self.cv2.cvtColor(frame[y1:y2, x1:x2], self.cv2.COLOR_BGR2GRAY)
            brightness = float(roi.mean())
            sharpness = float(self.cv2.Laplacian(roi, self.cv2.CV_64F).var())
            if brightness < 35 or brightness > 220 or sharpness < 60:
                raise Rejected("LOW_QUALITY", "图像模糊或光线不足，请调整环境")
            validate_pose(face.get("pose"))
            embedding = normalize(face.normed_embedding)
            if not require_landmarks:
                return Observation(embedding)
            rgb = self.cv2.cvtColor(frame, self.cv2.COLOR_BGR2RGB)
            result = self.landmarker.detect(self.mp.Image(image_format=self.mp.ImageFormat.SRGB, data=rgb))
            if len(result.face_landmarks) > 1:
                raise Rejected("MULTIPLE_FACES", "多人入镜，请保持单人画面")
            if len(result.face_landmarks) != 1 or len(result.face_blendshapes) != 1:
                raise Rejected("LOW_QUALITY", "面部动作识别不稳定，请正对镜头")
            blend = {item.category_name: float(item.score) for item in result.face_blendshapes[0]}
            return Observation(embedding, *validate_blendshapes(blend))

    def close(self) -> None:
        with self.lock:
            if self.landmarker is not None:
                self.landmarker.close()
                self.landmarker = None
            self.face = None
            self.ready = False
