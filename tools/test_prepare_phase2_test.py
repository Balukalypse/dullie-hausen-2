import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import uuid
import zipfile
from prepare_phase2_test import prepare, source_digest, MARKER


class PrepareTest(unittest.TestCase):
    """Synthetic jar fixtures test validation/setup only, never a successful Fabric build."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.project = self.root / 'project'
        (self.project / 'src/main/java').mkdir(parents=True)
        (self.project / 'src/main/java/Example.java').write_text('class Example {}')
        for name in ('build.gradle', 'settings.gradle'):
            (self.project / name).write_text('// synthetic fixture')
        (self.project / 'gradle.properties').write_text('fabric_api_version=0.165.0+26.3\n')
        self.mod = self.root / 'erzmarken-PHASE2-TEST-fixture.jar'
        self.api = self.root / 'fabric-api.jar'
        self.launcher = self.root / 'launcher.jar'
        self.report = self.root / 'report.json'
        self.target = self.root / 'fresh-server'
        self.testers = [str(uuid.uuid4()) + ':TestOwner', str(uuid.uuid4()) + ':TestBuyer']
        with zipfile.ZipFile(self.mod, 'w') as jar:
            jar.writestr('fabric.mod.json', json.dumps({'id': 'erzmarken', 'version': 'fixture-test',
                'custom': {'dulliehausen:phase2-test': 'true'}}))
            for name in ('de/baluserver/erzmarken/Phase2TestAccess.class',
                         'de/baluserver/erzmarken/mixin/MinecraftServerPlayerDataAccessor.class', 'erzmarken.mixins.json'):
                jar.writestr(name, b'SYNTHETIC-NOT-EXECUTABLE')
        with zipfile.ZipFile(self.api, 'w') as jar:
            jar.writestr('fabric.mod.json', json.dumps({'id': 'fabric-api', 'version': '0.165.0+26.3'}))
        with zipfile.ZipFile(self.launcher, 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nMain-Class: net.fabricmc.installer.ServerLauncher\n')
        self.write_report()

    def tearDown(self):
        self.temp.cleanup()

    def write_report(self, **changes):
        report = {'schema': 1, 'task': 'build', 'testBuild': True, 'version': 'fixture-test',
                  'minecraft': '26.3', 'javaRelease': 25, 'gradle': '9.7.1', 'jar': self.mod.name,
                  'sha256': hashlib.sha256(self.mod.read_bytes()).hexdigest(), 'sourceDigest': source_digest(self.project)}
        report.update(changes)
        self.report.write_text(json.dumps(report), encoding='utf-8')

    def run_prepare(self, **kwargs):
        return prepare(self.project, self.mod, self.report, self.launcher, self.api, self.target, self.testers, **kwargs)

    def test_dry_run_writes_nothing(self):
        result = self.run_prepare(dry_run=True)
        self.assertTrue(result['dryRun']); self.assertFalse(self.target.exists())

    def test_new_world_is_scoped_and_eula_not_accepted(self):
        self.run_prepare()
        self.assertEqual((self.target / 'eula.txt').read_text().splitlines()[-1], 'eula=false')
        marker = json.loads((self.target / 'test-world' / MARKER).read_text())
        self.assertEqual(marker['worldPath'], str(self.target / 'test-world'))
        self.assertEqual(len(marker['testers']), 2)
        properties = (self.target / 'server.properties').read_text()
        for required in ('server-ip=127.0.0.1', 'server-port=25566', 'white-list=true', 'online-mode=true'):
            self.assertIn(required, properties)
        self.assertIn('-Ddulliehausen.phase2Test=true', (self.target / 'START-PHASE2-TEST.ps1').read_text())
        self.assertFalse((self.target / 'test-world/level.dat').exists())
        self.assertFalse((self.target / 'ops.json').exists())

    def test_existing_world_never_overwritten(self):
        self.target.mkdir(); sentinel = self.target / 'level.dat'; sentinel.write_bytes(b'private world')
        with self.assertRaises(ValueError): self.run_prepare()
        self.assertEqual(sentinel.read_bytes(), b'private world')

    def test_old_sources_rejected_before_writes(self):
        (self.project / 'src/main/java/Example.java').write_text('class Changed {}')
        with self.assertRaises(ValueError): self.run_prepare()
        self.assertFalse(self.target.exists())

    def test_modified_jar_rejected(self):
        with zipfile.ZipFile(self.mod, 'a') as jar: jar.writestr('changed.txt', 'changed')
        with self.assertRaises(ValueError): self.run_prepare()
        self.assertFalse(self.target.exists())

    def test_normal_build_rejected(self):
        self.write_report(testBuild=False)
        with self.assertRaises(ValueError): self.run_prepare()
        self.assertFalse(self.target.exists())

    def test_wrong_gradle_rejected(self):
        self.write_report(gradle='9.6.0')
        with self.assertRaises(ValueError): self.run_prepare()

    def test_two_accounts_required(self):
        self.testers.pop()
        with self.assertRaises(ValueError): self.run_prepare()

    def test_duplicate_account_rejected(self):
        self.testers[1] = self.testers[0]
        with self.assertRaises(ValueError): self.run_prepare()

    def test_wrong_api_rejected(self):
        with zipfile.ZipFile(self.api, 'w') as jar:
            jar.writestr('fabric.mod.json', json.dumps({'id': 'fabric-api', 'version': 'wrong'}))
        with self.assertRaises(ValueError): self.run_prepare()

    def test_client_installer_rejected(self):
        with zipfile.ZipFile(self.launcher, 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Main-Class: net.fabricmc.installer.Main\n')
        with self.assertRaises(ValueError): self.run_prepare()


if __name__ == '__main__':
    unittest.main()
