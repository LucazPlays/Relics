package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Eisgrab per-Hit-Effekte:
 *
 *  - Slowness + Mining Fatigue: Level steigt pro Hit additiv (Cap = Amplifier 4 = Level V).
 *  - Feuerticks des Targets werden auf 0 gesetzt (Kälte löscht Feuer).
 *  - Schneepartikel am Trefferpunkt (visuelles Feedback).
 *
 * Das aktuelle Level des Effekts auf dem Target ist die einzige Quelle der Wahrheit
 * (Bukkit trägt es auf der LivingEntity). Beim Re-Apply: neuer Amplifier = min(4, current + delta),
 * neue Dauer (konfigurierbar, default 40 Ticks = 2s).
 *
 * Greift NUR, wenn der Spieler das Eisgrab in Haupt- oder Nebenhand hält.
 */
public class IceGrabOnHitListener implements Listener {

    private static final String RELIC_ID = "ice_grab";
    /** Bukkit-Hard-Cap für Effekt-Amplifier (Level V). */
    private static final int MAX_AMPLIFIER = 4;

    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    public IceGrabOnHitListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private ItemStack findIceGrab(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (isIceGrab(main)) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        if (isIceGrab(off)) return off;
        return null;
    }

    private boolean isIceGrab(ItemStack stack) {
        if (stack == null || !factory.isRelic(stack)) return false;
        return RELIC_ID.equals(factory.getRelicId(stack));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target.isDead()) return;

        ItemStack weapon = findIceGrab(attacker);
        if (weapon == null) return;

        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;

        // Slowness: aktuellen Amplifier lesen, +delta, bei maxAmplifier kappen.
        int slownessDelta = def.getEffectInt("on-hit-slowness-amplifier-per-hit", 1);
        if (slownessDelta > 0) {
            int duration = def.getEffectInt("on-hit-slowness-duration-ticks", 40);
            applyStackedEffect(target, PotionEffectType.SLOWNESS, slownessDelta, duration);
        }

        // Mining Fatigue: gleiche Mechanik.
        int fatigueDelta = def.getEffectInt("on-hit-fatigue-amplifier-per-hit", 1);
        if (fatigueDelta > 0) {
            int duration = def.getEffectInt("on-hit-fatigue-duration-ticks", 40);
            applyStackedEffect(target, PotionEffectType.MINING_FATIGUE, fatigueDelta, duration);
        }

        // Feuer löschen (Kälte → Brand aus).
        if (def.getEffectBoolean("on-hit-clear-fire", true) && target.getFireTicks() > 0) {
            target.setFireTicks(0);
        }

        // Schneepartikel am Target (Trefferfeedback).
        if (def.getEffectBoolean("on-hit-snow-particles", true)) {
            target.getWorld().spawnParticle(
                    Particle.SNOWFLAKE,
                    target.getEyeLocation(),
                    30,    // count
                    0.4, 0.5, 0.4, // offset
                    0.05   // speed
            );
        }
    }

    /**
     * Liest das aktuelle Amplifier-Level vom Target, erhöht um delta (Cap MAX_AMPLIFIER)
     * und setzt einen neuen Potion-Effekt mit der gewünschten Dauer.
     */
    private void applyStackedEffect(LivingEntity target, PotionEffectType type, int delta, int durationTicks) {
        int current = 0;
        PotionEffect existing = target.getPotionEffect(type);
        if (existing != null) {
            current = existing.getAmplifier();
        }
        int next = Math.min(MAX_AMPLIFIER, current + delta);
        if (next == current && existing != null) {
            // Effekt steht schon auf Max. Nur die Dauer refreshen, sonst nichts.
            target.addPotionEffect(new PotionEffect(type, durationTicks, current, true, false));
            return;
        }
        target.addPotionEffect(new PotionEffect(type, durationTicks, next, true, false));
    }
}
