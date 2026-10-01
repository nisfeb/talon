#!/usr/bin/env python3
"""
Every Talon icon from the mark in branding/, centred.

The mark in branding/ic_launcher_foreground_1024.png sits low and left of
centre, and the desktop and iOS icons were a navy rounded square cropped
off at the bottom (hard corners). This places the mark, cropped to its own
bounds, in the middle of each icon, at a size each platform expects.

Run from the repository root: python3 scripts/make-icons.py [out-root]
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
NAVY = (0x1E, 0x1B, 0x4B)  # @color/ic_launcher_background


def mark(path):
    im = Image.open(path).convert("RGBA")
    return im.crop(im.getchannel("A").getbbox())


def placed(m, size, frac, bg=None):
    """[m] with its longer side [frac] of [size], centred; drawn at 1024 and scaled down."""
    big = 1024
    w, h = m.size
    s = frac * big / max(w, h)
    mm = m.resize((round(w * s), round(h * s)), Image.LANCZOS)
    canvas = Image.new("RGBA", (big, big), (bg + (255,)) if bg else (0, 0, 0, 0))
    canvas.alpha_composite(mm, ((big - mm.width) // 2, (big - mm.height) // 2))
    return canvas if size == big else canvas.resize((size, size), Image.LANCZOS)


def out(rel):
    p = OUT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    return p


colour = mark("branding/ic_launcher_foreground_1024.png")
mono = mark("branding/ic_launcher_monochrome_1024.png")

# In the app: the mark alone, tinted with the theme's primary colour.
placed(colour, 256, 0.90).save(out("composeApp/src/commonMain/composeResources/drawable/talon_logo.png"), optimize=True)

# Desktop window, taskbar, notifications, .dmg/.msi/.deb: a whole rounded square.
body, margin = 920, 52
desk = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
ImageDraw.Draw(desk).rounded_rectangle((margin, margin, margin + body - 1, margin + body - 1), radius=200, fill=NAVY + (255,))
desk.alpha_composite(placed(colour, 1024, 0.62))
desk.save(out("composeApp/src/desktopMain/resources/icon.png"), optimize=True)
desk.save(out("composeApp/src/desktopMain/resources/icon.ico"), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])

# iOS: full bleed and opaque; iOS rounds the corners itself.
placed(colour, 1024, 0.64, bg=NAVY).convert("RGB").save(out("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"), optimize=True)

# Android adaptive icon: a 108dp layer of which the launcher shows the
# middle 72dp, and promises only the middle 66dp: the mark keeps inside it.
for density, layer, legacy in [("mdpi", 108, 48), ("hdpi", 162, 72), ("xhdpi", 216, 96), ("xxhdpi", 324, 144), ("xxxhdpi", 432, 192)]:
    res = f"composeApp/src/androidMain/res/mipmap-{density}"
    placed(colour, layer, 0.58).save(out(f"{res}/ic_launcher_foreground.png"), optimize=True)
    placed(mono, layer, 0.58).convert("LA").save(out(f"{res}/ic_launcher_monochrome.png"), optimize=True)
    # Launchers older than adaptive icons; minSdk 26 means few, if any.
    flat = placed(colour, legacy, 0.64, bg=NAVY).convert("RGB")
    flat.save(out(f"{res}/ic_launcher.png"), optimize=True)
    flat.save(out(f"{res}/ic_launcher_round.png"), optimize=True)
