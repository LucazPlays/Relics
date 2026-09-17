package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

public class ChainCrossbowListener implements Listener {

    private static final String RELIC_ID = "chain_crossbow";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Integer> hitStreak = new HashMap<>();
    private final Map<UUID, Long> recoilCooldowns = new HashMap<>();
    private final Map<UUID, Long> snareCooldowns = new HashMap<>();
    private final Set<UUID> snareArrows = new HashSet<>();
    private final Set<UUID> streakArrows = new HashSet<>();

    public ChainCrossbowListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isChainCrossbow(ItemStack item) {
        return item != null && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    // ==========================================
    // 1. PASSIV: SCHARFSCHÜTZEN-KETTE
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter)) return;
        if (!isChainCrossbow(event.getBow())) return;

        if (event.getProjectile() instanceof Arrow arrow) {
            UUID sId = shooter.getUniqueId();
            int streak = hitStreak.getOrDefault(sId, 0);

            if (streak >= 2) {
                // 3. Schuss ist der Präzisions-Finisher!
                streakArrows.add(arrow.getUniqueId());
                arrow.setGlowing(true);
                arrow.setPierceLevel(2);
                shooter.getWorld().playSound(shooter.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f, 1.8f);

                shooter.sendActionBar(MiniMessage.miniMessage().deserialize(
                        "<gradient:#00ddff:#0044aa><bold>🎯 PRÄZISIONS-FINISHER GELADEN!</bold></gradient> <aqua>(Rüstungsdurchschlag & Absorption)</aqua>"
                ));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player tp && RelicUtils.isSameTeam(shooter, tp)) return;

        UUID arrowId = arrow.getUniqueId();
        UUID sId = shooter.getUniqueId();

        // Fangnetz-Bolzen Check
        if (snareArrows.remove(arrowId)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 9, false, true, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 30, 200, false, true, true));
            target.getWorld().spawnParticle(Particle.BLOCK, target.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.05, Material.COBWEB.createBlockData());
            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 1.2f, 1.2f);
            shooter.sendActionBar(MiniMessage.miniMessage().deserialize("<gradient:#00ddff:#0044aa><bold>🕸 FANGNETZ GEFANGEN (1.5s Root)!</bold></gradient>"));
            return;
        }

        // Streak-Bolzen Check
        if (streakArrows.remove(arrowId)) {
            hitStreak.put(sId, 0);

            RelicUtils.applyMagicDamage(target, shooter, 4.0); // 50% Rüstungsdurchschlag
            RelicUtils.giveAbsorption(shooter, 4.0, 100);       // +2 Absorptions-Herzen
            shooter.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 0, false, true, true));

            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1.5f, 1.6f);
            target.getWorld().spawnParticle(Particle.GLOW, target.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.1);

            shooter.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00ddff:#0044aa><bold>🎯 PRÄZISIONSTREFFER!</bold></gradient> <green>+2 Absorptions-Herzen & Speed I!</green>"
            ));
        } else {
            // Normaler Treffer: Streak erhöhen
            int streak = hitStreak.getOrDefault(sId, 0) + 1;
            hitStreak.put(sId, streak);

            shooter.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00ddff:#0044aa><bold>🎯 Scharfschützen-Kette:</bold></gradient> <aqua>[" + "◆".repeat(Math.min(2, streak)) + "◇".repeat(Math.max(0, 2 - streak)) + "]</aqua>"
            ));
        }
    }

    // ==========================================
    // 2. RECHTSKLICK: RÜCKSTOSS-SALVE & FANGNETZ
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isChainCrossbow(item)) return;

        Player player = event.getPlayer();

        if (player.isSneaking()) {
            event.setCancelled(true);
            triggerSnareBolt(player);
        } else {
            // Klick in die Luft ohne geladenen Bolzen: Disengage-Salve
            if (event.getAction() == Action.RIGHT_CLICK_AIR) {
                triggerRecoilVolley(player);
            }
        }
    }

    private void triggerRecoilVolley(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = recoilCooldowns.getOrDefault(uuid, 0L);
        long cd = 7000L;

        if (now - lastUse < cd) return;
        recoilCooldowns.put(uuid, now);

        Location loc = player.getLocation();
        Vector dir = loc.getDirection().normalize();
        World world = loc.getWorld();
        if (world == null) return;

        world.playSound(loc, Sound.ITEM_CROSSBOW_SHOOT, 1.4f, 1.5f);

        // 4 Blöcke nach hinten katapultieren (Disengage)
        player.setVelocity(dir.clone().multiply(-1.1).setY(0.25));

        // 3 Pfeile im Fächer
        for (double angle : new double[]{-15.0, 0.0, 15.0}) {
            Vector spread = rotateY(dir.clone(), Math.toRadians(angle)).multiply(1.8);
            Arrow arrow = world.spawn(player.getEyeLocation(), Arrow.class);
            arrow.setShooter(player);
            arrow.setVelocity(spread);
        }

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#00ddff:#0044aa><bold>🏹 RÜCKSTOSS-SALVE!</bold></gradient> <aqua>4m Rückzug ausgeführt!</aqua>"
        ));
    }

    private void triggerSnareBolt(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = snareCooldowns.getOrDefault(uuid, 0L);
        long cd = 14000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🏹 Fangnetz Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        snareCooldowns.put(uuid, now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize().multiply(2.2);
        World world = player.getWorld();

        world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.2f, 0.8f);

        Arrow arrow = world.spawn(eye, Arrow.class);
        arrow.setShooter(player);
        arrow.setVelocity(dir);
        arrow.setCustomName("SnareBolt");
        snareArrows.add(arrow.getUniqueId());

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#00ddff:#ffffff><bold>🕸 FANGNETZ-BOLZEN ABGEFEUERT!</bold></gradient>"
        ));
    }

    private Vector rotateY(Vector v, double rad) {
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new Vector(v.getX() * cos + v.getZ() * sin, v.getY(), -v.getX() * sin + v.getZ() * cos);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hitStreak.remove(uuid);
        recoilCooldowns.remove(uuid);
        snareCooldowns.remove(uuid);
    }
}
