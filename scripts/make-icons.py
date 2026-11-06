#!/usr/bin/env python3
"""
Every app icon: the grounding symbol of branding/groundwire-mark.svg,
white on black, centred.

The symbol is drawn from the SVG's own strokes, cropped to itself, and
placed in the middle of each icon at the size each platform expects: a
black rounded square where the system draws the icon as it is (desktop,
the Mac's Dock), a black square where it rounds or masks it (iOS, the
Play Store, Android's adaptive icon). The in-app logo is the mark's
monochrome layer, tinted by the theme.

Run from the repository root: python3 scripts/make-icons.py [out-root]
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")


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


mono = mark("branding/ic_launcher_monochrome_1024.png")
BLACK = (0, 0, 0)  # @color/ic_launcher_background


def glyph():
    """The grounding symbol of branding/groundwire-mark.svg, white on nothing, cropped to itself.

    Every app icon is it on black: the tile is the black, not a disc on a
    navy one (sneagan, 2026-11-05: "the black should just be the whole
    background", in the rounded square that suits phones and the Mac)."""
    k = 4  # drawn at four times the SVG's 509 units, for smooth ends
    im = Image.new("RGBA", (509 * k, 509 * k), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = 22 * k
    for x1, y1, x2, y2 in [(254, 105, 254, 273), (425, 275, 84, 275), (362, 339, 147, 339), (297, 403, 211, 403)]:
        d.line((x1 * k, y1 * k, x2 * k, y2 * k), fill=(255, 255, 255, 255), width=w)
        for x, y in ((x1, y1), (x2, y2)):  # round caps, as the SVG's
            d.ellipse((x * k - w / 2, y * k - w / 2, x * k + w / 2, y * k + w / 2), fill=(255, 255, 255, 255))
    return im.crop(im.getchannel("A").getbbox())


def tile(body, margin, radius, frac):
    """A black rounded square [body] across and [margin] in, the symbol [frac] of its width."""
    im = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle((margin, margin, margin + body - 1, margin + body - 1), radius=radius, fill=BLACK + (255,))
    im.alpha_composite(placed(sym, 1024, frac * body / 1024))
    return im


sym = glyph()

# In the app: the mark alone, tinted with the theme's primary colour. The
# monochrome layer, so the grounding lines stay holes the tint keeps.
placed(mono, 256, 0.90).save(out("composeApp/src/commonMain/composeResources/drawable/talon_logo.png"), optimize=True)

# Desktop window, taskbar, notifications, .dmg/.msi/.deb, and the Linux
# hicolor icons (scripts/build-appimage.sh installs these): a whole rounded square.
desk = tile(920, 52, 200, 0.50)
desk.save(out("composeApp/src/desktopMain/resources/icon.png"), optimize=True)
desk.save(out("composeApp/src/desktopMain/resources/icon.ico"), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
for n in (16, 24, 32, 48, 64, 128, 256, 512):
    desk.resize((n, n), Image.LANCZOS).save(out(f"branding/linux/talon-{n}.png"), optimize=True)

# macOS (Dock, menu bar, .app/.dmg): Apple's grid, an 824 px tile 100 px
# in, so it is the size of the icons beside it.
tile(824, 100, 185, 0.50).save(out("composeApp/src/desktopMain/resources/icon-macos.png"), optimize=True)

# iOS: full bleed and opaque; iOS rounds the corners itself. The same
# square is the Play Store listing's and the website's.
full = placed(sym, 1024, 0.50, bg=BLACK).convert("RGB")
full.save(out("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"), optimize=True)
full.save(out("branding/ic_launcher_full_1024.png"), optimize=True)
full.resize((512, 512), Image.LANCZOS).save(out("branding/play_store_icon_512.png"), optimize=True)

# Android adaptive icon: a 108dp layer of which the launcher shows the
# middle 72dp, and promises only the middle 66dp. The background is
# @color/ic_launcher_background, black; the symbol keeps well inside the
# 66dp. Themed icons tint the monochrome layer: the symbol itself.
for density, layer, legacy in [("mdpi", 108, 48), ("hdpi", 162, 72), ("xhdpi", 216, 96), ("xxhdpi", 324, 144), ("xxxhdpi", 432, 192)]:
    res = f"composeApp/src/androidMain/res/mipmap-{density}"
    placed(sym, layer, 0.36).save(out(f"{res}/ic_launcher_foreground.png"), optimize=True)
    placed(sym, layer, 0.36).convert("LA").save(out(f"{res}/ic_launcher_monochrome.png"), optimize=True)
    # Launchers older than adaptive icons; minSdk 26 means few, if any.
    flat = placed(sym, legacy, 0.50, bg=BLACK).convert("RGB")
    flat.save(out(f"{res}/ic_launcher.png"), optimize=True)
    flat.save(out(f"{res}/ic_launcher_round.png"), optimize=True)
