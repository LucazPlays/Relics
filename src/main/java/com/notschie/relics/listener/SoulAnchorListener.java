package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.util.RelicFactory;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mechanisches Zusatz-Modul (analog zum Locator-Störsender):
 * Bewahrt das Inventar des Spielers für genau EINEN Tod (KeepInventory).
 * Das Modul zerbricht und verschwindet nach dem Tod.
 */
public class SoulAnchorListener implements Listener {

    public static final String RELIC_ID = "soul_anchor";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Set<UUID> protectedDeaths = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public SoulAnchorListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    public boolean isSoulAnchor(ItemStack stack) {
        if (stack == null || !factory.isRelic(stack)) return false;
        String id = factory.getRelicId(stack);
        return RELIC_ID.equalsIgnoreCase(id)
                || "seelenanker".equalsIgnoreCase(id)
                || "inventar_sicherung".equalsIgnoreCase(id)
                || "inventory_saver".equalsIgnoreCase(id);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        PlayerInventory inv = player.getInventory();
        int foundSlot = -1;
        String matchedId = RELIC_ID;

        // Durchsuche gesamtes Inventar (Storage, Armor, Offhand)
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack != null && isSoulAnchor(stack)) {
                foundSlot = slot;
                matchedId = factory.getRelicId(stack);
                break;
            }
        }

        if (foundSlot == -1) return;

        // 1. KeepInventory & KeepLevel aktivieren
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.getDrops().clear();

        // 2. Genau 1 Exemplar des Schutz-Moduls verbrauchen
        ItemStack anchor = inv.getItem(foundSlot);
        if (anchor != null) {
            if (anchor.getAmount() > 1) {
                anchor.setAmount(anchor.getAmount() - 1);
            } else {
                inv.setItem(foundSlot, null);
            }
        }

        // 3. Aus RelicTracker entfernen, falls getrackt
        if (plugin.getRelicTracker().exists(matchedId)) {
            plugin.getRelicTracker().remove(matchedId);
            plugin.getRelicTracker().save();
        }

        protectedDeaths.add(player.getUniqueId());

        // 4. Todes-Effekte und Sounds
        var loc = player.getLocation();
        var world = player.getWorld();
        world.playSound(loc, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.4f, 0.85f);
        world.playSound(loc, Sound.ITEM_TOTEM_USE, 0.9f, 1.15f);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0, 1.0, 0), 45, 0.5, 0.8, 0.5, 0.08);
        world.spawnParticle(Particle.PORTAL, loc.clone().add(0, 1.0, 0), 35, 0.4, 0.7, 0.4, 0.12);

        player.sendMessage(MM.deserialize(
                "<gray><bold>Inventar-Sicherung</bold> <dark_gray>» <aqua>Dein Inventar wurde bewahrt! Das Schutz-Modul ist zerbrochen."
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!protectedDeaths.remove(player.getUniqueId())) return;

        player.sendTitle(
                "§b✦ Inventar bewahrt ✦",
                "§7Das Schutz-Modul hat deine Besitztümer gerettet.",
                10, 50, 20
        );
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.2f);
        player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.5f, 1.5f);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (isSoulAnchor(item)) {
            event.setCancelled(true);
            Player p = event.getPlayer();
            p.sendActionBar(MM.deserialize(
                    "<gray><bold>Inventar-Sicherung</bold> <dark_gray>» <green>Aktiv im Inventar (schützt vor Verlust bei 1x Tod)."
            ));
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
            p.spawnParticle(Particle.SOUL_FIRE_FLAME, p.getLocation().add(0, 1.2, 0), 8, 0.2, 0.2, 0.2, 0.04);
        }
    }
}
