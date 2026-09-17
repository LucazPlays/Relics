package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class KineticMaceListener implements Listener {

    private static final String RELIC_ID = "kinetic_mace";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Long> shockwaveCooldowns = new HashMap<>();
    private final Map<UUID, Long> titanCooldowns = new HashMap<>();

    public KineticMaceListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isKineticMace(ItemStack item) {
        return item != null && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    // ==========================================
    // 1. PASSIV: BODEN-RESONANZ (Sprung-Crit Shockwave)
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player tp && RelicUtils.isSameTeam(attacker, tp)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isKineticMace(weapon)) return;

        // Sprung-Crit Check (Kritischer Schlag)
        if (attacker.getFallDistance() > 0.0f && !attacker.isOnGround() && !attacker.isClimbing()) {
            Location loc = target.getLocation();
            World world = loc.getWorld();
            if (world != null) {
                world.playSound(loc, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.4f, 0.6f);
                world.spawnParticle(Particle.BLOCK, loc.add(0, 0.2, 0), 25, 1.2, 0.2, 1.2, 0.1, Material.COBBLESTONE.createBlockData());

                // +2 Absorptions-Herzen für den Angreifer
                RelicUtils.giveAbsorption(attacker, 4.0, 100);

                attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                        "<gradient:#ff6600:#662200><bold>🔨 SEISMISCHE RESONANZ!</bold></gradient> <green>Sprung-Crit schlug ein (+2 Absorptions-Herzen)!</green>"
                ));
            }
        }
    }

    // ==========================================
    // 2. RECHTSKLICK: SCHOCKWELLEN-SPALTE & TITAN-SCHLAG
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isKineticMace(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerTitanSlam(player);
        } else {
            triggerShockwaveLine(player);
        }
    }

    private void triggerShockwaveLine(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = shockwaveCooldowns.getOrDefault(uuid, 0L);
        long cd = 6000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🔨 Schockwelle Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        shockwaveCooldowns.put(uuid, now);

        Location loc = player.getLocation();
        Vector dir = loc.getDirection().setY(0).normalize();
        World world = loc.getWorld();
        if (world == null) return;

        world.playSound(loc, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.2f, 0.6f);

        for (double d = 1.0; d <= 7.0; d += 0.8) {
            Location step = loc.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.BLOCK, step.add(0, 0.2, 0), 8, 0.3, 0.1, 0.3, 0.05, Material.DIRT.createBlockData());

            for (Entity e : world.getNearbyEntities(step, 1.2, 1.5, 1.2)) {
                if (e instanceof LivingEntity target && !e.equals(player)) {
                    if (target instanceof Player tp && RelicUtils.isSameTeam(player, tp)) continue;
                    target.damage(5.0, player);
                    target.setVelocity(new Vector(0, 0.7, 0)); // Hochwerfen
                }
            }
        }

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#ff6600:#662200><bold>🔨 SCHOCKWELLE ENTLAUFEN!</bold></gradient> <yellow>Feinde hochgeschleudert!</yellow>"
        ));
    }

    private void triggerTitanSlam(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = titanCooldowns.getOrDefault(uuid, 0L);
        long cd = 14000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🔨 Titan-Schlag Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        titanCooldowns.put(uuid, now);

        // 1. Sprung 3 Blöcke hoch
        player.setVelocity(new Vector(0, 0.75, 0));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1.2f, 1.4f);

        // 2. Nach 8 Ticks (am Scheitelpunkt) nach unten schmettern
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                player.setVelocity(new Vector(0, -1.2, 0));

                // Nach weiterem Einschlag (4 Ticks später) Ground-Impact
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) return;
                        Location pLoc = player.getLocation();
                        World w = pLoc.getWorld();
                        if (w == null) return;

                        w.playSound(pLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.7f);
                        w.spawnParticle(Particle.EXPLOSION, pLoc.add(0, 0.5, 0), 1);
                        w.spawnParticle(Particle.BLOCK, pLoc, 40, 2.0, 0.2, 2.0, 0.1, Material.STONE.createBlockData());

                        for (Entity e : w.getNearbyEntities(pLoc, 5.0, 2.5, 5.0)) {
                            if (e instanceof LivingEntity target && !e.equals(player)) {
                                if (target instanceof Player tp && RelicUtils.isSameTeam(player, tp)) continue;
                                // Heranziehen
                                Vector pull = pLoc.toVector().subtract(target.getLocation().toVector()).normalize().multiply(1.2).setY(0.25);
                                target.setVelocity(pull);
                                target.damage(4.0, player);
                                target.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 50, 1, false, true, true));
                            }
                        }

                        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                                "<gradient:#ff6600:#ffffff><bold>🔨 TITAN-EINSCHLAG!</bold></gradient> <gold>Feinde herangezogen & verlangsamt!</gold>"
                        ));
                    }
                }.runTaskLater(plugin, 6L);
            }
        }.runTaskLater(plugin, 8L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        shockwaveCooldowns.remove(uuid);
        titanCooldowns.remove(uuid);
    }
}
