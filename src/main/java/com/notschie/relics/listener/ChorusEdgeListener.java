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
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class ChorusEdgeListener implements Listener {

    private static final String RELIC_ID = "chorus_edge";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Long> blinkCooldowns = new HashMap<>();
    private final Map<UUID, Long> swapCooldowns = new HashMap<>();
    private final Map<UUID, Long> lastBlinkTimes = new HashMap<>();

    public ChorusEdgeListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isChorusEdge(ItemStack item) {
        return item != null && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    // ==========================================
    // 1. PASSIV: FLANKEN-HINTERHALT
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player tp && RelicUtils.isSameTeam(attacker, tp)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isChorusEdge(weapon)) return;

        UUID uuid = attacker.getUniqueId();
        long now = System.currentTimeMillis();
        long lastBlink = lastBlinkTimes.getOrDefault(uuid, 0L);

        if (now - lastBlink <= 1500L) {
            // Prüfen, ob der Schlag von hinten ausgeführt wurde
            Vector tDir = target.getLocation().getDirection().setY(0).normalize();
            Vector aDir = attacker.getLocation().getDirection().setY(0).normalize();

            if (tDir.dot(aDir) > 0.3) {
                // Von hinten getroffen!
                RelicUtils.applyMagicDamage(target, attacker, 4.0); // Rüstungsdurchschlag
                RelicUtils.giveAbsorption(attacker, 4.0, 100);       // +2 Absorptions-Herzen

                target.getWorld().playSound(target.getLocation(), Sound.ITEM_CHORUS_FRUIT_TELEPORT, 1.2f, 1.6f);
                target.getWorld().spawnParticle(Particle.PORTAL, target.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.1);

                attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                        "<gradient:#cc44ff:#ff88ff><bold>🌀 FLANKEN-HINTERHALT!</bold></gradient> <green>+2 Absorptions-Herzen & 40% Rüstung ignoriert!</green>"
                ));
            }
        }
    }

    // ==========================================
    // 2. RECHTSKLICK: PHASEN-SCHRITT & RISS-TAUSCH
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isChorusEdge(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerRiftSwap(player);
        } else {
            triggerPhaseBlink(player);
        }
    }

    private void triggerPhaseBlink(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = blinkCooldowns.getOrDefault(uuid, 0L);
        long cd = 6000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🌀 Phasen-Schritt Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        blinkCooldowns.put(uuid, now);
        lastBlinkTimes.put(uuid, now);

        Location start = player.getLocation();
        Vector dir = start.getDirection().normalize();
        World world = player.getWorld();

        world.playSound(start, Sound.ITEM_CHORUS_FRUIT_TELEPORT, 1.2f, 1.4f);
        world.spawnParticle(Particle.PORTAL, start.add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.1);

        Location dest = start.clone().add(dir.clone().multiply(6.0));
        // Kollisions-Check: nicht in solide Blöcke teleportieren
        if (dest.getBlock().getType().isSolid()) {
            dest = start.clone().add(dir.clone().multiply(4.0));
        }

        // Schaden an durchquerten Gegnern
        for (double d = 1.0; d <= 6.0; d += 1.0) {
            Location step = start.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.DRAGON_BREATH, step, 2, 0.1, 0.1, 0.1, 0.01);
            for (Entity e : world.getNearbyEntities(step, 1.2, 1.5, 1.2)) {
                if (e instanceof LivingEntity target && !e.equals(player)) {
                    if (target instanceof Player tp && RelicUtils.isSameTeam(player, tp)) continue;
                    target.damage(4.0, player);
                }
            }
        }

        player.teleport(dest);
        player.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 1.6f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#cc44ff:#ff88ff><bold>🌀 PHASEN-SCHRITT!</bold></gradient> <light_purple>Schlag jetzt von hinten für die Flanken-Combo!</light_purple>"
        ));
    }

    private void triggerRiftSwap(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = swapCooldowns.getOrDefault(uuid, 0L);
        long cd = 16000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🌀 Riss-Tausch Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        RayTraceResult rt = player.getWorld().rayTraceEntities(eye, dir, 12.0, 1.2, e -> {
            if (e.equals(player) || !(e instanceof LivingEntity)) return false;
            return !(e instanceof Player tp && RelicUtils.isSameTeam(player, tp));
        });

        if (rt == null || !(rt.getHitEntity() instanceof LivingEntity target)) {
            player.sendActionBar(MiniMessage.miniMessage().deserialize("<gray>Kein Ziel für den Riss-Tausch im Visier (max 12m).</gray>"));
            return;
        }

        swapCooldowns.put(uuid, now);

        Location pLoc = player.getLocation();
        Location tLoc = target.getLocation();

        // Tausch
        player.teleport(tLoc);

        Location turned = pLoc.clone();
        turned.setYaw(turned.getYaw() + 180f); // 180 Grad Drehung
        target.teleport(turned);

        player.getWorld().playSound(pLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.4f, 0.8f);
        player.getWorld().playSound(tLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.4f, 1.2f);

        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 50, 1, false, true, true));

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#cc44ff:#ffffff><bold>🌀 RISS-TAUSCH DURCHGEFÜHRT!</bold></gradient> <green>Positionen getauscht & Feind umgedreht!</green>"
        ));
        if (target instanceof Player tp) {
            tp.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#cc44ff:#ff00aa><bold>🌀 RISS-TAUSCH:</bold> Du wurdest teleportiert und verwirrt!</gradient>"
            ));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        blinkCooldowns.remove(uuid);
        swapCooldowns.remove(uuid);
        lastBlinkTimes.remove(uuid);
    }
}
