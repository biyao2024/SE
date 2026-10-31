import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock
from app.cpu_runtime import make_cpu_face_analysis
from app.settings import Settings

class CpuRuntimeTests(unittest.TestCase):
    def test_thread_count_is_bounded(self):
        for value in (0,-1,9):
            with self.assertRaises(ValueError): make_cpu_face_analysis(Path('unused'),value)
            with self.assertRaises(ValueError): Settings('test',Path('unused'),inference_threads=value)

    def test_all_required_models_receive_explicit_cpu_session_options(self):
        models=[]
        for name in ('detection','landmark_3d_68','recognition'):
            model=MagicMock();model.taskname=name;models.append(model)
        with patch('insightface.model_zoo.model_zoo.ModelRouter') as router:
            router.return_value.get_model.side_effect=models
            face=make_cpu_face_analysis(Path('verified-model-dir'),8)
            self.assertEqual(set(face.models),{'detection','landmark_3d_68','recognition'})
            self.assertIs(face.det_model,models[0])
            self.assertEqual(router.call_count,3)
            for call in router.return_value.get_model.call_args_list:
                self.assertEqual(call.kwargs['providers'],['CPUExecutionProvider'])
                self.assertEqual(call.kwargs['sess_options'].intra_op_num_threads,8)
                self.assertEqual(call.kwargs['sess_options'].inter_op_num_threads,1)
