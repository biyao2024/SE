from dataclasses import dataclass
import os
import math
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    internal_api_key: str
    model_dir: Path
    insightface_model: str = "buffalo_l"
    enrollment_threshold: float = 0.50
    duplicate_threshold: float = 0.50
    inference_threads: int = 8

    def __post_init__(self):
        if not isinstance(self.inference_threads, int) or not 1 <= self.inference_threads <= 8:
            raise ValueError('CPU_INFERENCE_THREADS must be between 1 and 8')
        for name, value in (("ENROLLMENT_THRESHOLD", self.enrollment_threshold),
                            ("DUPLICATE_THRESHOLD", self.duplicate_threshold)):
            if not math.isfinite(value) or not 0 < value <= 1:
                raise ValueError(f"{name}必须为0至1之间的有限正数，默认值仍需独立校准")

    @classmethod
    def from_environment(cls):
        return cls(os.getenv("INTERNAL_API_KEY", ""), Path(os.getenv("MODEL_DIR", "./models")).resolve(),
                   os.getenv("INSIGHTFACE_MODEL", "buffalo_l"),
                   float(os.getenv("ENROLLMENT_THRESHOLD", "0.50")),
                   float(os.getenv("DUPLICATE_THRESHOLD", "0.50")), int(os.getenv("CPU_INFERENCE_THREADS", "8")))
