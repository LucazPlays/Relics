package com.notschie.relics.gui;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.model.RelicState;
import com.notschie.relics.util.RelicFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Chest-Menu für die Relikt-Übersicht und Detail-Ansicht.
 * 
 * 1. Hauptmenü (TrackerMainHolder):
 *    - Jedes Relikt belegt genau einen Slot mit echter Textur / ItemModel.
 *    - Sortiert ABSTEIGEND nach der Menge der Leute, die das Relikt besitzen!
 *    - Stack-Größe entspricht der Anzahl der Besitzer.
 *    - Lore zählt alle aktuellen Besitzer auf.
 *    - Klick öffnet das Detail-Menü für dieses Relikt.
 * 
 * 2. Detailmenü (TrackerDetailHolder):
 *    - Zeigt jede einzelne getrackte Instanz (Player-Head für Spieler, Chest für Container, Torch für Drops).
 *    - Enthält Zurück-Button (« Zurück zur Übersicht).
 */
public class RelicTrackerGUI implements Listener {

    private static final Component GUI_TITLE = Component.text("Relikt-Tracker").color(NamedTextColor.GOLD);
    private static RelicTrackerGUI instance;

    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    public static class TrackerMainHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static class TrackerDetailHolder implements InventoryHolder {
        private final String relicId;

        public TrackerDetailHolder(String relicId) {
            this.relicId = relicId;
        }

        public String getRelicId() {
            return relicId;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public RelicTrackerGUI(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
        instance = this;
    }

    public static void open(CommandSender sender) {
        if (sender instanceof Player p) {
            RelicsPlugin plugin = RelicsPlugin.getInstance();
            if (instance == null) {
                instance = new RelicTrackerGUI(plugin);
                Bukkit.getPluginManager().registerEvents(instance, plugin);
            }
            instance.openMainMenu(p);
        } else {
            sender.sendMessage(Component.text("GUI ist nur für Spieler.", NamedTextColor.RED));
        }
    }

    public static org.bukkit.plugin.java.JavaPlugin getInstance() {
        return (org.bukkit.plugin.java.JavaPlugin) Bukkit.getPluginManager().getPlugin("Relics");
    }

    /**
     * Öffnet das Hauptmenü: Alle Relikte sortiert nach Menge der Leute, die das Relikt haben.
     */
    public void openMainMenu(Player p) {
        plugin.getRelicTracker().syncOnlinePlayers();

        Inventory inv = Bukkit.createInventory(new TrackerMainHolder(), 54, GUI_TITLE);

        // Hintergrund mit Glass Panes füllen
        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta bgMeta = bg.getItemMeta();
        if (bgMeta != null) {
            bgMeta.displayName(Component.text(" "));
            bg.setItemMeta(bgMeta);
        }
        for (int i = 0; i < 54; i++) inv.setItem(i, bg);

        Map<String, RelicDefinition> allDefs = plugin.allDefinitions();
        Map<String, List<RelicState>> grouped = plugin.getRelicTracker().getGroupedByRelic();

        record RelicEntry(
                String relicId,
                RelicDefinition def,
                List<RelicState> instances,
                Set<UUID> uniquePeople,
                int peopleCount,
                int totalCount
        ) {}

        List<RelicEntry> entries = new ArrayList<>();
        for (var entry : allDefs.entrySet()) {
            String relicId = entry.getKey();
            RelicDefinition def = entry.getValue();
            List<RelicState> instances = grouped.getOrDefault(relicId, Collections.emptyList());

            Set<UUID> people = new LinkedHashSet<>();
            for (RelicState s : instances) {
                if (s.inInventory() && s.ownerUuid() != null) {
                    people.add(s.ownerUuid());
                }
            }
            entries.add(new RelicEntry(relicId, def, instances, people, people.size(), instances.size()));
        }

        for (var groupEntry : grouped.entrySet()) {
            String relicId = groupEntry.getKey();
            if (!allDefs.containsKey(relicId)) {
                List<RelicState> instances = groupEntry.getValue();
                Set<UUID> people = new LinkedHashSet<>();
                for (RelicState s : instances) {
                    if (s.inInventory() && s.ownerUuid() != null) {
                        people.add(s.ownerUuid());
                    }
                }
                entries.add(new RelicEntry(relicId, null, instances, people, people.size(), instances.size()));
            }
        }

        // SORTIERUNG:
        // 1. Menge der Leute, die das Relikt haben (peopleCount) absteigend
        // 2. Gesamtanzahl der Exemplare (totalCount) absteigend
        // 3. Name alphabetisch aufsteigend
        entries.sort((a, b) -> {
            int cmp = Integer.compare(b.peopleCount(), a.peopleCount());
            if (cmp != 0) return cmp;
            int cmpTotal = Integer.compare(b.totalCount(), a.totalCount());
            if (cmpTotal != 0) return cmpTotal;
            return a.relicId().compareToIgnoreCase(b.relicId());
        });

        int slot = 0;
        for (RelicEntry re : entries) {
            if (slot >= 45) break;
            inv.setItem(slot, buildMainIcon(re.relicId(), re.def(), re.instances(), re.uniquePeople()));
            slot++;
        }

        // Info-Button (Slot 49)
        ItemStack info = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        if (infoMeta != null) {
            infoMeta.displayName(Component.text("Tracking-Übersicht").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            infoMeta.lore(List.of(
                    Component.text("Sortiert nach Anzahl der Besitzer.").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Klicke ein Relikt für die Einzel-Instanzen.").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                    Component.text(""),
                    Component.text("Aktualisiert sich live beim Öffnen.").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            info.setItemMeta(infoMeta);
        }
        inv.setItem(49, info);

        // Refresh-Button (Slot 53)
        ItemStack refresh = new ItemStack(Material.COMPASS);
        ItemMeta refMeta = refresh.getItemMeta();
        if (refMeta != null) {
            refMeta.displayName(Component.text("Aktualisieren").color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            refMeta.lore(List.of(
                    Component.text("Scannt alle Inventare neu.").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            refresh.setItemMeta(refMeta);
        }
        inv.setItem(53, refresh);

        p.openInventory(inv);
    }

    private ItemStack buildMainIcon(String relicId, RelicDefinition def, List<RelicState> instances, Set<UUID> owners) {
        ItemStack icon;
        if (def != null) {
            icon = factory.createRelic(def);
        } else {
            icon = new ItemStack(Material.CHEST);
        }

        int count = owners.size();
        icon.setAmount(Math.max(1, Math.min(64, count > 0 ? count : 1)));

        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;

        if (def != null) {
            meta.displayName(factory.parse(def.name()).decoration(TextDecoration.ITALIC, false));
            if (def.customModelData() > 0) {
                meta.setCustomModelData(def.customModelData());
            }
            meta.setItemModel(new NamespacedKey("relics", def.id()));
        } else {
            meta.displayName(Component.text(relicId).color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }

        meta.setAttributeModifiers(null);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("ID: " + relicId).color(NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false));

        if (count > 0) {
            lore.add(Component.text("Besitzer: ").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(count + " Spieler").color(NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true))
                    .append(Component.text(" (" + instances.size() + " im Umlauf)").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false)));
        } else if (instances.size() > 0) {
            lore.add(Component.text("Besitzer: ").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("0 Spieler").color(NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
                    .append(Component.text(" (" + instances.size() + " in Welt/Kisten)").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false)));
        } else {
            lore.add(Component.text("Besitzer: ").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("0 Spieler").color(NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
                    .append(Component.text(" (Unverteilt)").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false)));
        }

        lore.add(Component.text("─────────────────────────").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Im Besitz von:").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));

        if (!owners.isEmpty()) {
            Map<UUID, Integer> ownerCounts = new LinkedHashMap<>();
            for (RelicState s : instances) {
                if (s.inInventory() && s.ownerUuid() != null) {
                    ownerCounts.put(s.ownerUuid(), ownerCounts.getOrDefault(s.ownerUuid(), 0) + 1);
                }
            }
            for (var oEntry : ownerCounts.entrySet()) {
                UUID uuid = oEntry.getKey();
                int c = oEntry.getValue();
                Player online = Bukkit.getPlayer(uuid);
                String name = online != null ? online.getName() : Bukkit.getOfflinePlayer(uuid).getName();
                if (name == null) name = uuid.toString().substring(0, 8);

                boolean isOnline = (online != null && online.isOnline());
                String countSuffix = c > 1 ? " (" + c + "x)" : "";
                lore.add(Component.text("  • ").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(name + countSuffix).color(NamedTextColor.WHITE))
                        .append(Component.text(isOnline ? " (Online)" : " (Offline)").color(isOnline ? NamedTextColor.DARK_GREEN : NamedTextColor.DARK_GRAY)));
            }
        }

        int inContainerCount = 0;
        int onGroundCount = 0;
        for (RelicState s : instances) {
            if (s.inContainer()) inContainerCount++;
            else if (s.onGround()) onGroundCount++;
        }
        if (inContainerCount > 0) {
            lore.add(Component.text("  • " + inContainerCount + "x in Container/Kiste").color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        }
        if (onGroundCount > 0) {
            lore.add(Component.text("  • " + onGroundCount + "x am Boden liegend").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        }
        if (owners.isEmpty() && inContainerCount == 0 && onGroundCount == 0) {
            lore.add(Component.text("  • Niemand").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }

        lore.add(Component.text("─────────────────────────").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Klick: Detail-Ansicht öffnen").color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        meta.getPersistentDataContainer().set(factory.relicKey(), PersistentDataType.STRING, relicId);
        icon.setItemMeta(meta);
        return icon;
    }

    public void openDetailMenu(Player p, String relicId) {
        RelicDefinition def = plugin.getDefinition(relicId);
        Component title = Component.text("Details: ").color(NamedTextColor.GOLD)
                .append(def != null ? factory.parse(def.name()) : Component.text(relicId).color(NamedTextColor.YELLOW));

        Inventory inv = Bukkit.createInventory(new TrackerDetailHolder(relicId), 54, title);

        // Hintergrund
        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta bgMeta = bg.getItemMeta();
        if (bgMeta != null) {
            bgMeta.displayName(Component.text(" "));
            bg.setItemMeta(bgMeta);
        }
        for (int i = 0; i < 54; i++) inv.setItem(i, bg);

        List<RelicState> instances = plugin.getRelicTracker().getInstances(relicId);

        if (instances.isEmpty()) {
            ItemStack empty = new ItemStack(Material.BARRIER);
            ItemMeta em = empty.getItemMeta();
            if (em != null) {
                em.displayName(Component.text("Keine Exemplare getrackt").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                em.lore(List.of(
                        Component.text("Dieses Relikt befindet sich aktuell").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.text("bei keinem Spieler und in keiner Kiste.").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                ));
                empty.setItemMeta(em);
            }
            inv.setItem(22, empty);
        } else {
            int slot = 0;
            for (RelicState s : instances) {
                if (slot >= 45) break;
                inv.setItem(slot, buildDetailInstanceIcon(s, def));
                slot++;
            }
        }

        // Relikt-Icon Vorschau (Slot 45)
        if (def != null) {
            ItemStack preview = factory.createRelic(def);
            ItemMeta prevMeta = preview.getItemMeta();
            if (prevMeta != null) {
                prevMeta.displayName(factory.parse(def.name()).decoration(TextDecoration.ITALIC, false));
                prevMeta.setAttributeModifiers(null);
                prevMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
                prevMeta.lore(List.of(
                        Component.text("Relikt: " + relicId).color(NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false),
                        Component.text("Exemplare insgesamt: " + instances.size()).color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                ));
                preview.setItemMeta(prevMeta);
            }
            inv.setItem(45, preview);
        }

        // Zurück-Button (Slot 49)
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.displayName(Component.text("« Zurück zur Übersicht").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
            backMeta.lore(List.of(
                    Component.text("Zurück zum Hauptmenü").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            back.setItemMeta(backMeta);
        }
        inv.setItem(49, back);

        p.openInventory(inv);
    }

    private ItemStack buildDetailInstanceIcon(RelicState state, RelicDefinition def) {
        ItemStack icon;
        if (state.inInventory() && state.ownerUuid() != null) {
            icon = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) icon.getItemMeta();
            if (sm != null) {
                sm.setOwningPlayer(Bukkit.getOfflinePlayer(state.ownerUuid()));
                String ownerName = Bukkit.getOfflinePlayer(state.ownerUuid()).getName();
                if (ownerName == null) ownerName = state.ownerUuid().toString().substring(0, 8);
                sm.displayName(Component.text("Spieler: " + ownerName).color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));

                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("Status: Im Spieler-Inventar").color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Item-UUID: " + state.itemUuid().toString()).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Position: " + state.worldName() + " (" + (int)state.x() + "/" + (int)state.y() + "/" + (int)state.z() + ")").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Letzte Aktivität: " + ((System.currentTimeMillis() - state.lastActivity()) / 1000) + "s her").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.empty());
                lore.add(Component.text("Klick: Info in Chat ausgeben").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                sm.lore(lore);
                icon.setItemMeta(sm);
            }
            return icon;
        } else if (state.inContainer()) {
            icon = new ItemStack(Material.CHEST);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("Container @ " + state.worldName()).color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("Status: In Kiste / Container").color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Item-UUID: " + state.itemUuid().toString()).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Block: " + state.worldName() + " (" + state.containerX() + "/" + state.containerY() + "/" + state.containerZ() + ")").color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Slot im Container: " + state.containerSlot()).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Letzte Aktivität: " + ((System.currentTimeMillis() - state.lastActivity()) / 1000) + "s her").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.empty());
                lore.add(Component.text("Klick: Info in Chat ausgeben").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                icon.setItemMeta(meta);
            }
            return icon;
        } else if (state.onGround()) {
            icon = new ItemStack(Material.REDSTONE_TORCH);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("Am Boden @ " + state.worldName()).color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("Status: Dropped Item (Boden)").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Item-UUID: " + state.itemUuid().toString()).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Position: " + state.worldName() + " (" + (int)state.x() + "/" + (int)state.y() + "/" + (int)state.z() + ")").color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Letzte Aktivität: " + ((System.currentTimeMillis() - state.lastActivity()) / 1000) + "s her").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.empty());
                lore.add(Component.text("Klick: Info in Chat ausgeben").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                icon.setItemMeta(meta);
            }
            return icon;
        } else {
            icon = new ItemStack(Material.ITEM_FRAME);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("Unbekannter Status").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(
                        Component.text("Item-UUID: " + state.itemUuid().toString()).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                ));
                icon.setItemMeta(meta);
            }
            return icon;
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        InventoryHolder holder = event.getInventory().getHolder();

        // 1. Klick im Hauptmenü (TrackerMainHolder)
        if (holder instanceof TrackerMainHolder) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;
            if (clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

            // Klick auf Refresh (Slot 53)
            if (event.getRawSlot() == 53 && clicked.getType() == Material.COMPASS) {
                openMainMenu(p);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
                return;
            }

            // Klick auf ein Relikt-Icon (Slots 0 bis 44)
            if (event.getRawSlot() < 45 && clicked.getType() != Material.WRITABLE_BOOK) {
                String relicId = factory.getRelicId(clicked);
                if (relicId == null && clicked.getItemMeta() != null && clicked.getItemMeta().getPersistentDataContainer().has(factory.relicKey())) {
                    relicId = clicked.getItemMeta().getPersistentDataContainer().get(factory.relicKey(), PersistentDataType.STRING);
                }
                if (relicId != null) {
                    p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.0f);
                    openDetailMenu(p, relicId);
                }
            }
            return;
        }

        // 2. Klick im Detailmenü (TrackerDetailHolder)
        if (holder instanceof TrackerDetailHolder detailHolder) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;
            if (clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

            // Zurück-Button (Slot 49)
            if (event.getRawSlot() == 49 && clicked.getType() == Material.ARROW) {
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 0.8f);
                openMainMenu(p);
                return;
            }

            // Klick auf ein Exemplar (Slots 0 bis 44)
            if (event.getRawSlot() < 45 && clicked.getType() != Material.BARRIER) {
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.0f);
                p.sendMessage(Component.text("=== Relikt: " + detailHolder.getRelicId() + " ===").color(NamedTextColor.GOLD));
                if (clicked.getItemMeta() != null && clicked.getItemMeta().hasLore()) {
                    for (Component loreLine : clicked.getItemMeta().lore()) {
                        p.sendMessage(loreLine);
                    }
                }
            }
        }
    }
}

