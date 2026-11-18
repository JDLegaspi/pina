"""Generate Pina's launcher and notification vectors from one pineapple geometry.

Usage: python3 build_pina_icon.py <android res dir> [svg preview dir]
"""
import math
import sys
from pathlib import Path

BODY = (12.0, 15.6, 5.6, 6.9)
LEAVES = [
    ("M11.2,10.2 C8.6,10.8 6.1,10.0 4.6,8.1 C7.1,7.4 9.6,8.3 11.2,10.2 Z", "side"),
    ("M12.8,10.2 C15.4,10.8 17.9,10.0 19.4,8.1 C16.9,7.4 14.4,8.3 12.8,10.2 Z", "side"),
    ("M11.6,9.8 C9.0,9.7 6.9,7.9 6.3,5.0 C9.0,5.2 11.0,7.0 11.6,9.8 Z", "inner"),
    ("M12.4,9.8 C15.0,9.7 17.1,7.9 17.7,5.0 C15.0,5.2 13.0,7.0 12.4,9.8 Z", "inner"),
    ("M12,1.0 C13.8,3.1 14.1,6.1 12,9.6 C9.9,6.1 10.2,3.1 12,1.0 Z", "center"),
]
LEAF_COLORS = {"side": ("#3DBE5E", "#1C8F42"), "inner": ("#4CD06C", "#21A04A"), "center": ("#5EDB7A", "#26A94F")}
BODY_COLORS = ("#FFD84D", "#F6A400")
HATCH = "#E28A00"
BACKGROUND = ("#FFF4D8", "#FFD98C")
SPACING = 2.4


def ellipse_path(cx, cy, rx, ry):
    return f"M{cx - rx:g},{cy:g} A{rx:g},{ry:g} 0 1,0 {cx + rx:g},{cy:g} A{rx:g},{ry:g} 0 1,0 {cx - rx:g},{cy:g} Z"


def hatch_path():
    cx, cy, rx, ry = BODY
    reach = rx + ry
    segments = []
    for i in range(-8, 9):
        offset = i * SPACING
        for direction in (1, -1):
            x0, y0 = cx + offset - reach, cy - direction * reach
            x1, y1 = cx + offset + reach, cy + direction * reach
            segments.append(f"M{x0:.2f},{y0:.2f} L{x1:.2f},{y1:.2f}")
    return " ".join(segments)


def eye_holes():
    cx, cy, rx, ry = BODY
    holes = []
    half = 0.85
    for row in range(-3, 4):
        y = cy + row * SPACING * 0.75 * 1.6
        shift = 0 if row % 2 == 0 else SPACING * 0.62
        for col in range(-3, 4):
            x = cx + col * SPACING * 1.24 + shift
            inside = ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2
            if inside < 0.55:
                holes.append(f"M{x:.2f},{y - half:.2f} L{x + half:.2f},{y:.2f} L{x:.2f},{y + half:.2f} L{x - half:.2f},{y:.2f} Z")
    return " ".join(holes)


def gradient_xml(top, bottom, y0, y1):
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="0" android:startY="{y0:g}" '
            f'android:endX="0" android:endY="{y1:g}" android:startColor="{top}" android:endColor="{bottom}"/></aapt:attr>')


def vector(width, viewport, body, group=None):
    head = (f'<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" '
            f'android:width="{width}dp" android:height="{width}dp" android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n')
    if group:
        body = f'    <group android:scaleX="{group[0]}" android:scaleY="{group[0]}" android:translateX="{group[1]}" android:translateY="{group[1]}">\n{body}    </group>\n'
    return head + body + "</vector>\n"


def colour_glyph():
    cx, cy, rx, ry = BODY
    body = ellipse_path(*BODY)
    parts = [f'    <path android:pathData="{body}">{gradient_xml(*BODY_COLORS, cy - ry, cy + ry)}</path>\n',
             f'    <group>\n        <clip-path android:pathData="{body}"/>\n'
             f'        <path android:pathData="{hatch_path()}" android:strokeColor="{HATCH}" android:strokeWidth="0.42" android:strokeAlpha="0.75" android:strokeLineCap="round"/>\n    </group>\n']
    for path, kind in LEAVES:
        top, bottom = LEAF_COLORS[kind]
        parts.append(f'    <path android:pathData="{path}">{gradient_xml(top, bottom, 1, 10.8)}</path>\n')
    return "".join(parts)


def mono_glyph(colour="#FFFFFFFF"):
    body = ellipse_path(*BODY) + " " + eye_holes()
    leaves = " ".join(path for path, _ in LEAVES)
    return (f'    <path android:fillColor="{colour}" android:fillType="evenOdd" android:pathData="{body}"/>\n'
            f'    <path android:fillColor="{colour}" android:pathData="{leaves}"/>\n')


def background():
    return (f'    <path android:pathData="M0,0 L108,0 L108,108 L0,108 Z">{gradient_xml(*BACKGROUND, 0, 108)}</path>\n')


ICON_GROUP = ("2.3", "26.4")


def svg_preview(mono):
    import re
    def convert(xml):
        out = []
        for match in re.finditer(r'<path android:pathData="([^"]+)"(?:>|( [^/]*)/>)(?:<aapt:attr[^>]*><gradient[^>]*startColor="([^"]+)" android:endColor="([^"]+)"/></aapt:attr></path>)?', xml):
            data, attrs, top, bottom = match.groups()
            if top:
                out.append(f'<path d="{data}" fill="{bottom}"/>')
            elif attrs and "strokeColor" in attrs:
                out.append(f'<path d="{data}" stroke="{HATCH}" stroke-width="0.42" stroke-opacity="0.75" fill="none" clip-path="url(#b)"/>')
        return "".join(out)
    defs = f'<defs><clipPath id="b"><path d="{ellipse_path(*BODY)}"/></clipPath><clipPath id="m"><rect x="0" y="0" width="108" height="108" rx="24"/></clipPath></defs>'
    if mono:
        leaves = " ".join(path for path, _ in LEAVES)
        inner = f'<path d="{ellipse_path(*BODY)} {eye_holes()}" fill="#3C3C43" fill-rule="evenodd"/><path d="{leaves}" fill="#3C3C43"/>'
    else:
        inner = convert(colour_glyph())
    bg = "" if mono else f'<rect x="0" y="0" width="108" height="108" fill="{BACKGROUND[1]}"/>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="18 18 72 72">{defs}<g clip-path="url(#m)">{bg}'
            f'<g transform="translate({ICON_GROUP[1]},{ICON_GROUP[1]}) scale({ICON_GROUP[0]})">{inner}</g></g></svg>')


def main():
    res = Path(sys.argv[1])
    drawable = res / "drawable"
    (drawable / "ic_launcher_background.xml").write_text(vector(108, 108, background()))
    (drawable / "ic_launcher_foreground.xml").write_text(vector(108, 108, colour_glyph(), ICON_GROUP))
    (drawable / "ic_launcher_monochrome.xml").write_text(vector(108, 108, mono_glyph(), ICON_GROUP))
    (drawable / "ic_notification.xml").write_text(vector(24, 24, mono_glyph()))
    if len(sys.argv) > 2:
        preview = Path(sys.argv[2])
        preview.mkdir(parents=True, exist_ok=True)
        (preview / "pina-icon.svg").write_text(svg_preview(False))
        (preview / "pina-icon-mono.svg").write_text(svg_preview(True))


if __name__ == "__main__":
    main()
