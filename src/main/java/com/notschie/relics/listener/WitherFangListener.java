package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class WitherFangListener implements Listener {

    private static final String RELIC_ID = "wither_fang";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Map<UUID, Integer>> hitStacks = new HashMap<>();
    private final Map<UUID, Long> antiHealTargets = new HashMap<>();
    private final Map<UUID, Long> graspCooldowns = new HashMap<>();
    private final Map<UUID, Long> ashCooldowns = new HashMap<>();

    public WitherFangListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isWitherFang(ItemStack item) {
        return item != null && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    // ==========================================
    // 1. PASSIV: SEELENBRAND (3-Hit Combo & Anti-Heal)
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player tp && RelicUtils.isSameTeam(attacker, tp)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isWitherFang(weapon)) return;

        UUID aId = attacker.getUniqueId();
        UUID tId = target.getUniqueId();

        Map<UUID, Integer> targetMap = hitStacks.computeIfAbsent(aId, k -> new HashMap<>());
        int stacks = targetMap.getOrDefault(tId, 0) + 1;

        if (stacks >= 3) {
            targetMap.put(tId, 0);

            // 3. Treffer: Detonation!
            RelicUtils.applyMagicDamage(target, attacker, 5.0);

            // Heilt Träger um 3 HP
            double maxHp = Objects.requireNonNull(attacker.getAttribute(Attribute.MAX_HEALTH)).getValue();
            attacker.setHealth(Math.min(maxHp, attacker.getHealth() + 3.0));

            // 50% Anti-Heal für 5 Sekunden
            long now = System.currentTimeMillis();
            antiHealTargets.put(tId, now + 5000L);

            World world = target.getWorld();
            world.playSound(target.getLocation(), Sound.ENTITY_WITHER_SHOOT, 1.2f, 1.2f);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, target.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.05);

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#222222:#888888><bold>💀 SEELENBRAND DETONIERT!</bold></gradient> <green>+3 HP Heilung & 50% Anti-Heal auf Ziel!</green>"
            ));
            if (target instanceof Player tp) {
                tp.sendActionBar(MiniMessage.miniMessage().deserialize(
                        "<red><bold>☠ SEELENBRAND:</bold> Deine Heilung ist für 5s um 50% reduziert!</red>"
                ));
            }
        } else {
            targetMap.put(tId, stacks);
            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#222222:#888888><bold>💀 Seelenbrand:</bold></gradient> <dark_gray>[" + "◆".repeat(stacks) + "◇".repeat(3 - stacks) + "]</dark_gray>"
            ));
            attacker.playSound(attacker.getLocation(), Sound.BLOCK_SOUL_SOIL_STEP, 1.0f, 1.2f + 0.3f * stacks);
        }
    }

    // Anti-Heal Handler
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHeal(EntityRegainHealthEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        Long expiry = antiHealTargets.get(uuid);
        if (expiry != null && System.currentTimeMillis() <= expiry) {
            // Heilung um 50% reduzieren (z. B. Goldapfel oder Tränke)
            event.setAmount(event.getAmount() * 0.5);
        }
    }

    // ==========================================
    // 2. RECHTSKLICK: SEELENSTRAHL & ASCHEWOLKE
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isWitherFang(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerAshCloud(player);
        } else {
            triggerSoulGrasp(player);
        }
    }

    private void triggerSoulGrasp(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = graspCooldowns.getOrDefault(uuid, 0L);
        long cd = 8000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>💀 Seelenstrahl Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        graspCooldowns.put(uuid, now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        world.playSound(eye, Sound.ENTITY_WITHER_HURT, 1.0f, 1.5f);

        for (double d = 1.0; d <= 10.0; d += 0.8) {
            Location pLoc = eye.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.SOUL, pLoc, 2, 0.1, 0.1, 0.1, 0.02);
        }

        RayTraceResult rt = world.rayTraceEntities(eye, dir, 10.0, 1.2, e -> {
            if (e.equals(player) || !(e instanceof LivingEntity)) return false;
            return !(e instanceof Player tp && RelicUtils.isSameTeam(player, tp));
        });

        if (rt != null && rt.getHitEntity() instanceof LivingEntity target) {
            Vector pull = player.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(1.1).setY(0.25);
            target.setVelocity(pull);
            target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40, 1, false, true, true));

            world.playSound(target.getLocation(), Sound.BLOCK_SOUL_SAND_BREAK, 1.2f, 1.5f);
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#222222:#888888><bold>💀 SEELENSTRAHL GETROFFEN!</bold></gradient> <gray>Feind herangezogen!</gray>"
            ));
        }
    }

    private void triggerAshCloud(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = ashCooldowns.getOrDefault(uuid, 0L);
        long cd = 14000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>💀 Asche-Entladung Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        ashCooldowns.put(uuid, now);

        Location loc = player.getLocation();
        World world = player.getWorld();
        if (world == null) return;

        world.playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1.2f, 0.5f);
        world.spawnParticle(Particle.SQUID_INK, loc.add(0, 1, 0), 40, 1.5, 0.8, 1.5, 0.05);

        for (Entity e : world.getNearbyEntities(loc, 4.0, 2.5, 4.0)) {
            if (e instanceof LivingEntity target && !e.equals(player)) {
                if (target instanceof Player tp && RelicUtils.isSameTeam(player, tp)) continue;
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, true, true));
                target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0, false, true, true));
            }
        }

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#222222:#ffffff><bold>💀 ASCHEWOLKE ENTLEERT!</bold></gradient> <gray>Feinde im Umkreis erblindet & geschwächt!</gray>"
        ));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hitStacks.remove(uuid);
        antiHealTargets.remove(uuid);
        graspCooldowns.remove(uuid);
        ashCooldowns.remove(uuid);
    }
}
