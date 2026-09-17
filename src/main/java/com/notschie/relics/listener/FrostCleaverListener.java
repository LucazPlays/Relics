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

public class FrostCleaverListener implements Listener {

    private static final String RELIC_ID = "frost_cleaver";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Map<UUID, Integer>> frostStacks = new HashMap<>();
    private final Map<UUID, Long> tossCooldowns = new HashMap<>();
    private final Map<UUID, Long> gustCooldowns = new HashMap<>();

    public FrostCleaverListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isFrostCleaver(ItemStack item) {
        return item != null && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    // ==========================================
    // 1. PASSIV: EISBRECHER (3-Stack Shatter & Absorption)
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player tp && RelicUtils.isSameTeam(attacker, tp)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isFrostCleaver(weapon)) return;

        UUID aId = attacker.getUniqueId();
        UUID tId = target.getUniqueId();

        Map<UUID, Integer> targetMap = frostStacks.computeIfAbsent(aId, k -> new HashMap<>());
        int stacks = targetMap.getOrDefault(tId, 0) + 1;

        if (stacks >= 3) {
            targetMap.put(tId, 0);

            // Eisbrecher-Finisher: +5 HP Magieschaden, 1.2s Root & +2 Absorptions-Herzen!
            RelicUtils.applyMagicDamage(target, attacker, 5.0);
            RelicUtils.giveAbsorption(attacker, 4.0, 100);

            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 24, 9, false, true, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 24, 200, false, true, true));

            World world = target.getWorld();
            world.playSound(target.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.4f, 0.8f);
            world.playSound(target.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, 1.2f, 0.5f);
            world.spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0, 1, 0), 25, 0.4, 0.5, 0.4, 0.05);

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00ffff:#0088cc><bold>❄ EISBRECHER FINISHER!</bold></gradient> <green>+5 HP Bonusschaden, Root & +2 Absorptions-Herzen!</green>"
            ));
        } else {
            targetMap.put(tId, stacks);
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, stacks - 1, false, true, true));

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00ffff:#0088cc><bold>❄ Kältestacks:</bold></gradient> <aqua>[" + "◆".repeat(stacks) + "◇".repeat(3 - stacks) + "]</aqua>"
            ));
            attacker.playSound(attacker.getLocation(), Sound.BLOCK_POWDER_SNOW_STEP, 1.0f, 1.0f + 0.3f * stacks);
        }
    }

    // ==========================================
    // 2. RECHTSKLICK: FROSTBEIL-WURF & FROSTHAUCH
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isFrostCleaver(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerChillingGust(player);
        } else {
            triggerGlacialToss(player);
        }
    }

    private void triggerGlacialToss(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = tossCooldowns.getOrDefault(uuid, 0L);
        long cd = 8000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>❄ Frostbeil-Wurf Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        tossCooldowns.put(uuid, now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        world.playSound(eye, Sound.ITEM_TRIDENT_THROW, 1.2f, 1.4f);

        for (double d = 1.0; d <= 12.0; d += 0.8) {
            Location pLoc = eye.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.SNOWFLAKE, pLoc, 2, 0.1, 0.1, 0.1, 0.02);
        }

        RayTraceResult rt = world.rayTraceEntities(eye, dir, 12.0, 1.2, e -> {
            if (e.equals(player) || !(e instanceof LivingEntity)) return false;
            return !(e instanceof Player tp && RelicUtils.isSameTeam(player, tp));
        });

        if (rt != null && rt.getHitEntity() instanceof LivingEntity target) {
            target.damage(6.0, player);
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, false, true, true));

            if (target instanceof Player pTarget && pTarget.isBlocking()) {
                pTarget.setCooldown(Material.SHIELD, 60);
                pTarget.sendActionBar(MiniMessage.miniMessage().deserialize("<red><bold>❄ SCHILD EINGEFROREN!</bold></red>"));
            }

            // Beil fangen: Cooldown halbiert
            tossCooldowns.put(uuid, now - 4000L); // 8s - 4s = 4s Cooldown!
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 1.0f, 1.5f);

            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00ffff:#ffffff><bold>❄ FROSTBEIL GEFANGEN!</bold></gradient> <green>Cooldown halbiert (4s)!</green>"
            ));
        }
    }

    private void triggerChillingGust(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = gustCooldowns.getOrDefault(uuid, 0L);
        long cd = 12000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>❄ Frosthauch Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        gustCooldowns.put(uuid, now);

        // Eigenes Feuer sofort löschen
        player.setFireTicks(0);

        Location loc = player.getLocation();
        Vector dir = loc.getDirection().setY(0).normalize();
        World world = loc.getWorld();
        if (world == null) return;

        world.playSound(loc, Sound.ENTITY_PLAYER_BREATH, 1.4f, 0.5f);

        for (double d = 1.0; d <= 5.0; d += 0.7) {
            Location pLoc = loc.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.SNOWFLAKE, pLoc.add(0, 1, 0), 6, 0.4, 0.4, 0.4, 0.05);
        }

        for (Entity e : world.getNearbyEntities(loc.clone().add(dir.clone().multiply(2.5)), 3.0, 2.0, 3.0)) {
            if (e instanceof LivingEntity target && !e.equals(player)) {
                if (target instanceof Player tp && RelicUtils.isSameTeam(player, tp)) continue;
                target.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 60, 1, false, true, true));
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, true, true));
            }
        }

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#00ffff:#ffffff><bold>❄ FROSTHAUCH AUSGESTOSSEN!</bold></gradient> <aqua>Feuer gelöscht & Feinde verlangsamt!</aqua>"
        ));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        frostStacks.remove(uuid);
        tossCooldowns.remove(uuid);
        gustCooldowns.remove(uuid);
    }
}
