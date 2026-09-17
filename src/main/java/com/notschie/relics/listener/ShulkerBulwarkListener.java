package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ShulkerBullet;
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

public class ShulkerBulwarkListener implements Listener {

    private static final String RELIC_ID = "shulker_bulwark";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Boolean> kineticCharged = new HashMap<>();
    private final Map<UUID, Long> orbCooldowns = new HashMap<>();
    private final Map<UUID, Long> fortifyCooldowns = new HashMap<>();

    public ShulkerBulwarkListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isShulkerBulwark(ItemStack item) {
        return item != null && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    private boolean hasBulwark(Player p) {
        return isShulkerBulwark(p.getInventory().getItemInMainHand())
                || isShulkerBulwark(p.getInventory().getItemInOffHand());
    }

    // ==========================================
    // 1. PASSIV: KINETIK-ABSORB (Block -> Herz)
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlock(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!hasBulwark(victim)) return;

        if (victim.isBlocking()) {
            UUID uuid = victim.getUniqueId();
            kineticCharged.put(uuid, true);

            victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 1.2f, 1.5f);
            victim.getWorld().spawnParticle(Particle.END_ROD, victim.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);

            victim.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#ff88ff:#aa00aa><bold>🛡 KINETIK AUFGELADEN!</bold></gradient> <light_purple>Nächster Schlag entlädt +2 Absorptions-Herzen!</light_purple>"
            ));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player tp && RelicUtils.isSameTeam(attacker, tp)) return;
        if (!hasBulwark(attacker)) return;

        UUID uuid = attacker.getUniqueId();
        if (Boolean.TRUE.equals(kineticCharged.remove(uuid))) {
            // Entladung!
            target.damage(3.0, attacker);
            RelicUtils.giveAbsorption(attacker, 4.0, 100);

            World world = target.getWorld();
            world.playSound(target.getLocation(), Sound.ENTITY_SHULKER_BULLET_HIT, 1.4f, 1.2f);
            world.spawnParticle(Particle.DRAGON_BREATH, target.getLocation().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.05);

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#ff88ff:#aa00aa><bold>🛡 KINETIK-ENTLADUNG!</bold></gradient> <green>+2 Absorptions-Herzen erhalten!</green>"
            ));
        }
    }

    // ==========================================
    // 2. RECHTSKLICK: SCHWEBEGESCHOSS & KINETISCHE SCHALE
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isShulkerBulwark(item)) return;

        Player player = event.getPlayer();

        if (player.isSneaking()) {
            event.setCancelled(true);
            triggerFortify(player);
        } else {
            // Falls Luft: Schwebegeschoss schießen
            if (event.getAction() == Action.RIGHT_CLICK_AIR) {
                triggerLevitationOrb(player);
            }
        }
    }

    private void triggerLevitationOrb(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = orbCooldowns.getOrDefault(uuid, 0L);
        long cd = 10000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🛡 Schwebegeschoss Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        orbCooldowns.put(uuid, now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        world.playSound(eye, Sound.ENTITY_SHULKER_SHOOT, 1.3f, 1.2f);

        // Schießt Shulker-Projektil
        ShulkerBullet bullet = world.spawn(eye.add(dir.clone().multiply(0.5)), ShulkerBullet.class);
        bullet.setShooter(player);
        bullet.setVelocity(dir.multiply(0.8));

        // Partikel-Trail
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (bullet.isDead() || !bullet.isValid() || ticks++ > 40) {
                    cancel();
                    return;
                }
                bullet.getWorld().spawnParticle(Particle.END_ROD, bullet.getLocation(), 2, 0.05, 0.05, 0.05, 0.01);
            }
        }.runTaskTimer(plugin, 1L, 1L);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#ff88ff:#aa00aa><bold>🛡 SCHWEBEGESCHOSS ABGEFEUERT!</bold></gradient>"
        ));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBulletHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof ShulkerBullet bullet && bullet.getShooter() instanceof Player shooter) {
            if (hasBulwark(shooter) && event.getEntity() instanceof LivingEntity target) {
                if (target instanceof Player tp && RelicUtils.isSameTeam(shooter, tp)) {
                    event.setCancelled(true);
                    return;
                }
                target.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 30, 1, false, true, true));
                target.getWorld().playSound(target.getLocation(), Sound.ENTITY_SHULKER_BULLET_HIT, 1.4f, 1.0f);
            }
        }
    }

    private void triggerFortify(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = fortifyCooldowns.getOrDefault(uuid, 0L);
        long cd = 16000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🛡 Kinetische Schale Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        fortifyCooldowns.put(uuid, now);

        // 2s Schale
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40, 2, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true, true));

        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_CLOSE, 1.4f, 0.8f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#ff88ff:#aa00aa><bold>🛡 KINETISCHE SCHALE AKTIV (2s)!</bold></gradient> <gray>Resistenz III & Ausbruch-Explosion!</gray>"
        ));

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                Location loc = player.getLocation();
                loc.getWorld().playSound(loc, Sound.ENTITY_SHULKER_BULLET_HIT, 1.5f, 0.6f);
                loc.getWorld().spawnParticle(Particle.EXPLOSION, loc.add(0, 1, 0), 1);

                for (Entity e : loc.getWorld().getNearbyEntities(loc, 3.5, 2.0, 3.5)) {
                    if (e instanceof LivingEntity target && !e.equals(player)) {
                        if (target instanceof Player tp && RelicUtils.isSameTeam(player, tp)) continue;
                        Vector push = target.getLocation().toVector().subtract(loc.toVector()).normalize().multiply(1.4).setY(0.4);
                        target.setVelocity(push);
                        target.damage(4.0, player);
                    }
                }
            }
        }.runTaskLater(plugin, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        kineticCharged.remove(uuid);
        orbCooldowns.remove(uuid);
        fortifyCooldowns.remove(uuid);
    }
}
