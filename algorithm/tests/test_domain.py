import math
import time
import unittest

from app.domain import (ActionTracker, Gallery, MAX_FRAME_BYTES, Observation,
                        Registry, Rejected, Session, normalize)


def vector(index=0):
    values = [0.0] * 512
    values[index] = 1.0
    return tuple(values)


def gallery(version=1, count=2):
    return Gallery.build(version, [{"employeeId": i + 1, "templates": [vector(i)] * 3} for i in range(count)])


def session(liveness=False, session_id="test-session-0001", actions=("BLINK", "MOUTH")):
    return Session(session_id, 1, 1, liveness, actions, 0.5, 0.1, time.monotonic() + 60)


class VectorTests(unittest.TestCase):
    def test_invalid_vectors_rejected(self):
        for value in ([0] * 512, [1] * 511, [math.inf] * 512, [math.nan] * 512):
            with self.subTest(value=value[0]), self.assertRaises(Rejected):
                normalize(value)

    def test_max_template_score_and_margin(self):
        mixed = normalize([1, 0.6] + [0] * 510)
        items = [{"employeeId": 1, "templates": [vector(2), vector(3), vector(0)]},
                 {"employeeId": 2, "templates": [mixed] * 3}]
        match = Gallery.build(1, items).search(vector(0))
        self.assertEqual(match.employee_id, 1)
        self.assertAlmostEqual(match.score, 1)
        self.assertFalse(match.accepted(0.5, 0.2))
        self.assertTrue(match.accepted(0.5, 0.1))

    def test_single_candidate_ignores_margin_and_duplicate_exclusion(self):
        g = gallery(count=1)
        self.assertTrue(g.search(vector()).accepted(0.99, 2))
        self.assertIsNone(g.search(vector(), exclude=1))
        self.assertFalse(g.search(vector(1)).accepted(0.5, 0))

    def test_gallery_rejects_duplicates_and_wrong_template_count(self):
        for employees in ([{"employeeId": 1, "templates": [vector()] * 2}],
                          [{"employeeId": 1, "templates": [vector()] * 3}] * 2):
            with self.assertRaises(Rejected):
                Gallery.build(1, employees)


class SessionTests(unittest.TestCase):
    def test_three_frames_required_and_skipped_is_not_passed(self):
        current, g = session(), gallery()
        self.assertEqual(current.process(Observation(vector()), g, "test")["type"], "PROMPT")
        self.assertEqual(current.process(Observation(vector()), g, "test")["type"], "PROMPT")
        result = current.process(Observation(vector()), g, "test")
        self.assertEqual(result["type"], "RESULT")
        self.assertEqual(result["livenessStatus"], "SKIPPED")
        with self.assertRaises(Rejected):
            current.process(Observation(vector()), g, "test")

    def test_identity_change_during_challenge_rejected(self):
        current, g = session(True), gallery()
        current.process(Observation(vector()), g, "test")
        with self.assertRaises(Rejected) as context:
            current.process(Observation(vector(1)), g, "test")
        self.assertEqual(context.exception.code, "IDENTITY_UNSTABLE")

    def test_blink_requires_complete_open_closed_open(self):
        tracker = ActionTracker("BLINK")
        opened = Observation(vector())
        closed = Observation(vector(), 0.9, 0.9)
        self.assertFalse(tracker.update(closed))
        self.assertFalse(tracker.update(opened))
        self.assertFalse(tracker.update(opened))
        self.assertFalse(tracker.update(closed))
        self.assertFalse(tracker.update(closed))
        self.assertFalse(tracker.update(opened))
        self.assertTrue(tracker.update(opened))

    def test_mouth_dwell_and_return_to_closed(self):
        tracker = ActionTracker("MOUTH")
        closed, opened = Observation(vector()), Observation(vector(), mouth=0.7)
        for obs in [closed, closed, opened, closed, opened, opened, closed]:
            self.assertFalse(tracker.update(obs))
        self.assertTrue(tracker.update(closed))

    def test_full_challenges_either_order_and_final_identity(self):
        for order in [("BLINK", "MOUTH"), ("MOUTH", "BLINK")]:
            current, g = session(True, actions=order), gallery()
            neutral = Observation(vector())
            for _ in range(4):
                result = current.process(neutral, g, "model-v1")
            self.assertEqual(result["action"], order[0])
            for action in order:
                active = Observation(vector(), 0.8, 0.8) if action == "BLINK" else Observation(vector(), mouth=0.8)
                frames = [neutral, neutral, active] + ([] if action == "BLINK" else [active]) + [neutral, neutral]
                for obs in frames:
                    result = current.process(obs, g, "model-v1")
                    self.assertNotEqual(result["type"], "RESULT")
            for i in range(3):
                result = current.process(neutral, g, "model-v1")
                self.assertEqual(result["type"], "RESULT" if i == 2 else "PROMPT")
            self.assertEqual(result["livenessStatus"], "PASSED")
            self.assertEqual(result["galleryVersion"], 1)

    def test_static_neutral_photo_cannot_complete_actions(self):
        current, g = session(True), gallery()
        for _ in range(80):
            self.assertEqual(current.process(Observation(vector()), g, "test")["type"], "PROMPT")

    def test_resource_and_monotonic_limits(self):
        current = session()
        start = current.deadline - 60
        current.accept_frame(MAX_FRAME_BYTES, 1, start)
        with self.assertRaises(Rejected):
            current.accept_frame(1, 1, start + 12)
        current = session()
        with self.assertRaises(Rejected):
            current.accept_frame(MAX_FRAME_BYTES + 1, 1)
        current.frame_count = 80
        with self.assertRaises(Rejected):
            current.accept_frame(1, 1)
        current.frame_count = 0
        current.byte_count = 12 * 1024 * 1024
        with self.assertRaises(Rejected):
            current.accept_frame(1, 1)


class RegistryTests(unittest.TestCase):
    def test_cancelled_session_identifier_cannot_be_recreated(self):
        registry = Registry()
        registry.replace(gallery())
        registry.create(session())
        registry.remove("test-session-0001")
        with self.assertRaises(Rejected) as context:
            registry.create(session())
        self.assertEqual(context.exception.code, "SESSION_EXISTS")

    def test_capacity_and_atomic_invalidation(self):
        registry = Registry()
        registry.replace(gallery())
        first = session(session_id="session-a")
        registry.create(first)
        registry.create(session(session_id="session-b"))
        with self.assertRaises(Rejected) as context:
            registry.create(session(session_id="session-c"))
        self.assertEqual(context.exception.code, "BUSY")
        registry.replace(gallery(2))
        self.assertTrue(first.invalidated)
        self.assertFalse(registry.sessions)

    def test_version_rollback_and_same_version_content_change(self):
        registry = Registry()
        registry.replace(gallery(2))
        registry.replace(gallery(2))
        with self.assertRaises(Rejected):
            registry.replace(gallery(1))
        with self.assertRaises(Rejected):
            registry.replace(gallery(2, 1))

    def test_new_config_cancels_older_sessions(self):
        registry = Registry()
        registry.replace(gallery())
        first = session()
        registry.create(first)
        newer = session(session_id="session-new-config")
        newer.config_version = 2
        registry.create(newer)
        self.assertTrue(first.invalidated)
        with self.assertRaises(Rejected):
            registry.create(session())


if __name__ == "__main__":
    unittest.main()
