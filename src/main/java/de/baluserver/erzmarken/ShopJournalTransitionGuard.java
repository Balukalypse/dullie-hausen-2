package de.baluserver.erzmarken;

/** Stateless validation: stale, mismatched and replayed journal writes fail closed. */
public final class ShopJournalTransitionGuard {
    private ShopJournalTransitionGuard() { }

    /** All transaction identifiers and immutable booking details must match the durable entry.
     * A stage-only comparison is insufficient: it could rewrite player/item/amount metadata.
     */
    public static boolean sameImmutableData(String expectedId, String expectedPlayer,
                                             String expectedItem, int expectedQuantity,
                                             long expectedTotal, long expectedCreatedAt,
                                             String storedId, String storedPlayer,
                                             String storedItem, int storedQuantity,
                                             long storedTotal, long storedCreatedAt) {
        return expectedId != null && !expectedId.isBlank()
            && expectedId.equals(storedId)
            && expectedPlayer != null && !expectedPlayer.isBlank()
            && expectedPlayer.equals(storedPlayer)
            && expectedItem != null && !expectedItem.isBlank()
            && expectedItem.equals(storedItem)
            && expectedQuantity > 0 && expectedQuantity == storedQuantity
            && expectedTotal >= 0 && expectedTotal == storedTotal
            && expectedCreatedAt > 0 && expectedCreatedAt == storedCreatedAt;
    }

    public static boolean mayAdvance(String expectedId, String expectedStage,
                                     String storedId, String storedStage, String nextStage) {
        return expectedId != null && !expectedId.isBlank()
            && expectedId.equals(storedId) && expectedStage != null
            && expectedStage.equals(storedStage)
            && ShopTransactionStages.permitted(storedStage, nextStage);
    }
}
