"""Static runtime-construction check, not a server startup."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'src/main/java/de/baluserver/erzmarken'
mod = (root / 'ErzmarkenMod.java').read_text(encoding='utf-8')
assert 'tokens = TokenStore.forWorld(server.getWorldPath(LevelResource.ROOT));' in mod
for source in root.glob('*.java'):
    if source.name != 'TokenStore.java':
        assert 'new TokenStore(' not in source.read_text(encoding='utf-8'), source.name
print('Runtime store binding checked: server uses the journal-guarded factory; source inspection only')
