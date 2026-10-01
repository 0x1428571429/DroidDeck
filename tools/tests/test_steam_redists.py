from pathlib import Path
import hashlib
import runpy
import tempfile
import unittest
from unittest.mock import patch

COMPAT = runpy.run_path(str(Path(__file__).resolve().parents[1] /
                           'linuxfs/overlay/usr/local/bin/bannerlator-steam-compat'))
PREPARE = COMPAT['prepare_installscript']


class SteamRedistTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.steam = Path(self.tmp.name) / 'Steam root'
        (self.steam / 'legacycompat').mkdir(parents=True)
        self.path = self.steam / 'legacycompat/evaluatorscript_50620.vdf'
        self.msi = str(self.steam / 'legacycompat/cache/vcredist.msi')
        self.installer_dir = self.steam / 'steamapps/common/Darksiders'
        self.installer_dir.mkdir(parents=True)
        (self.installer_dir / 'vcredist_x86_sp1_atl_4053.exe').touch()
        self.original = '''"evaluatorscript" { "0" {
          "appid" "50620" "install_path" "%s"
          "compat_installscript" {
            "registry" { "unrelated" { "string" { "keep" "value" } } }
            "RuN PrOcEsS" {
              "VCREDIST" { "Process 1" "%%INSTALLDIR%%\\\\vcredist_x86_sp1_atl_4053.exe"
                "Command 1" "/q:a" "IgnoreExitCode" "1" "NoCleanUp" "1" }
              "Accessories" { "process 1" "%%INSTALLDIR%%\\\\DSInstaller.exe"
                "command 1" "" "description" "Comic and Soundtrack" }
            }
          }
        } }''' % (self.steam / 'steamapps/common/Darksiders')
        self.path.write_text(self.original)

    def prepare(self):
        return PREPARE(str(self.steam), r'legacycompat\evaluatorscript_50620.vdf')

    def test_msi_launch_preserves_other_install_steps_and_registry(self):
        with patch.dict(PREPARE.__globals__, redist_msi=lambda *args: self.msi):
            self.assertTrue(self.prepare())
        tokens = COMPAT['tokenize'](self.path.read_text())
        decoded = [COMPAT['vdf_value'](t) for t in tokens if t.startswith('"')]
        self.assertIn(r'C:\windows\syswow64\msiexec.exe', decoded)
        self.assertIn('/i "Z:%s" /qn /norestart' % self.msi.replace('/', '\\'), decoded)
        self.assertIn(r'%INSTALLDIR%\DSInstaller.exe', decoded)
        self.assertIn('Comic and Soundtrack', decoded)
        self.assertIn('unrelated', decoded)
        self.assertIn('IgnoreExitCode', decoded)
        # Once converted, another wrapper invocation leaves the file untouched.
        before = self.path.read_bytes()
        self.assertFalse(self.prepare())
        self.assertEqual(before, self.path.read_bytes())

    def test_uninstall_custom_commands_and_other_architectures_are_untouched(self):
        for old, new in (('/q:a', '/uninstall /quiet'), ('/q:a', '/q:a /custom'),
                         ('vcredist_x86', 'vcredist_x64'),
                         ('vcredist_x86_sp1_atl_4053.exe', 'other.exe')):
            self.path.write_text(self.original.replace(old, new))
            before = self.path.read_bytes()
            with patch.dict(PREPARE.__globals__, redist_msi=lambda *args: self.fail('not a supported installer')):
                self.assertFalse(self.prepare())
            self.assertEqual(before, self.path.read_bytes())

    def test_failed_or_unsupported_extraction_keeps_original(self):
        def fail(*args):
            raise OSError('missing archive tool')
        for resolver in (fail, lambda *args: None):
            with patch.dict(PREPARE.__globals__, redist_msi=resolver):
                self.assertFalse(self.prepare())
            self.assertEqual(self.original, self.path.read_text())

    def test_only_transient_evaluator_paths_can_be_rewritten(self):
        for path in ('../legacycompat/evaluatorscript_50620.vdf',
                     str(self.path), r'legacycompat\other.vdf',
                     r'legacycompat\..\evaluatorscript_50620.vdf'):
            self.assertFalse(PREPARE(str(self.steam), path))
            self.assertEqual(self.original, self.path.read_text())

    def test_subdirectory_and_case_sensitive_linux_files_are_resolved(self):
        support = self.installer_dir / 'Support'
        support.mkdir()
        installer = support / 'vcredist_x86.exe'
        installer.touch()
        self.path.write_text(self.original.replace('vcredist_x86_sp1_atl_4053.exe',
                                                  r'support\\\\VCREDIST_X86.EXE'))
        def resolver(source, cache):
            self.assertTrue(Path(source).samefile(installer))
            return self.msi
        with patch.dict(PREPARE.__globals__, redist_msi=resolver):
            self.assertTrue(self.prepare())

    def test_installer_traversal_is_untouched(self):
        self.path.write_text(self.original.replace('vcredist_x86_sp1_atl_4053.exe',
                                                  r'..\\\\vcredist_x86.exe'))
        before = self.path.read_bytes()
        self.assertFalse(self.prepare())
        self.assertEqual(before, self.path.read_bytes())

    def test_archive_extraction_is_limited_to_recognized_flat_members(self):
        installer = Path(self.tmp.name) / 'vcredist_x86.exe'
        installer.write_bytes(b'fixture')
        cache = Path(self.tmp.name) / 'cache'
        def archive_run(argv, **kwargs):
            if argv[1] == '-tf':
                return type('Result', (), {'stdout': 'vcredist.msi\nvcredis1.cab\n../../outside\n'})()
            self.assertEqual(argv[-3:], ['--', 'vcredist.msi', 'vcredis1.cab'])
            destination = Path(argv[argv.index('-C') + 1])
            (destination / 'vcredist.msi').write_bytes(b'\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1')
            (destination / 'vcredis1.cab').write_bytes(b'MSCF')
        with patch.object(COMPAT['subprocess'], 'run', side_effect=archive_run):
            result = COMPAT['redist_msi'](str(installer), str(cache))
        self.assertTrue(Path(result).is_file())
        self.assertFalse((Path(self.tmp.name) / 'outside').exists())

    def physx_fixture(self, command=None):
        payload = b'verified Wise PhysX bundle fixture'
        directory = self.installer_dir / 'PhysX'
        directory.mkdir(exist_ok=True)
        installer = directory / 'PhysX_SystemSoftware.exe'
        installer.write_bytes(payload)
        script = '''"evaluatorscript" { "0" {
          "install_path" "%s" "compat_installscript" { "run process" {
            "PhysX Version" {
              "process 1" "%%INSTALLDIR%%\\\\physx\\\\PHYSX_SYSTEMSOFTWARE.EXE"
              %s
              "HasRunKey" "HKEY_LOCAL_MACHINE\\\\SOFTWARE\\\\AGEIA Technologies"
              "MinimumHasRunValue" "81017" "NoCleanUp" "1"
            }
            "Other step" { "process 1" "%%INSTALLDIR%%\\\\other.exe" }
          } }
        } }''' % (self.installer_dir, '' if command is None else
                  '"command 1" "%s"' % command)
        self.path.write_text(script)
        return installer, hashlib.sha256(payload).hexdigest()

    def test_verified_physx_runs_quietly_and_preserves_steam_completion_checks(self):
        for command in (None, ''):
            installer, digest = self.physx_fixture(command)
            with patch.dict(PREPARE.__globals__, QUIET_PHYSX_SHA256=digest):
                self.assertTrue(self.prepare())
                tokens = COMPAT['tokenize'](self.path.read_text())
                decoded = [COMPAT['vdf_value'](t) for t in tokens if t.startswith('"')]
                self.assertIn('/qn', decoded)
                self.assertIn(r'%INSTALLDIR%\physx\PHYSX_SYSTEMSOFTWARE.EXE', decoded)
                self.assertIn('81017', decoded)
                self.assertIn(r'HKEY_LOCAL_MACHINE\SOFTWARE\AGEIA Technologies', decoded)
                self.assertIn('Other step', decoded)
                before = self.path.read_bytes()
                self.assertFalse(self.prepare())
                self.assertEqual(before, self.path.read_bytes())

    def test_physx_other_versions_and_existing_commands_are_untouched(self):
        installer, digest = self.physx_fixture()
        before = self.path.read_bytes()
        self.assertFalse(self.prepare())  # Fixture is not the real bundle's hash.
        self.assertEqual(before, self.path.read_bytes())
        for command in ('/uninstall', '/custom', '/s', '/qn'):
            self.physx_fixture(command)
            before = self.path.read_bytes()
            with patch.dict(PREPARE.__globals__, QUIET_PHYSX_SHA256=digest):
                self.assertFalse(self.prepare())
            self.assertEqual(before, self.path.read_bytes())

    def test_physx_missing_or_traversing_installer_keeps_original(self):
        installer, digest = self.physx_fixture()
        installer.unlink()
        before = self.path.read_bytes()
        self.assertFalse(self.prepare())
        self.assertEqual(before, self.path.read_bytes())
        self.physx_fixture()
        self.path.write_text(self.path.read_text().replace('physx\\\\', '..\\\\'))
        before = self.path.read_bytes()
        with patch.dict(PREPARE.__globals__, QUIET_PHYSX_SHA256=digest):
            self.assertFalse(self.prepare())
        self.assertEqual(before, self.path.read_bytes())


if __name__ == '__main__':
    unittest.main()
