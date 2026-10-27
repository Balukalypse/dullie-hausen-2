import gzip
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from nbt_inventory_audit import count_item, NbtError
from audit_persisted_trade import audit

TX='12345678-1234-1234-1234-123456789abc'
PLAYER='87654321-1234-1234-1234-123456789abc'
ITEM='minecraft:diamond'


def name(s):
    data=s.encode('utf-8')
    return struct.pack('>H',len(data))+data


def tag(t, key, payload):
    return bytes([t])+name(key)+payload


def item(slot, count, identifier=ITEM):
    return (tag(1,'Slot',struct.pack('>b',slot))+tag(8,'id',name(identifier))+
            tag(1,'count',struct.pack('>b',count))+b'\0')


def nbt(count, *, compression=True):
    entries=[item(0,count)] if count else []
    inventory=tag(9,'Inventory',b'\x0a'+struct.pack('>i',len(entries))+b''.join(entries))
    raw=b'\x0a'+name('')+inventory+b'\0'
    return gzip.compress(raw) if compression else raw


class NbtAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.world=Path(self.temp.name)
        j=self.world/'data'/'erzmarken-shop-journal'
        j.mkdir(parents=True)
        (j/(TX+'.pending.json')).write_text(json.dumps({'id':TX,'playerId':PLAYER,'itemId':ITEM,'quantity':3,'total':15,'stage':'SHOP_DEBITED'}))
        self.snapshot('before',100,2500,4,8)
        self.snapshot('after',115,2485,7,5)

    def tearDown(self): self.temp.cleanup()

    def snapshot(self,moment,tokens,wallet,stock,items):
        d=self.world/'data'/'erzmarken-shop-evidence'/TX/moment
        d.mkdir(parents=True,exist_ok=True)
        shop=json.dumps({'shopWallet':wallet,'wholesaleStock':{ITEM:stock}}).encode()
        values=[nbt(items),json.dumps({'players':{PLAYER:{'balance':tokens}}}).encode(),shop,shop]
        manifest=[f'transaction={TX}',f'moment={moment.upper()}']
        for source,blob in zip(('PLAYERDATA','PLAYER_TOKENS','SHOP_REGISTER','SHOP_WAREHOUSE'),values):
            (d/(source.lower()+'.bin')).write_bytes(blob)
            manifest.append(f'{source}|{len(blob)}|{hashlib.sha256(blob).hexdigest()}')
        (d/'manifest.txt').write_text('\n'.join(manifest)+'\n')

    def test_valid_gzip_nbt(self): self.assertEqual(count_item(nbt(8),ITEM),8)
    def test_valid_raw_nbt(self): self.assertEqual(count_item(nbt(5,compression=False),ITEM),5)
    def test_other_item_is_not_counted(self):
        self.assertEqual(count_item(nbt(5), 'minecraft:stone'),0)
    def test_invalid_nbt_rejected(self):
        with self.assertRaises(NbtError): count_item(b'garbage',ITEM)
    def test_truncated_gzip_rejected(self):
        with self.assertRaises(NbtError): count_item(nbt(4)[:-8],ITEM)
    def test_gzip_bomb_rejected(self):
        with self.assertRaises(NbtError): count_item(gzip.compress(b'x'*(16*1024*1024+1)),ITEM)
    def test_actual_inventory_delta(self):
        r=audit(self.world,TX)
        self.assertEqual(r['inventoryResult'],'NBT_DELTA_MATCH')
        self.assertFalse(r['transactionApproved'])
    def test_inventory_delta_conflict(self):
        self.snapshot('after',115,2485,7,7)
        self.assertEqual(audit(self.world,TX)['inventoryResult'],'NBT_DELTA_CONFLICT')
    def test_valid_accounting_does_not_imply_inventory(self):
        self.snapshot('after',115,2485,7,8)
        r=audit(self.world,TX)
        self.assertEqual(r['accountingResult'],'JSON_DELTAS_MATCH')
        self.assertEqual(r['inventoryResult'],'NBT_DELTA_CONFLICT')
    def test_bad_nbt_reported_not_approved(self):
        d=self.world/'data'/'erzmarken-shop-evidence'/TX/'after'
        blob=b'not a valid NBT'
        (d/'playerdata.bin').write_bytes(blob)
        lines=(d/'manifest.txt').read_text().splitlines()
        lines[2]=f'PLAYERDATA|{len(blob)}|{hashlib.sha256(blob).hexdigest()}'
        (d/'manifest.txt').write_text('\n'.join(lines)+'\n')
        r=audit(self.world,TX)
        self.assertEqual(r['inventoryResult'],'NOT_VERIFIED_NBT')
        self.assertFalse(r['transactionApproved'])

if __name__=='__main__': unittest.main()
