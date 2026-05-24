from pathlib import Path
import shutil

REPO_APK_DIR = Path("repo/apk")

# APK names to never publish (removed or broken modules).
BLOCKED_APK_PREFIXES = (
    "aniyomi-it.hanime",
)

try:
    shutil.rmtree(REPO_APK_DIR)
except FileNotFoundError:
    pass

REPO_APK_DIR.mkdir(parents=True, exist_ok=True)

for apk in (Path.home() / "apk-artifacts").glob("**/*.apk"):
    if apk.name.startswith(BLOCKED_APK_PREFIXES):
        print(f"Skipping blocked APK: {apk.name}")
        apk.unlink(missing_ok=True)
        continue

    apk_name = apk.name.replace("-release.apk", ".apk")
    shutil.move(apk, REPO_APK_DIR / apk_name)
