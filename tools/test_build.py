# SPDX-License-Identifier: GPL-3.0-only
"""Check real compiler outputs and refusal of compile-only declarations."""

import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "owned_build", Path(__file__).with_name("build.py")
)
assert spec and spec.loader
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class ArchiveControls(unittest.TestCase):
    def test_all_compiled_parts_exclude_declarations(self):
        jars = list((build.BUILD / "card").glob("*.jar"))
        self.assertEqual(len(jars), 12)
        for path in jars:
            build.check_jar(path)

    def test_release_optional_paths_and_wireless_firewall(self):
        text = (build.ROOT / "bootstrap/lsd-extender.conf").read_text()
        settings = dict(
            line.removeprefix("prop ").split("=", 1)
            for line in text.splitlines()
            if line and not line.startswith("#") and "=" in line
        )
        for name in (
            "dsispy.enabled",
            "dsispy.slog",
            "keys.enabled",
            "input.trace",
            "touch.slog",
            "vehicle_probe.enabled",
            "nav.destination",
            "publish.pf",
        ):
            self.assertEqual(settings["lsdextender." + name], "false")
        self.assertEqual(settings.get("lsdextender.nav.aa"), "true")
        self.assertEqual(settings.get("lsdextender.nav.turnto"), "")
        self.assertEqual(settings.get("lsdextender.nav.turnto.sign"), "")
        self.assertEqual(settings.get("lsdextender.vehicle_probe.read_only"), "true")
        self.assertNotIn("lsdextender.wireless.card_remount", settings)
        self.assertEqual(settings["lsdextender.publish.port"], "0")
        self.assertEqual(settings["lsdextender.publish.pf_ifaces"], "$wlan_if")
        self.assertEqual(settings["lsdextender.wireless.pf"], "true")
        for name in ("updates.worker", "updates.agent.config", "games.directory"):
            self.assertEqual(settings["lsdextender." + name], "/dev/null")

    def test_renamed_real_declaration_is_refused(self):
        data = (
            build.BUILD / "compile-standins/org/dsi/ifc/base/DSIListener.class"
        ).read_bytes()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "wrong.jar"
            with zipfile.ZipFile(path, "w") as jar:
                jar.writestr("org/korho/lsdextender/Fake.class", data)
            with self.assertRaisesRegex(ValueError, "owned namespace"):
                build.check_jar(path)

    def test_newer_class_format_is_refused(self):
        data = bytearray(
            (build.BUILD / "core/org/korho/lsdextender/Build.class").read_bytes()
        )
        data[6:8] = b"\x00\x34"
        with self.assertRaisesRegex(ValueError, "Java 1.2"):
            build.class_name(data)

    def test_duplicate_real_class_is_refused(self):
        name = "org/korho/lsdextender/Build.class"
        data = (build.BUILD / "core" / name).read_bytes()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "duplicate.jar"
            with zipfile.ZipFile(path, "w") as jar:
                jar.writestr(name, data)
                with self.assertWarns(UserWarning):
                    jar.writestr(name, data)
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                build.check_jar(path)


if __name__ == "__main__":
    unittest.main()
