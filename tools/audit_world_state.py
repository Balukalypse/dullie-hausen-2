#!/usr/bin/env python3
"""Read-only inspection of a stopped Minecraft world COPY and transaction evidence.

Current world state is NOT transaction-time evidence: later gameplay can legitimately
change all balances, stock and inventory. Differences are informational only.
"""
import argparse
import json
from pathlib import Path
import sys
from audit_persisted_trade import audit, balances, decoded_object, ordinary_file, AuditError, ID_RE
from nbt_inventory_audit import count_item, NbtError

MAX_TRANSACTIONS = 1000


def safe_path(root, *parts):
    current = root
    if root.is_symlink() or not root.is_dir():
        raise AuditError('Weltordner fehlt oder ist verlinkt')
    for part in parts:
        if current.exists() and not current.is_dir():
            raise AuditError('Datei statt Verzeichnis im Datenpfad')
        current = current / part
        if current.is_symlink():
            raise AuditError('Symbolische Verknuepfung im Datenpfad: ' + part)
    return current


def inspect(world, transaction):
    """No write operations. All current-world comparisons are time-ambiguous."""
    journal = safe_path(world, 'data', 'erzmarken-shop-journal', transaction + '.pending.json')
    safe_path(world, 'data', 'erzmarken-shop-evidence', transaction, 'before')
    safe_path(world, 'data', 'erzmarken-shop-evidence', transaction, 'after')
    if not journal.is_file():
        raise AuditError('Offenes Journal nicht gefunden')
    result = audit(world, transaction)
    player = result['playerId']
    item = result['itemId']
    state = {'status': 'CURRENT_STATE_INFORMATIONAL_ONLY', 'allCurrentSourcesRead': False,
             'matchesAfterSnapshot': None, 'values': {}, 'errors': {}}
    sources = {
        'PLAYERDATA': None,
        'PLAYER_TOKENS': ('data', 'erzmarken.json'),
        'SHOP_REGISTER': ('data', 'erzmarken-shop.json'),
    }
    blobs = {}
    for name, parts in sources.items():
        try:
            path = playerdata_path(world, player) if name == 'PLAYERDATA' else safe_path(world, *parts)
            if name == 'PLAYERDATA':
                state['playerdataPath'] = str(path.relative_to(world))
            blobs[name] = ordinary_file(path, 16 * 1024 * 1024 if name == 'PLAYERDATA' else 64 * 1024 * 1024)
        except (AuditError, OSError) as exc:
            state['errors'][name] = str(exc)
    if 'PLAYERDATA' in blobs:
        try:
            state['values']['inventoryCount'] = count_item(blobs['PLAYERDATA'], item)
        except NbtError as exc:
            state['errors']['PLAYERDATA'] = str(exc)
    if 'PLAYER_TOKENS' in blobs and 'SHOP_REGISTER' in blobs:
        try:
            current = balances({'PLAYER_TOKENS': blobs['PLAYER_TOKENS'], 'SHOP_REGISTER': blobs['SHOP_REGISTER']}, player, item)
            state['values'].update(current)
        except AuditError as exc:
            state['errors']['BALANCES'] = str(exc)
    after = result['after']
    comparisons = {name: state['values'][name] == value for name, value in after.items()
                   if name in state['values']}
    if 'inventoryCount' in state['values'] and 'after' in result['inventoryCounts']:
        comparisons['inventoryCount'] = state['values']['inventoryCount'] == result['inventoryCounts']['after']
    state['comparisonsWithAfter'] = comparisons
    state['allCurrentSourcesRead'] = (len(blobs) == 3 and not state['errors'] and len(comparisons) == 4)
    if state['allCurrentSourcesRead']:
        state['matchesAfterSnapshot'] = all(comparisons.values())
    result['currentWorldState'] = state
    result['transactionApproved'] = False
    result['warning'] += ' Weltdateien koennen zeitlich spaeter sein; Abweichungen sind KEIN Beweis fuer Fehler oder Manipulation.'
    return result


def playerdata_path(world, player):
    """Reject ambiguous current/legacy files and stale fallback in migrated worlds."""
    modern = safe_path(world, 'players', 'data', player + '.dat')
    legacy = safe_path(world, 'playerdata', player + '.dat')
    if modern.exists() and legacy.exists():
        raise AuditError('Mehrere Spielerdateien; aktuellen Stand manuell bestimmen')
    if modern.exists():
        return modern
    if safe_path(world, 'players', 'data').exists():
        raise AuditError('Aktuelle Spielerdatei fehlt; kein Rueckgriff auf Legacy-Datei')
    return legacy


def scan(world, limit=MAX_TRANSACTIONS):
    folder = safe_path(world, 'data', 'erzmarken-shop-journal')
    if not folder.is_dir():
        raise AuditError('Journalordner fehlt')
    names = sorted(p.name for p in folder.iterdir() if p.name.endswith('.pending.json'))
    if len(names) > limit:
        raise AuditError('Zu viele offene Transaktionen; bitte einzelne ID pruefen')
    results = []
    for filename in names:
        txid = filename[:-len('.pending.json')]
        if not ID_RE.fullmatch(txid):
            results.append({'transactionId': txid[:80], 'result': 'NOT_VERIFIED', 'reason': 'Ungueltige Journal-ID'})
            continue
        try:
            results.append(inspect(world, txid))
        except (AuditError, OSError) as exc:
            results.append({'transactionId': txid, 'result': 'NOT_VERIFIED', 'reason': str(exc), 'transactionApproved': False})
    return {'world': str(world), 'inspected': len(results), 'transactions': results,
            'transactionApproved': False, 'warning': 'Nur Kopie einer gestoppten Welt verwenden; keine automatische Freigabe.'}


def main(argv=None):
    parser = argparse.ArgumentParser(description='Nur-lesender Weltdateien- und Transaktionsaudit')
    parser.add_argument('--world', required=True, type=Path, help='Kopie einer gestoppten Welt')
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--transaction', help='Transaktions-UUID')
    group.add_argument('--all-pending', action='store_true', help='Alle offenen Transaktionen (max. 1000)')
    args = parser.parse_args(argv)
    try:
        output = inspect(args.world, args.transaction) if args.transaction else scan(args.world)
    except (AuditError, OSError, ValueError) as exc:
        print(json.dumps({'result': 'NOT_VERIFIED', 'transactionApproved': False, 'error': str(exc)}, ensure_ascii=False, indent=2))
        return 2
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0

if __name__ == '__main__':
    sys.exit(main())
