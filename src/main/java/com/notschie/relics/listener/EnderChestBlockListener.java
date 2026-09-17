package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.util.RelicFactory;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Blockiert alle Versuche, ein Relikt in eine Enderchest zu legen.
 *
 * Greift NICHT beim Öffnen der Enderchest selbst (keine Öffnungs-Blockade), sondern
 * NUR bei den UI-Aktionen, die ein Item in die Enderchest verschieben würden:
 *
 *  - InventoryClickEvent:
 *      * Shift-Click auf Relikt im Spieler-Inventar (würde in Enderchest landen)
 *      * Klick mit Relikt auf Cursor, das in Enderchest-Slot gedroppt wird
 *      * Hotbar-Swap (NUMBER_KEY): Relikt von Hotbar in Enderchest-Slot
 *      * SWAP_OFFHAND oder DROP/SWAP_VARIANTS, die Enderchest betreffen
 *  - InventoryDragEvent:
 *      * Drag-Verteilung, die einen Relikt-Slot in der Enderchest berührt
 *
 * Drop auf den Boden (ClickType.DROP) bleibt erlaubt, weil der Tracker ohnehin
 * per Owner-UUID getrackt wird und AntiDupeListener den Owner-Schutz gewährleistet.
 */
public class EnderChestBlockListener implements Listener {

    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    public EnderChestBlockListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isEnderChest(Inventory inv) {
        return inv != null && inv.getType() == InventoryType.ENDER_CHEST;
    }

    private boolean isRelic(ItemStack stack) {
        if (stack == null || !factory.isRelic(stack)) return false;
        return stack.getAmount() > 0;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!isEnderChest(top)) return;

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        ClickType click = event.getClick();

        // Hotbar-Swap (NUMBER_KEY): wenn das Hotbar-Item ein Relikt ist, blocken.
        if (click == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
            ItemStack hotbarItem = event.getWhoClicked().getInventory().getItem(event.getHotbarButton());
            if (isRelic(hotbarItem)) {
                event.setCancelled(true);
                return;
            }
        }

        // SWAP_OFFHAND: Offhand-Item auf den geklickten Slot. Relikt blocken.
        if (click == ClickType.SWAP_OFFHAND) {
            ItemStack off = event.getWhoClicked().getInventory().getItemInOffHand();
            if (isRelic(off)) {
                event.setCancelled(true);
                return;
            }
        }

        // Shift-Click: Wenn das aktuell geklickte Item ein Relikt ist UND die View eine
        // Enderchest hat, würde Shift-Click es in die obere Hälfte (= Enderchest) verschieben.
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            if (isRelic(current)) {
                event.setCancelled(true);
                return;
            }
        }

        // Klick auf einen Slot in der oberen Hälfte (= Enderchest) mit Relikt auf Cursor.
        if (event.getRawSlot() < top.getSize()) {
            // Slot ist in der Enderchest.
            if (isRelic(cursor)) {
                event.setCancelled(true);
                return;
            }
            // Klick auf ein bereits vorhandenes Relikt in der Enderchest (nehmen)? Eigentlich
            // kann nichts in der Enderchest sein, wenn wir blocken — aber Altbestand könnte
            // existieren. Wir lassen das Nehmen zu, damit der Spieler Items rausholen kann.
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!isEnderChest(top)) return;

        // Wenn irgendein Slot in der oberen Hälfte (= Enderchest) betroffen ist UND
        // das alte Item ein Relikt ist, blocken.
        if (event.getOldCursor() == null) return;
        if (!isRelic(event.getOldCursor())) return;

        int topSize = top.getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
