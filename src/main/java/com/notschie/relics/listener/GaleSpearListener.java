package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class GaleSpearListener implements Listener {

    private static final String RELIC_ID = "gale_spear";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Long> thrustCooldowns = new HashMap<>();
    private final Map<UUID, Long> sweepCooldowns = new HashMap<>();

    public GaleSpearListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isGaleSpear(ItemStack item) {
        return item != null && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    // ==========================================
    // 1. PASSIV: AUFWIND-JUGGLE (Airborne Execution)
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player tp && RelicUtils.isSameTeam(attacker, tp)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isGaleSpear(weapon)) return;

        // Prüfen, ob das Ziel sich in der Luft befindet
        if (!target.isOnGround() || Math.abs(target.getVelocity().getY()) > 0.1 || target.getFallDistance() > 0.0) {
            event.setDamage(event.getDamage() * 1.4); // +40% DMG!

            // Cooldown des Vorstoßes sofort zurücksetzen
            thrustCooldowns.remove(attacker.getUniqueId());

            World world = target.getWorld();
            world.playSound(target.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1.2f, 1.5f);
            world.spawnParticle(Particle.GUST_EMITTER_LARGE, target.getLocation().add(0, 0.5, 0), 1);

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#d0e0ff:#7799ee><bold>✈ AIRBORNE JUGGLE!</bold></gradient> <green>+40% Schaden & Vorstoß-Reset!</green>"
            ));
        }
    }

    // ==========================================
    // 2. RECHTSKLICK: BREEZE-SPRUNGSTOSS & WINDSCHWUNG
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isGaleSpear(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerWindSweep(player);
        } else {
            triggerThrustLaunch(player);
        }
    }

    private void triggerThrustLaunch(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = thrustCooldowns.getOrDefault(uuid, 0L);
        long cd = 6000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🌀 Sprungstoß Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        thrustCooldowns.put(uuid, now);

        Location loc = player.getLocation();
        Vector dir = loc.getDirection().normalize();
        World world = loc.getWorld();
        if (world == null) return;

        world.playSound(loc, Sound.ENTITY_BREEZE_SHOOT, 1.3f, 1.4f);

        // 6m Vorstoß
        Vector dash = dir.clone().multiply(1.2).setY(0.2);
        player.setVelocity(dash);

        for (double d = 1.0; d <= 6.0; d += 0.7) {
            Location pLoc = loc.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.GUST, pLoc, 2, 0.2, 0.2, 0.2, 0.05);
        }

        RayTraceResult rt = world.rayTraceEntities(player.getEyeLocation(), dir, 6.0, 1.3, e -> {
            if (e.equals(player) || !(e instanceof LivingEntity)) return false;
            return !(e instanceof Player tp && RelicUtils.isSameTeam(player, tp));
        });

        if (rt != null && rt.getHitEntity() instanceof LivingEntity target) {
            target.damage(5.0, player);

            // Beide hochkatapultieren für Air-Combo
            player.setVelocity(new Vector(0, 0.85, 0));
            target.setVelocity(new Vector(0, 0.85, 0));

            world.playSound(target.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1.4f, 1.2f);
            world.spawnParticle(Particle.GUST_EMITTER_LARGE, target.getLocation(), 1);

            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#d0e0ff:#ffffff><bold>🌀 HOCHGESCHLEUDERT!</bold></gradient> <yellow>Lande jetzt den Airborne Juggle!</yellow>"
            ));
        }
    }

    private void triggerWindSweep(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = sweepCooldowns.getOrDefault(uuid, 0L);
        long cd = 10000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🌀 Windwirbel Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        sweepCooldowns.put(uuid, now);

        Location pLoc = player.getLocation();
        World world = pLoc.getWorld();
        if (world == null) return;

        world.playSound(pLoc, Sound.ENTITY_BREEZE_DEFLECT, 1.4f, 1.2f);

        // 360-Grad Partikel-Kreis
        for (int i = 0; i < 20; i++) {
            double angle = (2 * Math.PI / 20) * i;
            double x = Math.cos(angle) * 4.0;
            double z = Math.sin(angle) * 4.0;
            world.spawnParticle(Particle.GUST, pLoc.getX() + x, pLoc.getY() + 0.8, pLoc.getZ() + z, 1, 0, 0, 0, 0);
        }

        int hitCount = 0;
        for (Entity e : world.getNearbyEntities(pLoc, 4.5, 2.5, 4.5)) {
            if (e instanceof LivingEntity target && !e.equals(player)) {
                if (target instanceof Player tp && RelicUtils.isSameTeam(player, tp)) continue;
                Vector push = target.getLocation().toVector().subtract(pLoc.toVector()).normalize().multiply(1.3).setY(0.35);
                target.setVelocity(push);
                target.damage(4.0, player);
                hitCount++;
            } else if (e instanceof Projectile proj) {
                // Pfeile / Projektile in der Luft ablenken
                proj.setVelocity(proj.getVelocity().multiply(-1.0));
                world.spawnParticle(Particle.GUST, proj.getLocation(), 4, 0.1, 0.1, 0.1, 0.05);
            }
        }

        if (hitCount > 0) {
            RelicUtils.giveAbsorption(player, 4.0, 100);
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#d0e0ff:#ffffff><bold>🌀 WINDWIRBEL ERFOLGREICH!</bold></gradient> <green>+2 Absorptions-Herzen erhalten!</green>"
            ));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        thrustCooldowns.remove(uuid);
        sweepCooldowns.remove(uuid);
    }
}
