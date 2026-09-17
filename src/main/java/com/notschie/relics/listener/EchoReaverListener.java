package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
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
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class EchoReaverListener implements Listener {

    private static final String RELIC_ID = "echo_reaver";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Integer> hitCounters = new HashMap<>();
    private final Map<UUID, Long> lastHitTimes = new HashMap<>();
    private final Map<UUID, Long> thrustCooldowns = new HashMap<>();
    private final Map<UUID, Long> parryCooldowns = new HashMap<>();
    private final Map<UUID, Long> activeParries = new HashMap<>();

    public EchoReaverListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isEchoReaver(ItemStack item) {
        return item != null && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    // ==========================================
    // 1. PASSIV: SCHALL-RESONANZ (3-Hit Combo)
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player tp && RelicUtils.isSameTeam(attacker, tp)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isEchoReaver(weapon)) return;

        UUID uuid = attacker.getUniqueId();
        long now = System.currentTimeMillis();

        if (now - lastHitTimes.getOrDefault(uuid, 0L) > 4000L) {
            hitCounters.put(uuid, 0);
        }
        lastHitTimes.put(uuid, now);

        int count = hitCounters.getOrDefault(uuid, 0) + 1;
        if (count >= 3) {
            hitCounters.put(uuid, 0);

            // 3. Treffer: Schall-Resonanz!
            RelicUtils.applyMagicDamage(target, attacker, 4.0);
            RelicUtils.giveAbsorption(attacker, 4.0, 100);

            World world = target.getWorld();
            world.playSound(target.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 1.4f);
            world.spawnParticle(Particle.SONIC_BOOM, target.getLocation().add(0, 1, 0), 1);

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00ffff:#003344><bold>🦇 SCHALL-RESONANZ!</bold></gradient> <green>+2 Absorptions-Herzen & 4 HP Schallschaden!</green>"
            ));
        } else {
            hitCounters.put(uuid, count);
            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00ffff:#003344><bold>🦇 Schall-Combo:</bold></gradient> <aqua>[" + "◆".repeat(count) + "◇".repeat(3 - count) + "]</aqua>"
            ));
            attacker.playSound(attacker.getLocation(), Sound.BLOCK_SCULK_SENSOR_CLICKING, 1.0f, 1.2f + 0.3f * count);
        }
    }

    // ==========================================
    // 2. RECHTSKLICK: SCHALL-STOSS & ECHO-PARRY
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isEchoReaver(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerEchoParry(player);
        } else {
            triggerSonicThrust(player);
        }
    }

    private void triggerSonicThrust(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = thrustCooldowns.getOrDefault(uuid, 0L);
        long cd = 6000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🦇 Schall-Stoß Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        thrustCooldowns.put(uuid, now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.2f, 1.5f);

        // Raytrace 8 Blöcke
        for (double d = 1.0; d <= 8.0; d += 0.8) {
            Location pLoc = eye.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.SCULK_SOUL, pLoc, 2, 0.1, 0.1, 0.1, 0.02);
        }

        RayTraceResult rt = world.rayTraceEntities(eye, dir, 8.0, 1.2, e -> {
            if (e.equals(player) || !(e instanceof LivingEntity)) return false;
            return !(e instanceof Player tp && RelicUtils.isSameTeam(player, tp));
        });

        if (rt != null && rt.getHitEntity() instanceof LivingEntity target) {
            world.spawnParticle(Particle.SONIC_BOOM, target.getLocation().add(0, 1, 0), 1);
            world.playSound(target.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.6f);

            // Stoppt Sprint / Velocity
            target.setVelocity(new Vector(0, 0.05, 0));

            // Falls Spieler, Schild für 2.5s deaktivieren
            if (target instanceof Player pTarget && pTarget.isBlocking()) {
                pTarget.setCooldown(Material.SHIELD, 50);
                pTarget.sendActionBar(MiniMessage.miniMessage().deserialize("<red>⛔ Dein Schild wurde durch den Schall-Stoß deaktiviert!</red>"));
            }

            // Caster erhält Speed II für 2s
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 1, false, true, true));
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00ffff:#003344><bold>🦇 SCHALL-STOSS GETROFFEN!</bold></gradient> <green>Sprint gestoppt & Speed II erhalten!</green>"
            ));
        }
    }

    private void triggerEchoParry(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = parryCooldowns.getOrDefault(uuid, 0L);
        long cd = 12000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🛡 Echo-Parry Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        parryCooldowns.put(uuid, now);
        activeParries.put(uuid, now + 800L); // 0.8s Parry-Fenster

        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 1.0f, 1.5f);
        player.getWorld().spawnParticle(Particle.SCULK_SOUL, player.getLocation().add(0, 1, 0), 15, 0.4, 0.6, 0.4, 0.05);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<aqua><bold>🛡 ECHO-PARRY AKTIV (0.8s)!</bold></aqua> <gray>Wehre den nächsten Angriff ab!</gray>"
        ));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onIncomingDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        UUID uuid = victim.getUniqueId();
        long now = System.currentTimeMillis();

        Long parryUntil = activeParries.get(uuid);
        if (parryUntil == null || now > parryUntil) return;

        // Treffer erfolgreich pariert!
        event.setCancelled(true);
        activeParries.remove(uuid);

        // Cooldown auf 5s senken
        parryCooldowns.put(uuid, now - 7000L); // 12s - 7s = 5s Rest-Cooldown

        // +3 Absorptions-Herzen
        RelicUtils.giveAbsorption(victim, 6.0, 120);

        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_WARDEN_ROAR, 1.4f, 1.5f);
        victim.getWorld().spawnParticle(Particle.SONIC_BOOM, victim.getLocation().add(0, 1, 0), 1);

        // Angreifer zurückstoßen & Schaden reflektieren
        if (event.getDamager() instanceof LivingEntity attacker) {
            Vector knockback = attacker.getLocation().toVector().subtract(victim.getLocation().toVector()).normalize().multiply(1.4).setY(0.3);
            attacker.setVelocity(knockback);
            RelicUtils.applyMagicDamage(attacker, victim, Math.max(4.0, event.getDamage()));
        }

        victim.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#00ffff:#ffffff><bold>💥 ERFOLGREICHER ECHO-PARRY!</bold></gradient> <green>Schaden reflektiert & +3 Absorptions-Herzen!</green>"
        ));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hitCounters.remove(uuid);
        lastHitTimes.remove(uuid);
        thrustCooldowns.remove(uuid);
        parryCooldowns.remove(uuid);
        activeParries.remove(uuid);
    }
}
