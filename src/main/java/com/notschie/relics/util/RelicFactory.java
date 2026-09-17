package com.notschie.relics.util;

import com.notschie.relics.RelicsPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import java.util.*;

public class RelicFactory {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RelicsPlugin plugin;

    public RelicFactory(RelicsPlugin plugin) {
        this.plugin = plugin;
    }

    public NamespacedKey relicKey() {
        return new NamespacedKey(plugin, "relic_id");
    }

    public NamespacedKey relicUuidKey() {
        return new NamespacedKey(plugin, "relic_uuid");
    }

    public ItemStack createRelic(com.notschie.relics.model.RelicDefinition def) {
        ItemStack stack = new ItemStack(def.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.displayName(parse(def.name()));
        List<Component> loreComps = new ArrayList<>();
        for (String line : def.lore()) loreComps.add(parse(line));
        meta.lore(loreComps);
        for (var e : def.enchantments().entrySet()) meta.addEnchant(e.getKey(), e.getValue(), true);
        if (def.unbreakable()) meta.setUnbreakable(true);
        if (def.glow()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        if (def.customModelData() > 0) meta.setCustomModelData(def.customModelData());
        meta.setItemModel(new NamespacedKey("relics", def.id()));

        // Attribut-Modifier aus den Effects (z. B. Spitzhacke des Donnerrers:
        // +14 Attack-Damage, Attack-Speed 1.6 gesamt).
        double attackDmg = def.getEffectDouble("held-attack-damage", 0);
        if (attackDmg > 0) {
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE,
                    new AttributeModifier(new NamespacedKey(plugin, "relic_attack_damage"),
                            attackDmg, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        }
        double attackSpeed = def.getEffectDouble("held-attack-speed", 0);
        if (attackSpeed > 0) {
            // held-attack-speed = ZIEL-Attack-Speed ABSOLUT (z. B. 1.6).
            // Vanilla-Basis ist 4.0, das Werkzeug-Material bringt eigene Modifier
            // mit (Netherite-Pickaxe −2.8 → 1.2 im Tooltip). Wir berechnen den
            // nötigen additiven Offset: Ziel − Ist-Wert.
            //
            // Paper-API-Hinweis: getAttributeModifiers(Attribute) kann seit neueren
            // Versionen NULL liefern, wenn das Material gar keine impliziten Modifier
            // mitbringt (z. B. manche Custom-Materials oder Paper-Builds ohne Forge).
            // Daher Null-Safe-Iteration (sonst NPE in RelicFactory.createRelic).
            double current = 4.0;
            java.util.Collection<AttributeModifier> existing = meta.getAttributeModifiers(Attribute.ATTACK_SPEED);
            if (existing != null) {
                for (AttributeModifier am : existing) {
                    if (am.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                        current += am.getAmount();
                    }
                }
            }
            double delta = attackSpeed - current;
            meta.addAttributeModifier(Attribute.ATTACK_SPEED,
                    new AttributeModifier(new NamespacedKey(plugin, "relic_attack_speed"),
                            delta, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(relicKey(), PersistentDataType.STRING, def.id());
        pdc.set(relicUuidKey(), PersistentDataType.STRING, UUID.randomUUID().toString());
        stack.setItemMeta(meta);
        return stack;
    }

    public String getRelicId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(relicKey(), PersistentDataType.STRING);
    }

    public String getRelicUuid(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(relicUuidKey(), PersistentDataType.STRING);
    }

    public boolean isRelic(ItemStack stack) {
        return getRelicId(stack) != null;
    }

    /**
     * Parst einen String als MiniMessage.
     * Konvertiert dabei zuerst &-Codes zu echten MiniMessage-Tags,
     * sodass sowohl "&c&lFeuer" als auch "<gradient:gold:yellow>" funktionieren.
     */
    public Component parse(String text) {
        return MM.deserialize(convertLegacy(text));
    }

    private static final java.util.Map<Character, String> LEGACY_MAP = new java.util.HashMap<>();
    static {
        LEGACY_MAP.put('0', "<black>");
        LEGACY_MAP.put('1', "<dark_blue>");
        LEGACY_MAP.put('2', "<dark_green>");
        LEGACY_MAP.put('3', "<dark_aqua>");
        LEGACY_MAP.put('4', "<dark_red>");
        LEGACY_MAP.put('5', "<dark_purple>");
        LEGACY_MAP.put('6', "<gold>");
        LEGACY_MAP.put('7', "<gray>");
        LEGACY_MAP.put('8', "<dark_gray>");
        LEGACY_MAP.put('9', "<blue>");
        LEGACY_MAP.put('a', "<green>");
        LEGACY_MAP.put('b', "<aqua>");
        LEGACY_MAP.put('c', "<red>");
        LEGACY_MAP.put('d', "<light_purple>");
        LEGACY_MAP.put('e', "<yellow>");
        LEGACY_MAP.put('f', "<white>");
        LEGACY_MAP.put('k', "<obfuscated>");
        LEGACY_MAP.put('l', "<bold>");
        LEGACY_MAP.put('m', "<strikethrough>");
        LEGACY_MAP.put('n', "<underline>");
        LEGACY_MAP.put('o', "<italic>");
        LEGACY_MAP.put('r', "<reset>");
    }

    private static String convertLegacy(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                String tag = LEGACY_MAP.get(code);
                if (tag != null) {
                    sb.append(tag);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
