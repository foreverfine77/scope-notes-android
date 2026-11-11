"""Validate a Claude theme before preview or native Android integration."""

import argparse
import json
import math
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

PACKAGE = Path(__file__).resolve().parents[1]
COLORS = {"background", "surface", "raised", "text", "muted", "border", "accent", "onAccent",
          "accentSoft", "danger", "onDanger", "viewport", "onViewport", "disabled"}
SECTIONS = {
    "radii": {"button": (0, 28), "panel": (0, 32), "sheet": (0, 36)},
    "sizes": {"touch": (48, 64), "icon": (20, 28), "gap": (8, 16), "pagePadding": (12, 24)},
    "type": {"body": (14, 18), "title": (18, 26), "caption": (12, 15)},
    "motion": {"rotationMs": (180, 180), "stateMs": (100, 250)},
}


def luminance(color):
    values = [int(color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [value / 12.92 if value <= .04045 else ((value + .055) / 1.055) ** 2.4 for value in values]
    return sum(value * weight for value, weight in zip(linear, (.2126, .7152, .0722)))


def contrast(first, second):
    a, b = sorted((luminance(first), luminance(second)))
    return (b + .05) / (a + .05)


def check(folder, final=False):
    errors = []
    tokens = json.loads((folder / "tokens.json").read_text(encoding="utf-8"))
    expected = {"schemaVersion", "name", "placeholderIcons", "colors", "radii", "sizes", "type", "motion", "effects", "annotation"}
    if set(tokens) != expected:
        errors.append("tokens.json 的顶层字段必须与契约完全一致。")
    if tokens.get("schemaVersion") != "1.0":
        errors.append("schemaVersion 必须为 1.0。")
    if not isinstance(tokens.get("name"), str) or not 1 <= len(tokens["name"]) <= 80:
        errors.append("主题名称应为 1–80 字符。")
    if not isinstance(tokens.get("placeholderIcons"), bool):
        errors.append("placeholderIcons 必须为布尔值。")
    if final and tokens.get("placeholderIcons") is not False:
        errors.append("最终交付不能保留占位图标。")
    for group, names in {"colors": COLORS, "annotation": {"vermilion", "amber", "teal", "white"}}.items():
        values = tokens.get(group, {})
        if set(values) != names:
            errors.append(group + " 字段与契约不符。")
        for key, value in values.items():
            if not isinstance(value, str) or not re.fullmatch(r"#[0-9A-Fa-f]{6}", value):
                errors.append(group + "." + key + " 应为 #RRGGBB。")
    for group, keys in SECTIONS.items():
        values = tokens.get(group, {})
        if set(values) != set(keys):
            errors.append(group + " 字段与契约不符。")
        for key, (low, high) in keys.items():
            value = values.get(key)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not low <= value <= high:
                errors.append(group + "." + key + " 超出允许范围。")
    effects = tokens.get("effects", {})
    if set(effects) != {"backgroundMode", "gradientEnd", "gradientAngle", "surfaceOpacity", "borderWidth", "shadowBlur", "shadowOpacity"}:
        errors.append("effects 字段与契约不符。")
    if effects.get("backgroundMode") not in {"solid", "linear"} or not re.fullmatch(r"#[0-9A-Fa-f]{6}", str(effects.get("gradientEnd"))):
        errors.append("背景模式或渐变色无效。")
    for key, low, high in [("gradientAngle", 0, 360), ("surfaceOpacity", .15, 1), ("borderWidth", 0, 2), ("shadowBlur", 0, 20), ("shadowOpacity", 0, .25)]:
        value = effects.get(key)
        if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or not low <= value <= high:
            errors.append("effects." + key + " 超出允许范围。")
    colors = tokens.get("colors", {})
    for foreground, background in [("text", "surface"), ("muted", "surface"), ("text", "background"), ("muted", "background"), ("onAccent", "accent"), ("onDanger", "danger"), ("onViewport", "viewport")]:
        try:
            ratio = contrast(colors[foreground], colors[background])
            if ratio < 4.5:
                errors.append(f"{foreground}/{background} 对比度 {ratio:.2f}，需要至少 4.5。")
        except (KeyError, TypeError, ValueError):
            pass
    required = set(json.loads((PACKAGE / "contracts/required-icons.json").read_text(encoding="utf-8"))["icons"])
    root = ET.parse(folder / "icons.svg").getroot()
    allowed = {"svg", "defs", "symbol", "g", "path", "circle", "ellipse", "rect", "line", "polyline", "polygon", "title", "desc"}
    found = set()
    for node in root.iter():
        tag = node.tag.split("}")[-1]
        if tag not in allowed:
            errors.append("不允许的 SVG 元素 " + tag)
        for key, value in node.attrib.items():
            local = key.split("}")[-1].lower()
            if local.startswith("on") or local in {"href", "style"} or "url(" in value.lower():
                errors.append("SVG 含脚本、外部引用或内嵌样式。")
        if tag == "symbol":
            identifier = node.get("id", "")
            if identifier in found:
                errors.append("重复图标 " + identifier)
            found.add(identifier)
            if node.get("viewBox") != "0 0 24 24":
                errors.append(identifier + " 必须使用 viewBox 0 0 24 24。")
    if required - found:
        errors.append("缺少图标 " + ", ".join(sorted(required - found)))
    if found - required:
        errors.append("存在未知图标 " + ", ".join(sorted(found - required)))
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != "1.0":
        errors.append("manifest schemaVersion 不匹配。")
    if final and manifest.get("placeholderIcons") is not False:
        errors.append("manifest 仍声明占位图标。")
    if manifest.get("files") != {"tokens": "tokens.json", "icons": "icons.svg", "decisions": "design-decisions.json"}:
        errors.append("manifest 文件接口不匹配。")
    decisions = json.loads((folder / "design-decisions.json").read_text(encoding="utf-8"))
    if decisions.get("schemaVersion") != "1.0" or not isinstance(decisions.get("decisions"), list):
        errors.append("design-decisions.json 接口错误。")
    return errors


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("folder", type=Path)
    parser.add_argument("--final", action="store_true")
    args = parser.parse_args()
    try:
        issues = check(args.folder, args.final)
    except (OSError, ValueError, TypeError, AttributeError, ET.ParseError) as error:
        print("FAIL", str(error))
        sys.exit(1)
    if issues:
        print("\n".join("FAIL " + error for error in issues))
        sys.exit(1)
    print("PASS theme structure, icon IDs, safe SVG, token ranges and contrast")
    print("Visual and native-device regression still required.")
