import importlib.util
from pathlib import Path
import sys
import json
import hashlib
import uuid
import tempfile
import unittest
import io
import contextlib

spec = importlib.util.spec_from_file_location('smoke', Path(__file__).with_name('run_phase2_server_smoke.py'))
smoke = importlib.util.module_from_spec(spec); spec.loader.exec_module(smoke)


class SmokeMonitorTest(unittest.TestCase):
    def run_fake(self, code, timeout=5, grace=1, echo=False):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            return smoke.run_process([sys.executable, '-u', '-c', code], root, root / 'log.txt',
                timeout=timeout, stop_grace=grace, echo=echo)

    def test_windows_console_encoding_cannot_block_child_pipe(self):
        code = '''import sys
sys.stdout.buffer.write(b'JAR f\\xfcr den Serverstart wird erzeugt\\n')
sys.stdout.flush()
for i in range(200): print('Unpacking library: ' + 'x' * 100, flush=True)
print('Done (1s)!', flush=True)
for line in sys.stdin:
    if line.strip() == 'shop test status': print('Isolierter Phase-2-Testbetrieb', flush=True)
    if line.strip() == 'stop': break
'''
        with io.TextIOWrapper(io.BytesIO(), encoding='cp1252', errors='strict') as console:
            with contextlib.redirect_stdout(console):
                result = self.run_fake(code, echo=True)
        self.assertTrue(result['success'])
        self.assertFalse(result['timedOut'])
        self.assertIsNone(result['monitorError'])

    def test_ready_status_and_stop(self):
        result = self.run_fake('''import sys
print('[Server thread/INFO]: Done (0.5s)! For help, type help', flush=True)
for line in sys.stdin:
    if line.strip() == 'shop test status': print('Isolierter Phase-2-Testbetrieb', flush=True)
    if line.strip() == 'stop': break
''')
        self.assertTrue(result['success'])
        self.assertTrue(result['stopRequested'])

    def test_exit_without_ready_is_not_success(self):
        self.assertFalse(self.run_fake("print('EULA missing')")['success'])

    def test_mixin_error_cannot_pass(self):
        result = self.run_fake("print('InvalidMixinException'); print('startup failed')")
        self.assertFalse(result['success']); self.assertTrue(result['mixinErrors'])

    def test_timeout_stops_own_child(self):
        result = self.run_fake('import time; time.sleep(10)', timeout=0.1, grace=0.1)
        self.assertTrue(result['timedOut']); self.assertTrue(result['forcedTermination'])
        self.assertFalse(result['success'])

    def test_missing_scope_not_success(self):
        result = self.run_fake('''import sys
print('Done (1s)!', flush=True)
for line in sys.stdin:
    if line.strip() == 'stop': break
''')
        self.assertTrue(result['serverReady']); self.assertFalse(result['success'])

    def test_missing_server_files_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(OSError): smoke.validate(Path(temp))

    def fixture(self, root):
        # Synthetic data for scope validation; not executable jars or a Minecraft server.
        (root / 'mods').mkdir(); (root / 'test-world').mkdir()
        players = [str(uuid.uuid4()), str(uuid.uuid4())]
        (root / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25566\n'
            'level-name=test-world\nonline-mode=true\nwhite-list=true\nenforce-whitelist=true\n'
            'enable-rcon=false\nenable-query=false\nmax-players=2\n')
        (root / 'eula.txt').write_text('eula=true\n')
        (root / 'whitelist.json').write_text(json.dumps([{'uuid': p, 'name': 'TestUser'} for p in players]))
        (root / 'test-world/.dulliehausen-phase2-test.json').write_text(json.dumps(dict(
            schema=1, purpose='isolated-phase2-test', worldPath=str(root / 'test-world'), testers=players)))
        (root / 'mods/test.jar').write_bytes(b'fixture')
        (root / 'mods/api.jar').write_bytes(b'fixture')
        (root / 'fabric-server-launch.jar').write_bytes(b'fixture')
        (root / 'phase2-build-report.json').write_text(json.dumps(dict(testBuild=True,
            jar='test.jar', sha256=hashlib.sha256(b'fixture').hexdigest())))

    def test_scope_accepts_prepared_fixture_without_writes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            before = {p: p.read_bytes() for p in root.rglob('*') if p.is_file()}
            self.assertEqual(smoke.validate(root), root)
            self.assertEqual(before, {p: p.read_bytes() for p in root.rglob('*') if p.is_file()})

    def test_scope_refuses_public_binding(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            props = root / 'server.properties'
            props.write_text(props.read_text().replace('127.0.0.1', '0.0.0.0'))
            with self.assertRaises(ValueError): smoke.validate(root)

    def test_scope_refuses_unaccepted_eula(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root); (root / 'eula.txt').write_text('eula=false')
            with self.assertRaises(ValueError): smoke.validate(root)

    def test_scope_refuses_changed_jar(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root); (root / 'mods/test.jar').write_bytes(b'changed')
            with self.assertRaises(ValueError): smoke.validate(root)

    def test_scope_refuses_other_world(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            file = root / 'test-world/.dulliehausen-phase2-test.json'
            marker = json.loads(file.read_text()); marker['worldPath'] = str(root / 'other-world')
            file.write_text(json.dumps(marker))
            with self.assertRaises(ValueError): smoke.validate(root)


if __name__ == '__main__': unittest.main()
