package com.notschie.relics.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RelicTracker {
    private final RelicsPlugin plugin;
    private final File dataFile;
    private final Map<String, RelicState> states = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public RelicTracker(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "relics-tracker.json");
    }

    public Map<String, RelicState> all() {
        return new HashMap<>(states);
    }

    public List<RelicState> allStates() {
        return new ArrayList<>(states.values());
    }

    public RelicState get(String idOrUuid) {
        if (idOrUuid == null) return null;
        if (states.containsKey(idOrUuid)) return states.get(idOrUuid);
        for (RelicState s : states.values()) {
            if (s.relicId().equalsIgnoreCase(idOrUuid)) return s;
        }
        return null;
    }

    public List<RelicState> getInstances(String relicId) {
        if (relicId == null) return Collections.emptyList();
        List<RelicState> list = new ArrayList<>();
        for (RelicState s : states.values()) {
            if (s.relicId().equalsIgnoreCase(relicId)) {
                list.add(s);
            }
        }
        return list;
    }

    public Map<String, List<RelicState>> getGroupedByRelic() {
        Map<String, List<RelicState>> map = new LinkedHashMap<>();
        for (RelicState s : states.values()) {
            map.computeIfAbsent(s.relicId(), k -> new ArrayList<>()).add(s);
        }
        return map;
    }

    public void put(String id, RelicState s) {
        if (s == null) return;
        String key = (s.itemUuid() != null) ? s.itemUuid().toString() : id;
        states.put(key, s);
    }

    public void put(RelicState s) {
        if (s == null) return;
        String key = (s.itemUuid() != null) ? s.itemUuid().toString() : UUID.randomUUID().toString();
        states.put(key, s);
    }

    public void remove(String idOrUuid) {
        if (idOrUuid == null) return;
        states.remove(idOrUuid);
        states.values().removeIf(s -> s.relicId().equalsIgnoreCase(idOrUuid));
    }

    public boolean exists(String idOrUuid) {
        if (idOrUuid == null) return false;
        if (states.containsKey(idOrUuid)) return true;
        for (RelicState s : states.values()) {
            if (s.relicId().equalsIgnoreCase(idOrUuid)) return true;
        }
        return false;
    }

    public RelicState findByItemUuid(String itemUuid) {
        if (itemUuid == null) return null;
        if (states.containsKey(itemUuid)) return states.get(itemUuid);
        for (RelicState s : states.values()) {
            if (s.itemUuid() != null && s.itemUuid().toString().equalsIgnoreCase(itemUuid)) return s;
        }
        return null;
    }

    /**
     * Scannt alle Online-Spieler und aktualisiert deren Relikte im Tracker.
     * Stellt sicher, dass jeder Spieler mit einem Relikt zuverlässig erfasst wird.
     */
    public void syncOnlinePlayers() {
        RelicFactory factory = plugin.getRelicFactory();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Set<String> currentUuids = new HashSet<>();
            for (ItemStack stack : p.getInventory().getContents()) {
                if (stack == null || !factory.isRelic(stack)) continue;
                String relicId = factory.getRelicId(stack);
                String uuidStr = factory.getRelicUuid(stack);
                if (relicId == null) continue;
                if ("death_scepter".equals(relicId) && stack.getType() == Material.MACE) {
                    stack.setType(Material.NETHERITE_SWORD);
                }
                UUID itemUuid;
                if (uuidStr != null) {
                    try {
                        itemUuid = UUID.fromString(uuidStr);
                    } catch (Exception e) {
                        itemUuid = UUID.randomUUID();
                    }
                } else {
                    itemUuid = UUID.randomUUID();
                }
                currentUuids.add(itemUuid.toString());
                RelicState s = new RelicState(
                        relicId,
                        itemUuid,
                        p.getUniqueId(),
                        p.getWorld().getName(),
                        p.getLocation().getX(),
                        p.getLocation().getY(),
                        p.getLocation().getZ(),
                        -1, -1, -1, -1,
                        System.currentTimeMillis(),
                        true, false, false
                );
                states.put(itemUuid.toString(), s);
            }
            // Bereinigen: Alte Einträge, die dieser Online-Spieler gar nicht mehr besitzt
            states.values().removeIf(s -> s.inInventory() && p.getUniqueId().equals(s.ownerUuid()) && !currentUuids.contains(s.itemUuid().toString()));
        }
    }

    public void save() {
        try {
            if (!dataFile.getParentFile().exists()) dataFile.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(dataFile)) { gson.toJson(states, w); }
        } catch (IOException e) { plugin.getLogger().warning("Tracker save failed: " + e.getMessage()); }
    }

    public void load() {
        if (!dataFile.exists()) return;
        try (FileReader r = new FileReader(dataFile)) {
            Type type = new TypeToken<ConcurrentHashMap<String, RelicState>>() {}.getType();
            Map<String, RelicState> loaded = gson.fromJson(r, type);
            if (loaded != null) {
                states.clear();
                for (var entry : loaded.entrySet()) {
                    RelicState s = entry.getValue();
                    if (s != null) {
                        String key = (s.itemUuid() != null) ? s.itemUuid().toString() : entry.getKey();
                        states.put(key, s);
                    }
                }
                plugin.getLogger().info("Tracker loaded: " + states.size() + " relics.");
            }
        } catch (IOException e) { plugin.getLogger().warning("Tracker load failed: " + e.getMessage()); }
    }
}

