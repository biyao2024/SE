import unittest
from app.domain import Session, Gallery, Observation, normalize
import time

def vector(i=0):
    return tuple(1.0 if j==i else 0.0 for j in range(512))

class DiagnosticsTests(unittest.TestCase):
    def test_single_person_no_fake_margin_and_no_identity_or_embedding_in_payload(self):
        gallery=Gallery.build(1,[{"employeeId":1,"templates":[vector()]*3}])
        session=Session('diagnostic',1,1,False,(),.5,.08,time.monotonic()+60)
        before=session.diagnostics(Observation(vector()),gallery)
        self.assertEqual(before['score'],1)
        self.assertIsNone(before['margin'])
        self.assertIsNone(before['referenceSimilarity'])
        self.assertIsNone(before['mouth'])
        self.assertEqual(before['gate'],'PASS')
        self.assertNotIn('employeeId',before)
        self.assertNotIn('embedding',before)
        self.assertIsNone(session.identity)  # Measuring does not alter recognition state.

    def test_score_margin_and_first_frame_failures_are_distinct(self):
        g=Gallery.build(1,[{"employeeId":1,"templates":[vector()]*3}])
        s=Session('diagnostic',1,1,True,('BLINK','MOUTH'),.5,.08,time.monotonic()+60)
        self.assertEqual(s.diagnostics(Observation(vector(1)),g)['gate'],'MATCH_SCORE')
        s.identity=1;s.baseline=vector(1)
        d=s.diagnostics(Observation(vector(),.6,.7,.4),g)
        self.assertEqual(d['gate'],'REFERENCE_SIMILARITY')
        self.assertEqual(d['eyeLeft'],.6);self.assertEqual(d['mouth'],.4)
        g2=Gallery.build(1,[{"employeeId":1,"templates":[vector()]*3},{"employeeId":2,"templates":[vector()]*3}])
        self.assertEqual(s.diagnostics(Observation(vector()),g2)['gate'],'CANDIDATE_MARGIN')
