import json
from pathlib import Path
import unittest
from test_audit_persisted_trade import AuditTest, TX, PLAYER, ITEM
from audit_world_state import inspect, scan, playerdata_path, AuditError


class WorldStateTest(AuditTest):
    def test_current_playerdata_layout(self):
        path = self.world / 'players' / 'data' / (PLAYER + '.dat')
        path.parent.mkdir(parents=True)
        path.write_bytes(b'current')
        self.assertEqual(playerdata_path(self.world, PLAYER), path)

    def test_ambiguous_playerdata_layout_rejected(self):
        self.prepare_current()
        path = self.world / 'players' / 'data' / (PLAYER + '.dat')
        path.parent.mkdir(parents=True)
        path.write_bytes(b'current')
        with self.assertRaises(AuditError): playerdata_path(self.world, PLAYER)

    def test_no_stale_legacy_fallback_after_migration(self):
        self.prepare_current()
        (self.world / 'players' / 'data').mkdir(parents=True)
        with self.assertRaises(AuditError): playerdata_path(self.world, PLAYER)

    def test_malformed_current_directory_no_legacy_fallback(self):
        self.prepare_current()
        (self.world / 'players').write_bytes(b'not a directory')
        with self.assertRaises(AuditError): playerdata_path(self.world, PLAYER)
    # Inherit snapshot fixture; tests inherited checks run too.
    def prepare_current(self):
        world = self.world
        (world / 'playerdata').mkdir(exist_ok=True)
        (world / 'playerdata' / (PLAYER + '.dat')).write_bytes(b'invalid-nbt')
        (world / 'data' / 'erzmarken.json').write_text(json.dumps({'players': {PLAYER: {'balance': 115}}}))
        (world / 'data' / 'erzmarken-shop.json').write_text(json.dumps({'shopWallet': 2485, 'wholesaleStock': {ITEM: 7}}))

    def test_world_missing_sources_reported_not_approved(self):
        report = inspect(self.world, TX)
        self.assertFalse(report['transactionApproved'])
        self.assertFalse(report['currentWorldState']['allCurrentSourcesRead'])
        self.assertIn('PLAYERDATA', report['currentWorldState']['errors'])

    def test_current_world_values_are_diagnostic(self):
        self.prepare_current()
        result = inspect(self.world, TX)
        self.assertEqual(result['currentWorldState']['values']['playerTokens'], 115)
        self.assertIn('PLAYERDATA', result['currentWorldState']['errors'])
        self.assertIsNone(result['currentWorldState']['matchesAfterSnapshot'])
        self.assertFalse(result['transactionApproved'])

    def test_changed_world_value_not_called_fraud(self):
        self.prepare_current()
        (self.world / 'data' / 'erzmarken.json').write_text(json.dumps({'players': {PLAYER: {'balance': 120}}}))
        result = inspect(self.world, TX)
        self.assertFalse(result['currentWorldState']['comparisonsWithAfter']['playerTokens'])
        self.assertEqual(result['currentWorldState']['status'], 'CURRENT_STATE_INFORMATIONAL_ONLY')


    def test_complete_current_world_is_compared_without_approval(self):
        from test_nbt_inventory_audit import nbt
        self.prepare_current()
        (self.world / 'playerdata' / (PLAYER + '.dat')).write_bytes(nbt(5))
        result = inspect(self.world, TX)
        state = result['currentWorldState']
        self.assertFalse(state['allCurrentSourcesRead'])
        self.assertEqual(state['values']['inventoryCount'], 5)
        self.assertIsNone(state['matchesAfterSnapshot'])  # Snapshot NBT in this fixture is invalid.
        self.assertFalse(result['transactionApproved'])

    def test_symlink_world_source_rejected(self):
        self.prepare_current()
        link = self.world / 'data' / 'erzmarken.json'
        link.unlink()
        try:
            link.symlink_to(self.world / 'data' / 'erzmarken-shop.json')
        except OSError as exc:
            if getattr(exc, 'winerror', None) != 1314:
                raise
            self.skipTest('Windows does not grant symlink creation rights')
        self.assertIn('PLAYER_TOKENS', inspect(self.world, TX)['currentWorldState']['errors'])

    def test_scan_includes_unverified_and_valid(self):
        (self.world / 'data' / 'erzmarken-shop-journal' / 'bad.pending.json').write_text('{}')
        result = scan(self.world)
        self.assertEqual(result['inspected'], 2)
        self.assertFalse(result['transactionApproved'])
        self.assertTrue(any(x.get('result') == 'NOT_VERIFIED' for x in result['transactions']))

    def test_symlink_journal_folder_rejected(self):
        import shutil
        directory = self.world / 'data' / 'erzmarken-shop-journal'
        shutil.rmtree(directory)
        try:
            directory.symlink_to(self.world / 'data' / 'erzmarken-shop-evidence', target_is_directory=True)
        except OSError as exc:
            if getattr(exc, 'winerror', None) != 1314:
                raise
            self.skipTest('Windows does not grant symlink creation rights')
        with self.assertRaises(AuditError): scan(self.world)

if __name__ == '__main__':
    unittest.main()
