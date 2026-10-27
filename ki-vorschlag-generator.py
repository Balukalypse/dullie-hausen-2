#!/usr/bin/env python3
"""Optionaler manueller Vorschlagsgenerator. Python 3, Standardbibliothek.
OpenAI API-Schlüssel ausschließlich aus OPENAI_API_KEY; keine KI-Credentials in Minecraft.
Ruft nur auf ausdrücklichen CLI-Aufruf das externe Modell auf (kostenpflichtig).
"""
import argparse
import json
import os
import pathlib
import sys
import uuid
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

P = argparse.ArgumentParser(description='Dullie Hausen KI: Einen Vorschlag generieren (API-Aufruf kostet Geld)')
P.add_argument('--typ', required=True, choices=['quest', 'price_event', 'announcement'])
P.add_argument('--inbox', required=True, help='Weltordner/data/dulliehausen-ki/inbox')
P.add_argument('--model', default='gpt-4.1-mini', help='API-Modell, falls verfügbar')
ARGS = P.parse_args()

key = os.environ.get('OPENAI_API_KEY', '').strip()
if not key:
    sys.exit('OPENAI_API_KEY fehlt. Keine Anfrage versendet.')

rules = '''Erstelle EINEN Minecraft-Wirtschaftsvorschlag in deutscher Sprache für Dullie Hausen.
Antworte ausschließlich als gültiges JSON mit genau den Feldern title, description, payload.
Der payload-Wert muss ein JSON-Objekt sein. Keine Befehle, keine Links, kein Code, keine realen Geldwetten.
Niemals automatisch in den Livebetrieb übernehmen. Kleine, ausgewogene Änderungen wählen.
Das Feld payload.data ist eine JSON-kodierte ZEICHENKETTE mit genau diesem Schema:
quest: {\"item\":\"minecraft:iron_ingot\",\"profession\":\"toolsmith\",\"amount\":32,\"tokens\":4};
Berufe: farmer,fisherman,librarian,mason,weaponsmith,toolsmith,shepherd,cleric,cartographer.
price_event: {\"item\":\"minecraft:diamond\",\"changePercent\":5,\"durationHours\":24}; changePercent -5 bis +5.
announcement: {\"message\":\"Ein neutraler kurzer Text.\"}.
Keine Befehle, keine Links, kein Code, keine realen Geldwetten.'''

request_body = {
    'model': ARGS.model,
    'input': [
        {'role': 'system', 'content': rules},
        {'role': 'user', 'content': f'Erstelle einen Vorschlag vom Typ {ARGS.typ}.'}
    ],
    'text': {'format': {'type': 'json_schema', 'name': 'proposal', 'strict': True, 'schema': {
        'type': 'object', 'additionalProperties': False,
        'properties': {
            'title': {'type': 'string'},
            'description': {'type': 'string'},
            'payload': {'type': 'object', 'additionalProperties': False,
                        'properties': {'data': {'type': 'string'}}, 'required': ['data']}
        }, 'required': ['title', 'description', 'payload']
    }}},
    'max_output_tokens': 450
}
try:
    req = Request('https://api.openai.com/v1/responses',
        data=json.dumps(request_body).encode('utf-8'),
        headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'},
        method='POST')
    with urlopen(req, timeout=45) as response:
        result = json.loads(response.read(250000))
    chunks = []
    for output in result.get('output', []):
        for part in output.get('content', []):
            if part.get('type') == 'output_text': chunks.append(part.get('text', ''))
    if not chunks: sys.exit('Keine Textantwort. Kein Vorschlag erstellt.')
    payload = json.loads(''.join(chunks))
    if not isinstance(payload['title'], str) or not isinstance(payload['description'], str):
        sys.exit('Ungültiger Vorschlag.')
    proposal = {
        'type': ARGS.typ,
        'title': payload['title'][:120],
        'description': payload['description'][:700],
        'payload': json.dumps(json.loads(payload['payload']['data']), ensure_ascii=False)
    }
    inbox = pathlib.Path(ARGS.inbox)
    if not inbox.exists() or not inbox.is_dir():
        sys.exit('Inbox-Ordner fehlt. Server zuerst einmal starten.')
    path = inbox / ('ki-' + str(uuid.uuid4()) + '.json')
    with path.open('x', encoding='utf-8') as f: json.dump(proposal, f, ensure_ascii=False, indent=2)
    print('Vorschlag gespeichert:', path)
    print('Anschließend in echter Minecraft-Serverkonsole: ki importieren')
except (HTTPError, URLError, TimeoutError, ValueError, KeyError) as exc:
    sys.exit('Kein Vorschlag erstellt: ' + str(exc))
