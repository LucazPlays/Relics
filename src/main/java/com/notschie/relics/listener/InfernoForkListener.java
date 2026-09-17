package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Inferno Fork abilities:
 * <ul>
 *     <li>Rechtsklick: normaler, bewusst langsamerer Hellfire-Bolt</li>
 *     <li>Sneak + Rechtsklick: Phoenix Dive</li>
 *     <li>Linksklick: Infernal Rift</li>
 * </ul>
 * Alle visuellen Projektile und Flächen sind ausschließlich Partikel.
 */
public class InfernoForkListener implements Listener {

    private static final String RELIC_ID = "inferno_fork";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Particle.DustOptions INFERNO_DUST =
            new Particle.DustOptions(Color.fromRGB(255, 75, 10), 1.15f);

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Map<UUID, Long> normalCooldowns = new HashMap<>();
    private final Map<UUID, Long> phoenixDiveCooldowns = new HashMap<>();
    private final Map<UUID, Long> infernalRiftCooldowns = new HashMap<>();
    private final Map<UUID, Set<BukkitTask>> activeTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> activeZones = new HashMap<>();
    private final Map<UUID, BukkitTask> activeRifts = new HashMap<>();

    public InfernoForkListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isInfernoFork(ItemStack stack) {
        return stack != null && factory.isRelic(stack)
                && RELIC_ID.equals(factory.getRelicId(stack));
    }

    private boolean isInfernoForkInHand(Player player) {
        return isInfernoFork(player.getInventory().getItemInMainHand())
                || isInfernoFork(player.getInventory().getItemInOffHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isInfernoFork(event.getItem()) && !isInfernoForkInHand(player)) return;

        event.setCancelled(true);
        if (player.isSneaking()) {
            activatePhoenixDive(player);
        } else {
            activateNormalBolt(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isInfernoFork(event.getItem()) && !isInfernoForkInHand(player)) return;

        event.setCancelled(true);
        activateInfernalRift(player);
    }

    /** Linksklick auf eine Entity liefert oft nur PlayerAnimationEvent. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeftClickAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (isInfernoForkInHand(player)) activateInfernalRift(player);
    }

    private void activateNormalBolt(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;

        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("inferno-normal-cooldown-ms",
                def.getEffectDouble("inferno-cooldown-ms", 3200.0));
        Long until = normalCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Inferno Fork", until - now);
            return;
        }

        Location start = player.getEyeLocation().clone();
        Vector direction = start.getDirection().clone();
        if (direction.lengthSquared() < 1.0e-8) return;
        normalCooldowns.put(player.getUniqueId(), now + cooldownMs);
        spawnFireball(player, def, start, direction.normalize());
        player.sendActionBar(MM.deserialize(
                "<gradient:#ff4b00:#ffd000><bold>Inferno Fork</bold> <dark_gray>» <red>Inferno-Bolt!"));
    }

    private void activatePhoenixDive(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;

        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("phoenix-dive-cooldown-ms", 14000.0);
        Long until = phoenixDiveCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Phoenix Dive", until - now);
            return;
        }

        Location start = player.getEyeLocation().clone();
        Vector direction = start.getDirection().clone();
        if (direction.lengthSquared() < 1.0e-8) return;
        direction.normalize();
        phoenixDiveCooldowns.put(player.getUniqueId(), now + cooldownMs);

        double step = Math.max(0.35, def.getEffectDouble("phoenix-dive-step", 1.35));
        int maxTicks = Math.max(1, def.getEffectInt("phoenix-dive-duration-ticks", 9));
        double impactRadius = def.getEffectDouble("phoenix-dive-impact-radius", 4.5);
        double impactDamage = def.getEffectDouble("phoenix-dive-impact-damage", 8.0);
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.2f, 0.55f);
        world.spawnParticle(Particle.FLAME, player.getLocation(), 36, 0.5, 0.6, 0.5, 0.15);

        final int[] elapsed = {0};
        final BukkitTask[] taskHolder = new BukkitTask[1];
        BukkitRunnable dive = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || !player.isValid() || elapsed[0]++ >= maxTicks) {
                    impactPhoenix(player, def, player.getLocation().clone(), impactRadius, impactDamage);
                    finish(player.getUniqueId(), taskHolder[0]);
                    return;
                }

                Location next = player.getLocation().clone().add(direction.clone().multiply(step));
                if (isSolid(next.getBlock())) {
                    impactPhoenix(player, def, player.getLocation().clone(), impactRadius, impactDamage);
                    finish(player.getUniqueId(), taskHolder[0]);
                    return;
                }

                player.teleport(next);
                world.spawnParticle(Particle.FLAME, next, 14, 0.22, 0.28, 0.22, 0.08);
                world.spawnParticle(Particle.SMOKE, next, 3, 0.12, 0.12, 0.12, 0.02);
            }
        };
        taskHolder[0] = dive.runTaskTimer(plugin, 0L, 1L);
        track(player.getUniqueId(), taskHolder[0]);
        player.sendActionBar(MM.deserialize(
                "<gradient:#ff4b00:#ffd000><bold>Phoenix Dive</bold> <dark_gray>» <gold>Sturzflug!"));
    }

    private void impactPhoenix(Player owner, RelicDefinition def, Location center,
                               double radius, double damage) {
        World world = owner.getWorld();
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.25f, 0.65f);
        world.spawnParticle(Particle.EXPLOSION, center, 3, 0.35, 0.25, 0.35, 0.0);
        world.spawnParticle(Particle.FLAME, center, 75, 1.0, 0.45, 1.0, 0.18);
        world.spawnParticle(Particle.LAVA, center, 20, 0.9, 0.3, 0.9, 0.0);

        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || !isAllowedTarget(owner, target)) continue;
            target.damage(damage, owner);
            target.setFireTicks(Math.max(target.getFireTicks(), 100));
            Vector push = target.getLocation().toVector().subtract(center.toVector());
            if (push.lengthSquared() > 1.0e-6) {
                target.setVelocity(push.normalize().multiply(0.8).setY(0.45));
            }
        }
    }

    private void activateInfernalRift(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;

        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("infernal-rift-cooldown-ms", 12000.0);
        Long until = infernalRiftCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Infernal Rift", until - now);
            return;
        }

        Location origin = player.getEyeLocation().clone();
        Vector direction = origin.getDirection().clone();
        if (direction.lengthSquared() < 1.0e-8) return;
        direction.normalize();
        RelicDefinition definition = def;
        Location end = targetPoint(player, origin, direction,
                definition.getEffectDouble("infernal-rift-range", 22.0));
        infernalRiftCooldowns.put(player.getUniqueId(), now + cooldownMs);

        World world = player.getWorld();
        world.playSound(origin, Sound.ENTITY_BLAZE_SHOOT, 1.1f, 0.45f);
        world.playSound(end, Sound.BLOCK_FIRE_AMBIENT, 1.0f, 0.6f);
        world.spawnParticle(Particle.FLAME, origin, 30, 0.25, 0.25, 0.25, 0.1);

        BukkitTask old = activeRifts.remove(player.getUniqueId());
        if (old != null) old.cancel();

        int duration = Math.max(1, definition.getEffectInt("infernal-rift-duration-ticks", 75));
        int pulseInterval = Math.max(1, definition.getEffectInt("infernal-rift-pulse-interval", 6));
        double radius = definition.getEffectDouble("infernal-rift-radius", 2.5);
        double damage = definition.getEffectDouble("infernal-rift-damage", 3.5);
        final int[] elapsed = {0};
        final Set<UUID> hitOnPulse = new HashSet<>();
        final BukkitTask[] taskHolder = new BukkitTask[1];
        BukkitRunnable rift = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || elapsed[0] >= duration) {
                    activeRifts.remove(player.getUniqueId());
                    finish(player.getUniqueId(), taskHolder[0]);
                    return;
                }

                drawRift(world, origin, end, radius, elapsed[0]);
                if (elapsed[0] % pulseInterval == 0) {
                    hitOnPulse.clear();
                    for (LivingEntity target : world.getLivingEntities()) {
                        if (!isAllowedTarget(player, target)) continue;
                        if (distanceToSegmentSquared(target.getLocation().add(0, target.getHeight() * 0.5, 0),
                                origin, end) > (radius + 0.65) * (radius + 0.65)) continue;
                        if (!hitOnPulse.add(target.getUniqueId())) continue;
                        target.damage(damage, player);
                        target.setFireTicks(Math.max(target.getFireTicks(), 60));
                        applyHellfire(target, definition.getEffectInt("inferno-hellfire-duration-ticks", 160));
                    }
                    world.playSound(end, Sound.BLOCK_FIRE_AMBIENT, 0.4f, 0.7f);
                }
                elapsed[0]++;
            }
        };
        taskHolder[0] = rift.runTaskTimer(plugin, 0L, 1L);
        activeRifts.put(player.getUniqueId(), taskHolder[0]);
        track(player.getUniqueId(), taskHolder[0]);
        player.sendActionBar(MM.deserialize(
                "<gradient:#ff4b00:#ffd000><bold>Infernal Rift</bold> <dark_gray>» <red>Der Riss brennt!"));
    }

    private void spawnFireball(Player owner, RelicDefinition def, Location start, Vector direction) {
        World world = owner.getWorld();
        Location launch = start.clone().add(direction.clone().multiply(0.4));
        world.playSound(start, Sound.ENTITY_BLAZE_SHOOT, 0.9f, 0.7f);
        world.spawnParticle(Particle.FLAME, launch, 16, 0.12, 0.12, 0.12, 0.03);

        final double speedPerTick = def.getEffectDouble("inferno-projectile-speed", 10.0) * 0.05;
        final double gravityPerTick = def.getEffectDouble("inferno-projectile-gravity", 0.018);
        final double range = def.getEffectDouble("inferno-projectile-range", 32.0);
        final double hitRadius = def.getEffectDouble("inferno-projectile-hit-radius", 0.8);
        final double directDamage = def.getEffectDouble("inferno-direct-damage", 5.0);
        final int maxFrames = Math.max(1, (int) Math.ceil(range / Math.max(0.05, speedPerTick)) + 10);
        final Vector velocity = direction.clone().normalize().multiply(speedPerTick);
        final Location[] position = {launch};
        final double[] travelled = {0.0};
        final int[] frame = {0};
        final BukkitTask[] taskHolder = new BukkitTask[1];

        BukkitRunnable flight = new BukkitRunnable() {
            @Override
            public void run() {
                if (!owner.isOnline() || owner.isDead() || !owner.isValid()
                        || frame[0]++ >= maxFrames || travelled[0] >= range) {
                    finish(owner.getUniqueId(), taskHolder[0]);
                    return;
                }

                velocity.setY(velocity.getY() - gravityPerTick);
                int subSteps = Math.max(1, (int) Math.ceil(velocity.length() / 0.22));
                Vector subStep = velocity.clone().multiply(1.0 / subSteps);
                boolean detonated = false;
                for (int i = 0; i < subSteps && !detonated; i++) {
                    Location next = position[0].clone().add(subStep);
                    travelled[0] += subStep.length();
                    if (isSolid(next.getBlock())) {
                        position[0] = next;
                        detonate(owner, def, position[0], directDamage, null);
                        detonated = true;
                        break;
                    }
                    LivingEntity target = findTarget(owner, next, hitRadius);
                    if (target != null) {
                        position[0] = next;
                        detonate(owner, def, position[0], directDamage, target);
                        detonated = true;
                        break;
                    }
                    position[0] = next;
                    world.spawnParticle(Particle.FLAME, position[0], 4, 0.06, 0.06, 0.06, 0.015);
                    world.spawnParticle(Particle.SMOKE, position[0], 1, 0.03, 0.03, 0.03, 0.01);
                }

                if (detonated) finish(owner.getUniqueId(), taskHolder[0]);
            }
        };

        taskHolder[0] = flight.runTaskTimer(plugin, 1L, 1L);
        track(owner.getUniqueId(), taskHolder[0]);
    }

    private void detonate(Player owner, RelicDefinition def, Location center,
                          double directDamage, LivingEntity directTarget) {
        World world = owner.getWorld();
        double zoneRadius = def.getEffectDouble("inferno-zone-radius", 4.7);
        int durationTicks = Math.max(1, def.getEffectInt("inferno-zone-duration-ticks", 45));
        int pulseInterval = Math.max(1, def.getEffectInt("inferno-zone-pulse-interval", 5));
        double pulseDamage = def.getEffectDouble("inferno-zone-damage", 3.0);
        int hellfireTicks = Math.max(1, def.getEffectInt("inferno-hellfire-duration-ticks", 240));
        int hellfireAmplifier = Math.max(0, def.getEffectInt("inferno-hellfire-amplifier", 0));

        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.65f);
        world.spawnParticle(Particle.EXPLOSION, center, 2, 0.35, 0.2, 0.35, 0.0);
        world.spawnParticle(Particle.FLAME, center, 40, 0.75, 0.35, 0.75, 0.12);
        world.spawnParticle(Particle.LAVA, center, 12, 0.75, 0.25, 0.75, 0.0);

        if (directTarget != null && !directTarget.isDead() && isAllowedTarget(owner, directTarget)) {
            directTarget.damage(directDamage, owner);
            applyHellfire(directTarget, hellfireTicks, hellfireAmplifier);
        }

        BukkitTask oldZone = activeZones.remove(owner.getUniqueId());
        if (oldZone != null) oldZone.cancel();
        final int[] elapsed = {0};
        final BukkitTask[] taskHolder = new BukkitTask[1];
        BukkitRunnable zone = new BukkitRunnable() {
            @Override
            public void run() {
                if (!owner.isOnline() || owner.isDead() || elapsed[0] >= durationTicks) {
                    activeZones.remove(owner.getUniqueId());
                    finish(owner.getUniqueId(), taskHolder[0]);
                    return;
                }

                spawnZoneParticles(world, center, zoneRadius, elapsed[0]);
                if (elapsed[0] % pulseInterval == 0) {
                    for (Entity entity : world.getNearbyEntities(center, zoneRadius, zoneRadius, zoneRadius)) {
                        if (!(entity instanceof LivingEntity target) || !isAllowedTarget(owner, target)) continue;
                        target.damage(pulseDamage, owner);
                        target.setFireTicks(Math.max(target.getFireTicks(), 40));
                        applyHellfire(target, hellfireTicks, hellfireAmplifier);
                    }
                    world.playSound(center, Sound.BLOCK_FIRE_AMBIENT, 0.55f, 0.75f);
                }
                elapsed[0]++;
            }
        };
        taskHolder[0] = zone.runTaskTimer(plugin, 0L, 1L);
        activeZones.put(owner.getUniqueId(), taskHolder[0]);
        track(owner.getUniqueId(), taskHolder[0]);
    }

    private void drawRift(World world, Location start, Location end, double radius, int tick) {
        Vector delta = end.toVector().subtract(start.toVector());
        double length = delta.length();
        if (length < 0.1) return;
        Vector forward = delta.clone().normalize();
        Vector side = new Vector(-forward.getZ(), 0.0, forward.getX());
        if (side.lengthSquared() < 0.01) side = new Vector(1, 0, 0);
        side.normalize();
        int samples = Math.max(2, (int) Math.ceil(length / 0.55));
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            Location point = start.clone().add(delta.clone().multiply(t));
            double wave = Math.sin(t * Math.PI * 8.0 + tick * 0.35) * radius * 0.45;
            point.add(side.clone().multiply(wave));
            world.spawnParticle(Particle.FLAME, point, 2, 0.12, 0.08, 0.12, 0.035);
            if (i % 2 == 0) world.spawnParticle(Particle.SMOKE, point.clone().add(0, 0.35, 0),
                    1, 0.06, 0.12, 0.06, 0.02);
        }
        for (int i = 0; i < 16; i++) {
            double angle = Math.PI * 2.0 * i / 16.0 + tick * 0.12;
            Location edge = end.clone().add(Math.cos(angle) * radius, 0.12,
                    Math.sin(angle) * radius);
            world.spawnParticle(Particle.FLAME, edge, 1, 0.04, 0.08, 0.04, 0.015);
        }
    }

    private LivingEntity findTarget(Player owner, Location location, double radius) {
        for (Entity entity : owner.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (entity instanceof LivingEntity target && isAllowedTarget(owner, target)) return target;
        }
        return null;
    }

    private boolean isAllowedTarget(Player owner, LivingEntity target) {
        if (target.isDead() || !target.isValid()) return false;
        if (target.getUniqueId().equals(owner.getUniqueId())) return false;
        return !(target instanceof Player player) || !RelicUtils.isSameTeam(owner, player);
    }

    private void applyHellfire(LivingEntity target, int durationTicks) {
        applyHellfire(target, durationTicks, 0);
    }

    private void applyHellfire(LivingEntity target, int durationTicks, int amplifier) {
        target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                durationTicks, amplifier, true, true, true));
    }

    private Location targetPoint(Player player, Location origin, Vector direction, double range) {
        RayTraceResult trace = player.getWorld().rayTraceBlocks(origin, direction, range,
                org.bukkit.FluidCollisionMode.NEVER, true);
        if (trace != null && trace.getHitPosition() != null) {
            Vector hit = trace.getHitPosition();
            return new Location(player.getWorld(), hit.getX(), hit.getY(), hit.getZ())
                    .subtract(direction.clone().multiply(0.15));
        }
        return origin.clone().add(direction.clone().multiply(range));
    }

    private static double distanceToSegmentSquared(Location point, Location start, Location end) {
        Vector segment = end.toVector().subtract(start.toVector());
        Vector fromStart = point.toVector().subtract(start.toVector());
        double lengthSquared = segment.lengthSquared();
        if (lengthSquared < 1.0e-8) return fromStart.lengthSquared();
        double t = Math.max(0.0, Math.min(1.0, fromStart.dot(segment) / lengthSquared));
        return point.toVector().distanceSquared(start.toVector().add(segment.multiply(t)));
    }

    private void track(UUID ownerId, BukkitTask task) {
        activeTasks.computeIfAbsent(ownerId, ignored -> new HashSet<>()).add(task);
    }

    private void finish(UUID ownerId, BukkitTask task) {
        if (task != null) task.cancel();
        Set<BukkitTask> tasks = activeTasks.get(ownerId);
        if (tasks != null) {
            tasks.remove(task);
            if (tasks.isEmpty()) activeTasks.remove(ownerId);
        }
    }

    private void sendCooldown(Player player, String ability, long remainingMs) {
        player.sendActionBar(MM.deserialize(
                "<gradient:#ff4b00:#ffd000><bold>" + ability + "</bold> <dark_gray>» <red>lädt noch <white>"
                        + String.format("%.1f", remainingMs / 1000.0) + "s <red>auf."));
    }

    private void cleanupPlayer(UUID playerId) {
        Set<BukkitTask> tasks = activeTasks.remove(playerId);
        if (tasks != null) {
            for (BukkitTask task : new HashSet<>(tasks)) task.cancel();
        }
        BukkitTask zone = activeZones.remove(playerId);
        if (zone != null) zone.cancel();
        BukkitTask rift = activeRifts.remove(playerId);
        if (rift != null) rift.cancel();
        normalCooldowns.remove(playerId);
        phoenixDiveCooldowns.remove(playerId);
        infernalRiftCooldowns.remove(playerId);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cleanupPlayer(event.getPlayer().getUniqueId());
    }

    private static boolean isSolid(Block block) {
        Material material = block.getType();
        if (material.isAir() || material == Material.WATER || material == Material.LAVA) return false;
        return !block.isPassable();
    }

    private void spawnZoneParticles(World world, Location center, double radius, int tick) {
        for (int i = 0; i < 16; i++) {
            double angle = (Math.PI * 2.0 * i / 16.0) + tick * 0.08;
            double y = 0.15 + (i % 3) * 0.25;
            Location point = center.clone().add(Math.cos(angle) * radius, y,
                    Math.sin(angle) * radius);
            world.spawnParticle(Particle.FLAME, point, 1, 0.05, 0.12, 0.05, 0.02);
        }
        world.spawnParticle(Particle.FLAME, center, 8, radius * 0.55, 0.15, radius * 0.55, 0.05);
        world.spawnParticle(Particle.DUST, center, 4, radius * 0.45, 0.12,
                radius * 0.45, 0.04, INFERNO_DUST);
    }
}
