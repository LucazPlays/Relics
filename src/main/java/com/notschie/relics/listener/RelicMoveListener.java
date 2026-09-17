package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicState;
import com.notschie.relics.util.RelicFactory;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class RelicMoveListener implements Listener {
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    public RelicMoveListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        for (RelicState state : plugin.getRelicTracker().all().values()) {
            if (state.ownerUuid() != null && state.ownerUuid().equals(p.getUniqueId())) {
                plugin.getRelicTracker().put(state.relicId(), new RelicState(
                        state.relicId(), state.itemUuid(), p.getUniqueId(),
                        p.getWorld().getName(), p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ(),
                        -1, -1, -1, -1, System.currentTimeMillis(), true, false, false));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getRelicTracker().save();
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        Item item = event.getItemDrop();
        ItemStack stack = item.getItemStack();
        if (!factory.isRelic(stack)) return;
        String relicId = factory.getRelicId(stack);
        if (relicId == null) return;
        RelicState oldState = plugin.getRelicTracker().get(relicId);
        if (oldState == null) return;
        Location loc = item.getLocation();
        plugin.getRelicTracker().put(relicId, new RelicState(
                relicId, oldState.itemUuid(), null,
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                -1, -1, -1, -1, System.currentTimeMillis(), false, true, false));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        ItemStack relStack = null;
        if (current != null && factory.isRelic(current)) relStack = current;
        else if (cursor != null && factory.isRelic(cursor)) relStack = cursor;
        if (relStack == null) return;
        String relicId = factory.getRelicId(relStack);
        if (relicId == null) return;
        RelicState oldState = plugin.getRelicTracker().get(relicId);
        if (oldState == null) return;

        Inventory top = event.getView().getTopInventory();
        Inventory bottom = event.getView().getBottomInventory();
        int rawSlot = event.getRawSlot();
        if (rawSlot < bottom.getSize()) {
            plugin.getRelicTracker().put(relicId, new RelicState(
                    relicId, oldState.itemUuid(), p.getUniqueId(),
                    p.getWorld().getName(), p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ(),
                    -1, -1, -1, -1, System.currentTimeMillis(), true, false, false));
        } else {
            Block block = top.getLocation() != null ? top.getLocation().getBlock() : null;
            if (block != null && block.getState() instanceof Container) {
                Location loc = block.getLocation();
                plugin.getRelicTracker().put(relicId, new RelicState(
                        relicId, oldState.itemUuid(), null,
                        loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                        loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), rawSlot - bottom.getSize(),
                        System.currentTimeMillis(), false, false, true));
            }
        }
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        if (!factory.isRelic(item.getItemStack())) return;
        String relicId = factory.getRelicId(item.getItemStack());
        if (relicId == null) return;
        RelicState oldState = plugin.getRelicTracker().get(relicId);
        if (oldState == null) return;
        Location loc = item.getLocation();
        plugin.getRelicTracker().put(relicId, new RelicState(
                relicId, oldState.itemUuid(), null,
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                -1, -1, -1, -1, System.currentTimeMillis(), false, true, false));
    }
}
