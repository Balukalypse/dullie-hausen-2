#!/usr/bin/env python3
"""Restricted, read-only Java NBT reader for offline Minecraft player Inventory.

Supports gzip, zlib or uncompressed NBT. Never executes commands or writes data.
Only direct Inventory slots are counted; containers nested inside item components
are not unpacked. Count checks alone cannot establish cross-file atomicity.
"""
import gzip
import io
import struct
import zlib

MAX_NBT = 16 * 1024 * 1024
MAX_TAGS = 150_000
MAX_DEPTH = 48


class NbtError(ValueError):
    pass


def uncompress(raw):
    if len(raw) > MAX_NBT:
        raise NbtError('NBT-Beleg zu gross')
    try:
        if raw[:2] == b'\x1f\x8b':
            with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
                expanded = stream.read(MAX_NBT + 1)
        elif len(raw) > 2 and raw[0] == 0x78:
            obj = zlib.decompressobj()
            expanded = obj.decompress(raw, MAX_NBT + 1)
            if obj.unconsumed_tail or not obj.eof:
                raise NbtError('NBT-Zlib unvollstaendig oder zu gross')
        else:
            expanded = raw
    except (OSError, EOFError, zlib.error) as exc:
        raise NbtError('NBT-Kompression fehlerhaft') from exc
    if len(expanded) > MAX_NBT:
        raise NbtError('NBT entpackt zu gross')
    return expanded


class Reader:
    def __init__(self, raw):
        self.buf = memoryview(uncompress(raw))
        self.pos = 0
        self.remaining_tags = MAX_TAGS

    def take(self, n):
        if n < 0 or n > len(self.buf) - self.pos:
            raise NbtError('NBT abgekuerzt oder Laenge ungueltig')
        data = self.buf[self.pos:self.pos + n]
        self.pos += n
        return data

    def num(self, fmt):
        return struct.unpack('>' + fmt, self.take(struct.calcsize('>' + fmt)))[0]

    def string(self):
        n = self.num('H')
        try:
            return self.take(n).tobytes().decode('utf-8')
        except UnicodeDecodeError as exc:
            raise NbtError('NBT-Text ungueltig') from exc

    def payload(self, kind, depth=0):
        self.remaining_tags -= 1
        if self.remaining_tags < 0 or depth > MAX_DEPTH:
            raise NbtError('NBT Verschachtelungs-/Taglimit erreicht')
        if kind == 1: return self.num('b')
        if kind == 2: return self.num('h')
        if kind == 3: return self.num('i')
        if kind == 4: return self.num('q')
        if kind == 5: return self.num('f')
        if kind == 6: return self.num('d')
        if kind == 7:
            n = self.num('i')
            return self.take(n).tobytes()
        if kind == 8: return self.string()
        if kind == 9:
            subtype, length = self.num('B'), self.num('i')
            if length < 0 or length > MAX_TAGS or (subtype == 0 and length):
                raise NbtError('NBT Liste ungueltig')
            return [self.payload(subtype, depth + 1) for _ in range(length)]
        if kind == 10:
            result = {}
            while True:
                subtype = self.num('B')
                if subtype == 0: return result
                name = self.string()
                if name in result:
                    raise NbtError('Doppelter NBT-Schluessel')
                result[name] = self.payload(subtype, depth + 1)
        if kind in (11, 12):
            n = self.num('i')
            width = 4 if kind == 11 else 8
            if n < 0 or n > MAX_NBT // width:
                raise NbtError('NBT Array ungueltig')
            return self.take(n * width).tobytes()
        raise NbtError('Unbekannter NBT-Typ')

    def root(self):
        if self.num('B') != 10:
            raise NbtError('NBT-Wurzel ist kein Compound')
        self.string()
        root = self.payload(10)
        if self.pos != len(self.buf):
            raise NbtError('NBT mit unerwarteten Restdaten')
        return root


def count_item(raw, item_id):
    root = Reader(raw).root()
    inv = root.get('Inventory')
    if not isinstance(inv, list):
        raise NbtError('Inventory-Liste fehlt')
    seen_slots = set()
    count = 0
    for entry in inv:
        if not isinstance(entry, dict):
            raise NbtError('Inventory-Eintrag ungueltig')
        slot = entry.get('Slot')
        if type(slot) is not int or slot < -128 or slot > 127 or slot in seen_slots:
            raise NbtError('Inventory-Slot fehlt, doppelt oder ungueltig')
        seen_slots.add(slot)
        identifier = entry.get('id')
        amount = entry.get('count', entry.get('Count'))
        if not isinstance(identifier, str) or type(amount) is not int or not 1 <= amount <= 99:
            raise NbtError('Item-ID/Count fehlt oder ist ungueltig')
        if identifier == item_id:
            count += amount
    return count
