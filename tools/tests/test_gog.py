import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

from test_steam_games import BIN, imports, load, shortcuts

gog = load('droiddeck-gog')

# Stands in for gogdl: answers info/auth like it, and "downloads" by writing the game's info file.
FAKE_GOGDL = r'''
import json, os, sys
args = sys.argv[1:]
auth = args[args.index("--auth-config-path") + 1]
args = args[args.index("--auth-config-path") + 2:]
cmd = args[0]
if cmd == "auth":
    if "--code" in args:
        print(json.dumps({"error": True}) if args[-1] == "bad" else json.dumps({"access_token": "A", "refresh_token": "R", "user_id": "7", "expires_in": 3600, "loginTime": 1000}))
    elif os.path.exists(auth):
        print(json.dumps({"access_token": "A", "refresh_token": "R", "user_id": "7", "expires_in": 3600, "loginTime": 1000}))
    else:
        print("null")
elif cmd == "info":
    print("[MAIN] INFO: working", file=sys.stderr)
    print(json.dumps({"folder_name": "Example", "languages": ["de-DE", "en-US"], "buildId": "b1",
                      "size": {"*": {"download_size": 100, "disk_size": 200}, "en-US": {"download_size": 10, "disk_size": 20},
                               "de-DE": {"download_size": 5, "disk_size": 6}}}))
elif cmd == "download":
    base = args[args.index("--path") + 1]
    if os.environ.get("FAIL"):
        print("[DOWNLOAD] ERROR: disk full", file=sys.stderr)
        sys.exit(1)
    for p in ("0.00 0/100", "50.10 50/100", "50.90 51/100", "100.00 100/100"):
        print("[PROGRESS] INFO: = Progress: %s, Running for: 00:00:01, ETA: 00:00:01" % p, file=sys.stderr)
    target = os.path.join(base, "Example")
    os.makedirs(target, exist_ok=True)
    open(os.path.join(target, "goggame-%s.info" % args[1]), "w").write("{}")
'''


class GogHelperTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        fake = self.root / 'gogdl'
        fake.write_text(FAKE_GOGDL)
        patcher = patch.object(gog, 'GOGDL', str(fake))
        patcher.start()
        self.addCleanup(patcher.stop)
        self.auth = str(self.root / 'gog/auth.json')

    def run_helper(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            status = gog.main(['droiddeck-gog'] + list(args))
        lines = [json.loads(line) for line in out.getvalue().splitlines() if line.startswith('{')]
        return status, lines, out.getvalue()

    def test_token_output_never_carries_the_refresh_token(self):
        Path(self.auth).parent.mkdir()
        Path(self.auth).write_text('{}')
        status, lines, raw = self.run_helper('token', self.auth)
        self.assertEqual(0, status)
        self.assertEqual({'e': 'token', 'access_token': 'A', 'user_id': '7', 'expires': 4600}, lines[0])
        self.assertNotIn('"R"', raw)

    def test_signed_out_without_the_auth_file(self):
        status, lines, _ = self.run_helper('token', self.auth)
        self.assertEqual(1, status)
        self.assertEqual('error', lines[0]['e'])

    def test_login_reports_success_without_tokens(self):
        status, lines, raw = self.run_helper('login', self.auth, 'good')
        self.assertEqual((0, [{'e': 'done', 'user_id': '7'}]), (status, lines))
        self.assertNotIn('access_token', raw)
        status, lines, _ = self.run_helper('login', self.auth, 'bad')
        self.assertEqual(1, status)

    def test_sizes_are_shared_depots_plus_one_language(self):
        status, lines, _ = self.run_helper('info', self.auth, '42')
        self.assertEqual(0, status)
        self.assertEqual(dict(e='info', folder='Example', language='en-US', download_size=110, disk_size=220, build='b1'), lines[0])

    def test_install_reports_progress_once_per_percent_and_marks_the_folder(self):
        base = self.root / 'GOG'
        status, lines, _ = self.run_helper('install', self.auth, '42', str(base))
        self.assertEqual(0, status)
        self.assertEqual([0, 50, 100], [line['percent'] for line in lines if line['e'] == 'progress'])
        self.assertEqual({'e': 'done', 'dir': str(base / 'Example')}, lines[-1])
        self.assertEqual('42', json.loads((base / 'Example' / gog.MARKER).read_text())['id'])

    def test_failed_download_passes_on_gogdls_error(self):
        with patch.dict('os.environ', {'FAIL': '1'}):
            status, lines, _ = self.run_helper('install', self.auth, '42', str(self.root / 'GOG'))
        self.assertEqual(1, status)
        self.assertEqual({'e': 'error', 'message': 'disk full'}, lines[-1])


class StoreShortcutsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.steam = self.root / 'Steam'
        self.acct = self.steam / 'userdata/123'
        (self.acct / 'config').mkdir(parents=True)
        folder = self.root / 'GOG/Example'
        folder.mkdir(parents=True)
        (folder / 'Example.exe').write_bytes(b'game')
        self.game = dict(name='Example', exe=str(folder / 'Example.exe'), folder=str(folder), dir=str(folder),
                         appid=0x87654321, args='-windowed', store='gog')

    def write(self, *games):
        listing = self.root / 'games.json'
        listing.write_text(json.dumps(list(games)))
        subprocess.run(['python3', str(BIN / 'droiddeck-steam-shortcuts'), str(self.steam), str(listing)], check=True, capture_output=True)
        return list(shortcuts.parse((self.acct / 'config/shortcuts.vdf').read_bytes())['shortcuts'].values())

    def test_store_arguments_become_launch_options(self):
        self.assertEqual('-windowed', self.write(self.game)[0]['LaunchOptions'])

    def test_launch_options_set_in_steam_are_kept(self):
        self.write(self.game)
        path = self.acct / 'config/shortcuts.vdf'
        entries = shortcuts.parse(path.read_bytes())['shortcuts']
        entries['0']['LaunchOptions'] = '-dx11'
        path.write_bytes(shortcuts.write({'shortcuts': entries}))
        self.assertEqual('-dx11', self.write(self.game)[0]['LaunchOptions'])

    def test_store_games_are_never_routed_to_an_owned_steam_title(self):
        imports.save_json(self.acct / 'config' / imports.STATE,
                          dict(account='123', owned={'42': True}, candidates={self.game['folder']: 42},
                               sources={self.game['folder']: imports.source_stamp(self.game)}))
        games, routes = imports.route(self.steam, self.acct, [self.game])
        self.assertEqual(([self.game], {}), (games, routes))


if __name__ == '__main__':
    unittest.main()
