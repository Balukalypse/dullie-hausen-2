"""Static wiring only; does not execute Minecraft players or transfers."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'src/main/java/de/baluserver/erzmarken'
trade = (root / 'TradeService.java').read_text(encoding='utf-8')
create = trade.split('public String create(', 1)[1].split('public long price(', 1)[0]
accept = trade.split('public String accept(', 1)[1].split('private static String privateInventoryProblem(', 1)[0]
assert create.index('privateInventoryProblem(seller, buyer)') < create.index('offers.put(')
assert create.index('privateInventoryProblem(seller, buyer)') < create.index('seller.getMainHandItem()')
assert accept.index('privateInventoryProblem(seller, buyer)') < accept.index('ledger.transfer(')
assert accept.index('privateInventoryProblem(seller, buyer)') < accept.index('held.shrink(')
assert 'if (admission != null) {\n            offers.remove(seller.getUUID());' in accept
assert 'seller.getUUID(), buyer.getUUID()' in trade.split('private static String privateInventoryProblem(', 1)[1]
token = (root / 'TokenCommands.java').read_text(encoding='utf-8')
buy = token.split('private static int buy(', 1)[1].split('private static int pay(', 1)[0]
assert buy.index('ShopPrivateTradeAdmission.require(') < buy.index('ErzmarkenMod.tokens.take(')
assert buy.index('ShopPrivateTradeAdmission.require(') < buy.index('InventorySupport.deliver(')
print('8 private-trade vault wiring checks passed; source inspection only')
