import de.baluserver.erzmarken.ShopJournalTransitionGuard;
public class ShopJournalImmutableDataTest {
    private static int checks;
    static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Metadaten-Test #" + checks);
    }
    static boolean equal(String id, String player, String item, int qty, long price, long time) {
        return ShopJournalTransitionGuard.sameImmutableData("a-transaction", "player-1", "minecraft:stone", 16, 80, 123456,
            id, player, item, qty, price, time);
    }
    public static void main(String[] args) {
        check(equal("a-transaction", "player-1", "minecraft:stone", 16, 80, 123456));
        check(!equal("b-transaction", "player-1", "minecraft:stone", 16, 80, 123456));
        check(!equal("a-transaction", "player-2", "minecraft:stone", 16, 80, 123456));
        check(!equal("a-transaction", "player-1", "minecraft:dirt", 16, 80, 123456));
        check(!equal("a-transaction", "player-1", "minecraft:stone", 15, 80, 123456));
        check(!equal("a-transaction", "player-1", "minecraft:stone", 16, 81, 123456));
        check(!equal("a-transaction", "player-1", "minecraft:stone", 16, 80, 123457));
        check(!equal("a-transaction", "player-1", "minecraft:stone", 0, 80, 123456));
        check(!equal("a-transaction", "player-1", "minecraft:stone", 16, -80, 123456));
        check(!equal("a-transaction", "player-1", "minecraft:stone", 16, 80, 0));
        check(!equal("a-transaction", null, "minecraft:stone", 16, 80, 123456));
        check(!equal("a-transaction", "player-1", null, 16, 80, 123456));
        check(!equal("a-transaction", "player-1", "minecraft:stone", 16, 80, -1));
        System.out.println(checks+" Journal-Metadatenpruefungen bestanden");
    }
}
