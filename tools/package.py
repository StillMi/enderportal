#!/usr/bin/env python3
"""把 javac 的产物和资源打成可以直接丢进 mods/ 的模组 jar。

本机的 Gradle 跑不起来（native-platform 加载失败），所以用这个脚本代替 `gradlew build`：

  * 类文件来自 `build/classes`（由 `tools/compile.ps1` 里的 javac 命令生成）
  * 资源来自 `src/main/resources`，其中的 `${...}` 用 `gradle.properties` 里的值展开
  * 时间戳固定，同样的输入永远得到同样的 jar（可复现构建）

用法：

    python tools/package.py

输出：`build/libs/enderportal-<mod_version>.jar`
"""

from __future__ import annotations

import hashlib
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLASSES = ROOT / "build" / "classes"
RESOURCES = ROOT / "src" / "main" / "resources"
PROPERTIES = ROOT / "gradle.properties"
OUT_DIR = ROOT / "build" / "libs"

# zip 能表示的最早时间；固定它可以让打包结果可复现
FIXED_TIME = (1980, 1, 1, 0, 0, 0)

PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_.-]+)\}")
TEXT_SUFFIXES = {".toml", ".json", ".mcmeta", ".json5"}


def read_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def expand(text: str, props: dict[str, str], where: str) -> str:
    def replace(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in props:
            raise KeyError(f"{where}: gradle.properties 里没有 {key}")
        return props[key]

    return PLACEHOLDER.sub(replace, text)


def manifest_for(props: dict[str, str]) -> bytes:
    lines = [
        "Manifest-Version: 1.0",
        f"Specification-Title: {props['mod_id']}",
        f"Specification-Vendor: {props['mod_authors']}",
        "Specification-Version: 1",
        f"Implementation-Title: {props['mod_id']}",
        f"Implementation-Version: {props['mod_version']}",
        f"Implementation-Vendor: {props['mod_authors']}",
        "",
        "",
    ]
    return "\r\n".join(lines).encode("utf-8")


def collect() -> list[tuple[str, bytes]]:
    props = read_properties(PROPERTIES)
    if not CLASSES.is_dir():
        raise SystemExit(f"没有找到 {CLASSES}，先编译（javac）再打包")

    entries: list[tuple[str, bytes]] = [("META-INF/MANIFEST.MF", manifest_for(props))]

    for path in sorted(CLASSES.rglob("*.class")):
        name = path.relative_to(CLASSES).as_posix()
        entries.append((name, path.read_bytes()))

    for path in sorted(RESOURCES.rglob("*")):
        if not path.is_file():
            continue
        name = path.relative_to(RESOURCES).as_posix()
        if path.suffix.lower() in TEXT_SUFFIXES:
            text = expand(path.read_text(encoding="utf-8"), props, name)
            if PLACEHOLDER.search(text):
                raise SystemExit(f"{name}: 还有没展开的 ${{...}}")
            entries.append((name, text.encode("utf-8")))
        else:
            entries.append((name, path.read_bytes()))

    return entries


def main() -> int:
    props = read_properties(PROPERTIES)
    version = props["mod_version"]
    entries = collect()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    target = OUT_DIR / f"{props['mod_id']}-{version}.jar"

    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as jar:
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            jar.writestr(info, data)

    raw = target.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    print(f"{target}")
    print(f"  entries : {len(entries)}")
    print(f"  size    : {len(raw):,} bytes")
    print(f"  sha256  : {digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
