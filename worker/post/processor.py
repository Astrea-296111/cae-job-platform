"""Real Matplotlib images behind a replaceable professional-tool adapter."""

import threading
from typing import Protocol

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

PLOT_LOCK = threading.Lock()


class PostProcessor(Protocol):
    def process(self, request, raw, directory) -> None: ...


class MockHyperViewPostProcessor:
    def process(self, request, raw, directory):
        metrics = {m.metric_code: m for m in request.metrics}
        with PLOT_LOCK:
            for spec in request.specs:
                figure, axes = plt.subplots(figsize=(10, 6), dpi=120)
                try:
                    x = np.linspace(-2, 2, 70)
                    y = np.linspace(-1, 1, 45)
                    xx, yy = np.meshgrid(x, y)
                    scale = float(raw.get("parameters", {}).get("thicknessScale", 1))
                    if spec.result_type == "CONTOUR":
                        values = scale * (np.sin(xx * 2) ** 2 + np.cos(yy * 3) ** 2)
                        plot = axes.contourf(xx, yy, values, levels=16, cmap="viridis")
                        figure.colorbar(plot, ax=axes, label="Synthetic field amplitude")
                        axes.set(xlabel="Synthetic body X", ylabel="Synthetic body Y")
                    elif spec.result_type == "MODE_SHAPE":
                        frequency = (
                            float(metrics.get("MODE_1_FREQUENCY").value_num)
                            if "MODE_1_FREQUENCY" in metrics
                            else 1
                        )
                        shape = np.sin(xx * np.pi / 4) * np.cos(yy * np.pi / 2) * frequency / 30
                        plot = axes.contourf(xx, yy, shape, levels=16, cmap="coolwarm")
                        figure.colorbar(plot, ax=axes, label="Synthetic mode displacement")
                        axes.set(xlabel="Synthetic body X", ylabel="Synthetic body Y")
                    elif spec.result_type == "COMPARISON":
                        rows = [row for row in request.comparisons if row.current is not None]
                        positions = np.arange(len(rows))
                        # The backend supplies every comparison value; each pair is normalized
                        # for display only because the metrics have different engineering units.
                        maxima = [
                            max(abs(float(row.current)), abs(float(row.baseline or 0)), 1e-10)
                            for row in rows
                        ]
                        axes.bar(
                            positions - 0.18,
                            [
                                float(row.baseline or 0) / top
                                for row, top in zip(rows, maxima, strict=True)
                            ],
                            0.36,
                            label="Baseline (N/A when absent)",
                        )
                        axes.bar(
                            positions + 0.18,
                            [
                                float(row.current) / top
                                for row, top in zip(rows, maxima, strict=True)
                            ],
                            0.36,
                            label="Current",
                        )
                        axes.set_xticks(
                            positions,
                            [row.metric_code.replace("_", "\n") for row in rows],
                            fontsize=7,
                        )
                        axes.set_ylabel("Per-metric relative display amplitude")
                        axes.legend()
                        if request.baseline is None:
                            axes.text(
                                0.5, 0.9, "Baseline: N/A", transform=axes.transAxes, ha="center"
                            )
                    else:
                        time = np.linspace(0, 1, 160)
                        amplitude = float(
                            next(m.value_num for m in request.metrics if m.value_num is not None)
                        )
                        axes.plot(
                            time,
                            amplitude * scale * np.exp(-time * 2) * np.sin(time * np.pi * 8),
                            label="Synthetic response",
                        )
                        axes.set(
                            xlabel="Synthetic time coordinate",
                            ylabel="Synthetic response amplitude",
                        )
                        axes.legend()
                    axes.set_title(
                        f"{request.analysis_type} / {request.condition_code} / "
                        f"Subcase {request.subcase}\n{spec.result_type} · {request.parser_version}"
                    )
                    figure.text(
                        0.5,
                        0.015,
                        "SYNTHETIC PUBLIC DEMO · DRAFT",
                        ha="center",
                        fontsize=11,
                        color="#9a3412",
                    )
                    figure.tight_layout(rect=(0, 0.04, 1, 1))
                    figure.savefig(
                        directory / spec.output_name,
                        metadata={
                            "Description": f"{request.condition_code};"
                            f"subcase={request.subcase};{spec.result_type}"
                        },
                    )
                finally:
                    plt.close(figure)
