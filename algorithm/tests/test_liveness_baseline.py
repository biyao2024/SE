import time
import unittest
from app.domain import ActionTracker, Gallery, Observation, Session

VECTOR=tuple([1.0]+[0.0]*511)
GALLERY=Gallery.build(1,[{"employeeId":1,"templates":[VECTOR]*3}])
NATURAL=Observation(VECTOR,.25,.23,.01)

class LivenessBaselineTests(unittest.TestCase):
    def session(self,order=('BLINK','MOUTH')):
        start=time.monotonic()
        return start,Session('baseline-test',1,1,True,order,.7,.3,start+60)

    def test_observed_natural_state_starts_first_action_without_duplicate_baseline(self):
        _,s=self.session()
        for _ in range(4): result=s.process(NATURAL,GALLERY,'model')
        self.assertEqual(s.neutral_frames,2)
        self.assertEqual(s.tracker.phase,1)
        self.assertEqual(result['action'],'BLINK')
        self.assertIn('闭合双眼',result['message'])

    def test_ambiguous_and_closed_eyes_cannot_establish_natural_baseline(self):
        for left,right in [(0.31,.1),(.1,.31),(.55,.55),(.8,.8)]:
            _,s=self.session()
            for _ in range(10): result=s.process(Observation(VECTOR,left,right,.01),GALLERY,'model')
            self.assertIsNone(s.tracker)
            self.assertNotEqual(result['type'],'RESULT')

    def test_static_natural_state_still_cannot_finish_liveness(self):
        _,s=self.session()
        for _ in range(80): self.assertNotEqual(s.process(NATURAL,GALLERY,'model')['type'],'RESULT')
        self.assertEqual(s.action_index,0)

    def test_complete_actions_with_observed_baseline_at_slow_cpu_cadence(self):
        for order in [('BLINK','MOUTH'),('MOUTH','BLINK')]:
            start,s=self.session(order)
            frames=[NATURAL]*4
            for i,action in enumerate(order):
                if i: frames += [NATURAL]*2
                active=Observation(VECTOR,.8,.8,.01) if action=='BLINK' else Observation(VECTOR,.25,.23,.7)
                frames += [active]*(1 if action=='BLINK' else 2)+[NATURAL]*2
            frames += [NATURAL]*3
            for index,obs in enumerate(frames):
                moment=start+index*1.5
                s.accept_frame(1000,1,now=moment)
                result=s.process(obs,GALLERY,'model',now=moment)
                if index<len(frames)-1:self.assertNotEqual(result['type'],'RESULT')
            self.assertEqual(result['livenessStatus'],'PASSED')
            self.assertLess((len(frames)-1)*1.5,35)
