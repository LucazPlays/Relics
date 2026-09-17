package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicState;
import com.notschie.relics.util.RelicFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.*;

public class AntiDupeListener implements Listener {
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    public AntiDupeListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        ItemStack stack = event.getCursor();
        if (stack == null || !factory.isRelic(stack)) return;
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player p)
            p.sendMessage(Component.text("§c§lRelics §8» §7Relikte können nicht ins Creative-Inventar übernommen werden!"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean isRelic = (current != null && factory.isRelic(current)) || (cursor != null && factory.isRelic(cursor));
        if (!isRelic) return;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            event.setCancelled(true);
            player.sendMessage(Component.text("§c§lRelics §8» §7Relikte können in Creative nicht bewegt werden!"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack stack : event.getInventory().getMatrix()) {
            if (stack != null && factory.isRelic(stack)) { event.getInventory().setResult(null); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Relikte DROPPEN jetzt beim Tod (vorher wurden sie hart aus den Drops
        // entfernt, um Duplikate zu verhindern). Das Anti-Dupe-System über
        // RelicState-Tracking + runScan() verhindert weiterhin mehrfache Instanzen.
        //
        // Glow: gedroppte Relikt-Items leuchten auf dem Boden (setGlowingTag).
        // Tracker-Update: equipped=false, holder=null → Item liegt am Boden.
        for (ItemStack stack : event.getDrops()) {
            if (stack != null && factory.isRelic(stack)) {
                String relicId = factory.getRelicId(stack);
                if (relicId == null) continue;
                RelicState oldState = plugin.getRelicTracker().get(relicId);
                if (oldState != null) {
                    plugin.getRelicTracker().put(relicId, new RelicState(
                            relicId, oldState.itemUuid(), null,
                            event.getPlayer().getWorld().getName(),
                            event.getPlayer().getLocation().getX(), event.getPlayer().getLocation().getY(), event.getPlayer().getLocation().getZ(),
                            -1, -1, -1, -1, System.currentTimeMillis(), false, false, false));
                }
            }
        }
        // Glow wird beim ItemDrop-Event gesetzt (siehe onItemDrop).
    }

    /**
     * Wenn ein Relikt manuell gedroppt wird (Q-Taste), auf dem Boden glowen.
     * Anti-Dupe-Schutz: das gedroppte Item behält seine UUID; ein Duplikat-Scan
     * läuft periodisch und entfernt ggf. überschüssige Kopien.
     *
     * Priority NORMAL (Default) + ignoreCancelled=true — wir wollen NUR laufen
     * wenn das Drop-Event nicht schon durch ein anderes Plugin gecancelt wurde.
     * MONITOR-Priority würde auch bei ignorierten Drops laufen und Item-Effekte
     * auf Items setzen, die dann gar nicht in der Welt erscheinen.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        ItemStack stack = event.getItemDrop().getItemStack();
        if (stack == null || !factory.isRelic(stack)) return;
        // Vanilla-Glow reicht meist nicht für gedropfte Entities — wir setzen
        // explizit das Entity-Glow-Tag (sichtbar für alle Spieler).
        event.getItemDrop().setGlowing(true);
        // Tracker-Update: equipped=false, holder=null
        String relicId = factory.getRelicId(stack);
        if (relicId == null) return;
        RelicState oldState = plugin.getRelicTracker().get(relicId);
        if (oldState != null) {
            plugin.getRelicTracker().put(relicId, new RelicState(
                    relicId, oldState.itemUuid(), null,
                    event.getPlayer().getWorld().getName(),
                    event.getPlayer().getLocation().getX(), event.getPlayer().getLocation().getY(), event.getPlayer().getLocation().getZ(),
                    -1, -1, -1, -1, System.currentTimeMillis(), false, false, false));
        }
    }

    /**
     * Item-Spawn auf dem Boden (z. B. nach Tod) glowen lassen.
     * Priority NORMAL: wir wollen das Item glühend haben NACHDEM es spawnt ist.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(org.bukkit.event.entity.ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (stack == null || !factory.isRelic(stack)) return;
        event.getEntity().setGlowing(true);
    }

    @EventHandler
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (!factory.isRelic(event.getItem().getItemStack())) return;
        String relicId = factory.getRelicId(event.getItem().getItemStack());
        if (relicId == null) return;
        RelicState oldState = plugin.getRelicTracker().get(relicId);
        if (oldState != null) {
            plugin.getRelicTracker().put(relicId, new RelicState(
                    relicId, oldState.itemUuid(), p.getUniqueId(),
                    p.getWorld().getName(),
                    p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ(),
                    -1, -1, -1, -1, System.currentTimeMillis(), true, false, false));
        }
    }

    public static void runScan(RelicsPlugin plugin) {
        RelicFactory factory = plugin.getRelicFactory();
        var tracker = plugin.getRelicTracker();
        java.util.Map<String, List<ItemStack>> instances = new java.util.HashMap<>();
        for (RelicState state : tracker.all().values()) instances.computeIfAbsent(state.itemUuid().toString(), k -> new ArrayList<>());

        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack stack : p.getInventory().getContents()) {
                if (stack == null || !factory.isRelic(stack)) continue;
                String uuid = factory.getRelicUuid(stack);
                if (uuid != null && instances.containsKey(uuid)) instances.get(uuid).add(stack);
            }
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Item entity : world.getEntitiesByClass(Item.class)) {
                ItemStack stack = entity.getItemStack();
                if (!factory.isRelic(stack)) continue;
                String uuid = factory.getRelicUuid(stack);
                if (uuid != null && instances.containsKey(uuid)) instances.get(uuid).add(stack);
            }
        }
        int removed = 0;
        for (var entry : instances.entrySet()) {
            List<ItemStack> list = entry.getValue();
            if (list.size() <= 1) continue;
            for (int i = 1; i < list.size(); i++) {
                ItemStack dupe = list.get(i);
                boolean removedEntity = false;
                for (org.bukkit.World w : Bukkit.getWorlds()) {
                    for (Item entity : w.getEntitiesByClass(Item.class)) {
                        if (entity.getItemStack().equals(dupe)) { entity.remove(); removedEntity = true; break; }
                    }
                    if (removedEntity) break;
                }
                if (!removedEntity) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getInventory().containsAtLeast(dupe, 1)) { p.getInventory().remove(dupe); break; }
                    }
                }
                removed++;
            }
        }
        if (removed > 0) plugin.getLogger().info("Anti-Dupe-Scan: " + removed + " duplicates removed.");
    }
}
