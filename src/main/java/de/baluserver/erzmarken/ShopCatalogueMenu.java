package de.baluserver.erzmarken;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Die sechsreihige Vanilla-Truhenansicht ist ein KATALOG, kein echtes Kreativinventar.
 * Der Server entscheidet allein, was der Shopbesitzer entnehmen darf.
 * Weder die Menue-Icons noch Spieler-Items koennen in den Katalog eingelagert werden.
 */
public final class ShopCatalogueMenu extends ChestMenu {
    private record CatalogueProvider(ServerPlayer owner, List<ShopState.CatalogueItem> entries,
                                     SimpleContainer icons) implements MenuProvider {
        @Override public Component getDisplayName() {
            return Component.literal("§6§lDULLIE HAUSEN §8| §aKreativkatalog");
        }
        @Override public ShopCatalogueMenu createMenu(int menuId, Inventory inventory, Player user) {
            return new ShopCatalogueMenu(menuId, inventory, owner, entries, icons);
        }
    }
    public static boolean isOwnProvider(MenuProvider provider, ServerPlayer player) {
        return provider instanceof CatalogueProvider catalogue && catalogue.owner() == player;
    }
    private static final int SLOTS = 54;
    private final List<ShopState.CatalogueItem> entries;
    private final ServerPlayer owner;

    private ShopCatalogueMenu(int containerId, Inventory inventory, ServerPlayer owner,
                              List<ShopState.CatalogueItem> entries, SimpleContainer catalogue) {
        super(MenuType.GENERIC_9x6, containerId, inventory, catalogue, 6);
        this.entries = entries;
        this.owner = owner;
    }

    public static String open(ServerPlayer player) {
        if (ErzmarkenMod.shopState == null) return "Shopdaten sind noch nicht geladen.";
        if (!ErzmarkenMod.shopState.isOwner(player)) return "Nur der Shopbesitzer darf das Katalogmenue benutzen.";
        if (!"kreativ".equals(ErzmarkenMod.shopState.shopMode())) return "§6[Dullie Hausen] §cKostenlose Artikel sind im Grosshandelsmodus gesperrt. Bitte zuerst einen bezahlten Wareneinkauf einrichten.";
        if (!ErzmarkenMod.shopState.contains(player)) return "Das Katalogmenue funktioniert nur im Shopgebaeude.";
        if (player.isCreative() || player.isSpectator()) return "Katalog bitte im Survival- oder Adventure-Modus verwenden.";

        List<ShopState.CatalogueItem> snapshot = ErzmarkenMod.shopState.catalogue();
        SimpleContainer icons = new SimpleContainer(SLOTS);
        for (int i = 0; i < snapshot.size(); i++) {
            var entry = snapshot.get(i);
            var item = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(entry.itemId()));
            final int slot = i;
            item.ifPresent(value -> icons.setItem(slot, new ItemStack(value, entry.amount())));
        }
        if (player.openMenu(new CatalogueProvider(player, snapshot, icons)).isEmpty())
            return "Katalog konnte nicht geoeffnet werden. Inventarsitzung zuerst pruefen lassen.";
        return snapshot.isEmpty()
            ? "Der Katalog ist leer. Nur die Serverkonsole kann Items freigeben."
            : "Shop-Katalog geoeffnet. Linksklick: Menge erhalten. Shift-Klick: ebenfalls Menge erhalten.";
    }

    private boolean permitted(Player player) {
        return player == owner && ErzmarkenMod.shopState != null
            && ErzmarkenMod.shopState.isOwner(owner)
            && ErzmarkenMod.shopState.contains(owner)
            && "kreativ".equals(ErzmarkenMod.shopState.shopMode())
            && de.baluserver.erzmarken.ShopInventoryTransferGuard.mayUseCatalogue(owner)
            && !owner.isCreative() && !owner.isSpectator();
    }

    @Override
    public boolean stillValid(Player player) {
        return permitted(player);
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (!permitted(player)) {
            owner.closeContainer();
            return;
        }
        // Niemals Clientaktionen auf den Katalog durch Vanilla verarbeiten lassen:
        // Sie koennten sonst Icons entfernen, tauschen, klonen oder doppelt nehmen.
        if (slot >= 0 && slot < SLOTS) {
            if ((input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)
                    && (button == 0 || button == 1) && getCarried().isEmpty()) {
                ShopState.CatalogueItem entry = slot < entries.size() ? entries.get(slot) : null;
                if (entry != null && ErzmarkenMod.shopState.catalogue().contains(entry)) {
                    Item item = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(entry.itemId())).orElse(null);
                    if (item != null) {
                        ItemStack granted = new ItemStack(item, entry.amount());
                        int wanted = granted.getCount();
                        owner.getInventory().add(granted);
                        int delivered = wanted - granted.getCount();
                        if (delivered == 0) owner.sendSystemMessage(Component.literal("§6[Dullie Hausen] §cInventar ist voll."));
                        else owner.sendSystemMessage(Component.literal("§6[Dullie Hausen] §a+" + delivered + "x §f" + entry.itemId()));
                    }
                }
            }
            broadcastChanges();
            return;
        }
        // Umfuellen per Shiftklick, Doppelklick, Ziehen und Tauschen wird
        // komplett gesperrt; normaler Klick in eigene Inventarslots ist erlaubt.
        if (input == ContainerInput.QUICK_MOVE || input == ContainerInput.QUICK_CRAFT
                || input == ContainerInput.PICKUP_ALL || input == ContainerInput.CLONE
                || input == ContainerInput.SWAP) {
            broadcastChanges();
            return;
        }
        super.clicked(slot, button, input, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }
}
