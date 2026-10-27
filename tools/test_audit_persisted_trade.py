import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from audit_persisted_trade import audit, AuditError

TX = '12345678-1234-1234-1234-123456789abc'
PLAYER = '87654321-1234-1234-1234-123456789abc'
ITEM = 'minecraft:diamond'

class AuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.world = Path(self.temp.name)
        folder = self.world / 'data' / 'erzmarken-shop-journal'
        folder.mkdir(parents=True)
        (folder / (TX + '.pending.json')).write_text(json.dumps(dict(id=TX, playerId=PLAYER, itemId=ITEM,
            quantity=3, total=15, stage='SHOP_DEBITED')), encoding='utf-8')
        self.write('before', 100, 2500, 4)
        self.write('after', 115, 2485, 7)

    def tearDown(self): self.temp.cleanup()

    def write(self, moment, player_balance, shop_balance, stock):
        directory = self.world / 'data' / 'erzmarken-shop-evidence' / TX / moment
        directory.mkdir(parents=True, exist_ok=True)
        shop = json.dumps(dict(shopWallet=shop_balance, wholesaleStock={ITEM:stock})).encode()
        contents = [b'NBT-bytes', json.dumps({'players': {PLAYER: {'balance': player_balance}}}).encode(), shop, shop]
        lines = [f'transaction={TX}', f'moment={moment.upper()}']
        for source, contents_bytes in zip(('PLAYERDATA','PLAYER_TOKENS','SHOP_REGISTER','SHOP_WAREHOUSE'), contents):
            (directory / (source.lower()+'.bin')).write_bytes(contents_bytes)
            lines.append(f'{source}|{len(contents_bytes)}|{hashlib.sha256(contents_bytes).hexdigest()}')
        (directory / 'manifest.txt').write_text('\n'.join(lines)+'\n')

    def test_success_but_never_approval(self):
        result = audit(self.world, TX)
        self.assertEqual(result['accountingResult'], 'JSON_DELTAS_MATCH')
        self.assertFalse(result['transactionApproved'])
        self.assertEqual(result['inventoryResult'], 'NOT_VERIFIED_NBT')

    def test_changed_player_balance_detected(self):
        self.write('after', 116, 2485, 7)
        self.assertEqual(audit(self.world, TX)['accountingResult'], 'JSON_DELTAS_CONFLICT')

    def test_changed_shop_wallet_detected(self):
        self.write('after', 115, 2484, 7)
        self.assertEqual(audit(self.world, TX)['accountingResult'], 'JSON_DELTAS_CONFLICT')

    def test_changed_stock_detected(self):
        self.write('after', 115, 2485, 8)
        self.assertEqual(audit(self.world, TX)['accountingResult'], 'JSON_DELTAS_CONFLICT')

    def test_tampered_snapshot_rejected(self):
        path = self.world / 'data' / 'erzmarken-shop-evidence' / TX / 'after' / 'player_tokens.bin'
        path.write_bytes(b'{"players":{}}')
        with self.assertRaises(AuditError): audit(self.world, TX)

    def test_missing_snapshot_rejected(self):
        path = self.world / 'data' / 'erzmarken-shop-evidence' / TX / 'before' / 'manifest.txt'
        path.unlink()
        with self.assertRaises(AuditError): audit(self.world, TX)

    def test_invalid_id_rejected(self):
        with self.assertRaises(AuditError): audit(self.world, '../../other')

    def test_boolean_balance_rejected(self):
        # A bool is an int subclass in Python but never a valid balance.
        folder = self.world / 'data' / 'erzmarken-shop-evidence' / TX / 'after'
        blob = json.dumps({'players': {PLAYER: {'balance': True}}}).encode()
        (folder / 'player_tokens.bin').write_bytes(blob)
        lines = (folder / 'manifest.txt').read_text().splitlines()
        lines[3] = f'PLAYER_TOKENS|{len(blob)}|{hashlib.sha256(blob).hexdigest()}'
        (folder / 'manifest.txt').write_text('\n'.join(lines)+'\n')
        with self.assertRaises(AuditError): audit(self.world, TX)

    def test_shop_copies_differ_rejected(self):
        folder = self.world / 'data' / 'erzmarken-shop-evidence' / TX / 'after'
        blob = b'{"shopWallet":2,"wholesaleStock":{}}'
        (folder / 'shop_warehouse.bin').write_bytes(blob)
        lines = (folder / 'manifest.txt').read_text().splitlines()
        lines[5] = f'SHOP_WAREHOUSE|{len(blob)}|{hashlib.sha256(blob).hexdigest()}'
        (folder / 'manifest.txt').write_text('\n'.join(lines)+'\n')
        with self.assertRaises(AuditError): audit(self.world, TX)

if __name__ == '__main__': unittest.main()
