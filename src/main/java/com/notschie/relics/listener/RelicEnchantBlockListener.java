package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.util.RelicFactory;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Blockiert das externe Verzaubern von Relikten.
 *
 * Relikte sind „fertig" — Enchantment-Lore, Glow und Stats sind hardcoded.
 * Wir verhindern, dass Spieler zusätzliche Enchantments via Enchanting Table oder
 * Anvil (Bücher, Smaragden) hinzufügen können. HIDE_ENCHANTS versteckt nur die
 * Anzeige, nicht die Anwendung.
 *
 *  - PrepareItemEnchantEvent (Enchanting Table): sofort abbrechen, sobald das
 *    Item ein Relikt ist. Das Event feuert pro Button-Vorschau; einmal canceln
 *    reicht für alle drei Optionen.
 *  - PrepareAnvilEvent (Anvil + Smaragden + Bücher): sobald ein Relikt im Anvil
 *    liegt, wird das Resultat-Item unterdrückt. Greift auch für Villager-Bücher.
 *
 * Wichtig: Prepare*-Events feuern VOR der eigentlichen Verzauberung. Damit ist
 * garantiert, dass kein zusätzlicher Enchant auf das Item geschrieben wird.
 */
public class RelicEnchantBlockListener implements Listener {

    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    public RelicEnchantBlockListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        ItemStack item = event.getItem();
        if (item == null) return;
        if (factory.isRelic(item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        // PrepareAnvilEvent hat in Paper kein setCancelled(). Wir nullen das Resultat,
        // sobald ein Relikt im Spiel ist. Damit gibt es keine Ausgabe, der Spieler
        // bekommt nichts im Resultat-Slot. Greift auch für Bücher aus Villager-Trades.
        ItemStack result = event.getResult();
        if (result != null && factory.isRelic(result)) {
            event.setResult(null);
            return;
        }
        // Falls eines der Input-Slots ein Relikt ist (Reparatur wäre sowieso nicht
        // möglich, weil Unbreakable — aber wir blocken sicherheitshalber auch das).
        for (ItemStack stack : event.getInventory().getContents()) {
            if (stack != null && factory.isRelic(stack)) {
                event.setResult(null);
                return;
            }
        }
    }
}
