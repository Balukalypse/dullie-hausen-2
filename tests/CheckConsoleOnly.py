"""Quelltext-Smoke-Test, KEIN Minecraft/Fabric-Laufzeittest."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'src/main/java/de/baluserver/erzmarken'
token = (root / 'TokenCommands.java').read_text(encoding='utf-8')
shop = (root / 'ShopCommands.java').read_text(encoding='utf-8')
state = (root / 'ShopState.java').read_text(encoding='utf-8')
assert 'src.getEntity() == null && "Server".equals(src.getTextName())' in token, 'Konsolenquelle nicht gesichert'
assert 'Commands.literal("admin").requires(TokenCommands::consoleOnly)' in token
for cmd in ('status', 'katalog', 'owner', 'clearowner', 'region', 'journal', 'test'):
    assert f'Commands.literal("{cmd}").requires(TokenCommands::consoleOnly)' in shop, cmd
assert 'TokenCommands.admin(' not in shop
assert 'Commands.literal("pos1")' not in shop and 'Commands.literal("pos2")' not in shop
assert 'IdentifierArgument.id()' in shop and 'setRegion(dimName, corner1, corner2)' in shop
assert 'data.first = first.clone();' in state and 'data.second = second.clone();' in state
assert 'ErzmarkenMod.shopState.isOwner(player)' in shop and 'ErzmarkenMod.shopState.contains(player)' in shop
assert 'shopState.catalogue().stream().noneMatch' in shop
recovery = (root / 'ShopBuybackRecoveryFinalizer.java').read_text(encoding='utf-8')
assert 'public static final boolean COMMAND_ENABLED = false' in recovery
assert shop.index('if (!ShopBuybackRecoveryFinalizer.COMMAND_ENABLED') < shop.index('ShopBuybackRecoveryFinalizer.finishConfirmed(')
print('OK: Konsolenpflicht, Region, Besitzerrechte und Katalog-Freigabe im Quelltext enthalten.')
print('Hinweis: Kein Live-Test gegen Minecraft/Fabric.')
