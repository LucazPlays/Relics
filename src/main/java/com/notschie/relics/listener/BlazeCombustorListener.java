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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class BlazeCombustorListener implements Listener {

    private static final String RELIC_ID = "blaze_combustor";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Integer> heatGauge = new HashMap<>();
    private final Map<UUID, Long> lashCooldowns = new HashMap<>();
    private final Map<UUID, Long> barrierCooldowns = new HashMap<>();
    private final Map<UUID, Long> activeBarriers = new HashMap<>();

    public BlazeCombustorListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isBlazeCombustor(ItemStack item) {
        return item != null && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    // ==========================================
    // 1. PASSIV: ÜBERHITZUNG (Heat Gauge & Shield Break)
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player tp && RelicUtils.isSameTeam(attacker, tp)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isBlazeCombustor(weapon)) return;

        UUID uuid = attacker.getUniqueId();
        int heat = heatGauge.getOrDefault(uuid, 0);

        if (heat >= 100) {
            // 100% Überhitzungs-Schlag!
            heatGauge.put(uuid, 0);

            target.setFireTicks(80); // 4s Brennen
            if (target instanceof Player pTarget) {
                pTarget.setCooldown(Material.SHIELD, 60); // 3s Schild-Disable
                pTarget.sendActionBar(MiniMessage.miniMessage().deserialize("<red><bold>🔥 SCHILD GESCHMOLZEN!</bold></red>"));
            }

            RelicUtils.giveAbsorption(attacker, 4.0, 100);

            World world = target.getWorld();
            world.playSound(target.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1.4f, 0.8f);
            world.spawnParticle(Particle.LAVA, target.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.1);

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#ff5500:#ffff00><bold>🔥 ÜBERHITZUNGS-ENTLADUNG!</bold></gradient> <green>Schild gebrochen & +2 Absorptions-Herzen!</green>"
            ));
        } else {
            heat += 25;
            heatGauge.put(uuid, heat);

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#ff5500:#ffff00><bold>🔥 Hitze:</bold></gradient> <gold>[" + "■".repeat(heat / 25) + "□".repeat(4 - (heat / 25)) + "] " + heat + "%" + (heat == 100 ? " (BEREIT!)" : "") + "</gold>"
            ));
            attacker.playSound(attacker.getLocation(), Sound.BLOCK_FURNACE_FIRE_CRACKLE, 1.0f, 1.0f + 0.2f * (heat / 25));
        }
    }

    // ==========================================
    // 2. RECHTSKLICK: FLAMMENPEITSCHE & MAGMA-SCHILD
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isBlazeCombustor(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerMoltenBarrier(player);
        } else {
            triggerFlameLash(player);
        }
    }

    private void triggerFlameLash(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = lashCooldowns.getOrDefault(uuid, 0L);
        long cd = 6000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🔥 Flammenpeitsche Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        lashCooldowns.put(uuid, now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        world.playSound(eye, Sound.ENTITY_BLAZE_SHOOT, 1.2f, 1.2f);

        for (double d = 1.0; d <= 7.0; d += 0.7) {
            Location pLoc = eye.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.FLAME, pLoc, 3, 0.1, 0.1, 0.1, 0.02);
            world.spawnParticle(Particle.SMALL_FLAME, pLoc, 2, 0.1, 0.1, 0.1, 0.01);
        }

        RayTraceResult rt = world.rayTraceEntities(eye, dir, 7.0, 1.3, e -> {
            if (e.equals(player) || !(e instanceof LivingEntity)) return false;
            return !(e instanceof Player tp && RelicUtils.isSameTeam(player, tp));
        });

        if (rt != null && rt.getHitEntity() instanceof LivingEntity target) {
            target.damage(4.5, player);
            target.setFireTicks(60);

            // Heranziehen
            Vector pull = player.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(0.85).setY(0.2);
            target.setVelocity(pull);

            world.playSound(target.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 1.2f, 1.5f);
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#ff5500:#ffff00><bold>🔥 FLAMMENPEITSCHE GETROFFEN!</bold></gradient> <yellow>Ziel herangezogen!</yellow>"
            ));
        }
    }

    private void triggerMoltenBarrier(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = barrierCooldowns.getOrDefault(uuid, 0L);
        long cd = 12000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🔥 Magma-Barriere Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        barrierCooldowns.put(uuid, now);
        activeBarriers.put(uuid, now + 2500L); // 2.5s Barriere

        player.getWorld().playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1.3f, 1.2f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#ff5500:#ffff00><bold>🔥 MAGMA-BARRIERE AKTIV (2.5s)!</bold></gradient> <gray>Verbrennt Pfeile & reflektiert Schläge!</gray>"
        ));

        // Partikel-Ticker
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!player.isOnline() || ticks++ >= 12 || System.currentTimeMillis() > activeBarriers.getOrDefault(uuid, 0L)) {
                    cancel();
                    return;
                }
                Location loc = player.getLocation().add(0, 1, 0);
                for (int i = 0; i < 8; i++) {
                    double angle = (2 * Math.PI / 8) * i + ticks * 0.4;
                    double x = Math.cos(angle) * 1.5;
                    double z = Math.sin(angle) * 1.5;
                    loc.getWorld().spawnParticle(Particle.FLAME, loc.getX() + x, loc.getY(), loc.getZ() + z, 1, 0, 0, 0, 0);
                }

                // Pfeile abwehren
                for (Entity e : loc.getWorld().getNearbyEntities(loc, 2.5, 2.0, 2.5)) {
                    if (e instanceof Projectile proj && !player.equals(proj.getShooter())) {
                        proj.remove();
                        loc.getWorld().spawnParticle(Particle.LAVA, proj.getLocation(), 4, 0.1, 0.1, 0.1, 0.1);
                        loc.getWorld().playSound(proj.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.8f);
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 4L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBarrierHurt(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        UUID uuid = victim.getUniqueId();
        Long expiry = activeBarriers.get(uuid);

        if (expiry != null && System.currentTimeMillis() <= expiry) {
            if (event.getDamager() instanceof LivingEntity attacker) {
                attacker.damage(2.0, victim);
                attacker.setFireTicks(60);
                victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_BLAZE_HURT, 1.0f, 1.5f);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        heatGauge.remove(uuid);
        lashCooldowns.remove(uuid);
        barrierCooldowns.remove(uuid);
        activeBarriers.remove(uuid);
    }
}
