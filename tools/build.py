# SPDX-License-Identifier: GPL-3.0-only
"""Build owned archives. Compile declarations are never packaged."""

import argparse
import json
import os
import shutil
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = Path(os.environ.get("OWNED_JAVA_BUILD_DIR", ROOT / "build")).resolve()
IMAGE = "docker.io/library/eclipse-temurin@sha256:32733e308c91c4cde5f003e37c3def82bb931e0242bf4062cfa45eefe3814df2"


def run(args):
    if os.environ.get("OWNED_JAVA_DIRECT") == "1":
        compiler = Path(os.environ["JAVA_HOME"]) / "bin/javac"
        subprocess.run([str(compiler), *args[1:]], cwd=ROOT, check=True, timeout=180)
        return
    engine = os.environ.get("CONTAINER_ENGINE", "podman")
    mapped = [
        arg.replace(str(BUILD), "/src/build").replace(str(ROOT), "/src") for arg in args
    ]
    user_options = []
    if Path(engine).name == "docker":
        security = subprocess.check_output(
            [engine, "info", "--format", "{{json .SecurityOptions}}"],
            text=True,
            timeout=10,
        )
        if "rootless" not in security:
            user_options = ["--user", str(os.getuid()) + ":" + str(os.getgid())]
    with tempfile.TemporaryDirectory(prefix="owned-java-") as temporary:
        cid = Path(temporary) / "container.cid"
        try:
            subprocess.run(
                [
                    engine,
                    "run",
                    "--rm",
                    "--network=none",
                    "--cidfile",
                    str(cid),
                    *user_options,
                    "-v",
                    str(ROOT) + ":/src:ro",
                    "-v",
                    str(BUILD) + ":/src/build",
                    "-w",
                    "/src",
                    IMAGE,
                    *mapped,
                ],
                check=True,
                timeout=180,
            )
        except BaseException:
            if cid.exists():
                identity = cid.read_text().strip()
                if len(identity) == 64 and all(
                    c in "0123456789abcdef" for c in identity
                ):
                    subprocess.run(
                        [engine, "rm", "--force", identity], check=False, timeout=15
                    )
            raise


def compile_sources(sources, destination, classpath=None):
    destination.mkdir(parents=True, exist_ok=True)
    listing = destination.parent / (destination.name + "-sources.txt")
    listing.write_text("".join(str(p.relative_to(ROOT)) + "\n" for p in sources))
    args = [
        "javac",
        "-source",
        "1.2",
        "-target",
        "1.2",
        "-Xlint:-options",
        "-encoding",
        "UTF-8",
        "-sourcepath",
        "",
        "-d",
        str(destination),
    ]
    if classpath:
        args += ["-classpath", classpath]
    run(args + ["@" + str(listing)])


def class_name(data):
    if len(data) < 10 or data[:8] != b"\xca\xfe\xba\xbe\x00\x00\x00\x2e":
        raise ValueError("Expected Java 1.2 class data")
    count = struct.unpack_from(">H", data, 8)[0]
    pool = {}
    position, index = 10, 1
    while index < count:
        tag = data[position]
        position += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, position)[0]
            position += 2
            pool[index] = data[position : position + length]
            position += length
        elif tag == 7:
            pool[index] = struct.unpack_from(">H", data, position)[0]
            position += 2
        elif tag in (3, 4, 5, 6, 8, 9, 10, 11, 12):
            position += {3: 4, 4: 4, 5: 8, 6: 8, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4}[tag]
        else:
            raise ValueError("Unsupported class constant")
        index += 2 if tag in (5, 6) else 1
    this_class = struct.unpack_from(">H", data, position + 2)[0]
    return pool[pool[this_class]].decode("ascii")


def check_jar(path):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate archive member")
        for member in names:
            if not member.endswith(".class"):
                raise ValueError("Unexpected archive resource")
            actual = class_name(jar.read(member))
            if (
                not actual.startswith("org/korho/lsdextender/")
                or member != actual + ".class"
            ):
                raise ValueError("Class outside the owned namespace")


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    if BUILD.exists() and any(BUILD.iterdir()):
        raise SystemExit(
            "Remove the previous generated build output before a clean build"
        )
    BUILD.mkdir(parents=True, exist_ok=True)
    api = BUILD / "compile-standins"
    compile_sources(sorted((ROOT / "compile-standins").rglob("*.java")), api)
    parts = json.loads((ROOT / "parts.json").read_text())
    parts.sort(key=lambda part: part["feature"] != "core")
    core = BUILD / "core"
    for part in parts:
        feature = part["feature"]
        classes = BUILD / feature
        sources = sorted((ROOT / "src" / feature).rglob("*.java"))
        sources += sorted((ROOT / "generated" / feature).rglob("*.java"))
        compile_sources(sources, classes, str(api) + ":" + str(core))
        output = BUILD / "card" / part["jar"]
        output.parent.mkdir(exist_ok=True)
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as jar:
            for source in sorted(classes.rglob("*.class")):
                name = source.relative_to(classes).as_posix()
                entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                entry.external_attr = 0o644 << 16
                entry.compress_type = zipfile.ZIP_DEFLATED
                jar.writestr(entry, source.read_bytes())
        check_jar(output)
        print("Built " + part["jar"] + " " + part["version"])
    for source in sorted((ROOT / "bootstrap").rglob("*")):
        if source.is_symlink():
            raise ValueError("Linked bootstrap source")
        if source.is_file():
            destination = BUILD / "card" / source.relative_to(ROOT / "bootstrap")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
    shutil.copy2(
        ROOT / "generated/core/core-build.properties",
        BUILD / "card/core-build.properties",
    )


if __name__ == "__main__":
    main()
