from __future__ import annotations

import asyncio
import hmac
import json
import logging
import re
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Annotated, Literal

from fastapi import FastAPI, File, Form, Request, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .domain import (MAX_CAPTURE_SECONDS, MAX_FRAME_BYTES, MAX_SESSION_SECONDS,
                     Gallery, Registry, Rejected, Session, cosine)
from .engine import FaceEngine
from .settings import Settings

MAX_HTTP_BODY = 8 * 1024 * 1024
REQUEST_ID_PATTERN = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
http_log = logging.getLogger("uvicorn.error")


class InternalBoundary:
    """Authenticate before multipart parsing and bound requests including chunked bodies."""
    def __init__(self, app, api_key: str):
        self.app, self.api_key = app, api_key

    async def __call__(self, scope, receive, send):
        if scope["type"] not in {"http", "websocket"}:
            return await self.app(scope, receive, send)
        headers = dict(scope.get("headers", []))
        response_started = False
        if scope["type"] == "http":
            supplied_id = headers.get(b"x-request-id", b"").decode("ascii", errors="replace")
            request_id = supplied_id if REQUEST_ID_PATTERN.fullmatch(supplied_id) else str(uuid.uuid4())
            scope.setdefault("state", {})["request_id"] = request_id
            downstream_send = send

            async def correlated_send(message):
                nonlocal response_started
                if message["type"] == "http.response.start":
                    response_started = True
                    response_headers = [(name, value) for name, value in message.get("headers", [])
                                        if name.lower() != b"x-request-id"]
                    message = {**message, "headers": response_headers + [(b"x-request-id", request_id.encode("ascii"))]}
                    # JSON escaping prevents control characters in a path from forging log lines.
                    # Queries, bodies, vectors, images, credentials and other headers are omitted.
                    http_log.info(json.dumps({"method": scope.get("method", ""),
                                              "path": scope.get("path", "")[:512],
                                              "status": message["status"], "requestId": request_id},
                                             ensure_ascii=False, separators=(",", ":")))
                await downstream_send(message)

            send = correlated_send
        supplied = headers.get(b"x-internal-key", b"")
        if len(self.api_key) < 32 or not hmac.compare_digest(supplied, self.api_key.encode()):
            if scope["type"] == "websocket":
                await send({"type": "websocket.close", "code": 1008})
            else:
                await JSONResponse({"code": "UNAUTHORIZED", "message": "内部接口未授权"}, status_code=401)(scope, receive, send)
            return
        if scope["type"] == "websocket":
            return await self.app(scope, receive, send)
        # Keep enrollment multipart below Starlette's 1 MiB spool threshold: raw images
        # remain in memory even for a malformed oversized individual upload.
        body_limit = 3 * MAX_FRAME_BYTES + 64 * 1024 if scope.get("path") == "/internal/face/extract" else MAX_HTTP_BODY
        try:
            declared = int(headers.get(b"content-length", b"0"))
        except ValueError:
            declared = MAX_HTTP_BODY + 1
        if declared < 0 or declared > body_limit:
            await JSONResponse({"code": "BODY_TOO_LARGE", "message": "请求体超过允许大小"}, status_code=413)(scope, receive, send)
            return
        chunks, size = [], 0
        try:
            async with asyncio.timeout(15):
                while True:
                    message = await receive()
                    if message["type"] == "http.disconnect":
                        return
                    body = message.get("body", b"")
                    size += len(body)
                    if size > body_limit:
                        await JSONResponse({"code": "BODY_TOO_LARGE", "message": "请求体超过允许大小"}, status_code=413)(scope, receive, send)
                        return
                    chunks.append(body)
                    if not message.get("more_body", False):
                        break
        except TimeoutError:
            await JSONResponse({"code": "REQUEST_TIMEOUT", "message": "请求上传超时"}, status_code=408)(scope, receive, send)
            return
        delivered = False

        async def bounded_receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": b"".join(chunks), "more_body": False}
            return await receive()
        try:
            await self.app(scope, bounded_receive, send)
        except Exception:
            if response_started:
                raise
            # Keep unexpected inference failures inside the correlated boundary as well.
            await JSONResponse({"code": "INTERNAL_ERROR", "message": "算法处理失败，请联系管理员"},
                               status_code=500)(scope, bounded_receive, send)


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


Vector = Annotated[list[float], Field(min_length=512, max_length=512)]


class EmployeeTemplates(Input):
    employeeId: int = Field(gt=0)
    templates: list[Vector] = Field(min_length=3, max_length=3)


class GalleryInput(Input):
    version: int = Field(ge=0)
    employees: list[EmployeeTemplates] = Field(max_length=100)


class SessionInput(Input):
    sessionId: str = Field(min_length=16, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")
    galleryVersion: int = Field(ge=0)
    configVersion: int = Field(ge=0)
    livenessRequired: bool
    actions: list[Literal["BLINK", "MOUTH"]] = Field(max_length=2)
    threshold: float = Field(gt=0, le=1)
    margin: float = Field(ge=0, le=2)
    expiresAt: datetime

    @field_validator("expiresAt")
    @classmethod
    def aware_date(cls, value):
        if value.tzinfo is None:
            raise ValueError("expiresAt 必须含时区")
        return value

    @model_validator(mode="after")
    def challenges(self):
        if self.livenessRequired and (len(self.actions) != 2 or set(self.actions) != {"BLINK", "MOUTH"}):
            raise ValueError("启用动作检测时必须包含一次眨眼和一次张嘴")
        return self


def create_app(settings: Settings | None = None, engine=None) -> FastAPI:
    settings = settings or Settings.from_environment()
    engine = engine or FaceEngine(settings)
    registry = Registry()

    @asynccontextmanager
    async def lifespan(app):
        load_task = asyncio.create_task(asyncio.to_thread(engine.load))
        yield
        await load_task
        await asyncio.to_thread(engine.close)

    app = FastAPI(title="Face Attendance Internal Inference", version="1.0.0", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(InternalBoundary, api_key=settings.internal_api_key)
    app.state.engine, app.state.registry = engine, registry

    @app.exception_handler(Rejected)
    async def rejected_handler(request: Request, error: Rejected):
        return JSONResponse({"code": error.code, "message": error.message}, status_code=error.status)

    @app.exception_handler(RequestValidationError)
    async def validation_handler(request: Request, error):
        # Pydantic's default error can echo complete template vectors; never return input values.
        return JSONResponse({"code": "INVALID_REQUEST", "message": "请求字段无效，请检查接口契约"}, status_code=422)

    def require_ready():
        if not engine.ready:
            raise Rejected("MODEL_UNAVAILABLE", "算法服务不可用，请联系管理员", 503)

    @app.get("/internal/health")
    async def health():
        detail = "CPU模型已加载，人员库已同步" if engine.ready and registry.gallery.version >= 0 else engine.detail
        return {"ready": bool(engine.ready), "modelVersion": engine.model_version,
                "galleryVersion": registry.gallery.version, "detail": detail}

    @app.put("/internal/gallery")
    async def replace_gallery(data: GalleryInput):
        gallery = await asyncio.to_thread(Gallery.build, data.version, [employee.model_dump() for employee in data.employees])
        registry.replace(gallery)
        return {"version": gallery.version}

    @app.post("/internal/face/extract")
    async def extract(files: Annotated[list[UploadFile], File()], employeeId: Annotated[int | None, Form()] = None):
        require_ready()
        if len(files) != 3 or (employeeId is not None and employeeId <= 0):
            raise Rejected("INVALID_ENROLLMENT", "请采集正脸、轻微左转及右转共3张图像")
        gallery = registry.gallery
        if gallery.version < 0:
            raise Rejected("STALE_GALLERY", "人员库尚未同步，暂不能检查重复录入", 409)
        observations = []
        try:
            for upload in files:
                content = await upload.read(MAX_FRAME_BYTES + 1)
                observations.append(await asyncio.to_thread(engine.observe, content, False))
        finally:
            for upload in files:
                await upload.close()
        for i in range(3):
            for j in range(i + 1, 3):
                if cosine(observations[i].embedding, observations[j].embedding) < settings.enrollment_threshold:
                    raise Rejected("INCONSISTENT_ENROLLMENT", "三张图像身份不一致，请重新核对采集")
        for observation in observations:
            match = gallery.search(observation.embedding, exclude=employeeId)
            if match and match.score >= settings.duplicate_threshold:
                raise Rejected("DUPLICATE_FACE", "疑似已绑定其他人员，请核查已有档案", 409)
        if registry.gallery is not gallery:
            raise Rejected("STALE_GALLERY", "采集期间人员库已变化，请重试", 409)
        return {"embeddings": [list(obs.embedding) for obs in observations], "modelVersion": engine.model_version}

    @app.post("/internal/sessions")
    async def create_session(data: SessionInput):
        require_ready()
        remaining = (data.expiresAt - datetime.now(timezone.utc)).total_seconds()
        if remaining <= 0 or remaining > MAX_SESSION_SECONDS + 2:
            raise Rejected("INVALID_EXPIRY", "会话有效期必须在未来60秒以内")
        session = Session(data.sessionId, data.galleryVersion, data.configVersion, data.livenessRequired,
                          tuple(data.actions), data.threshold, data.margin,
                          time.monotonic() + min(remaining, MAX_SESSION_SECONDS))
        registry.create(session)
        return {"sessionId": session.session_id}

    @app.delete("/internal/sessions/{session_id}")
    async def cancel_session(session_id: str):
        registry.remove(session_id)
        return {"sessionId": session_id}

    @app.websocket("/internal/sessions/{session_id}/stream")
    async def stream(websocket: WebSocket, session_id: str):
        await websocket.accept()
        session = None
        claimed = False
        try:
            require_ready()
            with registry.lock:
                session = registry.sessions.get(session_id)
                if session is None:
                    raise Rejected("SESSION_NOT_FOUND", "会话不存在或已结束", 404)
                session.check(registry.gallery.version)
                if session.connected:
                    raise Rejected("SESSION_IN_USE", "会话已有连接", 409)
                session.connected = claimed = True
            await websocket.send_json({"type": "PROMPT", "message": "请保持单人正对镜头"})
            while True:
                now = time.monotonic()
                deadline = min(session.deadline, session.capture_start + MAX_CAPTURE_SECONDS if session.capture_start is not None else session.deadline)
                message = await asyncio.wait_for(websocket.receive(), max(0.001, deadline - now))
                if message["type"] == "websocket.disconnect":
                    break
                if message.get("bytes") is None:
                    raise Rejected("INVALID_FRAME", "只接受JPEG二进制帧")
                jpeg = message["bytes"]
                session.accept_frame(len(jpeg), registry.gallery.version)
                try:
                    observation = await asyncio.to_thread(engine.observe, jpeg, session.liveness_required)
                except Rejected as error:
                    if error.code in {"NO_FACE", "LOW_QUALITY"} and session.identity is None:
                        session.check(registry.gallery.version)
                        await websocket.send_json({"type": "PROMPT", "code": error.code, "message": error.message, "frameProcessed": True})
                        continue
                    raise
                with registry.lock:
                    output = session.process(observation, registry.gallery, engine.model_version)
                output["frameProcessed"] = True
                await websocket.send_json(output)
                if output["type"] == "RESULT":
                    break
        except TimeoutError:
            await websocket.send_json({"type": "ERROR", "code": "TIMEOUT", "message": "验证超时，请重新开始"})
        except Rejected as error:
            await websocket.send_json({"type": "ERROR", "code": error.code, "message": error.message})
        except WebSocketDisconnect:
            pass
        except Exception:
            try:
                await websocket.send_json({"type": "ERROR", "code": "INFERENCE_FAILED", "message": "算法处理失败，请联系管理员"})
            except Exception:
                pass
        finally:
            if claimed:
                registry.remove(session_id)
            try:
                await websocket.close()
            except RuntimeError:
                pass

    return app


app = create_app()
