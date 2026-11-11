"""Check inventory coverage and reject broken theme/review interfaces."""

import csv
import json
from pathlib import Path
import shutil
import tempfile
import xml.etree.ElementTree as ET

from check_theme import check
from csv_to_review import convert

PACKAGE = Path(__file__).resolve().parents[1]
data = json.loads((PACKAGE / "inventory.json").read_text(encoding="utf-8"))
keys = {node.attrib["name"] for node in ET.parse(PACKAGE / "references/current-source/values-strings.xml").getroot()}
actual = {item["key"] for item in data["copy"] if "key" in item}
assert keys == actual, "Resource text inventory is incomplete."
ids = ["page." + p["id"] for p in data["pages"]] + ["action." + a["id"] for a in data["actions"]] + [c["id"] for c in data["copy"]] + [e["id"] for e in data["edges"]]
assert len(ids) == len(set(ids)), "Duplicate review IDs."
icons = set(json.loads((PACKAGE / "contracts/required-icons.json").read_text(encoding="utf-8"))["icons"])
assert icons == {action["icon"] for action in data["actions"] if action["icon"]}, "Icon mapping mismatch."
assert not check(PACKAGE / "starter-theme"), "Neutral starter theme invalid."
assert check(PACKAGE / "starter-theme", final=True), "A placeholder theme must fail final delivery."
qa = PACKAGE / ".qa"
qa.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="contracts-", dir=qa) as temporary:
    work = Path(temporary).resolve()
    assert work.is_relative_to(qa.resolve())
    theme = work / "theme"
    shutil.copytree(PACKAGE / "starter-theme", theme)
    tokens = json.loads((theme / "tokens.json").read_text(encoding="utf-8"))
    tokens["sizes"]["touch"] = 20
    (theme / "tokens.json").write_text(json.dumps(tokens), encoding="utf-8")
    assert check(theme), "Small touch target accepted."
    shutil.copy2(PACKAGE / "starter-theme/tokens.json", theme / "tokens.json")
    (theme / "icons.svg").write_text('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>', encoding="utf-8")
    assert check(theme), "Unsafe or missing SVG icons accepted."
    rows = list(csv.DictReader((PACKAGE / "文案审核.csv").open(encoding="utf-8-sig", newline="")))
    target = next(row for row in rows if row["ID"] == "copy.connect_hint")
    target["审核结论"] = "需要修改"
    target["你的修改或意见"] = "QA round trip"
    reviewed = work / "reviewed.csv"
    with reviewed.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    output = work / "review.json"
    assert convert(reviewed, output) == len(data["copy"])
    result = json.loads(output.read_text(encoding="utf-8"))
    assert next(item for item in result["decisions"] if item["id"] == target["ID"])["status"] == "revise"
    rows[0]["源快照"] = "outdated"
    with reviewed.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    try:
        convert(reviewed, output)
    except ValueError:
        pass
    else:
        raise AssertionError("Outdated CSV snapshot accepted.")
print("PASS full text coverage, unique IDs, icon mapping, invalid themes, CSV/JSON round trip and stale snapshot rejection")
