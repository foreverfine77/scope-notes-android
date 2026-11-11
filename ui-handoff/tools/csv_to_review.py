"""Convert a reviewed UTF-8 CSV into the audit tool's JSON format."""

import argparse
import csv
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

PACKAGE = Path(__file__).resolve().parents[1]
STATUSES = {"待审核": "pending", "通过": "approve", "需要修改": "revise", "修改": "revise", "取消": "drop",
            "pending": "pending", "approve": "approve", "revise": "revise", "drop": "drop"}


def convert(source, output):
    inventory = json.loads((PACKAGE / "inventory.json").read_text(encoding="utf-8"))
    known = {row["id"] for row in inventory["copy"]}
    seen = set()
    decisions = []
    with source.open(encoding="utf-8-sig", newline="") as stream:
        for line, row in enumerate(csv.DictReader(stream), 2):
            identifier = row.get("ID", "").strip()
            if identifier not in known or identifier in seen:
                raise ValueError(f"第 {line} 行 ID 未知或重复。")
            if row.get("源快照") != inventory["snapshot"]:
                raise ValueError(f"第 {line} 行快照不同，需先核对来源。")
            status = STATUSES.get(row.get("审核结论", "").strip())
            if status is None:
                raise ValueError(f"第 {line} 行审核结论未知。")
            seen.add(identifier)
            decisions.append({"id": identifier, "status": status, "proposal": row.get("候选文案", ""),
                              "note": row.get("你的修改或意见", "")})
    payload = {"schemaVersion": "1.0", "snapshot": inventory["snapshot"],
               "exportedAt": datetime.now(timezone.utc).isoformat(), "decisions": decisions}
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return len(decisions)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        count = convert(args.csv, args.output)
        print("PASS", count, "review rows converted")
    except (OSError, ValueError) as error:
        print("FAIL", str(error))
        sys.exit(1)
