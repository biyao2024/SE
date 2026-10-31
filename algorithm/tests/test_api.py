"""Contract/security tests with an explicit injected test-only engine; no real biometric claims."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import unittest
import uuid
from unittest.mock import patch

try:
    from fastapi.testclient import TestClient
    from starlette.websockets import WebSocketDisconnect
    from app.main import create_app
except ImportError:
    TestClient = None

from app.domain import Observation
from app.settings import Settings
from tests.test_domain import vector

KEY = "test-only-internal-key-32-characters-long"


class TestEngine:
    ready = True
    detail = "Injected test engine; never used by production entrypoint"
    model_version = "test-only"

    def load(self):
        pass

    def close(self):
        pass

    def observe(self, content, require_landmarks=True):
        return Observation(vector(1 if content == b"second-person" else 0))


@unittest.skipIf(TestClient is None, "Install requirements-test.txt to run HTTP/WebSocket contract tests")
class ApiTests(unittest.TestCase):
    def setUp(self):
        self.engine = TestEngine()
        self.app = create_app(Settings(KEY, Path("models")), self.engine)
        self.client = TestClient(self.app)
        self.headers = {"X-Internal-Key": KEY}

    def request(self, method, path, **kwargs):
        return self.client.request(method, path, headers=self.headers, **kwargs)

    def sync(self):
        return self.request("PUT", "/internal/gallery", json={"version": 1, "employees": [
            {"employeeId": i + 1, "templates": [vector(i)] * 3} for i in range(2)]})

    def start(self, identifier="test-session-0001", **overrides):
        data = {"sessionId": identifier, "galleryVersion": 1, "configVersion": 1,
                "livenessRequired": False, "actions": [], "threshold": 0.5, "margin": 0.1,
                "expiresAt": (datetime.now(timezone.utc) + timedelta(seconds=50)).isoformat()}
        data.update(overrides)
        return self.request("POST", "/internal/sessions", json=data)

    def test_internal_auth_all_routes_and_websocket(self):
        self.assertEqual(self.client.get("/internal/health").status_code, 401)
        self.assertEqual(self.client.post("/internal/face/extract", content=b"bad body").status_code, 401)
        self.assertEqual(self.client.get("/docs").status_code, 401)
        with self.assertRaises(WebSocketDisconnect):
            with self.client.websocket_connect("/internal/sessions/unknown/stream"):
                pass

    def test_http_request_id_is_preserved_and_invalid_input_is_replaced(self):
        request_id = str(uuid.uuid4())
        with self.assertLogs("uvicorn.error", level="INFO") as captured:
            response = self.client.get("/internal/health", headers={**self.headers, "X-Request-ID": request_id})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["X-Request-ID"], request_id)
        self.assertIn(f'"requestId":"{request_id}"', captured.output[0])
        self.assertNotIn(KEY, captured.output[0])
        # Authentication failures also carry a traceable, safe response identifier.
        response = self.client.get("/internal/health", headers={"X-Request-ID": "invalid value"})
        self.assertEqual(response.status_code, 401)
        self.assertEqual(str(uuid.UUID(response.headers["X-Request-ID"])), response.headers["X-Request-ID"])
        self.sync()
        files = [("files", (f"capture{i}.jpg", b"test-frame", "image/jpeg")) for i in range(3)]
        with patch.object(self.engine, "observe", side_effect=RuntimeError("sensitive-inference-detail")):
            response = self.client.post("/internal/face/extract", files=files,
                                        headers={**self.headers, "X-Request-ID": request_id})
        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.headers["X-Request-ID"], request_id)
        self.assertNotIn("sensitive-inference-detail", response.text)

    def test_health_and_unready_fail_closed(self):
        self.assertTrue(self.request("GET", "/internal/health").json()["ready"])
        self.sync()
        self.assertEqual(self.request("GET", "/internal/health").json()["detail"], "CPU模型已加载，人员库已同步")
        self.engine.ready = False
        self.assertFalse(self.request("GET", "/internal/health").json()["ready"])
        self.assertEqual(self.request("GET", "/internal/health").json()["detail"], self.engine.detail)
        self.assertEqual(self.start().status_code, 503)

    def test_session_requires_synced_gallery_and_valid_actions(self):
        self.assertEqual(self.start().status_code, 409)
        self.assertEqual(self.sync().status_code, 200)
        self.assertEqual(self.start(livenessRequired=True, actions=["BLINK", "BLINK"]).status_code, 422)
        self.assertEqual(self.start(expiresAt="2030-01-01T00:00:00Z").status_code, 422)
        self.assertEqual(self.start().status_code, 200)

    def test_body_bound_precedes_json_and_validation_does_not_echo_vectors(self):
        response = self.client.put("/internal/gallery", headers={**self.headers, "Content-Length": "9000000"}, content=b"{}")
        self.assertEqual(response.status_code, 413)
        response = self.request("PUT", "/internal/gallery", json={"version": 1, "employees": [{"employeeId": 1, "templates": [[123.456]] * 3}]})
        self.assertEqual(response.status_code, 422)
        self.assertNotIn("123.456", response.text)

    def test_stream_three_frames_and_reconnect_rejected(self):
        self.sync()
        self.start()
        with self.client.websocket_connect("/internal/sessions/test-session-0001/stream", headers=self.headers) as ws:
            self.assertEqual(ws.receive_json()["type"], "PROMPT")
            for i in range(3):
                ws.send_bytes(b"test-frame")
                result = ws.receive_json()
                self.assertEqual(result["type"], "RESULT" if i == 2 else "PROMPT")
                self.assertTrue(result["frameProcessed"])
            self.assertEqual(result["employeeId"], 1)
            self.assertEqual(result["livenessStatus"], "SKIPPED")
        with self.client.websocket_connect("/internal/sessions/test-session-0001/stream", headers=self.headers) as ws:
            self.assertEqual(ws.receive_json()["code"], "SESSION_NOT_FOUND")

    def test_second_connection_does_not_cancel_first(self):
        self.sync()
        self.start()
        path = "/internal/sessions/test-session-0001/stream"
        with self.client.websocket_connect(path, headers=self.headers) as first:
            first.receive_json()
            with self.client.websocket_connect(path, headers=self.headers) as second:
                self.assertEqual(second.receive_json()["code"], "SESSION_IN_USE")
            first.send_bytes(b"test-frame")
            self.assertEqual(first.receive_json()["type"], "PROMPT")

    def test_stream_identity_change_and_oversized_frame(self):
        self.sync()
        self.start()
        with self.client.websocket_connect("/internal/sessions/test-session-0001/stream", headers=self.headers) as ws:
            ws.receive_json()
            ws.send_bytes(b"test-frame")
            ws.receive_json()
            ws.send_bytes(b"second-person")
            self.assertEqual(ws.receive_json()["code"], "IDENTITY_UNSTABLE")
        self.start("test-session-0002")
        with self.client.websocket_connect("/internal/sessions/test-session-0002/stream", headers=self.headers) as ws:
            ws.receive_json()
            ws.send_bytes(b"x" * (200 * 1024 + 1))
            self.assertEqual(ws.receive_json()["code"], "FRAME_TOO_LARGE")

    def test_gallery_replacement_invalidates_live_connection(self):
        self.sync()
        self.start()
        with self.client.websocket_connect("/internal/sessions/test-session-0001/stream", headers=self.headers) as ws:
            ws.receive_json()
            self.request("PUT", "/internal/gallery", json={"version": 2, "employees": []})
            ws.send_bytes(b"test-frame")
            self.assertEqual(ws.receive_json()["code"], "STALE_SESSION")

    def test_enrollment_duplicate_and_employee_exclusion(self):
        self.sync()
        files = [("files", (f"capture{i}.jpg", b"test-frame", "image/jpeg")) for i in range(3)]
        self.assertEqual(self.request("POST", "/internal/face/extract", files=files).status_code, 409)
        response = self.request("POST", "/internal/face/extract", files=files, data={"employeeId": "1"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()["embeddings"]), 3)
        files[1] = ("files", ("other.jpg", b"second-person", "image/jpeg"))
        self.assertEqual(self.request("POST", "/internal/face/extract", files=files, data={"employeeId": "1"}).status_code, 422)


if __name__ == "__main__":
    unittest.main()
