"""Recognition policy and bounded challenge state, independent of model runtimes."""
from __future__ import annotations

import math
import threading
import time
from dataclasses import dataclass, field
from typing import Iterable

MAX_FRAME_BYTES = 200 * 1024
MAX_FRAMES = 80
MAX_SESSION_BYTES = 12 * 1024 * 1024
MAX_CAPTURE_SECONDS = 35.0
# Capture has its own budget, separate from the longer session creation lifetime.
# Browser capture limits must remain consistent with this value.
MAX_SESSION_SECONDS = 60.0
EMBEDDING_SIZE = 512


class Rejected(Exception):
    def __init__(self, code: str, message: str, status: int = 422):
        self.code, self.message, self.status = code, message, status
        super().__init__(message)


def normalize(vector: Iterable[float], size: int = EMBEDDING_SIZE) -> tuple[float, ...]:
    values = tuple(float(v) for v in vector)
    if len(values) != size or not all(math.isfinite(v) for v in values):
        raise Rejected("INVALID_TEMPLATE", "人脸特征维度或数值无效")
    norm = math.sqrt(sum(v * v for v in values))
    if not math.isfinite(norm) or norm < 1e-9:
        raise Rejected("INVALID_TEMPLATE", "人脸特征不能为零向量")
    return tuple(v / norm for v in values)


def cosine(a: tuple[float, ...], b: tuple[float, ...]) -> float:
    return max(-1.0, min(1.0, sum(x * y for x, y in zip(a, b, strict=True))))


@dataclass(frozen=True)
class Match:
    employee_id: int
    score: float
    margin: float
    candidate_count: int

    def accepted(self, threshold: float, margin: float) -> bool:
        return self.score >= threshold and (self.candidate_count == 1 or self.margin >= margin)


@dataclass(frozen=True)
class Gallery:
    version: int = -1
    employees: tuple[tuple[int, tuple[tuple[float, ...], ...]], ...] = ()
    matrix: object = field(default=None, compare=False, repr=False)

    @classmethod
    def build(cls, version: int, employees: list[dict]) -> Gallery:
        import numpy as np
        if version < 0 or len(employees) > 100:
            raise Rejected("INVALID_GALLERY", "人员库版本无效或超过100人")
        seen, rows = set(), []
        for item in employees:
            employee_id = item["employeeId"]
            if employee_id <= 0 or employee_id in seen or len(item["templates"]) != 3:
                raise Rejected("INVALID_GALLERY", "人员编号重复或每人模板数量不是3份")
            seen.add(employee_id)
            rows.append((employee_id, tuple(normalize(v) for v in item["templates"])))
        matrix = np.asarray([v for _, templates in rows for v in templates], dtype=np.float32)
        matrix.setflags(write=False)
        return cls(version, tuple(rows), matrix)

    def search(self, vector: tuple[float, ...], exclude: int | None = None) -> Match | None:
        import numpy as np
        if not self.employees:
            return None
        scores = (self.matrix @ np.asarray(vector, dtype=np.float32)).reshape((-1, 3)).max(axis=1)
        ranked = sorted(((min(1.0, max(-1.0, float(scores[i]))), employee_id)
                         for i, (employee_id, _) in enumerate(self.employees)
                         if employee_id != exclude), reverse=True)
        if not ranked:
            return None
        score, employee_id = ranked[0]
        # A finite sentinel is serializable JSON; margin is ignored for a singleton gallery.
        margin = score - ranked[1][0] if len(ranked) > 1 else 2.0
        return Match(employee_id, score, margin, len(ranked))


@dataclass(frozen=True)
class Observation:
    embedding: tuple[float, ...]
    eye_left: float = 0.0
    eye_right: float = 0.0
    mouth: float = 0.0

    @property
    def neutral(self) -> bool:
        return max(self.eye_left, self.eye_right) <= 0.20 and self.mouth <= 0.15


@dataclass
class ActionTracker:
    action: str
    phase: int = 0
    count: int = 0

    def update(self, obs: Observation) -> bool:
        if self.action == "BLINK":
            neutral = max(obs.eye_left, obs.eye_right) <= 0.20
            active = min(obs.eye_left, obs.eye_right) >= 0.55
            holds = (2, 1, 2)  # At 6 fps a real blink may occupy just one closed frame.
        else:
            neutral, active = obs.mouth <= 0.15, obs.mouth >= 0.35
            holds = (2, 2, 2)
        expected = active if self.phase == 1 else neutral
        self.count = self.count + 1 if expected else 0
        if self.count >= holds[self.phase]:
            self.phase += 1
            self.count = 0
        return self.phase == 3


@dataclass
class Session:
    session_id: str
    gallery_version: int
    config_version: int
    liveness_required: bool
    actions: tuple[str, ...]
    threshold: float
    margin: float
    deadline: float
    capture_start: float | None = None
    frame_count: int = 0
    byte_count: int = 0
    connected: bool = False
    invalidated: bool = False
    finished: bool = False
    identity: int | None = None
    baseline: tuple[float, ...] | None = None
    stable_frames: int = 0
    neutral_frames: int = 0
    final_frames: int = 0
    action_index: int = 0
    tracker: ActionTracker | None = None

    def check(self, gallery_version: int, now: float | None = None) -> None:
        now = time.monotonic() if now is None else now
        if self.invalidated or gallery_version != self.gallery_version:
            raise Rejected("STALE_SESSION", "人员库或设置已变化，请重新开始", 409)
        if self.finished:
            raise Rejected("SESSION_USED", "会话已结束，请重新开始", 409)
        if now >= self.deadline or (self.capture_start is not None and now - self.capture_start >= MAX_CAPTURE_SECONDS):
            raise Rejected("TIMEOUT", "验证超时，请重新开始", 408)

    def accept_frame(self, length: int, gallery_version: int, now: float | None = None) -> None:
        now = time.monotonic() if now is None else now
        self.check(gallery_version, now)
        if length <= 0 or length > MAX_FRAME_BYTES:
            raise Rejected("FRAME_TOO_LARGE", "单帧须为1至200KB的JPEG图像", 413)
        if self.frame_count >= MAX_FRAMES or self.byte_count + length > MAX_SESSION_BYTES:
            raise Rejected("CAPTURE_LIMIT", "采集超过帧数或累计体积限制", 413)
        if self.capture_start is None:
            self.capture_start = now
        self.frame_count += 1
        self.byte_count += length

    def process(self, obs: Observation, gallery: Gallery, model_version: str, now: float | None = None) -> dict:
        self.check(gallery.version, now)
        match = gallery.search(obs.embedding)
        if match is None or not match.accepted(self.threshold, self.margin):
            if self.identity is not None:
                raise Rejected("IDENTITY_UNSTABLE", "身份分数或候选分差未达标，请保持正脸、改善光线后重试")
            self.stable_frames = self.neutral_frames = 0
            return {"type": "PROMPT", "message": "未识别到已录入人员，请正对镜头"}
        if self.identity is None:
            self.identity, self.baseline = match.employee_id, obs.embedding
        if match.employee_id != self.identity or cosine(self.baseline, obs.embedding) < self.threshold:
            raise Rejected("IDENTITY_UNSTABLE", "身份候选变化或与首帧不一致，请保持同一人正对镜头重试")
        self.stable_frames += 1
        if self.stable_frames < 3:
            return {"type": "PROMPT", "message": "请保持正对镜头"}
        if not self.liveness_required:
            return self.result(match, model_version, "SKIPPED")
        if self.tracker is None and self.action_index == 0:
            self.neutral_frames = self.neutral_frames + 1 if obs.neutral else 0
            if self.neutral_frames < 2:
                return {"type": "PROMPT", "message": "请自然睁眼、闭嘴，建立基准状态"}
            self.tracker = ActionTracker(self.actions[0])
            return self.prompt()
        if self.action_index < len(self.actions):
            if self.tracker.update(obs):
                self.action_index += 1
                self.tracker = ActionTracker(self.actions[self.action_index]) if self.action_index < len(self.actions) else None
            return self.prompt()
        # Identity is checked on every frame, including three final neutral frames.
        self.final_frames = self.final_frames + 1 if obs.neutral else 0
        if self.final_frames < 3:
            return {"type": "PROMPT", "message": "动作已完成，请自然睁眼闭嘴并保持正对镜头"}
        return self.result(match, model_version, "PASSED")

    def prompt(self) -> dict:
        if self.action_index >= len(self.actions):
            return {"type": "PROMPT", "message": "动作已完成，请保持正对镜头"}
        action = self.actions[self.action_index]
        phase = self.tracker.phase if self.tracker is not None else 0
        messages = {
            "BLINK": ("请先自然睁眼，等待闭眼提示", "请缓慢闭合双眼并保持片刻", "请睁开双眼，保持正对镜头"),
            "MOUTH": ("请先自然闭嘴，等待张嘴提示", "请张嘴并保持片刻", "请闭嘴，保持正对镜头"),
        }
        return {"type": "PROMPT", "action": action,
                "message": messages[action][phase]}

    def result(self, match: Match, model_version: str, liveness: str) -> dict:
        self.finished = True
        return {"type": "RESULT", "message": "识别通过，等待业务服务确认", "employeeId": match.employee_id,
                "score": match.score, "margin": match.margin, "livenessStatus": liveness,
                "modelVersion": model_version, "galleryVersion": self.gallery_version,
                "configVersion": self.config_version}


class Registry:
    def __init__(self):
        self.lock = threading.RLock()
        self.gallery = Gallery()
        self.sessions: dict[str, Session] = {}
        self.used_ids: dict[str, float] = {}
        self.config_version = -1

    def replace(self, gallery: Gallery) -> None:
        with self.lock:
            if gallery.version < self.gallery.version:
                raise Rejected("STALE_GALLERY", "人员库版本不能倒退", 409)
            if gallery.version == self.gallery.version:
                if gallery.employees != self.gallery.employees:
                    raise Rejected("VERSION_CONFLICT", "相同版本不能包含不同人员数据", 409)
                return
            self.gallery = gallery
            for session in self.sessions.values():
                session.invalidated = True
            self.sessions.clear()

    def create(self, session: Session) -> None:
        with self.lock:
            now = time.monotonic()
            if session.gallery_version != self.gallery.version or self.gallery.version < 0:
                raise Rejected("STALE_GALLERY", "人员库未同步或版本不一致", 409)
            if session.config_version < self.config_version:
                raise Rejected("STALE_CONFIG", "算法配置版本已失效", 409)
            if session.config_version > self.config_version:
                self.config_version = session.config_version
                for old in self.sessions.values():
                    old.invalidated = True
                self.sessions.clear()
            self.sessions = {key: value for key, value in self.sessions.items()
                             if not value.finished and not value.invalidated and value.deadline > now
                             and (value.capture_start is None or now - value.capture_start < MAX_CAPTURE_SECONDS)}
            self.used_ids = {key: expiry for key, expiry in self.used_ids.items() if expiry > now}
            if session.session_id in self.used_ids:
                raise Rejected("SESSION_EXISTS", "会话编号已使用，请生成新编号", 409)
            if len(self.used_ids) >= 1024:
                raise Rejected("BUSY", "会话创建频率过高，请稍后重试", 429)
            if len(self.sessions) >= 2:
                raise Rejected("BUSY", "算法服务繁忙，请稍后重试", 429)
            self.sessions[session.session_id] = session
            self.used_ids[session.session_id] = session.deadline

    def remove(self, session_id: str) -> None:
        with self.lock:
            session = self.sessions.pop(session_id, None)
            if session is not None:
                session.invalidated = True
