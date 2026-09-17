package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
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
 * Aqua Scepter abilities:
 * <ul>
 *     <li>Rechtsklick: schneller Wasserstrom</li>
 *     <li>Sneak + Rechtsklick: Hydro Prison</li>
 *     <li>Linksklick: Maelstrom</li>
 * </ul>
 * Aqua-Treffer setzen das Ziel unter Wasser-Druck: die Luft bleibt leer, bis acht
 * Sekunden lang kein weiterer Aqua-Treffer erfolgt.
 */
public class AquaScepterListener implements Listener {

    private static final String RELIC_ID = "aqua_scepter";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Map<UUID, Long> normalCooldowns = new HashMap<>();
    private final Map<UUID, Long> hydroPrisonCooldowns = new HashMap<>();
    private final Map<UUID, Long> maelstromCooldowns = new HashMap<>();
    private final Map<UUID, Long> airLockUntil = new HashMap<>();
    private final Map<UUID, Set<BukkitTask>> activeTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> activeMaelstroms = new HashMap<>();
    private final Map<UUID, BukkitTask> activePrisons = new HashMap<>();

    public AquaScepterListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
        new BukkitRunnable() {
            @Override
            public void run() {
                enforceAirLocks();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private boolean isAquaScepter(ItemStack stack) {
        return stack != null && factory.isRelic(stack)
                && RELIC_ID.equals(factory.getRelicId(stack));
    }

    private boolean isAquaScepterInHand(Player player) {
        return isAquaScepter(player.getInventory().getItemInMainHand())
                || isAquaScepter(player.getInventory().getItemInOffHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isAquaScepter(event.getItem()) && !isAquaScepterInHand(player)) return;

        event.setCancelled(true);
        if (player.isSneaking()) activateHydroPrison(player);
        else activateNormalStream(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAirChange(org.bukkit.event.entity.EntityAirChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Long lockedUntil = airLockUntil.get(player.getUniqueId());
        if (lockedUntil != null && System.currentTimeMillis() < lockedUntil) {
            event.setAmount(Math.min(event.getAmount(), 0));
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isAquaScepter(event.getItem()) && !isAquaScepterInHand(player)) return;

        event.setCancelled(true);
        activateMaelstrom(player);
    }

    private void activateNormalStream(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("aqua-cooldown-ms", 650.0);
        Long until = normalCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Aqua Scepter", until - now);
            return;
        }
        normalCooldowns.put(player.getUniqueId(), now + cooldownMs);

        int streamCount = Math.max(1, def.getEffectInt("aqua-stream-count", 2));
        double spreadDegrees = def.getEffectDouble("aqua-stream-spread-degrees", 8.0);
        Location start = player.getEyeLocation().clone();
        Vector forward = start.getDirection().clone().normalize();
        World world = player.getWorld();
        world.playSound(start, Sound.ENTITY_PLAYER_SPLASH, 0.9f, 1.35f);
        world.spawnParticle(Particle.SPLASH, start, 10, 0.15, 0.15, 0.15, 0.15);
        for (int i = 0; i < streamCount; i++) {
            double offset = i - (streamCount - 1) / 2.0;
            Vector direction = rotateAroundY(forward, Math.toRadians(offset * spreadDegrees));
            spawnStream(player, def, start.clone().add(direction.clone().multiply(0.35)), direction,
                    def.getEffectDouble("aqua-stream-damage", 6.0));
        }
        player.sendActionBar(MM.deserialize(
                "<gradient:#00bfff:#eaffff><bold>Aqua Scepter</bold> <dark_gray>» <aqua>Schneller Wasserstrom!"));
    }

    private void activateHydroPrison(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("hydro-prison-cooldown-ms", 14000.0);
        Long until = hydroPrisonCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Hydro Prison", until - now);
            return;
        }
        hydroPrisonCooldowns.put(player.getUniqueId(), now + cooldownMs);

        Location origin = player.getEyeLocation().clone();
        Vector direction = origin.getDirection().clone().normalize();
        double range = def.getEffectDouble("hydro-prison-range", 24.0);
        LivingEntity directTarget = findTargetAlongRay(player, origin, direction, range,
                def.getEffectDouble("hydro-prison-hit-radius", 1.2));
        Location center = directTarget != null ? directTarget.getLocation().clone().add(0, directTarget.getHeight() * 0.5, 0)
                : targetPoint(player, origin, direction, range).add(0, 1.0, 0);
        World world = player.getWorld();
        double radius = def.getEffectDouble("hydro-prison-radius", 2.0);
        int duration = Math.max(1, def.getEffectInt("hydro-prison-duration-ticks", 70));
        double damage = def.getEffectDouble("hydro-prison-damage", 5.0);
        double pullRadius = def.getEffectDouble("hydro-prison-pull-radius", 6.0);
        double pullStrength = def.getEffectDouble("hydro-prison-pull-strength", 0.35);
        world.playSound(center, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 1.3f, 0.7f);
        world.spawnParticle(Particle.BUBBLE_COLUMN_UP, center, 80, radius, radius, radius, 0.12);

        BukkitTask old = activePrisons.remove(player.getUniqueId());
        if (old != null) old.cancel();
        final int[] elapsed = {0};
        final BukkitTask[] taskHolder = new BukkitTask[1];
        BukkitRunnable prison = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || elapsed[0] >= duration) {
                    activePrisons.remove(player.getUniqueId());
                    finish(player.getUniqueId(), taskHolder[0]);
                    return;
                }
                drawPrison(world, center, radius, elapsed[0]);

                // AOE Vortex: Zieht alle erlaubten Ziele in die Mitte des Hydroprisons
                for (Entity entity : world.getNearbyEntities(center, pullRadius, pullRadius, pullRadius)) {
                    if (entity.equals(player)) continue;
                    if (entity instanceof LivingEntity le) {
                        if (!isAllowedTarget(player, le)) continue;

                        Location leLoc = le.getLocation().add(0, le.getHeight() * 0.5, 0);
                        Vector toCenter = center.toVector().subtract(leLoc.toVector());
                        double dist = toCenter.length();

                        if (dist > 0.45) {
                            // Starker Strudel-Sog in Richtung Zentrum
                            double speed = Math.min(0.45, Math.max(0.20, pullStrength * (dist / 2.0)));
                            Vector pullVel = toCenter.normalize().multiply(speed);
                            if (center.getY() > leLoc.getY()) {
                                pullVel.setY(Math.min(0.32, pullVel.getY() + 0.08));
                            }
                            le.setVelocity(pullVel);
                        } else {
                            // Im Zentrum fesseln / schweben lassen
                            le.setVelocity(new Vector(0, 0.02, 0));
                        }

                        // Schaden & Luft-Entzug alle 10 Ticks (0.5s)
                        if (elapsed[0] == 0 || elapsed[0] % 10 == 0) {
                            le.setNoDamageTicks(0);
                            le.damage(damage, player);
                            registerAquaHit(le, def);
                        }
                    } else if (entity instanceof Item item) {
                        Vector toCenter = center.toVector().subtract(item.getLocation().toVector());
                        if (toCenter.length() > 0.3) {
                            item.setVelocity(toCenter.normalize().multiply(0.25));
                        }
                    }
                }

                if (elapsed[0] % 15 == 0) {
                    world.playSound(center, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 0.8f, 0.9f);
                }

                elapsed[0]++;
            }
        };
        taskHolder[0] = prison.runTaskTimer(plugin, 0L, 1L);
        activePrisons.put(player.getUniqueId(), taskHolder[0]);
        track(player.getUniqueId(), taskHolder[0]);
        player.sendActionBar(MM.deserialize(
                "<gradient:#00bfff:#eaffff><bold>Hydro Prison</bold> <dark_gray>» <aqua>Im Wasser gefangen!"));
    }

    private void activateMaelstrom(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("maelstrom-cooldown-ms", 12000.0);
        Long until = maelstromCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Maelstrom", until - now);
            return;
        }
        maelstromCooldowns.put(player.getUniqueId(), now + cooldownMs);

        Location origin = player.getEyeLocation().clone();
        Vector direction = origin.getDirection().clone().normalize();
        Location center = targetPoint(player, origin, direction,
                def.getEffectDouble("maelstrom-range", 26.0));
        World world = player.getWorld();
        double radius = def.getEffectDouble("maelstrom-radius", 5.5);
        int duration = Math.max(1, def.getEffectInt("maelstrom-duration-ticks", 100));
        int pulseInterval = Math.max(1, def.getEffectInt("maelstrom-pulse-interval", 8));
        double damage = def.getEffectDouble("maelstrom-damage", 3.0);
        double pull = def.getEffectDouble("maelstrom-pull", 0.22);
        world.playSound(center, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 1.5f, 0.65f);
        world.spawnParticle(Particle.BUBBLE_COLUMN_UP, center, 100, radius, 0.5, radius, 0.12);

        BukkitTask old = activeMaelstroms.remove(player.getUniqueId());
        if (old != null) old.cancel();
        final int[] elapsed = {0};
        final Set<UUID> hitThisPulse = new HashSet<>();
        final BukkitTask[] taskHolder = new BukkitTask[1];
        BukkitRunnable maelstrom = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || elapsed[0] >= duration) {
                    activeMaelstroms.remove(player.getUniqueId());
                    finish(player.getUniqueId(), taskHolder[0]);
                    return;
                }
                drawMaelstrom(world, center, radius, elapsed[0]);
                if (elapsed[0] % pulseInterval == 0) {
                    hitThisPulse.clear();
                    for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
                        if (!(entity instanceof LivingEntity target) || !isAllowedTarget(player, target)) continue;
                        Vector toCenter = center.toVector().subtract(target.getLocation().toVector());
                        if (toCenter.lengthSquared() > 0.01) {
                            target.setVelocity(target.getVelocity().add(toCenter.normalize().multiply(pull)));
                        }
                        if (hitThisPulse.add(target.getUniqueId())) {
                            target.damage(damage, player);
                            registerAquaHit(target, def);
                        }
                    }
                    world.playSound(center, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 0.8f, 0.7f);
                }
                elapsed[0]++;
            }
        };
        taskHolder[0] = maelstrom.runTaskTimer(plugin, 0L, 1L);
        activeMaelstroms.put(player.getUniqueId(), taskHolder[0]);
        track(player.getUniqueId(), taskHolder[0]);
        player.sendActionBar(MM.deserialize(
                "<gradient:#00bfff:#eaffff><bold>Maelstrom</bold> <dark_gray>» <aqua>Der Wasserstrudel zieht alles an!"));
    }

    private void spawnStream(Player owner, RelicDefinition def, Location start, Vector direction,
                             double damage) {
        World world = owner.getWorld();
        world.spawnParticle(Particle.SPLASH, start, 6, 0.08, 0.08, 0.08, 0.12);
        final double speedPerTick = def.getEffectDouble("aqua-stream-speed", 30.0) * 0.05;
        final double gravityPerTick = def.getEffectDouble("aqua-stream-gravity", 0.035);
        final double range = def.getEffectDouble("aqua-stream-range", 32.0);
        final double hitRadius = def.getEffectDouble("aqua-stream-hit-radius", 0.7);
        final int maxHits = Math.max(1, def.getEffectInt("aqua-stream-pierce", 5));
        final boolean lavaBounce = def.getEffectBoolean("aqua-stream-bounce-lava", true);
        final int maxFrames = Math.max(1, (int) Math.ceil(range / Math.max(0.05, speedPerTick)) + 12);
        final Set<UUID> hitTargets = new HashSet<>();
        final Vector velocity = direction.clone().normalize().multiply(speedPerTick);
        final Location[] position = {start.clone()};
        final boolean[] bounced = {false};
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
                int subSteps = Math.max(1, (int) Math.ceil(velocity.length() / 0.25));
                Vector subStep = velocity.clone().multiply(1.0 / subSteps);
                boolean stop = false;
                for (int i = 0; i < subSteps && !stop; i++) {
                    Location next = position[0].clone().add(subStep);
                    travelled[0] += subStep.length();
                    Block block = next.getBlock();
                    if (block.getType() == Material.LAVA && lavaBounce && !bounced[0]
                            && velocity.getY() < 0.0) {
                        position[0] = next;
                        velocity.setY(Math.max(0.28, Math.abs(velocity.getY()) * 0.85 + 0.2));
                        velocity.setX(velocity.getX() * 0.92);
                        velocity.setZ(velocity.getZ() * 0.92);
                        bounced[0] = true;
                        world.spawnParticle(Particle.SPLASH, position[0], 12, 0.2, 0.1, 0.2, 0.18);
                        break;
                    }
                    if (isSolid(block)) {
                        stop = true;
                        break;
                    }
                    position[0] = next;
                    world.spawnParticle(Particle.SPLASH, position[0], 3,
                            0.05, 0.05, 0.05, 0.08);
                    hitTargetsAt(owner, position[0], hitRadius, damage, maxHits, hitTargets, def);
                    if (hitTargets.size() >= maxHits) stop = true;
                }
                if (stop) {
                    world.playSound(position[0], Sound.ENTITY_PLAYER_SPLASH, 0.35f, 1.6f);
                    finish(owner.getUniqueId(), taskHolder[0]);
                }
            }
        };
        taskHolder[0] = flight.runTaskTimer(plugin, 1L, 1L);
        track(owner.getUniqueId(), taskHolder[0]);
    }

    private void hitTargetsAt(Player owner, Location location, double radius, double damage,
                              int maxHits, Set<UUID> hitTargets, RelicDefinition def) {
        if (hitTargets.size() >= maxHits) return;
        for (Entity entity : owner.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || hitTargets.contains(target.getUniqueId())) continue;
            if (!isAllowedTarget(owner, target)) continue;
            hitTargets.add(target.getUniqueId());
            target.damage(damage, owner);
            registerAquaHit(target, def);
            owner.getWorld().spawnParticle(Particle.SPLASH, target.getEyeLocation(), 10,
                    0.2, 0.2, 0.2, 0.12);
            owner.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_SPLASH, 0.45f, 1.8f);
            if (hitTargets.size() >= maxHits) return;
        }
    }

    private void registerAquaHit(LivingEntity target, RelicDefinition def) {
        if (target instanceof Player player) {
            int maximumAir = Math.max(1, player.getMaximumAir());
            int drain = Math.max(1, def.getEffectInt("aqua-air-drain", maximumAir));
            player.setRemainingAir(Math.max(-20, player.getRemainingAir() - drain));
            airLockUntil.put(player.getUniqueId(), System.currentTimeMillis()
                    + (long) def.getEffectDouble("aqua-air-lock-ms", 8000.0));
        }
    }

    /** Hält bei jedem Server-Tick die Luft für Aqua-markierte Spieler auf 0. */
    private void enforceAirLocks() {
        long now = System.currentTimeMillis();
        airLockUntil.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) return true;
            if (now < entry.getValue()) {
                player.setRemainingAir(Math.min(player.getRemainingAir(), 0));
                return false;
            }
            return true;
        });
    }

    private LivingEntity findTargetAlongRay(Player owner, Location origin, Vector direction,
                                            double range, double radius) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : owner.getWorld().getNearbyEntities(origin, range, range, range)) {
            if (!(entity instanceof LivingEntity target) || !isAllowedTarget(owner, target)) continue;
            Vector offset = target.getEyeLocation().toVector().subtract(origin.toVector());
            double along = offset.dot(direction);
            if (along < 0 || along > range) continue;
            Vector perpendicular = offset.clone().subtract(direction.clone().multiply(along));
            if (perpendicular.lengthSquared() <= radius * radius && along < bestDistance) {
                best = target;
                bestDistance = along;
            }
        }
        return best;
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

    private boolean isAllowedTarget(Player owner, LivingEntity target) {
        if (target.isDead() || !target.isValid()) return false;
        if (target.getUniqueId().equals(owner.getUniqueId())) return false;
        return !(target instanceof Player player) || !RelicUtils.isSameTeam(owner, player);
    }

    private void drawPrison(World world, Location center, double radius, int tick) {
        for (int i = 0; i < 32; i++) {
            double angle = Math.PI * 2.0 * i / 32.0 + tick * 0.1;
            for (int y = 0; y < 3; y++) {
                Location point = center.clone().add(Math.cos(angle) * radius,
                        -1.0 + y * radius, Math.sin(angle) * radius);
                world.spawnParticle(Particle.BUBBLE, point, 2, 0.06, 0.06, 0.06, 0.08);
            }
        }
        world.spawnParticle(Particle.BUBBLE_COLUMN_UP, center, 16,
                radius * 0.55, radius * 0.65, radius * 0.55, 0.12);

        // Einziehende Wirbel-Partikel von außen in die Mitte (AOE Vortex-Sog)
        for (int s = 0; s < 4; s++) {
            double spiralAngle = (tick * 0.22) + (s * (Math.PI / 2.0));
            double spiralRadius = radius + 1.2 + Math.sin((tick + s * 4) * 0.2) * 0.8;
            Location inPoint = center.clone().add(Math.cos(spiralAngle) * spiralRadius,
                    (Math.sin(tick * 0.15 + s) * 0.8),
                    Math.sin(spiralAngle) * spiralRadius);
            Vector toCenter = center.toVector().subtract(inPoint.toVector()).normalize().multiply(0.18);
            world.spawnParticle(Particle.SPLASH, inPoint, 2, toCenter.getX(), toCenter.getY(), toCenter.getZ(), 0.1);
        }
    }

    private void drawMaelstrom(World world, Location center, double radius, int tick) {
        for (int i = 0; i < 48; i++) {
            double angle = Math.PI * 2.0 * i / 48.0 + tick * 0.18;
            double ring = radius * (0.25 + 0.75 * ((i % 4) / 3.0));
            Location point = center.clone().add(Math.cos(angle) * ring,
                    0.2 + (i % 4) * 0.35, Math.sin(angle) * ring);
            world.spawnParticle(Particle.BUBBLE_COLUMN_UP, point, 1,
                    0.05, 0.05, 0.05, 0.1);
            world.spawnParticle(Particle.SPLASH, point, 1,
                    0.04, 0.04, 0.04, 0.08);
        }
        world.spawnParticle(Particle.BUBBLE, center, 10,
                radius * 0.45, 0.2, radius * 0.45, 0.08);
    }

    private void sendCooldown(Player player, String ability, long remainingMs) {
        player.sendActionBar(MM.deserialize(
                "<gradient:#00bfff:#eaffff><bold>" + ability + "</bold> <dark_gray>» <red>lädt noch <white>"
                        + String.format("%.1f", remainingMs / 1000.0) + "s <red>auf."));
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

    private void cleanupPlayer(UUID playerId) {
        Set<BukkitTask> tasks = activeTasks.remove(playerId);
        if (tasks != null) for (BukkitTask task : new HashSet<>(tasks)) task.cancel();
        BukkitTask maelstrom = activeMaelstroms.remove(playerId);
        if (maelstrom != null) maelstrom.cancel();
        BukkitTask prison = activePrisons.remove(playerId);
        if (prison != null) prison.cancel();
        normalCooldowns.remove(playerId);
        hydroPrisonCooldowns.remove(playerId);
        maelstromCooldowns.remove(playerId);
        airLockUntil.remove(playerId);
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

    private static Vector rotateAroundY(Vector vector, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new Vector(vector.getX() * cos + vector.getZ() * sin, vector.getY(),
                -vector.getX() * sin + vector.getZ() * cos).normalize();
    }
}
