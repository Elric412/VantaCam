"""Parametric film-style tone curve + Leica bias matrix + LUT export prototype."""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np


def _sigmoid(z: np.ndarray) -> np.ndarray:
    """Numerically stable logistic sigmoid."""
    return 1.0 / (1.0 + np.exp(-z))



def adjust_processing_parameters(iso_value: float) -> dict[str, float]:
    """Interpolate tone-curve parameters from scene ISO.

    Pseudo-logic:
      - ISO > 1600: progressively increase shadow contrast / black crush.
      - ISO < 400: progressively reduce highlight rolloff compression to maximize DR.
      - 400..1600: blend smoothly around base profile.
    """
    # Base look: balanced Hasselblad-like dynamic range + subtle Leica contrast.
    contrast = 7.5
    pivot_point = 0.42
    rolloff_strength = 0.65

    iso = float(max(50.0, iso_value))

    # Low ISO branch (bright scenes): preserve highlight range.
    if iso < 400.0:
        low_t = np.clip((400.0 - iso) / (400.0 - 100.0), 0.0, 1.0)
        # Lower shoulder compression in highlights.
        rolloff_strength -= 0.25 * low_t

    # High ISO branch (dark scenes): hide noise by stronger black separation/crush.
    if iso > 1600.0:
        high_t = np.clip((iso - 1600.0) / (6400.0 - 1600.0), 0.0, 1.0)
        contrast += 1.0 * high_t
        pivot_point += 0.03 * high_t

    return {
        "contrast": float(np.clip(contrast, 4.0, 12.0)),
        "pivot_point": float(np.clip(pivot_point, 0.30, 0.60)),
        "rolloff_strength": float(np.clip(rolloff_strength, 0.20, 1.20)),
    }
def apply_film_curve(
    x: np.ndarray,
    contrast: float = 7.5,
    pivot_point: float = 0.42,
    rolloff_strength: float = 0.65,
) -> np.ndarray:
    """Apply a film-like, parametric S-curve to linear values in [0, 1]."""
    x = np.asarray(x, dtype=np.float64)
    x = np.clip(x, 0.0, 1.0)

    s = _sigmoid(contrast * (x - pivot_point))
    s0 = _sigmoid(np.array(-contrast * pivot_point))
    s1 = _sigmoid(np.array(contrast * (1.0 - pivot_point)))
    y = (s - s0) / (s1 - s0 + 1e-12)

    shoulder_start = np.clip(0.82 - 0.22 * rolloff_strength, 0.55, 0.92)
    t = np.clip((y - shoulder_start) / (1.0 - shoulder_start + 1e-12), 0.0, 1.0)

    compression = 1.0 + 8.0 * rolloff_strength
    highlight_curve = shoulder_start + (1.0 - shoulder_start) * (
        np.log1p(t * compression) / np.log1p(compression)
    )
    y = y * (1.0 - t) + highlight_curve * t

    return np.clip(y, 0.0, 1.0)


# Leica character grade (3x3) for linear RGB in [0, 1].
# Rows are output channels [R', G', B']; columns are input [R, G, B].
LEICA_BIAS_MATRIX = np.array(
    [
        [1.020, -0.018, -0.002],
        [-0.010, 0.990, 0.020],
        [0.000, -0.030, 1.030],
    ],
    dtype=np.float64,
)


def apply_color_matrix(
    rgb: np.ndarray,
    matrix: np.ndarray = LEICA_BIAS_MATRIX,
) -> np.ndarray:
    """Apply a 3x3 color grading matrix to RGB values ([..., 3])."""
    rgb = np.asarray(rgb, dtype=np.float64)
    transformed = np.einsum("...c,dc->...d", rgb, matrix)
    return np.clip(transformed, 0.0, 1.0)


def create_identity_cube(size: int = 33) -> np.ndarray:
    """Create a high-resolution 3D identity LUT cube with shape [size,size,size,3]."""
    axis = np.linspace(0.0, 1.0, size, dtype=np.float64)
    rr, gg, bb = np.meshgrid(axis, axis, axis, indexing="ij")
    return np.stack([rr, gg, bb], axis=-1)


def generate_hasselblad_leica_lut(
    size: int = 33,
    contrast: float = 7.5,
    pivot_point: float = 0.42,
    rolloff_strength: float = 0.65,
    matrix: np.ndarray = LEICA_BIAS_MATRIX,
) -> np.ndarray:
    """Build LUT by applying color matrix first, then tone curve."""
    cube = create_identity_cube(size)
    cube = apply_color_matrix(cube, matrix=matrix)
    cube = apply_film_curve(
        cube,
        contrast=contrast,
        pivot_point=pivot_point,
        rolloff_strength=rolloff_strength,
    )
    return np.clip(cube, 0.0, 1.0)


def export_cube_file(
    lut: np.ndarray,
    path: str | Path,
    title: str = "Hasselblad-Leica-Look",
) -> Path:
    """Export a 3D LUT array [N,N,N,3] to Adobe-compatible .cube format."""
    lut = np.asarray(lut, dtype=np.float64)
    if lut.ndim != 4 or lut.shape[-1] != 3:
        raise ValueError("lut must have shape [N, N, N, 3]")
    if lut.shape[0] != lut.shape[1] or lut.shape[1] != lut.shape[2]:
        raise ValueError("lut must be a cube: [N, N, N, 3]")

    size = lut.shape[0]
    path = Path(path)

    with path.open("w", encoding="utf-8") as f:
        f.write(f'TITLE "{title}"\n')
        f.write(f"LUT_3D_SIZE {size}\n")
        f.write("DOMAIN_MIN 0.0 0.0 0.0\n")
        f.write("DOMAIN_MAX 1.0 1.0 1.0\n")

        # .cube expects B to vary fastest, then G, then R.
        for r in range(size):
            for g in range(size):
                for b in range(size):
                    rv, gv, bv = lut[r, g, b]
                    f.write(f"{rv:.10f} {gv:.10f} {bv:.10f}\n")

    return path


def plot_film_curve(
    contrast: float = 7.5,
    pivot_point: float = 0.42,
    rolloff_strength: float = 0.65,
) -> None:
    """Plot the tone curve for visual tuning."""
    x = np.linspace(0.0, 1.0, 1000)
    y = apply_film_curve(
        x,
        contrast=contrast,
        pivot_point=pivot_point,
        rolloff_strength=rolloff_strength,
    )

    plt.figure(figsize=(7.5, 6.0))
    plt.plot(x, y, linewidth=2.4, label="Film Curve")
    plt.plot(x, x, "--", linewidth=1.2, alpha=0.6, label="Identity")
    plt.title("Parametric Film-Style Tone Curve")
    plt.xlabel("Input (Linear 0..1)")
    plt.ylabel("Output")
    plt.xlim(0.0, 1.0)
    plt.ylim(0.0, 1.0)
    plt.grid(True, alpha=0.25)
    plt.legend()

    subtitle = (
        f"contrast={contrast:.2f}, pivot_point={pivot_point:.2f}, "
        f"rolloff_strength={rolloff_strength:.2f}"
    )
    plt.suptitle(subtitle, y=0.93, fontsize=10)
    plt.tight_layout()
    plt.show()


if __name__ == "__main__":
    lut = generate_hasselblad_leica_lut(size=33)
    out_path = export_cube_file(lut, path="hasselblad_leica_33.cube")
    print(f"Exported LUT: {out_path.resolve()}")
