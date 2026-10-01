from pathlib import Path
import runpy
import tempfile
import unittest

RESTORE = runpy.run_path(str(Path(__file__).resolve().parents[1] /
                            'linuxfs/overlay/usr/local/bin/bannerlator-restore-redists'))


class RestoreRedistsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def prefix(self, path):
        path.mkdir(parents=True)
        reg = path / 'system.reg'
        reg.write_text('WINE REGISTRY Version 2\n\n' +
            '[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2019] 1\n'
            '"x86"=dword:00000001\n"x64"=dword:00000001\n"OtherData"="preserved"\n\n'
            '[Software\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\DirectX\\\\Jun2010] 1\n'
            '"dxsetup"=dword:00000001\n\n'
            '[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\.NET\\\\4.8] 1\n'
            '"4.8"=dword:00000001\n\n'
            '[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\XNA\\\\4.0] 1\n'
            '"4.0"=dword:00000001\n\n'
            '[Software\\\\Valve\\\\Steam\\\\Apps\\\\50620] 1\n'
            '"Accessories"=dword:00000001\n\n'
            '[Software\\\\Microsoft\\\\Installer\\\\Products\\\\NativeProduct] 1\n'
            '"Installed"=dword:00000001\n')
        (path / '.bannerlator-redists').write_text('old checksum')
        return reg

    def test_migration_preserves_proton_defaults_native_products_and_game_checks(self):
        reg = self.prefix(self.root / 'pfx')
        stat = reg.stat()
        self.assertTrue(RESTORE['restore_prefix'](reg.parent))
        text = reg.read_text()
        for flag in ('"x86"', '"x64"', '"dxsetup"'):
            self.assertNotIn(flag + '=dword:00000001', text)
        for flag in ('"4.8"', '"4.0"', '"Accessories"', '"Installed"'):
            self.assertIn(flag + '=dword:00000001', text)
        self.assertIn('"OtherData"="preserved"', text)
        self.assertEqual(stat.st_mtime_ns, reg.stat().st_mtime_ns)
        self.assertEqual(stat.st_mode, reg.stat().st_mode)
        # Once Steam has really installed a prerequisite, a later startup keeps its result.
        with reg.open('a') as f:
            f.write('\n[Software\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2022] 2\n'
                    '"x86"=dword:00000001\n')
        installed = reg.read_bytes()
        self.assertFalse(RESTORE['restore_prefix'](reg.parent))
        self.assertEqual(installed, reg.read_bytes())

    def test_unstamped_prefixes_and_registry_symlinks_are_untouched(self):
        reg = self.prefix(self.root / 'pfx')
        original = reg.read_bytes()
        (reg.parent / '.bannerlator-redists').unlink()
        self.assertFalse(RESTORE['restore_prefix'](reg.parent))
        self.assertEqual(original, reg.read_bytes())
        (reg.parent / '.bannerlator-redists').touch()
        outside = self.root / 'original.reg'
        reg.rename(outside)
        reg.symlink_to(outside)
        self.assertFalse(RESTORE['restore_prefix'](reg.parent))
        self.assertEqual(original, outside.read_bytes())

    def test_primary_secondary_and_both_template_architectures_are_migrated(self):
        steam, secondary = self.root / 'Steam', self.root / 'SD'
        paths = [steam / 'steamapps/compatdata/1/pfx',
                 secondary / 'steamapps/compatdata/2/pfx',
                 steam / 'steamapps/common/Proton 11/files/share/default_pfx_arm64',
                 secondary / 'steamapps/common/Proton Experimental/files/share/default_pfx',
                 steam / 'compatibilitytools.d/GE/files/share/default_pfx_arm64']
        for path in paths:
            self.prefix(path)
        self.assertEqual(5, RESTORE['restore'](steam, [secondary]))
        self.assertEqual(0, RESTORE['restore'](steam, [secondary]))

    def test_invalid_registry_keeps_migration_stamp_for_retry(self):
        reg = self.prefix(self.root / 'pfx')
        reg.write_text('incomplete registry')
        with self.assertRaises(ValueError):
            RESTORE['restore_prefix'](reg.parent)
        self.assertTrue((reg.parent / '.bannerlator-redists').is_file())
        self.assertEqual('incomplete registry', reg.read_text())


if __name__ == '__main__':
    unittest.main()
