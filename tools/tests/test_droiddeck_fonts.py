import subprocess
import tempfile
import unittest
from pathlib import Path


FONTS = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-fonts"
SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


class DroidDeckFontsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        defaults = self.root / "usr/share/fontconfig/conf.default"
        defaults.mkdir(parents=True)
        (defaults / "60-latin.conf").write_text("<fontconfig/>")
        dejavu = self.root / "usr/share/fonts/truetype/dejavu"
        dejavu.mkdir(parents=True)
        (dejavu / "DejaVuSans.ttf").write_text("dejavu")

    def run_fonts(self):
        subprocess.run(["bash", str(FONTS), str(self.root)], check=True, capture_output=True)

    def local_conf(self):
        return (self.root / "etc/fonts/local.conf").read_text()

    def test_default_rules_linked_and_dejavu_is_the_default(self):
        self.run_fonts()
        self.assertTrue((self.root / "etc/fonts/conf.d/60-latin.conf").is_symlink())
        text = self.local_conf()
        self.assertIn("<family>sans-serif</family><prefer><family>DejaVu Sans</family>", text)
        self.assertIn("<family>monospace</family><prefer><family>DejaVu Sans Mono</family>", text)
        self.assertNotIn("Noto Sans CJK", text)
        self.assertNotIn("droiddeck-device", text)

    def test_an_existing_conf_d_rule_is_not_replaced(self):
        conf_d = self.root / "etc/fonts/conf.d"
        conf_d.mkdir(parents=True)
        (conf_d / "60-latin.conf").write_text("mine")
        self.run_fonts()
        self.assertFalse((conf_d / "60-latin.conf").is_symlink())
        self.assertEqual("mine", (conf_d / "60-latin.conf").read_text())

    def test_a_bound_cjk_font_adds_the_fallback_and_directory(self):
        cjk = self.root / "usr/share/fonts/droiddeck-device"
        cjk.mkdir(parents=True)
        (cjk / "NotoSansCJK-Regular.ttc").write_text("cjk")
        self.run_fonts()
        text = self.local_conf()
        self.assertIn("<dir>/usr/share/fonts/droiddeck-device</dir>", text)
        self.assertIn("<family>Noto Sans CJK SC</family>", text)
        self.assertIn("<family>Noto Serif CJK SC</family>", text)
        self.assertIn("<family>Noto Sans Mono CJK JP</family>", text)

    def test_unchanged_fonts_do_not_rewrite_local_conf(self):
        self.run_fonts()
        before = (self.root / "etc/fonts/local.conf").stat().st_mtime_ns
        self.run_fonts()
        self.assertEqual(before, (self.root / "etc/fonts/local.conf").stat().st_mtime_ns)

    def test_the_session_runs_the_bootstrap_before_it_starts_a_program(self):
        text = SESSION.read_text()
        self.assertIn("/usr/local/bin/droiddeck-fonts", text)
        self.assertLess(text.index("droiddeck-fonts"), text.index("droiddeck-steam-install"))


if __name__ == "__main__":
    unittest.main()
