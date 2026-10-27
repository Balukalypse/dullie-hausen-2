#!/usr/bin/env python3
"""Read-only offline accounting audit for a buyback's immutable evidence snapshots.

Never approves, retries, cancels or repairs trades. It cannot validate NBT player inventory,
Minecraft server flush timing, or cross-file atomicity. Run on a stopped server copy.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from nbt_inventory_audit import count_item, NbtError

ID_RE = re.compile(r'^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$')
MAX_JSON = 64 * 1024 * 1024
SOURCES = ('PLAYERDATA', 'PLAYER_TOKENS', 'SHOP_REGISTER', 'SHOP_WAREHOUSE')

class AuditError(Exception):
    pass


def ordinary_file(path, max_bytes=MAX_JSON):
    if path.is_symlink() or not path.is_file():
        raise AuditError(f'Datei fehlt oder ist verlinkt: {path.name}')
    if path.stat().st_size > max_bytes:
        raise AuditError(f'Datei zu gross: {path.name}')
    return path.read_bytes()


def load_json(path):
    try:
        return json.loads(ordinary_file(path).decode('utf-8'))
    except (UnicodeError, ValueError) as exc:
        raise AuditError(f'JSON unlesbar: {path.name}') from exc


def read_snapshot(world, txid, moment):
    directory = world / 'data' / 'erzmarken-shop-evidence' / txid / moment
    lines = ordinary_file(directory / 'manifest.txt', 4096).decode('utf-8').splitlines()
    if len(lines) != 6 or lines[:2] != [f'transaction={txid}', f'moment={moment.upper()}']:
        raise AuditError(f'{moment}: Manifest ungueltig')
    blobs = {}
    for source, line in zip(SOURCES, lines[2:]):
        parts = line.split('|')
        if len(parts) != 3 or parts[0] != source or not re.fullmatch(r'[0-9a-f]{64}', parts[2]):
            raise AuditError(f'{moment}: Quellbeleg ungueltig: {source}')
        try:
            expected_size = int(parts[1])
        except ValueError as exc:
            raise AuditError(f'{moment}: Laenge ungueltig') from exc
        blob = ordinary_file(directory / (source.lower() + '.bin'))
        if len(blob) != expected_size or hashlib.sha256(blob).hexdigest() != parts[2]:
            raise AuditError(f'{moment}: Snapshot-Pruefsumme falsch: {source}')
        blobs[source] = blob
    # Shop register + warehouse are currently two copies of the SAME shop JSON.
    if blobs['SHOP_REGISTER'] != blobs['SHOP_WAREHOUSE']:
        raise AuditError(f'{moment}: Shopregister und Lagerkopie widersprechen sich')
    return blobs


def decoded_object(blob, label):
    try:
        obj = json.loads(blob.decode('utf-8'))
    except (ValueError, UnicodeError) as exc:
        raise AuditError(f'{label}: JSON unlesbar') from exc
    if not isinstance(obj, dict):
        raise AuditError(f'{label}: JSON ist kein Objekt')
    return obj


def integer(value, label):
    if type(value) is not int or value < 0 or value > 1_000_000_000:
        raise AuditError(f'{label}: Kein gueltiger nichtnegativer Ganzzahlwert')
    return value


def balances(blobs, player, item):
    tokens = decoded_object(blobs['PLAYER_TOKENS'], 'Tokenkonto')
    shop = decoded_object(blobs['SHOP_REGISTER'], 'Shopdatei')
    players = tokens.get('players')
    if not isinstance(players, dict) or not isinstance(players.get(player), dict):
        raise AuditError('Spielerkonto im Snapshot fehlt')
    stock = shop.get('wholesaleStock')
    if not isinstance(stock, dict):
        raise AuditError('Shoplager im Snapshot fehlt')
    return {
        'playerTokens': integer(players[player].get('balance'), 'Spielertoken'),
        'shopWallet': integer(shop.get('shopWallet'), 'Shopkasse'),
        'shopStock': integer(stock.get(item, 0), 'Shopbestand'),
    }


def audit(world, txid):
    if not ID_RE.fullmatch(txid):
        raise AuditError('Transaktions-ID ungueltig')
    journal = world / 'data' / 'erzmarken-shop-journal' / (txid + '.pending.json')
    entry = load_json(journal)
    player, item, quantity, payment = (entry.get('playerId'), entry.get('itemId'),
                                       entry.get('quantity'), entry.get('total'))
    if entry.get('id') != txid or not isinstance(player, str) or not ID_RE.fullmatch(player) or not isinstance(item, str) or not item or len(item) > 256:
        raise AuditError('Journalidentitaet ungueltig')
    if type(quantity) is not int or not 1 <= quantity <= 64 or type(payment) is not int or not 0 < payment <= 1_000_000_000:
        raise AuditError('Journalmenge/-betrag ungueltig')
    if entry.get('stage') not in ('PREPARED', 'SHOP_DEBITED', 'PLAYER_CREDITED', 'ITEM_REMOVED_UNSAVED'):
        raise AuditError('Journalstatus unbekannt oder nicht offen')
    before_blobs = read_snapshot(world, txid, 'before')
    after_blobs = read_snapshot(world, txid, 'after')
    before = balances(before_blobs, player, item)
    after = balances(after_blobs, player, item)
    try:
        inventory_before = count_item(before_blobs['PLAYERDATA'], item)
        inventory_after = count_item(after_blobs['PLAYERDATA'], item)
        inventory_result = ('NBT_DELTA_MATCH' if inventory_after - inventory_before == -quantity
                            else 'NBT_DELTA_CONFLICT')
        inventory_counts = {'before': inventory_before, 'after': inventory_after,
                            'actualDelta': inventory_after - inventory_before, 'expectedDelta': -quantity}
    except NbtError as exc:
        inventory_result = 'NOT_VERIFIED_NBT'
        inventory_counts = {'reason': str(exc)}
    expected = {'playerTokens': payment, 'shopWallet': -payment, 'shopStock': quantity}
    differences = {key: after[key] - before[key] for key in expected}
    matching = all(differences[k] == expected[k] for k in expected)
    return {
        'transactionId': txid, 'stage': entry['stage'], 'playerId': player, 'itemId': item,
        'quantity': quantity, 'payment': payment, 'before': before, 'after': after,
        'actualDeltas': differences, 'expectedDeltas': expected,
        'accountingResult': 'JSON_DELTAS_MATCH' if matching else 'JSON_DELTAS_CONFLICT',
        'inventoryResult': inventory_result, 'inventoryCounts': inventory_counts,
        'transactionApproved': False,
        'warning': 'Nur Offline-Snapshots. NBT-Pruefung erfasst direkte Inventory-Slots, nicht Containerinhalte; keine Flush-Garantie, Online-Zustandspruefung oder atomare Transaktion. Keine automatische Freigabe.'
    }


def main():
    parser = argparse.ArgumentParser(description='Read-only JSON-Bestandsabgleich, keine Freigabe')
    parser.add_argument('--world', required=True, type=Path, help='Kopie eines gestoppten Weltordners')
    parser.add_argument('--transaction', required=True)
    args = parser.parse_args()
    try:
        result = audit(args.world, args.transaction)
    except (AuditError, OSError) as exc:
        print(json.dumps({'accountingResult': 'NOT_VERIFIED', 'transactionApproved': False,
                          'error': str(exc)}, ensure_ascii=False, indent=2))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['accountingResult'] == 'JSON_DELTAS_MATCH' else 1

if __name__ == '__main__':
    sys.exit(main())
