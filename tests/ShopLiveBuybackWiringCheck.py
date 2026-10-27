from pathlib import Path
root=Path(__file__).parent.parent/'src/main/java/de/baluserver/erzmarken'
s=(root/'ShopBuybackService.java').read_text(encoding='utf-8')
e=(root/'ShopBuybackExecution.java').read_text(encoding='utf-8')
assert s.count('ShopMinecraftLiveEvidenceBridge.capture(')==2
assert 'ShopTransactionJournal.begin(' not in s and 'ShopTransactionJournal.finish(' not in s
assert 'if (!shop.buybackTradesEnabled())' in s
assert 'if (!ENABLED && !Phase2TestAccess.allowed(' in s and 'public static final boolean ENABLED = false' in s
for prerequisite in ('inventory.requirePersistenceSupport()', 'MinecraftBuybackSnapshot.afterRemoval(',
                     'shop.requireStorageAvailable()', 'tokens.requireStorageAvailable()',
                     '.requirePrivateActive(player.getUUID())'):
    assert s.index(prerequisite)<s.index('ShopBuybackExecution.execute(')
assert s.index('.requirePrivateActive(player.getUUID())')<s.index('player.getMainHandItem()')
for callback in ('shop.commitBuyback(', 'tokens.creditShopPurchaseChecked(', 'held.shrink(count)',
                 'ShopOnlineInventoryEvidence.Moment.BEFORE', 'ShopOnlineInventoryEvidence.Moment.AFTER',
                 'player.connection.disconnect('):
    assert callback in s
ordered=('inventory.requirePersistenceSupport()', 'ShopTransactionJournal.begin(',
         'ShopBuybackIntentStore.prepare(', 'actions.captureBefore(', 'actions.debitShop(',
         '"SHOP_DEBITED"', 'actions.creditPlayer(', '"PLAYER_CREDITED"', 'actions.removeItems(',
         '"ITEM_REMOVED_UNSAVED"', 'actions.captureAfter(', 'ShopBuybackSettlement.finishCurrent(')
assert [e.index(step) for step in ordered]==sorted(e.index(step) for step in ordered)
assert 'actions.interrupted(journal, failure)' in e
print('Live buyback delegates to tested executor; gates, evidence callbacks and settlement ordering checked (static wiring only).')
