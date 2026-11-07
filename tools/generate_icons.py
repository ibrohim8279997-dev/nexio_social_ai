"""Builds the Android launcher assets from design/icon-reference.png.

Layers are separated instead of shipping one stretched image: the ribbon becomes
the adaptive foreground, the backdrop lives in a vector (ic_launcher_background.xml)
and a one-colour silhouette serves as the themed monochrome layer.

Usage: python tools/generate_icons.py   (needs Pillow, numpy, scipy)
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import gaussian_filter

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
BACKDROP = np.array([1.0, 9.0, 45.0])  # dark navy behind the ribbon in the reference
RIBBON_BOX = (170, 330, 1090, 885)
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def smoothstep(x, lo, hi):
    t = np.clip((x - lo) / (hi - lo), 0, 1)
    return t * t * (3 - 2 * t)


def extract():
    rgb = np.asarray(Image.open(ROOT / "design" / "icon-reference.png").convert("RGB")).astype(float)
    h, w, _ = rgb.shape
    # Separate ribbon from backdrop: alpha reproduces the source exactly over BACKDROP.
    diff = (rgb - BACKDROP) / (255.0 - BACKDROP)
    alpha = np.clip(diff.max(axis=2), 0, 1)
    alpha[alpha < 0.03] = 0
    # Fade out everything near the tile's neon border, keeping only the ribbon's glow.
    x0, y0, x1, y1 = RIBBON_BOX
    yy, xx = np.mgrid[0:h, 0:w]
    dist = np.maximum.reduce([x0 - xx, xx - x1, y0 - yy, yy - y1, np.zeros_like(xx)]).astype(float)
    alpha *= 1 - smoothstep(dist, 25, 75)
    safe = np.maximum(alpha, 1e-4)[..., None]
    fg = np.clip((rgb - BACKDROP * (1 - safe)) / safe, 0, 255)
    fg_img = Image.fromarray(np.dstack([fg, alpha * 255]).astype("uint8"), "RGBA")

    # Blue stays high across the whole ribbon, folds included, giving a crisp silhouette.
    mono = gaussian_filter(smoothstep(rgb[..., 2], 95, 125), 0.8)
    mono *= 1 - smoothstep(dist, 0, 8)
    mono_img = Image.fromarray(
        np.dstack([np.full_like(mono, 255), np.full_like(mono, 255), np.full_like(mono, 255), mono * 255]).astype("uint8"),
        "RGBA",
    )
    return fg_img, mono_img


def on_canvas(layer, canvas_px, ribbon_share):
    """Centres the ribbon on a square canvas; ribbon_share is its diagonal / canvas side."""
    x0, y0, x1, y1 = RIBBON_BOX
    diag = np.hypot(x1 - x0, y1 - y0)
    side = int(diag / ribbon_share)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    box = (int(cx - side / 2), int(cy - side / 2), int(cx + side / 2), int(cy + side / 2))
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(layer, (-box[0], -box[1]), layer)
    return canvas.resize((canvas_px, canvas_px), Image.LANCZOS)


def backdrop(px):
    yy, xx = np.mgrid[0:px, 0:px] / (px - 1)
    r = np.clip(np.hypot(xx - 0.5, yy - 0.42) / 0.75, 0, 1)[..., None]
    top, bottom = np.array([2, 16, 74.0]), np.array([0, 4, 28.0])
    return np.dstack([top * (1 - r) + bottom * r, np.full((px, px), 255.0)]).astype("uint8")


def legacy(fg, px, round_mask):
    bg = Image.fromarray(backdrop(px * 4), "RGBA")
    ribbon = on_canvas(fg, px * 4, 0.78)
    bg.alpha_composite(ribbon)
    mask = Image.new("L", (px * 4, px * 4), 0)
    draw = ImageDraw.Draw(mask)
    if round_mask:
        draw.ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
    else:
        draw.rounded_rectangle((0, 0, px * 4 - 1, px * 4 - 1), radius=px * 4 // 5, fill=255)
    bg.putalpha(mask)
    return bg.resize((px, px), Image.LANCZOS)


def main():
    fg, mono = extract()
    for name, scale in DENSITIES.items():
        out = RES / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        size = int(108 * scale)
        on_canvas(fg, size, 0.60).save(out / "ic_launcher_foreground.png", optimize=True)
        on_canvas(mono, size, 0.60).save(out / "ic_launcher_monochrome.png", optimize=True)
        legacy(fg, int(48 * scale), False).save(out / "ic_launcher.png", optimize=True)
        legacy(fg, int(48 * scale), True).save(out / "ic_launcher_round.png", optimize=True)
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    on_canvas(fg, 384, 0.96).save(nodpi / "nexio_mark.png", optimize=True)
    legacy(fg, 512, False).save(ROOT / "design" / "play-store-icon-512.png", optimize=True)


if __name__ == "__main__":
    main()
