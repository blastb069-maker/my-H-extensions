import json
import os
from pathlib import Path

repo_dir = Path("repo")
apk_dir = repo_dir / "apk"

repo_dir.mkdir(exist_ok=True)

extensions = []

for apk in apk_dir.glob("*.apk"):
    extensions.append({
        "name": apk.stem,
        "apk": f"apk/{apk.name}"
    })

with open(repo_dir / "index.json", "w", encoding="utf-8") as f:
    json.dump(extensions, f, indent=2)

with open(repo_dir / "index.min.json", "w", encoding="utf-8") as f:
    json.dump(extensions, f, separators=(",", ":"))

print("Generated index.json and index.min.json")
