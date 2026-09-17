package com.notschie.relics.command;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicState;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class RelicCommand implements CommandExecutor, TabCompleter {

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final PackCommand packCommand;

    public RelicCommand(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
        this.packCommand = new PackCommand(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "register" -> handleRegister(sender);
            case "give" -> handleGive(sender, args);
            case "revoke" -> handleRevoke(sender, args);
            case "untrack", "remove", "removetracker" -> handleUntrack(sender, args);
            case "tracker", "list", "track" -> handleTracker(sender, args);
            case "reload" -> handleReload(sender);
            case "tests", "test", "debug" -> {
                if (!sender.hasPermission("relics.tests")) {
                    sender.sendMessage(Component.text("Keine Berechtigung.", NamedTextColor.RED));
                    return true;
                }
                plugin.getTestsCommand().run(sender, Arrays.copyOfRange(args, 1, args.length));
            }
            case "pack" -> packCommand.run(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(Component.text("=== Relics Hilfe ===").color(NamedTextColor.GOLD));
        s.sendMessage(Component.text("/relic register").color(NamedTextColor.YELLOW)
                .append(Component.text(" - Owner-UUID-Permission registrieren").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic give [<Spieler>] <Relikt>").color(NamedTextColor.YELLOW)
                .append(Component.text(" - Relikt geben (auto-überschreibt Tracker)").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic revoke <Spieler>").color(NamedTextColor.YELLOW)
                .append(Component.text(" - Relikt entfernen (Owner)").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic untrack <Relikt-ID>").color(NamedTextColor.YELLOW)
                .append(Component.text(" - Tracker-Eintrag löschen, Relikt neu verteilbar (Owner)").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic tracker [gui]").color(NamedTextColor.YELLOW)
                .append(Component.text(" - Tracking anzeigen (Chat/GUI)").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic reload").color(NamedTextColor.YELLOW)
                .append(Component.text(" - Config neu laden (Owner)").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic tests ...").color(NamedTextColor.GREEN)
                .append(Component.text(" - Debug-Tests ausführen").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic pack ...").color(NamedTextColor.GREEN)
                .append(Component.text(" - Resource-Pack verwalten (URL, Host, Status, Push)").color(NamedTextColor.GRAY)));
    }

    private void handleRegister(CommandSender sender) {
        if (!sender.hasPermission("relics.admin.register")) {
            sender.sendMessage(Component.text("Keine Berechtigung für /relic register.", NamedTextColor.RED));
            return;
        }
        if (plugin.getOwnerUuid() != null) {
            sender.sendMessage(Component.text("Es ist bereits ein Owner registriert: ", NamedTextColor.RED)
                    .append(Component.text(plugin.getOwnerUuid().toString(), NamedTextColor.YELLOW)));
            return;
        }
        UUID newOwner = UUID.randomUUID();
        plugin.setOwnerUuid(newOwner);

        // Permission im Server registrieren
        String permNode = "relics.admin." + newOwner.toString().replace("-", "");
        try {
            Bukkit.getPluginManager().addPermission(new org.bukkit.permissions.Permission(permNode));
        } catch (Exception ignored) {}

        sender.sendMessage(Component.text("Owner registriert! UUID: ", NamedTextColor.GREEN)
                .append(Component.text(newOwner.toString(), NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("Permission: ", NamedTextColor.GREEN)
                .append(Component.text(permNode, NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("Diese Permission muss der Owner-Spieler bekommen (z.B. via LuckPerms).", NamedTextColor.GRAY));
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!isOwner(sender)) {
            sender.sendMessage(Component.text("Nur der registrierte Owner darf Relikte geben.", NamedTextColor.RED));
            return;
        }
        // Flexibles Argument-Parsing:
        //   /relic give <Relikt>                        → an Sender selbst
        //   /relic give <Spieler> <Relikt>              → an anderen Spieler
        Player target;
        String relicId;
        if (args.length == 2) {
            // /relic give <Relikt> — an Sender selbst
            target = (sender instanceof Player p) ? p : null;
            relicId = args[1];
            if (target == null) {
                sender.sendMessage(Component.text("Usage: /relic give <Spieler> <Relikt> (von Konsole braucht Spielername)", NamedTextColor.RED));
                return;
            }
        } else if (args.length >= 3) {
            // /relic give <Spieler> <Relikt>  ODER  /relic give <Relikt> <Spieler>
            // Wir probieren beides.
            Player p1 = Bukkit.getPlayer(args[1]);
            if (p1 != null) {
                target = p1;
                relicId = args[2];
            } else if (plugin.getDefinition(args[1]) != null) {
                target = Bukkit.getPlayer(args[2]);
                relicId = args[1];
            } else {
                target = Bukkit.getPlayer(args[1]);
                relicId = args[2];
            }
        } else {
            sender.sendMessage(Component.text("Usage: /relic give [<Spieler>] <Relikt>", NamedTextColor.RED));
            return;
        }

        if (target == null) {
            sender.sendMessage(Component.text("Spieler nicht online.", NamedTextColor.RED));
            return;
        }
        if (plugin.getDefinition(relicId) == null) {
            sender.sendMessage(Component.text("Unbekanntes Relikt: " + relicId, NamedTextColor.RED));
            return;
        }

        // AUTO-OVERWRITE: Wenn bereits ein Tracker-Eintrag existiert, wird er überschrieben
        // (früher brach der /relic give Befehl hier ab — das war umständlich).
        // Wir informieren nur, falls ein Override stattfand.
        if (plugin.getRelicTracker().exists(relicId)) {
            sender.sendMessage(Component.text("Tracker-Eintrag für '" + relicId + "' existierte — wird überschrieben.", NamedTextColor.YELLOW));
            // Alte Tracker-Referenz entfernen (alte Item-Instanzen in der Welt bleiben
            // unangetastet — sie funktionieren weiter als Relikte, sind nur nicht mehr
            // "die eine" getrackte Instanz).
            plugin.getRelicTracker().remove(relicId);
        }

        // Item erstellen und geben.
        ItemStack stack = factory.createRelic(plugin.getDefinition(relicId));
        var leftover = target.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            target.getWorld().dropItemNaturally(target.getLocation(), stack);
        }

        // Tracker-Eintrag neu anlegen.
        String itemUuid = factory.getRelicUuid(stack);
        RelicState state = new RelicState(
                relicId, UUID.fromString(itemUuid), target.getUniqueId(),
                target.getWorld().getName(),
                target.getLocation().getX(), target.getLocation().getY(), target.getLocation().getZ(),
                -1, -1, -1, -1,
                System.currentTimeMillis(),
                true, false, false
        );
        plugin.getRelicTracker().put(relicId, state);
        plugin.getRelicTracker().save();

        sender.sendMessage(Component.text("Relikt '" + relicId + "' an " + target.getName() + " gegeben.", NamedTextColor.GREEN));
        target.sendMessage(Component.text("Du hast das legendäre Relikt '" + relicId + "' erhalten!", NamedTextColor.GOLD));
    }

    private void handleRevoke(CommandSender sender, String[] args) {
        if (!isOwner(sender)) {
            sender.sendMessage(Component.text("Nur der registrierte Owner darf Relikte entfernen.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /relic revoke <Spieler>", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Spieler nicht online: " + args[1], NamedTextColor.RED));
            return;
        }

        // Alle Relikt-Items aus Inventar entfernen
        int removed = 0;
        for (ItemStack stack : target.getInventory().getContents()) {
            if (stack != null && factory.isRelic(stack)) {
                target.getInventory().remove(stack);
                removed++;
                plugin.getRelicTracker().remove(factory.getRelicId(stack));
            }
        }
        plugin.getRelicTracker().save();

        sender.sendMessage(Component.text("Entfernte " + removed + " Relikte von " + target.getName(), NamedTextColor.GREEN));
    }

    /**
     * /relic untrack <Relikt-ID> — Entfernt den Tracker-Eintrag eines Relikts.
     * Nötig wenn das Item physisch gelöscht wurde (z.B. via clear) und der Tracker
     * sonst für immer glaubt, es existiere noch. NUR der Owner darf das.
     */
    private void handleUntrack(CommandSender sender, String[] args) {
        if (!isOwner(sender)) {
            sender.sendMessage(Component.text("Nur der registrierte Owner darf Tracker-Einträge entfernen.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /relic untrack <Relikt-ID>", NamedTextColor.RED));
            return;
        }
        String relicId = args[1].toLowerCase();
        if (!plugin.getRelicTracker().exists(relicId)) {
            sender.sendMessage(Component.text("Relikt '" + relicId + "' ist nicht getrackt.", NamedTextColor.YELLOW));
            return;
        }
        plugin.getRelicTracker().remove(relicId);
        plugin.getRelicTracker().save();
        sender.sendMessage(Component.text("Tracker-Eintrag für '" + relicId + "' entfernt. Das Relikt kann jetzt neu verteilt werden.", NamedTextColor.GREEN));

        // Item auch aus allen Inventaren/Drops entfernen, falls es doch noch existiert
        var leftover = new java.util.ArrayList<ItemStack>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack stack : p.getInventory().getContents()) {
                if (stack != null && relicId.equals(factory.getRelicId(stack))) {
                    p.getInventory().remove(stack);
                    leftover.add(stack);
                }
            }
        }
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Item item : w.getEntitiesByClass(org.bukkit.entity.Item.class)) {
                if (relicId.equals(factory.getRelicId(item.getItemStack()))) {
                    item.remove();
                }
            }
        }
        if (!leftover.isEmpty()) {
            sender.sendMessage(Component.text("Warnung: Das Item existierte noch (" + leftover.size() + "x) und wurde aus allen Inventaren entfernt!", NamedTextColor.YELLOW));
        }
    }

    private void handleTracker(CommandSender sender, String[] args) {
        if (!sender.hasPermission("relics.tracker")) {
            sender.sendMessage(Component.text("Keine Berechtigung.", NamedTextColor.RED));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("gui")) {
            com.notschie.relics.gui.RelicTrackerGUI.open(sender);
            return;
        }

        String filter = args.length >= 2 ? args[1].toLowerCase() : null;

        // Topaktueller Sync der Online-Spieler
        plugin.getRelicTracker().syncOnlinePlayers();

        sender.sendMessage(Component.text("=== Relikt-Tracker" + (filter != null ? " (Filter: " + filter + ")" : "") + " ===").color(NamedTextColor.GOLD));
        if (plugin.getRelicTracker().all().isEmpty()) {
            sender.sendMessage(Component.text("Keine Relikte im Tracker. Owner hat noch nichts verteilt.", NamedTextColor.GRAY));
            return;
        }

        Map<String, List<RelicState>> grouped = plugin.getRelicTracker().getGroupedByRelic();

        record RelicChatEntry(
                String relicId,
                RelicDefinition def,
                List<RelicState> instances,
                Set<UUID> uniquePeople,
                int peopleCount,
                int totalCount
        ) {}

        List<RelicChatEntry> entries = new ArrayList<>();
        for (var groupEntry : grouped.entrySet()) {
            String relicId = groupEntry.getKey();
            List<RelicState> instances = groupEntry.getValue();
            Set<UUID> people = new LinkedHashSet<>();
            for (RelicState s : instances) {
                if (s.inInventory() && s.ownerUuid() != null) {
                    people.add(s.ownerUuid());
                }
            }
            RelicDefinition def = plugin.getDefinition(relicId);
            entries.add(new RelicChatEntry(relicId, def, instances, people, people.size(), instances.size()));
        }

        // Sortieren nach Menge der Leute, die das Relikt haben (absteigend)
        entries.sort((a, b) -> {
            int cmp = Integer.compare(b.peopleCount(), a.peopleCount());
            if (cmp != 0) return cmp;
            int cmpTotal = Integer.compare(b.totalCount(), a.totalCount());
            if (cmpTotal != 0) return cmpTotal;
            return a.relicId().compareToIgnoreCase(b.relicId());
        });

        int shown = 0;
        for (RelicChatEntry entry : entries) {
            String relicId = entry.relicId();
            if (filter != null) {
                boolean matches = relicId.toLowerCase().contains(filter);
                if (!matches && entry.def() != null) {
                    matches = entry.def().name().toLowerCase().contains(filter);
                }
                if (!matches) {
                    for (UUID uid : entry.uniquePeople()) {
                        String name = Bukkit.getOfflinePlayer(uid).getName();
                        if (name != null && name.toLowerCase().contains(filter)) {
                            matches = true;
                            break;
                        }
                    }
                }
                if (!matches) continue;
            }

            shown++;
            Component title = (entry.def() != null) ? factory.parse(entry.def().name()) : Component.text(relicId, NamedTextColor.YELLOW);
            sender.sendMessage(title.append(Component.text(" (" + entry.peopleCount() + " Besitzer, " + entry.totalCount() + " im Umlauf):").color(NamedTextColor.DARK_GRAY)));

            if (entry.instances().isEmpty()) {
                sender.sendMessage(Component.text("  • ").color(NamedTextColor.DARK_GRAY).append(Component.text("Niemand", NamedTextColor.GRAY)));
            } else {
                Map<UUID, List<RelicState>> playerInstances = new LinkedHashMap<>();
                List<RelicState> containers = new ArrayList<>();
                List<RelicState> ground = new ArrayList<>();
                for (RelicState s : entry.instances()) {
                    if (s.inInventory() && s.ownerUuid() != null) {
                        playerInstances.computeIfAbsent(s.ownerUuid(), k -> new ArrayList<>()).add(s);
                    } else if (s.inContainer()) {
                        containers.add(s);
                    } else if (s.onGround()) {
                        ground.add(s);
                    }
                }

                for (var pEntry : playerInstances.entrySet()) {
                    UUID uuid = pEntry.getKey();
                    List<RelicState> pStates = pEntry.getValue();
                    RelicState s = pStates.get(0);
                    Player op = Bukkit.getPlayer(uuid);
                    String name = op != null ? op.getName() : Bukkit.getOfflinePlayer(uuid).getName();
                    if (name == null) name = uuid.toString().substring(0, 8);
                    boolean isOnline = (op != null && op.isOnline());
                    String countSuffix = pStates.size() > 1 ? " (" + pStates.size() + "x)" : "";

                    sender.sendMessage(Component.text("  • ").color(NamedTextColor.GREEN)
                            .append(Component.text(name + countSuffix, NamedTextColor.WHITE))
                            .append(Component.text(isOnline ? " (Online)" : " (Offline)").color(isOnline ? NamedTextColor.DARK_GREEN : NamedTextColor.DARK_GRAY))
                            .append(Component.text(" [Inventar @ " + s.worldName() + " (" + (int)s.x() + "/" + (int)s.y() + "/" + (int)s.z() + "), vor " + ((System.currentTimeMillis() - s.lastActivity()) / 1000) + "s]", NamedTextColor.GRAY)));
                }

                for (RelicState s : containers) {
                    sender.sendMessage(Component.text("  • ").color(NamedTextColor.YELLOW)
                            .append(Component.text("Container @ " + s.worldName() + " (" + s.containerX() + "/" + s.containerY() + "/" + s.containerZ() + ", Slot " + s.containerSlot() + ", vor " + ((System.currentTimeMillis() - s.lastActivity()) / 1000) + "s)", NamedTextColor.WHITE)));
                }

                for (RelicState s : ground) {
                    sender.sendMessage(Component.text("  • ").color(NamedTextColor.RED)
                            .append(Component.text("Boden @ " + s.worldName() + " (" + (int)s.x() + "/" + (int)s.y() + "/" + (int)s.z() + ", vor " + ((System.currentTimeMillis() - s.lastActivity()) / 1000) + "s)", NamedTextColor.WHITE)));
                }
            }
        }

        if (filter != null && shown == 0) {
            sender.sendMessage(Component.text("Keine Relikte gefunden für '" + filter + "'.", NamedTextColor.GRAY));
        }

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text("Tippe /relic tracker gui für Detail-Ansicht.", NamedTextColor.GRAY));
    }

    private void handleReload(CommandSender sender) {
        if (!isOwner(sender)) {
            sender.sendMessage(Component.text("Nur der Owner.", NamedTextColor.RED));
            return;
        }
        // Es gibt keine config.yml mehr — reloadConfig() entfällt. Die Relikt-Definitionen
        // sind hardcoded in RelicRegistry.java und werden ohnehin beim Klassenladen
        // in die Map gespiegelt. Dieser Befehl triggert einen Tracker-Save und bestätigt,
        // dass das Plugin weiterhin sauber läuft.
        plugin.getRelicTracker().save();
        sender.sendMessage(Component.text("Plugin-Hardcode neu geladen. " + plugin.allDefinitions().size() + " Relikte.", NamedTextColor.GREEN));
    }

    private boolean isOwner(CommandSender sender) {
        UUID owner = plugin.getOwnerUuid();
        if (owner == null) return false;
        // Konsolenbefehl: Owner-Permission
        if (sender instanceof Player p) {
            return p.getUniqueId().equals(owner) || p.hasPermission("relics.admin." + owner.toString().replace("-", ""));
        }
        return true; // Konsole = Owner
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("register", "give", "revoke", "untrack", "tracker", "reload", "tests", "pack");
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("pack")) {
            // PackCommand-Tab-Completion
            return packCommand.tabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("untrack") || args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("removetracker"))) {
            return new ArrayList<>(plugin.allDefinitions().keySet());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("tests") || args[0].equalsIgnoreCase("test") || args[0].equalsIgnoreCase("debug"))) {
            return List.of("create", "give", "giveall", "list", "info", "owner", "removeall", "save");
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("tests") || args[0].equalsIgnoreCase("test") || args[0].equalsIgnoreCase("debug")) && args[1].equalsIgnoreCase("give")) {
            return new ArrayList<>(plugin.allDefinitions().keySet());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("tracker") || args[0].equalsIgnoreCase("list"))) {
            List<String> list = new ArrayList<>();
            list.add("gui");
            list.addAll(plugin.allDefinitions().keySet());
            list.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            return list;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            // An args[2] denken wir sind im Player-Format. Aber wenn User direkt die Relikt-ID
            // tippt (z. B. "vampire_knives"), wäre das ein "Relikt"-Slot. Beides anbieten.
            java.util.List<String> suggestions = new java.util.ArrayList<>();
            suggestions.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            suggestions.addAll(plugin.allDefinitions().keySet());
            return suggestions;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            // Wenn args[1] ein Spieler war, ist args[2] das Relikt. Wenn args[1] ein Relikt
            // war, ist args[2] der Spieler. Beides anbieten, was gut passt:
            if (plugin.getDefinition(args[1]) != null) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            }
            return new ArrayList<>(plugin.allDefinitions().keySet());
        }
        return List.of();
    }
}