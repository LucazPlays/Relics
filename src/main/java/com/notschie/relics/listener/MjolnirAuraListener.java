package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mjölnir (Donner-Aura) — Shift+Rechtsklick löst eine 5-Sekunden-Aura um den Spieler aus.
 *
 *  - Spawne 5 zufällige Blitz-Effekte (Kosmetik) im Umkreis beim Aktivieren.
 *  - Particle-Ring zeigt das Wirkungsgebiet für die Dauer von 5 Sekunden.
 *  - Alle 2 Sekunden (also 3 Ticks insgesamt) ein Blitz auf jedes LivingEntity, das
 *      a) im Radius von 8 Blöcken um den Spieler ist,
 *      b) KEINEN Block über sich hat (also im Freien / open-sky steht),
 *      c) im 90°-Cone in Blickrichtung des Spielers liegt.
 *  - MAGIC-Damage (4 HP / 2 Herzen) — umgeht Rüstung + Protection.
 *  - Cooldown 14 Sekunden.
 *
 * Pattern nach Vorbild der existierenden Relic-Listener (Shift+Rechtsklick + Cooldown).
 */
public class MjolnirAuraListener implements Listener {

    private static final String RELIC_ID = "mjolnir";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** Laufende Aura-Tasks pro Spieler, damit wir sie sauber cancellen können. */
    private final Map<UUID, BukkitTask> activeAuras = new HashMap<>();

    public MjolnirAuraListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isMjolnir(ItemStack stack) {
        if (stack == null || !factory.isRelic(stack)) return false;
        return RELIC_ID.equals(factory.getRelicId(stack));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

        ItemStack item = event.getItem();
        if (!isMjolnir(item)) return;

        Player p = event.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (!p.isSneaking()) return;

        // Vanilla-Block-Use unterdrücken.
        event.setCancelled(true);

        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null || !def.getEffectBoolean("thunder-aura-enabled", true)) return;

        UUID pid = p.getUniqueId();
        long now = System.currentTimeMillis();

        // Cooldown prüfen.
        long cdMs = (long) def.getEffectDouble("thunder-aura-cooldown-ms", 14000.0);
        Long until = cooldowns.get(pid);
        if (until != null && now < until) {
            double secLeft = (until - now) / 1000.0;
            p.sendActionBar(MM.deserialize(
                    "<gradient:#00d4ff:#ffff00><bold>Mjölnir</bold> <dark_gray>» <red>Aura lädt noch <white>"
                            + String.format("%.1f", secLeft) + "s <red>auf."));
            return;
        }

        cooldowns.put(pid, now + cdMs);

        long durationMs = (long) def.getEffectDouble("thunder-aura-duration-ms", 5000.0);
        long tickMs = (long) def.getEffectDouble("thunder-aura-tick-interval-ms", 2000.0);
        double radius = def.getEffectDouble("thunder-aura-radius", 8.0);
        double coneDeg = def.getEffectDouble("thunder-aura-cone-deg", 90.0);
        double damage = def.getEffectDouble("thunder-aura-damage", 4.0);
        int boltCount = def.getEffectInt("thunder-aura-bolt-count", 5);

        // Bestehende Aura abbrechen (falls noch eine lief).
        BukkitTask existing = activeAuras.get(pid);
        if (existing != null) existing.cancel();

        startAura(p, durationMs, tickMs, radius, coneDeg, damage, boltCount);
    }

    /**
     * Startet die Donner-Aura. Spawnt sofort Bolt-Count zufällige Blitz-Effekte
     * um den Spieler, plant den periodischen Tick (alle tickMs) und einen
     * Cleanup nach durationMs.
     */
    private void startAura(Player p, long durationMs, long tickMs,
                           double radius, double coneDeg, double damage, int boltCount) {
        World world = p.getWorld();
        Location origin = p.getLocation();

        // Aktivierungs-Sound + Lightning-Strike am Spieler-Standort.
        world.strikeLightningEffect(origin);
        world.playSound(origin, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 1.0f);
        world.playSound(origin, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 1.2f);

        // Bolt-Count zufällige Lightning-Effekte um den Spieler.
        for (int i = 0; i < boltCount; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double dist = Math.random() * radius;
            double x = origin.getX() + Math.cos(angle) * dist;
            double z = origin.getZ() + Math.sin(angle) * dist;
            // Y leicht unter dem Spieler oder am Boden — spawnen wir einen Block tiefer.
            Location boltLoc = new Location(world, x, origin.getY(), z);
            world.strikeLightningEffect(boltLoc);
        }

        // ActionBar Feedback beim Aktivieren.
        p.sendActionBar(MM.deserialize(
                "<gradient:#00d4ff:#ffff00><bold>Mjölnir-Aura</bold> <dark_gray>» <gray>aktiv für <white>"
                        + (durationMs / 1000) + "s"));

        // Periodischer Tick.
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) return;
            auraTick(p, origin, radius, coneDeg, damage);
        }, tickMs / 50L, tickMs / 50L);  // erste Iteration nach tickMs

        activeAuras.put(p.getUniqueId(), task);

        // Cleanup nach durationMs — cancelled Task und entferne aus Map.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            BukkitTask t = activeAuras.remove(p.getUniqueId());
            if (t != null) t.cancel();
            if (p.isOnline()) {
                p.sendActionBar(MM.deserialize(
                        "<gradient:#00d4ff:#ffff00><bold>Mjölnir-Aura</bold> <dark_gray>» <gray>verklungen."));
            }
        }, durationMs / 50L);
    }

    /**
     * Einzelner Aura-Tick: erzeugt Partikel-Ring und schlägt alle offenen Ziele
     * im 90°-Cone mit MAGIC-Bypass-Blitz.
     */
    private void auraTick(Player p, Location origin, double radius, double coneDeg, double damage) {
        World world = p.getWorld();
        Vector forward = origin.getDirection().normalize();
        double halfConeRad = Math.toRadians(coneDeg / 2.0);

        // Partikel-Ring (alle 1 Block einen Partikel um den Spieler).
        for (int i = 0; i < 32; i++) {
            double angle = (Math.PI * 2.0) * i / 32.0;
            double x = origin.getX() + Math.cos(angle) * radius;
            double z = origin.getZ() + Math.sin(angle) * radius;
            Location pLoc = new Location(world, x, origin.getY() + 0.2, z);
            world.spawnParticle(Particle.ELECTRIC_SPARK, pLoc, 1, 0.0, 0.0, 0.0, 0.0);
        }
        // Marker in der Mitte. FLASH braucht auf Paper 26.2 Particle.DustOptions-
        // Format statt Color direkt → alternativ ELECTRIC_SPARK als Marker.
        world.spawnParticle(Particle.ELECTRIC_SPARK, origin, 5, 0.0, 0.5, 0.0, 0.05);

        // Alle LivingEntities um den Spieler prüfen.
        for (LivingEntity target : world.getLivingEntities()) {
            if (target == p) continue;
            if (target instanceof Player tp && RelicUtils.isSameTeam(p, tp)) continue;
            double dist = target.getLocation().distance(origin);
            if (dist > radius) continue;

            // Cone-Check (90° in Blickrichtung).
            Vector toTarget = target.getLocation().toVector().subtract(origin.toVector()).normalize();
            double angle = Math.acos(Math.max(-1.0, Math.min(1.0, toTarget.dot(forward))));
            if (angle > halfConeRad) continue;

            // "Kein Block über sich"-Check: der Block über dem Target muss Luft sein.
            Block above = target.getWorld().getBlockAt(
                    target.getLocation().getBlockX(),
                    target.getLocation().getBlockY() + 2,  // +2 weil die Entity selbst auf Höhe +1 steht
                    target.getLocation().getBlockZ());
            if (!above.getType().isAir()) continue;

            // Treffer: kosmetischer Blitz + MAGIC-Bypass-Schaden.
            world.strikeLightningEffect(target.getLocation());
            RelicUtils.applyMagicDamage(target, p, damage);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cooldowns.remove(id);
        BukkitTask t = activeAuras.remove(id);
        if (t != null) t.cancel();
    }
}
