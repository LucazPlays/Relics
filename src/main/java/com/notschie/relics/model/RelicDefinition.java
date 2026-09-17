package com.notschie.relics.model;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

import java.util.List;
import java.util.Map;

/**
 * Datenklasse für eine Relikt-Definition (hardcoded, siehe {@link RelicRegistry}).
 */
public record RelicDefinition(
        String id,
        Material material,
        String name,
        List<String> lore,
        Map<Enchantment, Integer> enchantments,
        boolean glow,
        boolean unbreakable,
        int customModelData,
        Map<String, Object> effects,
        String heldMessage
) {
    public Object getEffect(String key) { return effects.get(key); }

    public int getEffectInt(String key, int def) {
        Object v = effects.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    public double getEffectDouble(String key, double def) {
        Object v = effects.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    public boolean getEffectBoolean(String key, boolean def) {
        Object v = effects.get(key);
        return v instanceof Boolean b ? b : def;
    }
}