"""Explicit CPU sessions for the pinned InsightFace adapter; no model downloads."""
from pathlib import Path

def make_cpu_face_analysis(model_dir: Path, threads: int = 2):
    if not isinstance(threads, int) or not 1 <= threads <= 8:
        raise ValueError('CPU inference threads must be between 1 and 8')
    import onnxruntime as ort
    from insightface.app import FaceAnalysis
    from insightface.model_zoo.model_zoo import ModelRouter

    class CpuFaceAnalysis(FaceAnalysis):
        def __init__(self):
            self.models = {}
            self.model_dir = str(model_dir)
            # InsightFace 0.7.3's public get_model drops sess_options. Use its pinned
            # router directly, rather than monkey-patching third-party/global state.
            for filename in ('det_10g.onnx', '1k3d68.onnx', 'w600k_r50.onnx'):
                options = ort.SessionOptions()
                options.intra_op_num_threads = threads
                options.inter_op_num_threads = 1
                options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
                model = ModelRouter(str(model_dir / filename)).get_model(
                    sess_options=options, providers=['CPUExecutionProvider'])
                if model is None:
                    raise RuntimeError('Unrecognized required CPU model')
                self.models[model.taskname] = model
            self.det_model = self.models['detection']
    return CpuFaceAnalysis()
