package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Donnerkatana — Shift+Rechtsklick Teleport-Dash in Blickrichtung.
 *
 *  - Maximaldistanz: lightning-dash-max-distance (Default 12 Blöcke).
 *  - Block-Raycast mit 0.25-Schrittweite stoppt an nicht-passierbaren Blöcken
 *    (Spieler teleportiert 1 Block vor die Wand, kein Clipping).
 *  - Treffer: alle LivingEntities im 1.5-Block-Radius um den Pfad bekommen
 *    lightning-dash-damage (Default 9 HP) als Vanilla-Schaden.
 *  - Kosmetischer Lightning-Effekt auf jedes getroffene Target.
 *  - Spieler bekommt Speed II + Jump-Boost II für 3s als Belohnung.
 *  - Cooldown: lightning-dash-cooldown-ms (Default 10s).
 */
public class LightningDashListener implements Listener {

    private static final String RELIC_ID = "thunder_katana";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Blöcke die der Spieler beim Teleport durchqueren darf (kein Stop). */
    private static final Set<Material> PASSABLE = Set.of(
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
            Material.LARGE_FERN, Material.DEAD_BUSH,
            Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID,
            Material.ALLIUM, Material.AZURE_BLUET, Material.RED_TULIP,
            Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP,
            Material.OXEYE_DAISY, Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY,
            Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY,
            Material.PITCHER_PLANT,
            Material.MOSS_CARPET, Material.SNOW, Material.POWDER_SNOW,
            Material.LILY_PAD, Material.COBWEB,
            Material.FIRE, Material.SOUL_FIRE,
            Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO,
            Material.VINE, Material.CAVE_VINES, Material.CAVE_VINES_PLANT,
            Material.HANGING_ROOTS, Material.CRIMSON_ROOTS, Material.WARPED_ROOTS,
            Material.NETHER_SPROUTS, Material.TWISTING_VINES, Material.WEEPING_VINES,
            Material.RED_MUSHROOM, Material.BROWN_MUSHROOM,
            Material.LANTERN, Material.SOUL_LANTERN,
            Material.RAIL, Material.POWERED_RAIL, Material.DETECTOR_RAIL,
            Material.ACTIVATOR_RAIL,
            Material.REDSTONE_WIRE, Material.REPEATER, Material.COMPARATOR,
            Material.TRIPWIRE, Material.TRIPWIRE_HOOK,
            Material.LEVER, Material.STONE_BUTTON, Material.OAK_BUTTON,
            Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH,
            Material.TORCH, Material.WALL_TORCH, Material.SOUL_TORCH,
            Material.SOUL_WALL_TORCH,
            Material.OAK_SIGN, Material.OAK_WALL_SIGN
    );

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** Start-Position des letzten Vorwärts-Dashes (für Rückkehr-Dash). */
    private final Map<UUID, Location> lastDashOrigin = new HashMap<>();
    /** End-Position des letzten Vorwärts-Dashes (für Rückkehr-Trail). */
    private final Map<UUID, Location> lastDashEnd = new HashMap<>();
    /** Timestamp (ms) des letzten Dash-Starts — Rückkehr-Dash nur innerhalb 700ms. */
    private final Map<UUID, Long> lastDashTime = new HashMap<>();

    public LightningDashListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isKatana(ItemStack stack) {
        if (stack == null || !factory.isRelic(stack)) return false;
        return RELIC_ID.equals(factory.getRelicId(stack));
    }

    private ItemStack findKatanaInHand(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (isKatana(main)) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        if (isKatana(off)) return off;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

        ItemStack item = event.getItem();
        if (!isKatana(item)) return;

        Player p = event.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;

        // Sneak-Pflicht: nur mit Shift + Rechtsklick
        if (!p.isSneaking()) {
            plugin.getLogger().info("[LightningDash] " + p.getName() + " right-clicked without sneak — ignoring");
            return;
        }

        // Vanilla-Block-Use unterdrücken
        event.setCancelled(true);

        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null || !def.getEffectBoolean("lightning-dash-enabled", true)) return;

        long now = System.currentTimeMillis();
        UUID pid = p.getUniqueId();
        plugin.getLogger().info("[LightningDash] " + p.getName() + " triggered Dash (sneak=true, action=" + event.getAction() + ")");

        double maxDistance = def.getEffectDouble("lightning-dash-max-distance", 12.0);
        double damage = def.getEffectDouble("lightning-dash-damage", 9.0);
        double hitRadius = def.getEffectDouble("lightning-dash-hit-radius", 3.0);
        double step = def.getEffectDouble("lightning-dash-step", 0.25);
        long cdMs = (long) def.getEffectDouble("lightning-dash-cooldown-ms", 10000.0);
        int speedAmp = def.getEffectInt("lightning-dash-self-speed-amplifier", 2);
        long speedDurMs = (long) def.getEffectDouble("lightning-dash-self-speed-duration-ms", 3000.0);
        int jumpAmp = def.getEffectInt("lightning-dash-self-jump-amplifier", 2);
        long jumpDurMs = (long) def.getEffectDouble("lightning-dash-self-jump-duration-ms", 3000.0);
        boolean particles = def.getEffectBoolean("lightning-dash-particle", true);
        boolean sounds = def.getEffectBoolean("lightning-dash-sound", true);
        int trailCount = def.getEffectInt("lightning-dash-trail-count", 8);
        long returnWindowMs = (long) def.getEffectDouble("lightning-dash-return-window-ms", 700.0);
        double returnDamage = def.getEffectDouble("lightning-dash-return-damage", 2.0);

        // === Rückkehr-Dash: innerhalb returnWindowMs nach letztem Vorwärts-Dash ===
        // (VOR dem Cooldown-Check, damit ein Return-Dash niemals am Cooldown scheitert)
        Long lastTime = lastDashTime.get(pid);
        Location lastOrigin = lastDashOrigin.get(pid);
        Location lastEnd = lastDashEnd.get(pid);
        long sinceLast = lastTime != null ? (now - lastTime) : -1;
        plugin.getLogger().info("[LightningDash] " + p.getName()
                + " — lastTime=" + lastTime + " sinceLast=" + sinceLast + "ms returnWindow=" + returnWindowMs + "ms"
                + " lastOrigin=" + (lastOrigin != null ? "yes" : "no") + " lastEnd=" + (lastEnd != null ? "yes" : "no"));
        if (lastTime != null && lastOrigin != null && lastEnd != null
                && (now - lastTime) <= returnWindowMs) {
            // Rückkehr-Dash! Kein Cooldown verbrauchen, Origin-Cache löschen.
            event.setCancelled(true);
            plugin.getLogger().info("[LightningDash] " + p.getName() + " — RETURN-DASH triggered!");
            performReturnDash(p, lastOrigin, lastEnd, returnDamage, hitRadius,
                              speedAmp, speedDurMs, jumpAmp, jumpDurMs,
                              particles, sounds, trailCount);
            lastDashOrigin.remove(pid);
            lastDashEnd.remove(pid);
            lastDashTime.remove(pid);
            return;
        }

        // Rückkehr-Cache verwerfen, wenn das Fenster abgelaufen ist (kein zweiter Return möglich).
        if (lastTime != null && (now - lastTime) > returnWindowMs) {
            lastDashOrigin.remove(pid);
            lastDashEnd.remove(pid);
            lastDashTime.remove(pid);
        }

        // Cooldown nur für Vorwärts-Dashes (Return-Dashes verbrauchen keinen Cooldown).
        Long until = cooldowns.get(pid);
        if (until != null && now < until) {
            double secLeft = (until - now) / 1000.0;
            p.sendActionBar(MM.deserialize(
                    "<gradient:#6666ff:#ffff00><bold>Donnerkatana</bold> <dark_gray>» <red>Dash lädt noch <white>"
                            + String.format("%.1f", secLeft) + "s <red>auf."));
            plugin.getLogger().info("[LightningDash] " + p.getName()
                    + " — blocked by cooldown (left " + String.format("%.2f", secLeft) + "s)");
            return;
        }

        cooldowns.put(pid, now + cdMs);
        // Vorwärts-Dash: speichere Origin für Rückkehr-Window.
        lastDashTime.put(pid, now);
        lastDashOrigin.put(pid, p.getLocation().clone());
        performDash(p, maxDistance, step, damage, hitRadius,
                    speedAmp, speedDurMs, jumpAmp, jumpDurMs,
                    particles, sounds, trailCount);
    }

    /**
     * Teleportiert den Spieler bis zu {@code maxDistance} Blöcke in Blickrichtung,
     * stoppt an Wänden, trifft Entities im Pfad mit {@code damage} HP Vanilla-Schaden,
     * spielt Lightning-Effekt + Sounds und gibt dem Spieler Speed/Jump-Boost.
     */
    private void performDash(Player p, double maxDistance, double step,
                             double damage, double hitRadius,
                             int speedAmp, long speedDurMs,
                             int jumpAmp, long jumpDurMs,
                             boolean particles, boolean sounds, int trailCount) {
        Location eyeStart = p.getEyeLocation().clone();
        Vector forward = eyeStart.getDirection().clone();
        if (forward.lengthSquared() < 1e-6) return;
        forward.normalize();

        World world = p.getWorld();

        // 1) Sound + Partikel am Start
        if (sounds) {
            world.playSound(eyeStart, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7f, 1.6f);
            world.playSound(eyeStart, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.9f);
        }
        if (particles) {
            spawnElectricParticles(world, eyeStart, trailCount);
        }

        // 2) Block-Raycast: finde erste nicht-passierbare Block-Position
        Location teleportTarget = eyeStart.clone();
        double stepSq = step * step;
        double maxSq = maxDistance * maxDistance;
        boolean hitWall = false;
        double traveled = 0.0;

        // Iteriere in 0.25er Schritten entlang der Blickrichtung.
        // Wir stoppen 1 Block VOR der ersten soliden Block-Kollision (kein Clipping).
        for (double d = step; d <= maxDistance + 1e-3; d += step) {
            Location probe = eyeStart.clone().add(forward.clone().multiply(d));
            traveled = d;
            Block feetBlock = probe.getBlock();              // Block wo Spieler stehen würde
            Block headBlock = probe.clone().add(0, 1, 0).getBlock();
            if (!isPassable(feetBlock.getType()) || !isPassable(headBlock.getType())) {
                // Wand getroffen — 1 Block zurück (= safe Position)
                teleportTarget = eyeStart.clone().add(forward.clone().multiply(d - step));
                hitWall = true;
                break;
            }
            teleportTarget = probe;
            if (d * d >= maxSq) break;
        }

        // 3) Teleportiere
        // Teleportiere Spieler mit gleicher Yaw/Pitch, neue Position.
        Location finalLoc = teleportTarget.clone();
        finalLoc.setYaw(p.getLocation().getYaw());
        finalLoc.setPitch(p.getLocation().getPitch());
        // Y-Korrektur: Spieler steht auf dem Boden, eyeLocation kann auf Midair-Höhe sein.
        // Wir setzen die Y auf die Block-Oberkante von teleportTarget.
        Block groundBlock = finalLoc.clone().subtract(0, 0.1, 0).getBlock();
        if (!groundBlock.getType().isAir()) {
            finalLoc.setY(groundBlock.getY() + 1.0);
        }
        p.teleport(finalLoc);
        plugin.getLogger().info("[LightningDash] " + p.getName() + " FORWARD-DASH completed: from " + eyeStart + " to " + finalLoc);

        // 4) Trail entlang des Dash-Pfads
        if (particles) {
            // Partikel-Trail vom Start bis zum Ziel
            int trailSteps = Math.max(2, (int) (traveled / 0.5));
            for (int i = 0; i <= trailSteps; i++) {
                double t = i / (double) trailSteps;
                Location trailLoc = eyeStart.clone().add(forward.clone().multiply(traveled * t));
                world.spawnParticle(Particle.ELECTRIC_SPARK, trailLoc, 3, 0.1, 0.1, 0.1, 0.02);
                world.spawnParticle(Particle.FIREWORK, trailLoc, 2, 0.05, 0.05, 0.05, 0.05);
            }
        }

        // 5) Schaden + Lightning auf Entities im Pfad
        Location start = eyeStart.clone();
        Vector dir = forward.clone();
        double lineLen = traveled;
        double hitRadiusSq = hitRadius * hitRadius;
        int hitCount = 0;
        for (LivingEntity target : world.getLivingEntities()) {
            if (target.getUniqueId().equals(p.getUniqueId())) continue;
            if (target.isDead() || !target.isValid()) continue;

            // Wir prüfen mehrere Punkte der Hitbox: Augen-Position (höher in der Box),
            // Fuß-Position und ein Mittelpunkt. So treffen wir auch grosse Mobs (Zombies,
            // Spieler 1.8m hoch) korrekt, selbst wenn deren Füße seitlich versetzt stehen.
            Location[] checkPoints = {
                    target.getEyeLocation(),                  // obere Hälfte der Box
                    target.getLocation().add(0, target.getHeight() * 0.5, 0), // Mitte
                    target.getLocation()                     // Füße
            };
            boolean hit = false;
            for (Location cp : checkPoints) {
                Vector toTarget = cp.toVector().subtract(start.toVector());
                double along = toTarget.dot(dir);
                if (along < -0.5 || along > lineLen + 0.5) continue;
                Vector perp = toTarget.subtract(dir.clone().multiply(along));
                if (perp.lengthSquared() <= hitRadiusSq) {
                    hit = true;
                    break;
                }
            }
            if (!hit) continue;

            // Vanilla-Schaden (durch Rüstung reduziert)
            if (damage > 0) target.damage(damage, p);
            // Kosmetischer Lightning-Effekt (kein Schaden)
            world.strikeLightningEffect(target.getEyeLocation());
            hitCount++;
        }

        // 6) Spieler-Belohnung: Speed + Jump-Boost
        p.addPotionEffect(new PotionEffect(
                resolvePotionType("SPEED"),
                (int) (speedDurMs / 50L),
                Math.max(0, speedAmp - 1),
                true, false, false));
        p.addPotionEffect(new PotionEffect(
                resolvePotionType("JUMP_BOOST"),
                (int) (jumpDurMs / 50L),
                Math.max(0, jumpAmp - 1),
                true, false, false));

        // 7) Sound + Partikel am Aufprall
        if (sounds) {
            world.playSound(finalLoc, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.7f, 1.4f);
            if (hitCount > 0) {
                world.playSound(finalLoc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f);
            }
        }
        if (particles) {
            // Kreis aus Funken um den Landepunkt
            for (int i = 0; i < 24; i++) {
                double angle = (Math.PI * 2.0 * i) / 24.0;
                double r = 1.2;
                Location ringLoc = finalLoc.clone().add(Math.cos(angle) * r, 0.1, Math.sin(angle) * r);
                world.spawnParticle(Particle.ELECTRIC_SPARK, ringLoc, 1, 0.0, 0.0, 0.0, 0.0);
            }
            world.spawnParticle(Particle.FIREWORK, finalLoc.clone().add(0, 0.5, 0),
                    15, 0.5, 0.3, 0.5, 0.05);
        }

        p.sendActionBar(MM.deserialize(
                "<gradient:#6666ff:#ffff00><bold>Donnerkatana</bold> <dark_gray>» <aqua>Blitz-Dash! <gray>("
                        + hitCount + " getroffen"
                        + (hitWall ? ", Wand)" : ")")));

        // Rückkehr-Dash-Cache: Origin (für Teleport), End (für Trail), Time (Window-Check).
        lastDashTime.put(p.getUniqueId(), System.currentTimeMillis());
        lastDashEnd.put(p.getUniqueId(), finalLoc.clone());
    }

    /**
     * Rückkehr-Dash: teleportiert den Spieler zurück zur Start-Position des letzten
     * Vorwärts-Dashes, mit Partikel-Trail, kleinerem Schaden und Hitbox entlang
     * des Rückwegs. Wird durch einen zweiten Shift+Rechtsklick innerhalb des
     * {@code returnWindowMs} (Default 700ms) getriggert.
     */
    private void performReturnDash(Player p, Location origin, Location end,
                                   double damage, double hitRadius,
                                   int speedAmp, long speedDurMs,
                                   int jumpAmp, long jumpDurMs,
                                   boolean particles, boolean sounds, int trailCount) {
        World world = p.getWorld();
        Location currentEye = p.getEyeLocation().clone();
        // Teleportiere Spieler zur Origin-Position (gleiche Yaw/Pitch).
        Location targetLoc = origin.clone();
        targetLoc.setYaw(currentEye.getYaw());
        targetLoc.setPitch(currentEye.getPitch());

        // Sounds am Start + Ende
        if (sounds) {
            world.playSound(currentEye, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 1.8f);
            world.playSound(targetLoc, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.5f, 1.6f);
        }

        // Partikel-Trail entlang des Rückwegs (Start → Origin)
        if (particles) {
            double dx = origin.getX() - currentEye.getX();
            double dy = origin.getY() - currentEye.getY();
            double dz = origin.getZ() - currentEye.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int steps = Math.max(2, (int) (dist / 0.5));
            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                Location trailLoc = currentEye.clone().add(
                        dx * t, dy * t, dz * t);
                world.spawnParticle(Particle.ELECTRIC_SPARK, trailLoc, 2, 0.1, 0.1, 0.1, 0.02);
                world.spawnParticle(Particle.FIREWORK, trailLoc, 1, 0.05, 0.05, 0.05, 0.03);
            }
        }

        // Hitbox-Check entlang des Rückwegs: alle Entities zwischen current und origin.
        int hitCount = 0;
        Vector dir = origin.toVector().subtract(currentEye.toVector()).normalize();
        double lineLen = currentEye.distance(origin);
        double hitRadiusSq = hitRadius * hitRadius;
        for (LivingEntity target : world.getLivingEntities()) {
            if (target.getUniqueId().equals(p.getUniqueId())) continue;
            if (target.isDead() || !target.isValid()) continue;

            Location[] checkPoints = {
                    target.getEyeLocation(),
                    target.getLocation().add(0, target.getHeight() * 0.5, 0),
                    target.getLocation()
            };
            boolean hit = false;
            for (Location cp : checkPoints) {
                Vector toTarget = cp.toVector().subtract(currentEye.toVector());
                double along = toTarget.dot(dir);
                if (along < -0.5 || along > lineLen + 0.5) continue;
                Vector perp = toTarget.subtract(dir.clone().multiply(along));
                if (perp.lengthSquared() <= hitRadiusSq) { hit = true; break; }
            }
            if (!hit) continue;

            if (damage > 0) target.damage(damage, p);
            world.strikeLightningEffect(target.getEyeLocation());
            hitCount++;
        }

        p.teleport(targetLoc);
        plugin.getLogger().info("[LightningDash] " + p.getName() + " RETURN-DASH completed: from " + currentEye + " to " + targetLoc);

        // Speed + Jump-Boost (kürzer, z.B. 1s — wir wollen kein massives Buff)
        // Wir nutzen hier nur die Hälfte der Original-Dauer um den Rückkehr-Dash
        // nicht zum Hauptnutzen zu machen.
        p.addPotionEffect(new PotionEffect(
                resolvePotionType("SPEED"),
                20, // 1s
                Math.max(0, speedAmp - 2),
                true, false, false));
        p.addPotionEffect(new PotionEffect(
                resolvePotionType("JUMP_BOOST"),
                20,
                Math.max(0, jumpAmp - 2),
                true, false, false));

        p.sendActionBar(MM.deserialize(
                "<gradient:#6666ff:#ffff00><bold>Donnerkatana</bold> <dark_gray>» <aqua>Blitz-Rückkehr! <gray>("
                        + hitCount + " getroffen)"));
    }

    /**
     * Startet einen dichten Funken-Pool am Pivot-Punkt des Dashs.
     */
    private void spawnElectricParticles(World world, Location loc, int count) {
        world.spawnParticle(Particle.ELECTRIC_SPARK, loc, count, 0.3, 0.3, 0.3, 0.05);
        world.spawnParticle(Particle.FIREWORK, loc, count / 2, 0.2, 0.2, 0.2, 0.05);
    }

    private boolean isPassable(Material m) {
        return PASSABLE.contains(m) || !m.isSolid() || m.isAir();
    }

    /**
     * Reflection-basierter PotionEffectType-Resolver für Namens-Variationen
     * (Paper 1.21+ benennt Felder teilweise um).
     */
    private PotionEffectType resolvePotionType(String primaryName) {
        try {
            return (PotionEffectType) PotionEffectType.class.getField(primaryName).get(null);
        } catch (Throwable t) {
            plugin.getLogger().warning("[LightningDash] Missing PotionEffectType." + primaryName);
            throw new RuntimeException("Missing PotionEffectType." + primaryName, t);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cooldowns.remove(id);
        lastDashOrigin.remove(id);
        lastDashEnd.remove(id);
        lastDashTime.remove(id);
    }
}