package com.notschie.relics.command;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.model.RelicState;
import com.notschie.relics.util.RelicFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.UUID;

public class TestsCommand {
    private final RelicsPlugin plugin;

    public TestsCommand(RelicsPlugin plugin) {
        this.plugin = plugin;
    }

    public void run(CommandSender sender, String[] args) {
        if (args.length == 0) { sendHelp(sender); return; }
        switch (args[0].toLowerCase()) {
            case "create" -> testCreate(sender);
            case "give" -> testGive(sender, args);
            case "giveall" -> testGiveAll(sender);
            case "list" -> testList(sender);
            case "info" -> testInfo(sender);
            case "owner" -> testOwnerInfo(sender);
            case "removeall" -> testRemoveAll(sender);
            case "save" -> { plugin.getRelicTracker().save(); sender.sendMessage(Component.text("Tracker saved.", NamedTextColor.GREEN)); }
            default -> sendHelp(sender);
        }
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(Component.text("=== /relic tests ===").color(NamedTextColor.GOLD));
        s.sendMessage(Component.text("/relic tests create").color(NamedTextColor.YELLOW).append(Component.text(" - Create test relic in hand (with Vanishing Curse)").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic tests give <id>").color(NamedTextColor.YELLOW).append(Component.text(" - Give specific test relic (with Vanishing Curse)").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic tests giveall").color(NamedTextColor.YELLOW).append(Component.text(" - Give all test relics (with Vanishing Curse)").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic tests list").color(NamedTextColor.YELLOW).append(Component.text(" - List tracker contents").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic tests info").color(NamedTextColor.YELLOW).append(Component.text(" - Check held item PDC").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic tests owner").color(NamedTextColor.YELLOW).append(Component.text(" - Show owner status").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic tests removeall").color(NamedTextColor.YELLOW).append(Component.text(" - Remove all relics from inventory").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic tests save").color(NamedTextColor.YELLOW).append(Component.text(" - Save tracker to disk").color(NamedTextColor.GRAY)));
    }

    private void testCreate(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Players only.", NamedTextColor.RED)); return; }
        if (plugin.allDefinitions().isEmpty()) { sender.sendMessage(Component.text("No relics defined.", NamedTextColor.RED)); return; }
        String firstId = plugin.allDefinitions().keySet().iterator().next();
        ItemStack stack = plugin.getRelicFactory().createRelic(plugin.getDefinition(firstId));
        stack.addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1);
        p.getInventory().addItem(stack);
        sender.sendMessage(Component.text("Test relic '" + firstId + "' created (NO tracker entry, mit Curse of Vanishing).", NamedTextColor.GREEN));
    }

    private void testGive(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Players only.", NamedTextColor.RED)); return; }
        if (args.length < 2) { sender.sendMessage(Component.text("Usage: /relic tests give <relic_id>", NamedTextColor.RED)); return; }
        String id = args[1].toLowerCase();
        RelicDefinition def = plugin.getDefinition(id);
        if (def == null) {
            sender.sendMessage(Component.text("Unbekanntes Relikt: " + id, NamedTextColor.RED));
            return;
        }
        ItemStack stack = plugin.getRelicFactory().createRelic(def);
        stack.addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1);
        p.getInventory().addItem(stack);
        sender.sendMessage(Component.text("Test relic '" + id + "' given (mit Curse of Vanishing).", NamedTextColor.GREEN));
    }

    private void testGiveAll(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Players only.", NamedTextColor.RED)); return; }
        for (String id : plugin.allDefinitions().keySet()) {
            ItemStack stack = plugin.getRelicFactory().createRelic(plugin.getDefinition(id));
            stack.addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1);
            p.getInventory().addItem(stack);
        }
        sender.sendMessage(Component.text(plugin.allDefinitions().size() + " test relics added (alle mit Curse of Vanishing).", NamedTextColor.GREEN));
    }

    private void testList(CommandSender sender) {
        sender.sendMessage(Component.text("Tracker: " + plugin.getRelicTracker().all().size() + " relics", NamedTextColor.GOLD));
        for (var entry : plugin.getRelicTracker().all().entrySet()) {
            RelicState state = entry.getValue();
            String ownerName = "Niemand";
            if (state.inInventory() && state.ownerUuid() != null) {
                Player p = Bukkit.getPlayer(state.ownerUuid());
                ownerName = p != null ? p.getName() : Bukkit.getOfflinePlayer(state.ownerUuid()).getName();
                if (ownerName == null || ownerName.isBlank()) ownerName = state.ownerUuid().toString().substring(0, 8);
            } else if (state.inContainer()) {
                ownerName = "Container @ " + state.worldName();
            } else if (state.onGround()) {
                ownerName = "Boden @ " + state.worldName();
            }
            sender.sendMessage(Component.text("  - " + entry.getKey() + " | Spieler: " + ownerName + " | item-uuid: " + state.itemUuid().toString().substring(0, 8)));
        }
        if (plugin.getRelicTracker().all().isEmpty()) sender.sendMessage(Component.text("  (empty)", NamedTextColor.GRAY));
    }

    private void testInfo(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Players only.", NamedTextColor.RED)); return; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) { sender.sendMessage(Component.text("Hold an item.", NamedTextColor.RED)); return; }
        RelicFactory f = plugin.getRelicFactory();
        String relicId = f.getRelicId(hand);
        String itemUuid = f.getRelicUuid(hand);
        if (relicId == null) { sender.sendMessage(Component.text("Not a relic (no PDC marker).", NamedTextColor.RED)); return; }
        sender.sendMessage(Component.text("Relic-ID: " + relicId, NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Item-UUID: " + itemUuid, NamedTextColor.GREEN));
        RelicState state = plugin.getRelicTracker().findByItemUuid(itemUuid);
        sender.sendMessage(Component.text("Tracker: " + (state != null ? "EXISTS" : "NONE"), NamedTextColor.GREEN));
    }

    private void testOwnerInfo(CommandSender sender) {
        UUID owner = plugin.getOwnerUuid();
        if (owner == null) sender.sendMessage(Component.text("No owner registered. /relic register", NamedTextColor.RED));
        else sender.sendMessage(Component.text("Owner UUID: " + owner, NamedTextColor.GREEN));
    }

    private void testRemoveAll(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Players only.", NamedTextColor.RED)); return; }
        int removed = 0;
        for (ItemStack stack : p.getInventory().getContents()) {
            if (stack != null && plugin.getRelicFactory().isRelic(stack)) { p.getInventory().remove(stack); removed++; }
        }
        sender.sendMessage(Component.text("Removed: " + removed + " relics.", NamedTextColor.GREEN));
    }
}
