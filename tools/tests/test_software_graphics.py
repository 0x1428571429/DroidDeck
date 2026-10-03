import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SESSION = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/bannerlator-session'
GRAPHICS_ENV = ('VK_DRIVER_FILES', 'VK_ICD_FILENAMES', 'LIBGL_ALWAYS_SOFTWARE',
                'GALLIUM_DRIVER', 'MESA_LOADER_DRIVER_OVERRIDE', 'SDL_VIDEODRIVER')


class SoftwareGraphicsTest(unittest.TestCase):
    def launch(self, **settings):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            binary = home / 'bin'
            binary.mkdir()
            capture = home / 'capture.json'
            gamescope = binary / 'gamescope'
            gamescope.write_text(f'#!{sys.executable}\nimport json, os, sys\n'
                                 'from pathlib import Path\n'
                                 'Path(os.environ["TEST_CAPTURE"]).write_text(json.dumps({"argv": sys.argv[1:], "env": dict(os.environ)}))\n')
            gamescope.chmod(0o755)
            for name in ('wayland-info', 'mangoapp'):
                probe = binary / name
                probe.write_text('#!/bin/sh\nexit 0\n')
                probe.chmod(0o755)
            library = home / 'custom.so'
            library.touch()
            manifest = home / 'icd.json'
            manifest.write_text(json.dumps({'ICD': {'library_path': str(library)}}))
            env = {'PATH': str(binary) + os.pathsep + os.defpath, 'HOME': str(home),
                   'TEST_CAPTURE': str(capture), 'BL_WIDTH': '960', 'BL_HEIGHT': '544',
                   'BL_STEAMDECK': '1', 'BL_REFRESH': '120', 'BL_HDR': '1'}
            env.update(settings)
            if env.pop('TEST_CUSTOM_DRIVER', ''):
                env['BL_VK_DRIVER'] = str(manifest)
            subprocess.run([shutil.which('bash'), str(SESSION), 'steam', 'argument with spaces'],
                           env=env, check=True, capture_output=True, text=True)
            result = json.loads(capture.read_text())
            config = result['env'].get('MANGOHUD_CONFIGFILE')
            if config:
                Path(config).unlink()
            return result, str(manifest)

    def test_default_keeps_accelerated_backend_overlay_and_session_options(self):
        result, _ = self.launch(VK_DRIVER_FILES='/selected/turnip.json', GALLIUM_DRIVER='zink')
        args, env = result['argv'], result['env']
        self.assertEqual(args[:2], ['--backend', 'wayland'])
        self.assertEqual(env['VK_DRIVER_FILES'], '/selected/turnip.json')
        self.assertEqual(env['GALLIUM_DRIVER'], 'zink')
        self.assertNotIn('LIBGL_ALWAYS_SOFTWARE', env)
        self.assertNotIn('MESA_LOADER_DRIVER_OVERRIDE', env)
        self.assertNotIn('SDL_VIDEODRIVER', env)
        self.assertIn('--mangoapp', args)
        self.assertIn('--hdr-enabled', args)
        self.assertEqual(args[args.index('-r') + 1], '120')
        self.assertEqual(args[-1], 'argument with spaces')

    def test_only_explicit_one_selects_software(self):
        for value in ('', '0', 'true', 'yes'):
            with self.subTest(value=value):
                result, _ = self.launch(BL_STEAM_SOFTWARE=value)
                self.assertEqual(result['argv'][:2], ['--backend', 'wayland'])
                self.assertNotIn('LIBGL_ALWAYS_SOFTWARE', result['env'])
                self.assertIn('--mangoapp', result['argv'])
        result, _ = self.launch(BL_STEAM_SOFTWARE='1')
        env = result['env']
        self.assertEqual(result['argv'][:2], ['--backend', 'sdl'])
        self.assertNotIn('--mangoapp', result['argv'])
        self.assertEqual(env['VK_DRIVER_FILES'], '/usr/share/vulkan/icd.d/lvp_icd.json')
        self.assertEqual(env['VK_ICD_FILENAMES'], env['VK_DRIVER_FILES'])
        self.assertEqual(env['LIBGL_ALWAYS_SOFTWARE'], '1')
        self.assertEqual(env['GALLIUM_DRIVER'], 'llvmpipe')
        self.assertEqual(env['SDL_VIDEODRIVER'], 'wayland')

    def test_custom_driver_is_preserved_except_when_software_is_requested(self):
        for software in ('0', '1'):
            result, manifest = self.launch(TEST_CUSTOM_DRIVER='1', BL_STEAM_SOFTWARE=software)
            expected = manifest if software == '0' else '/usr/share/vulkan/icd.d/lvp_icd.json'
            self.assertEqual(result['env']['VK_DRIVER_FILES'], expected)
            self.assertEqual(result['env']['VK_ICD_FILENAMES'], expected)

    def test_invalid_custom_driver_does_not_replace_inherited_selection(self):
        result, _ = self.launch(BL_VK_DRIVER='/missing/manifest.json', VK_DRIVER_FILES='/selected/turnip.json')
        self.assertEqual(result['env']['VK_DRIVER_FILES'], '/selected/turnip.json')
        self.assertEqual(result['argv'][:2], ['--backend', 'wayland'])


if __name__ == '__main__':
    unittest.main()
