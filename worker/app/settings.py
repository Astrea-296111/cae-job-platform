"""Non-secret node configuration; token values are never written to execution files."""

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    data_root: Path
    registry_path: Path
    node_id: str = "node-a"
    cpu_total: int = 16
    memory_total_gb: float = 32
    solver_tags: tuple[str, ...] = ("STRUCTURE", "NVH")
    service_token: str = "demo-service-token"
    backend_url: str = ""
    heartbeat_interval: float = 5

    @classmethod
    def from_environment(cls) -> "Settings":
        root = Path(os.getenv("DATA_ROOT", "../.data")).absolute()
        node = os.getenv("NODE_ID", "node-a")
        return cls(
            data_root=root,
            registry_path=Path(
                os.getenv("REGISTRY_PATH", str(root / "agents" / node / "registry.sqlite3"))
            ).absolute(),
            node_id=node,
            cpu_total=int(os.getenv("CPU_TOTAL", "16")),
            memory_total_gb=float(os.getenv("MEMORY_TOTAL_GB", "32")),
            solver_tags=tuple(os.getenv("SOLVER_TAGS", "STRUCTURE,NVH").split(",")),
            service_token=os.getenv("SERVICE_TOKEN", "demo-service-token"),
            backend_url=os.getenv("BACKEND_URL", ""),
            heartbeat_interval=max(0.1, float(os.getenv("HEARTBEAT_INTERVAL_SECONDS", "5"))),
        )
