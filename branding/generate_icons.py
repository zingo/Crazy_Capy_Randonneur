#!/usr/bin/env python3
"""Generate Android launcher icons from the Crazy Capy logo.

Usage: python3 branding/generate_icons.py
Output: app/src/main/res/{mipmap-*,drawable-nodpi,values/colors.xml parts}
"""
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "app", "src", "main", "res")

BRAND = (46, 93, 70)   # deep green background
LOGO_SRC = os.path.join(HERE, "CrazyCapy-D.png")

DEN = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
DP_108 = 4  # xxxhdpi scale: 108dp -> 432px


def load_logo_tinted(color):
    im = Image.open(LOGO_SRC).convert("RGBA")
    a = im.split()[3]
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    solid = Image.new("RGBA", im.size, (*color, 255))
    solid.putalpha(a)
    return solid


def load_logo_mask():
    im = Image.open(LOGO_SRC).convert("RGBA")
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.putalpha(im.split()[3])
    return out


def paste_fitted(base, logo, target, pad_bottom=0.0):
    """Paste logo centered, fitted so it covers ~`frac` of the side use 0.66."""
    base_w, base_h = base.size
    frac_to_fit = 0.66
    lw, lh = logo.size
    s = (base_w * frac_to_fit) / max(lw, lh)
    logo = logo.resize((int(lw * s), int(lh * s)), Image.LANCZOS)
    x = (base_w - logo.width) // 2
    y = (base_h - logo.height) // 2
    base.alpha_composite(logo, (x, y))
    return base


def square(size, color):
    return Image.new("RGBA", (size, size), (*color, 255))


def make_legacy(px, out):
    base = square(px, BRAND)
    paste_fitted(base, load_logo_tinted((255, 255, 255)), base)
    base.save(out)


def make_adaptive_foreground(out):
    # 108dp at xxxhdpi -> 432px, artwork kept inside safe zone (66dp/108dp)
    size = 108 * 4
    base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    logo = load_logo_tinted((255, 255, 255))
    paste_fitted(base, logo, base)
    base.save(out)


def make_monochrome(out):
    size = 108 * 4
    base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    logo = load_logo_mask()
    paste_fitted(base, logo, base)
    base.save(out)


def main():
    os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
    for d, size in DEN.items():
        os.makedirs(os.path.join(RES, f"mipmap-{d}"), exist_ok=True)
        make_legacy(size, os.path.join(RES, f"mipmap-{d}", "ic_launcher.png"))
        make_legacy(size, os.path.join(RES, f"mipmap-{d}", "ic_launcher_round.png"))

    os.makedirs(os.path.join(RES, "mipmap-xxxhdpi"), exist_ok=True)
    make_adaptive_foreground(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png"))
    make_monochrome(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_monochrome.png"))

    with open(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"), "w") as f:
        f.write(ADAPTIVE_XML)
    with open(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher_round.xml"), "w") as f:
        f.write(ADAPTIVE_XML)

    print("icons written under", RES)


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""

if __name__ == "__main__":
    main()