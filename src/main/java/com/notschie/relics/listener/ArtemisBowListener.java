package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.NoFallDamageManager;
import com.notschie.relics.util.RelicFactory;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Artemis Bow Rework v2:
 *
 *  A) LINKSKLICK-BOOST (7s Cooldown):
 *     Schlägt der Spieler mit dem Artemis-Bogen in die Luft → 0.7↑ + 0.6→ Velocity.
 *     10s NoFallDamage danach. Kein Boost beim Sneaken (Sneak = Burst).
 *
 *  B) SNEAK-SCHNELLFEUER (19s Cooldown):
 *     Sneakt der Spieler beim Schuss, werden 3 Pfeile im 7-Tick-Abstand abgefeuert
 *     (1 Vanilla + 2 Plugin-Spawns). Erster und Extra-Pfeile taggen ihre Ziele
 *     für den 12x Regen-Pfeile-AOE.
 *
 *  C) REGEN-PFEILE-AOE (12x):
 *     Trifft ein "artemis_main"-Pfeil eine LivingEntity, regnen aus 15 Blöcke
 *     Höhe 12 Pfeile in einem 5-Block-Radius zufällig herab.
 *     Jeder Regen-Pfeil macht 3 HP und resettet die no-damage-tick (Hurricane-Pattern).
 */
public class ArtemisBowListener implements Listener {

    private static final String RELIC_ID = "bow_of_artemis";
    private static final String TAG_RAIN = "artemis_rain";
    private static final String TAG_MAIN = "artemis_main";
    private static final String TAG_BURST = "artemis_burst";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final NoFallDamageManager noFallManager;
    private final Map<UUID, Long> boostCooldowns = new HashMap<>();
    private final Map<UUID, Long> burstCooldowns = new HashMap<>();

    public ArtemisBowListener(RelicsPlugin plugin, NoFallDamageManager noFallManager) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
        this.noFallManager = noFallManager;
    }

    private boolean isArtemisBow(ItemStack stack) {
        if (stack == null || !factory.isRelic(stack)) return false;
        return RELIC_ID.equals(factory.getRelicId(stack));
    }

    private ItemStack findArtemisBow(Player p) {
        if (isArtemisBow(p.getInventory().getItemInMainHand())) {
            return p.getInventory().getItemInMainHand();
        }
        if (isArtemisBow(p.getInventory().getItemInOffHand())) {
            return p.getInventory().getItemInOffHand();
        }
        return null;
    }

    private RelicDefinition def() {
        return plugin.getDefinition(RELIC_ID);
    }

    private static String formatCooldown(double secLeft) {
        return String.format("%.1f", secLeft);
    }

    // ============ A) LINKSKLICK-BOOST (7s Cooldown) ============

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClickAir(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR) return;
        Player p = event.getPlayer();
        // Sneak = Burst statt Boost
        if (p.isSneaking()) return;

        ItemStack bow = findArtemisBow(p);
        if (bow == null) return;

        RelicDefinition def = def();
        if (def == null) return;

        long cdMs = (long) def.getEffectDouble("artemis-jump-cooldown-ms", 7000.0);
        long noFallMs = (long) def.getEffectDouble("artemis-nofall-duration-ms", 10000.0);
        double velY = def.getEffectDouble("artemis-jump-velocity-y", 0.7);
        double velFwd = def.getEffectDouble("artemis-jump-velocity-forward", 0.6);

        long now = System.currentTimeMillis();
        Long until = boostCooldowns.get(p.getUniqueId());
        if (until != null && now < until) {
            double secLeft = (until - now) / 1000.0;
            p.sendActionBar(MM.deserialize(
                "<gradient:#00ff88:#aaffaa><bold>Artemis</bold> <dark_gray>» <red>Sprung lädt noch <white>"
                + formatCooldown(secLeft) + "s <red>auf."));
            return;
        }

        Vector dir = p.getLocation().getDirection();
        Vector vel = new Vector(dir.getX() * velFwd, velY, dir.getZ() * velFwd);
        p.setVelocity(vel);
        noFallManager.grant(p, noFallMs);

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 1.0f, 1.2f);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 12, 0.3, 0.1, 0.3, 0.05);

        boostCooldowns.put(p.getUniqueId(), now + cdMs);
        p.sendActionBar(MM.deserialize(
            "<gradient:#00ff88:#aaffaa><bold>Artemis</bold> <dark_gray>» <green>Du fliegst."));
    }

    // ============ B) SNEAK-SCHNELLFEUER (3 Pfeile, 7-Tick-Abstand, 19s CD) ============

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter)) return;
        if (!isArtemisBow(event.getBow())) return;
        if (!(event.getProjectile() instanceof AbstractArrow firstArrow)) return;
        // Vom Plugin gespawnte Salvo/Extra-Pfeile sind bereits getaggt (TAG_MAIN+TAG_BURST)
        // und sollen hier nicht erneut verarbeitet werden.
        if (firstArrow.getScoreboardTags().contains(TAG_MAIN)) return;

        // JEDER Artemis-Pfeil bekommt den Regen-AOE-Tag, damit jeder Treffer
        // (egal ob Normal-Schuss oder Burst-Pfeil) den 12x Regen ausloest.
        firstArrow.addScoreboardTag(TAG_MAIN);

        // Burst-Cooldown-Check und Extra-Pfeile nur beim SNEAK-Schuss.
        if (!shooter.isSneaking()) return;

        RelicDefinition def = def();
        if (def == null) return;

        long cdMs = (long) def.getEffectDouble("artemis-burst-cooldown-ms", 19000.0);
        long tickStep = def.getEffectInt("artemis-burst-tick-step", 7);
        int burstCount = def.getEffectInt("artemis-burst-count", 3);

        long now = System.currentTimeMillis();
        Long until = burstCooldowns.get(shooter.getUniqueId());
        if (until != null && now < until) {
            double secLeft = (until - now) / 1000.0;
            shooter.sendActionBar(MM.deserialize(
                "<gradient:#00ff88:#aaffaa><bold>Artemis</bold> <dark_gray>» <red>Burst lädt noch <white>"
                + formatCooldown(secLeft) + "s <red>auf."));
            return;
        }

        burstCooldowns.put(shooter.getUniqueId(), now + cdMs);
        firstArrow.addScoreboardTag(TAG_BURST);
        final double firstDamage = firstArrow.getDamage();

        // Schedule die restlichen Burst-Pfeile im 7-Tick-Abstand.
        for (int i = 1; i < burstCount; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!shooter.isOnline()) return;
                Vector dir = shooter.getLocation().getDirection();
                if (dir.lengthSquared() < 1e-6) return;
                dir.normalize();
                Location spawn = shooter.getEyeLocation().add(dir.clone().multiply(0.5));
                Arrow burstArrow = shooter.getWorld().spawn(spawn, Arrow.class);
                burstArrow.setShooter(shooter);
                burstArrow.setVelocity(dir.multiply(3.0));
                burstArrow.setDamage(firstDamage);
                burstArrow.addScoreboardTag(TAG_MAIN);
                burstArrow.addScoreboardTag(TAG_BURST);
                burstArrow.setCritical(false);
            }, tickStep * i);
        }
    }

    // ============ C) REGEN-PFEILE-AOE (12x, 5-Block-Radius, 3 HP/Pfeil) ============

    /**
     * Primärer Auslöser: ProjectileHitEvent — feuert sowohl bei Entity-Treffer als
     * auch bei Block-Treffer. Position: Hit-Block-Location (Block-Hit) oder
     * Hit-Entity-Location (Entity-Hit). Fallback: Pfeil-Position (selten).
     *
     * Regen ist NICHT an Schaden gekoppelt — der Pfeil muss das Ziel nicht
     * verletzen, damit die 12 Regen-Pfeile spawnen. Das macht die Fähigkeit
     * zuverlässig und "pulsiert" bei jedem Treffer.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;

        // Regen-Pfeil-Treffer → no-damage-tick-Reset (Hurricane-Pattern)
        if (arrow.getScoreboardTags().contains(TAG_RAIN)) {
            if (event.getHitEntity() instanceof LivingEntity rainTarget) {
                rainTarget.setNoDamageTicks(0);
            }
            return;
        }

        // Main-Pfeil-Treffer → 12x Regen spawnen.
        if (!arrow.getScoreboardTags().contains(TAG_MAIN)) return;

        // Doppel-Spawn vermeiden: Pfeile taggen sich nach erstem Hit mit TAG_SPAWNED,
        // damit eine zweite Hit-Welle (z. B. Chain-Hit) NICHT erneut 12 spawnt.
        if (arrow.getScoreboardTags().contains("artemis_spawned")) return;
        arrow.addScoreboardTag("artemis_spawned");

        // Hit-Position bestimmen — Block > Entity > Pfeil-Fallback.
        Vector hitPos;
        if (event.getHitBlock() != null) {
            // Block-Treffer: Mittelpunkt des Blocks + 0.5 (Block-Top bei Bodenhit).
            hitPos = event.getHitBlock().getLocation().add(0.0, 0.5, 0.0).toVector();
        } else if (event.getHitEntity() instanceof LivingEntity target) {
            hitPos = target.getLocation().add(0, 1.0, 0).toVector();
        } else {
            // Weder Block noch Entity (selten): Pfeil-Position als Fallback.
            hitPos = arrow.getLocation().toVector();
        }

        RelicDefinition def = def();
        if (def == null) return;

        int count = def.getEffectInt("artemis-rain-count", 12);
        double radius = def.getEffectDouble("artemis-rain-radius", 5.0);
        double dmg = def.getEffectDouble("artemis-rain-damage", 3.0);
        double altitude = def.getEffectDouble("artemis-rain-altitude", 15.0);

        World w = arrow.getWorld();
        ProjectileSource shooter = arrow.getShooter();

        for (int i = 0; i < count; i++) {
            // Random-Punkt im Radius (HORIZONTAL — Randomvektor).
            double angle = Math.random() * Math.PI * 2.0;
            double r = Math.random() * radius;
            double offX = Math.cos(angle) * r;
            double offZ = Math.sin(angle) * r;

            Location spawnLoc = new Location(
                w, hitPos.getX() + offX, hitPos.getY() + altitude, hitPos.getZ() + offZ
            );
            Location landLoc = new Location(
                w, hitPos.getX() + offX, hitPos.getY() + 0.1, hitPos.getZ() + offZ
            );
            Vector vel = landLoc.toVector().subtract(spawnLoc.toVector())
                .normalize().multiply(2.5);
            // leichter Random-Jitter auf der Velocity
            vel.add(new Vector(
                (Math.random() - 0.5) * 0.05,
                (Math.random() - 0.5) * 0.05,
                (Math.random() - 0.5) * 0.05
            ));

            Arrow rain = w.spawn(spawnLoc, Arrow.class);
            rain.setShooter(shooter);
            rain.setVelocity(vel);
            rain.setDamage(dmg);
            rain.setCritical(false);
            rain.addScoreboardTag(TAG_RAIN);
            // Pfeile vom Himmel sind NICHT pickupbar (Inventar-Vollausfall vermeiden).
            rain.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            // KEIN Flame (sonst Extra-Feuer-Damage on top).
        }
    }

    public void onQuit(UUID id) {
        boostCooldowns.remove(id);
        burstCooldowns.remove(id);
    }
}
