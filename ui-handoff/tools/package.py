"""Build a portable, offline ZIP without browser profiles or QA downloads."""

from pathlib import Path
import hashlib
import json
import zipfile

PACKAGE = Path(__file__).resolve().parents[1]
OUTPUT = PACKAGE.parent / "微镜记-UI交接包.zip"


def included(path):
    relative = path.relative_to(PACKAGE)
    return not any(part in {".qa", "__pycache__"} for part in relative.parts) and path.name != "package-manifest.json"


files = sorted(path for path in PACKAGE.rglob("*") if path.is_file() and included(path))
manifest = {"schemaVersion": "1.0", "entry": "index.html",
            "files": [{"path": path.relative_to(PACKAGE).as_posix(), "bytes": path.stat().st_size,
                       "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in files]}
manifest_path = PACKAGE / "package-manifest.json"
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
with zipfile.ZipFile(OUTPUT, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in files + [manifest_path]:
        archive.write(path, "ui-handoff/" + path.relative_to(PACKAGE).as_posix())
with zipfile.ZipFile(OUTPUT) as archive:
    failure = archive.testzip()
    if failure:
        raise RuntimeError("ZIP integrity error " + failure)
print(json.dumps({"files": len(files) + 1, "bytes": OUTPUT.stat().st_size, "zip": OUTPUT.name}, ensure_ascii=True))
