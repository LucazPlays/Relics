package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.NoFallDamageManager;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Schild-Dash-on-Sneak: Wenn der Spieler MIT DEM ATLAS-SCHILD aktiv blockt UND sneakt,
 * wird er horizontal 4 Blöcke nach vorne gedash-t und trifft alle Entities im Pfad.
 *
 * Trigger-Bedingungen:
 *  - Spieler blockt aktiv (Player.isBlocking())
 *  - Spieler sneakt (Player.isSneaking())
 *  - Schild des Atlas in Haupt- oder Nebenhand
 *  - Cooldown ist bereit
 *
 * KEIN ankommender Schlag nötig — der Dash wird rein über Sneak+Block ausgelöst.
 * Wir pollen jeden Tick (startSync) und feuern nur bei Rising-Edge
 * (false → true), damit pro Sneak+Block-Session nur ein Dash kommt.
 *
 * Rework v2: Sneak+Block-Trigger, Cooldown-ActionBar, NoFallDamage-Window (10s).
 */
public class ShieldDashListener implements Listener {

    private static final String RELIC_ID = "shield_of_atlas";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final NoFallDamageManager noFallManager;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Boolean> prevSneakBlock = new HashMap<>();
    private BukkitTask syncTask;

    public ShieldDashListener(RelicsPlugin plugin, NoFallDamageManager noFallManager) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
        this.noFallManager = noFallManager;
    }

    /** Sync-Task starten (jeden Tick Sneak+Block-Trigger prüfen). */
    public void startSync() {
        if (syncTask != null) return;
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                checkSneakBlockTrigger(p);
            }
        }, 1L, 1L);
    }

    /** Sync-Task stoppen (Plugin-Reload/Disable). */
    public void stopSync() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
    }

    /**
     * Polling-Trigger: löst den Dash aus, sobald Sneak+Block neu aktiviert wurde
     * (Rising-Edge) — also Übergang von "nicht (sneak+block)" → "sneak+block".
     * So wird pro "Sneak+Block-Session" nur ein Dash gefeuert; der Cooldown verhindert
     * Spam innerhalb derselben Session.
     */
    private void checkSneakBlockTrigger(Player p) {
        if (!p.isOnline()) return;
        ItemStack shield = findAtlasShield(p);
        if (shield == null) {
            prevSneakBlock.remove(p.getUniqueId());
            return;
        }
        boolean active = p.isBlocking() && p.isSneaking();
        Boolean prev = prevSneakBlock.get(p.getUniqueId());
        prevSneakBlock.put(p.getUniqueId(), active);
        // Rising-Edge: nur feuern wenn der State von false → true wechselt.
        if (!active || Boolean.TRUE.equals(prev)) return;

        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null || !def.getEffectBoolean("shield-dash-on-block", true)) return;

        long now = System.currentTimeMillis();
        Long until = cooldowns.get(p.getUniqueId());
        if (until != null && now < until) {
            // Cooldown noch nicht bereit — ActionBar mit verbleibenden Sekunden.
            double secLeft = (until - now) / 1000.0;
            p.sendActionBar(MM.deserialize(
                "<gradient:#ffd700:#aa5500><bold>Atlas</bold> <dark_gray>» <red>Dash lädt noch <white>"
                + String.format("%.1f", secLeft) + "s <red>auf."));
            return;
        }

        long cdMs = (long) def.getEffectDouble("shield-dash-cooldown-ms", 12000.0);
        int distance = def.getEffectInt("shield-dash-distance", 4);
        double speed = def.getEffectDouble("shield-dash-speed", 0.7);
        double dashDmg = def.getEffectDouble("shield-dash-damage", 10.0);
        boolean particles = def.getEffectBoolean("shield-dash-particle", true);
        boolean sounds = def.getEffectBoolean("shield-dash-sound", true);
        long noFallMs = (long) def.getEffectDouble("shield-nofall-duration-ms", 10000.0);

        cooldowns.put(p.getUniqueId(), now + cdMs);
        performDash(p, distance, speed, dashDmg, particles, sounds, noFallMs);
    }

    /** Dash-Logik: Velocity setzen, Partikel, Sound, AOE-Schaden entlang des Pfads. */
    private void performDash(Player p, int distance, double speed, double dashDmg,
                             boolean particles, boolean sounds, long noFallMs) {
        // Horizontale Richtung (Y auf 0). Spieler schaut nach oben/unten → kein Dash.
        Vector dir = p.getLocation().getDirection().clone();
        if (dir.lengthSquared() < 1e-6) return;
        dir.setY(0);
        if (dir.lengthSquared() < 1e-6) return;
        dir.normalize();

        // Velocity: horizontal full speed, vertikal minimaler Hub (0.1), damit der
        // Dash flüssig wirkt und der Spieler nicht an der Bodenkante hängt.
        Vector velocity = dir.multiply(speed);
        velocity.setY(0.1);
        p.setVelocity(velocity);

        // Partikel + Sound entlang des Pfads.
        World w = p.getWorld();
        Location start = p.getLocation().clone();
        if (sounds) {
            w.playSound(start, Sound.ITEM_SHIELD_BREAK, 1.2f, 0.7f);
            w.playSound(start, Sound.ENTITY_RAVAGER_ROAR, 0.5f, 1.6f);
        }

        if (particles) {
            int points = 12;
            for (int i = 0; i <= points; i++) {
                double t = i / (double) points;
                Location lp = start.clone().add(dir.clone().multiply(distance * t));
                w.spawnParticle(Particle.CLOUD, lp, 2, 0.1, 0.1, 0.1, 0.05);
            }
        }

        // AOE im Pfad: alle LivingEntity, deren Projektion auf die Linie <= 1.5 Blöcke Abstand hat.
        double hitRadiusSq = 1.5 * 1.5;
        double lineLen = distance;
        for (LivingEntity target : w.getLivingEntities()) {
            if (target.getUniqueId().equals(p.getUniqueId())) continue;
            if (target instanceof Player tp && RelicUtils.isSameTeam(p, tp)) continue;

            Vector toTarget = target.getLocation().toVector().subtract(start.toVector());
            double along = toTarget.dot(dir);
            if (along < -0.5 || along > lineLen + 0.5) continue;
            Vector perp = toTarget.subtract(dir.clone().multiply(along));
            if (perp.lengthSquared() > hitRadiusSq) continue;

            RelicUtils.applyMagicDamage(target, p, dashDmg);
        }

        p.sendActionBar(MM.deserialize(
                "<gradient:#ffd700:#aa5500><bold>Atlas</bold> <dark_gray>» <gray>Dein Schild durchschlägt alles im Weg."));

        // NoFallDamage-Fenster nach Dash (konsistent mit Artemis-Boost).
        noFallManager.grant(p, noFallMs);
    }

    private boolean isAtlasShield(ItemStack stack) {
        if (stack == null || !factory.isRelic(stack)) return false;
        return RELIC_ID.equals(factory.getRelicId(stack));
    }

    private ItemStack findAtlasShield(Player p) {
        if (isAtlasShield(p.getInventory().getItemInMainHand())) {
            return p.getInventory().getItemInMainHand();
        }
        if (isAtlasShield(p.getInventory().getItemInOffHand())) {
            return p.getInventory().getItemInOffHand();
        }
        return null;
    }

    public void onQuit(UUID id) {
        cooldowns.remove(id);
        prevSneakBlock.remove(id);
    }
}
