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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shadowbeam Staff abilities:
 * <ul>
 *     <li>Rechtsklick: langer, stark abprallender Shadowbeam</li>
 *     <li>Sneak + Rechtsklick: Void Rift</li>
 *     <li>Linksklick: Shadow Barrage</li>
 * </ul>
 * Die Strahlen werden ausschließlich mit violetten Partikeln dargestellt.
 */
public class ShadowbeamStaffListener implements Listener {

    private static final String RELIC_ID = "shadowbeam_staff";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Particle.DustOptions SHADOW_DUST =
            new Particle.DustOptions(Color.fromRGB(135, 40, 255), 1.15f);

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Map<UUID, Long> normalCooldowns = new HashMap<>();
    private final Map<UUID, Long> voidRiftCooldowns = new HashMap<>();
    private final Map<UUID, Long> shadowBarrageCooldowns = new HashMap<>();
    private final Map<UUID, BukkitTask> activeRifts = new HashMap<>();

    public ShadowbeamStaffListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isShadowbeamStaff(ItemStack stack) {
        return stack != null && factory.isRelic(stack)
                && RELIC_ID.equals(factory.getRelicId(stack));
    }

    private boolean isShadowbeamStaffInHand(Player player) {
        return isShadowbeamStaff(player.getInventory().getItemInMainHand())
                || isShadowbeamStaff(player.getInventory().getItemInOffHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isShadowbeamStaff(event.getItem()) && !isShadowbeamStaffInHand(player)) return;

        event.setCancelled(true);
        if (player.isSneaking()) activateVoidRift(player);
        else activateNormalBeam(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isShadowbeamStaff(event.getItem()) && !isShadowbeamStaffInHand(player)) return;

        event.setCancelled(true);
        activateShadowBarrage(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeftClickAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (isShadowbeamStaffInHand(player)) activateShadowBarrage(player);
    }

    private void activateNormalBeam(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("shadowbeam-cooldown-ms", 900.0);
        Long until = normalCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Shadowbeam Staff", until - now);
            return;
        }
        normalCooldowns.put(player.getUniqueId(), now + cooldownMs);
        fireBeam(player, def, def.getEffectDouble("shadowbeam-damage", 9.0),
                def.getEffectInt("shadowbeam-max-bounces", 12), false);
        player.sendActionBar(MM.deserialize(
                "<gradient:#762cff:#d7aaff><bold>Shadowbeam Staff</bold> <dark_gray>» <light_purple>Verbesserter Beam!"));
    }

    private void activateShadowBarrage(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("shadow-barrage-cooldown-ms", 12000.0);
        Long until = shadowBarrageCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Shadow Barrage", until - now);
            return;
        }
        shadowBarrageCooldowns.put(player.getUniqueId(), now + cooldownMs);

        Location origin = player.getEyeLocation().clone();
        Vector forward = origin.getDirection().clone().normalize();
        int count = Math.max(1, def.getEffectInt("shadow-barrage-count", 7));
        double spread = def.getEffectDouble("shadow-barrage-spread-degrees", 26.0);
        World world = player.getWorld();
        world.playSound(origin, Sound.ENTITY_ENDERMAN_SCREAM, 0.8f, 1.45f);
        world.spawnParticle(Particle.PORTAL, origin, 45, 0.35, 0.35, 0.35, 0.35);
        for (int i = 0; i < count; i++) {
            double offset = count == 1 ? 0.0 : i - (count - 1) / 2.0;
            Vector direction = rotateAroundY(forward, Math.toRadians(offset * spread / 2.0));
            fireBeam(player, def, def.getEffectDouble("shadow-barrage-damage", 6.0),
                    def.getEffectInt("shadow-barrage-max-bounces", 2), true,
                    origin.clone().add(direction.clone().multiply(0.3)), direction);
        }
        player.sendActionBar(MM.deserialize(
                "<gradient:#762cff:#d7aaff><bold>Shadow Barrage</bold> <dark_gray>» <light_purple>Die Leere feuert!"));
    }

    private void activateVoidRift(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("void-rift-cooldown-ms", 16000.0);
        Long until = voidRiftCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Void Rift", until - now);
            return;
        }
        voidRiftCooldowns.put(player.getUniqueId(), now + cooldownMs);

        Location origin = player.getEyeLocation().clone();
        Vector direction = origin.getDirection().clone().normalize();
        double range = def.getEffectDouble("void-rift-range", 28.0);
        Location center = targetPoint(player, origin, direction, range);
        World world = player.getWorld();
        double radius = def.getEffectDouble("void-rift-radius", 4.5);
        int duration = Math.max(1, def.getEffectInt("void-rift-duration-ticks", 100));
        int pulseInterval = Math.max(1, def.getEffectInt("void-rift-pulse-interval", 8));
        double damage = def.getEffectDouble("void-rift-damage", 4.0);
        double pull = def.getEffectDouble("void-rift-pull", 0.12);
        world.playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1.4f, 0.5f);
        world.spawnParticle(Particle.REVERSE_PORTAL, center, 100, radius * 0.5, radius * 0.5, radius * 0.5, 0.18);

        BukkitTask old = activeRifts.remove(player.getUniqueId());
        if (old != null) old.cancel();
        final int[] elapsed = {0};
        final Set<UUID> hitThisPulse = new HashSet<>();
        final BukkitTask[] taskHolder = new BukkitTask[1];
        BukkitRunnable rift = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || elapsed[0] >= duration) {
                    activeRifts.remove(player.getUniqueId());
                    cancel();
                    return;
                }
                drawVoidRift(world, center, radius, elapsed[0]);
                if (elapsed[0] % pulseInterval == 0) {
                    hitThisPulse.clear();
                    for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
                        if (!(entity instanceof LivingEntity target) || !isAllowedTarget(player, target)) continue;
                        if (!hitThisPulse.add(target.getUniqueId())) continue;
                        double normalPulse = damage * 0.55;
                        double truePulse = damage * 0.45;
                        RelicUtils.applyHybridDamage(target, player, normalPulse, truePulse);
                        Vector toCenter = center.toVector().subtract(target.getLocation().toVector());
                        if (toCenter.lengthSquared() > 0.01) {
                            target.setVelocity(target.getVelocity().add(toCenter.normalize().multiply(pull)));
                        }
                    }
                    world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_AMBIENT, 0.5f, 0.7f);
                }
                elapsed[0]++;
            }
        };
        taskHolder[0] = rift.runTaskTimer(plugin, 0L, 1L);
        activeRifts.put(player.getUniqueId(), taskHolder[0]);
        player.sendActionBar(MM.deserialize(
                "<gradient:#762cff:#d7aaff><bold>Void Rift</bold> <dark_gray>» <light_purple>Ein Riss in der Leere!"));
    }

    private void fireBeam(Player owner, RelicDefinition def, double damage, int maxBounces,
                          boolean barrage) {
        Location origin = owner.getEyeLocation().clone();
        Vector direction = origin.getDirection().clone().normalize();
        fireBeam(owner, def, damage, maxBounces, barrage,
                origin.clone().add(direction.clone().multiply(0.3)), direction);
    }

    private void fireBeam(Player owner, RelicDefinition def, double damage, int maxBounces,
                          boolean barrage, Location origin, Vector direction) {
        World world = owner.getWorld();
        double maxRange = def.getEffectDouble(barrage ? "shadow-barrage-range" : "shadowbeam-range",
                barrage ? 32.0 : 84.0);
        double step = Math.max(0.05, def.getEffectDouble("shadowbeam-step", 0.1));
        double hitRadius = Math.max(0.15, def.getEffectDouble("shadowbeam-hit-radius", 0.55));
        double decay = Math.max(0.0, Math.min(1.0,
                def.getEffectDouble("shadowbeam-damage-decay", 0.10)));
        Set<UUID> hitTargets = new HashSet<>();
        List<Location> beamPath = new ArrayList<>();
        beamPath.add(origin.clone());

        Location segmentStart = origin.clone();
        Vector segmentDirection = direction.clone().normalize();
        double remainingRange = maxRange;
        int bounceCount = 0;
        int entityHits = 0;
        while (remainingRange > 0.05 && bounceCount <= maxBounces) {
            RayTraceResult blockTrace = world.rayTraceBlocks(segmentStart, segmentDirection,
                    remainingRange, org.bukkit.FluidCollisionMode.NEVER, true);
            double blockDistance = blockTrace != null && blockTrace.getHitPosition() != null
                    ? blockTrace.getHitPosition().distance(segmentStart.toVector()) : remainingRange;
            blockDistance = Math.max(0.05, Math.min(remainingRange, blockDistance));
            Location segmentEnd = segmentStart.clone().add(segmentDirection.clone().multiply(blockDistance));
            beamPath.add(segmentEnd.clone());
            entityHits += hitEntities(owner, segmentStart, segmentDirection, blockDistance,
                    hitRadius, damage, decay, hitTargets);

            if (blockTrace == null || blockTrace.getHitBlock() == null
                    || blockTrace.getHitBlockFace() == null || blockDistance >= remainingRange - 0.02) break;

            Block block = blockTrace.getHitBlock();
            Vector normal = blockTrace.getHitBlockFace().getDirection().clone().normalize();
            Vector reflected = reflect(segmentDirection, normal);
            if (reflected.lengthSquared() < 1.0e-8) break;
            bounceCount++;
            remainingRange -= blockDistance;
            segmentStart = segmentEnd.clone().add(reflected.clone().multiply(0.08));
            segmentDirection = reflected.normalize();
            world.spawnParticle(Particle.PORTAL, segmentEnd, barrage ? 10 : 20,
                    0.15, 0.15, 0.15, 0.25);
        }

        drawBeam(world, beamPath, SHADOW_DUST, barrage ? 0.22 : 0.18);
        world.playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, barrage ? 0.22f : 0.55f,
                barrage ? 1.9f : 1.65f);
        if (entityHits > 0) {
            world.playSound(beamPath.get(beamPath.size() - 1), Sound.ENTITY_PLAYER_ATTACK_CRIT,
                    0.55f, 1.5f);
        }
    }

    private int hitEntities(Player owner, Location start, Vector direction, double length,
                            double hitRadius, double baseDamage, double decay,
                            Set<UUID> hitTargets) {
        int hits = 0;
        Vector startVector = start.toVector();
        for (Entity entity : owner.getWorld().getNearbyEntities(start, length + hitRadius,
                length + hitRadius, length + hitRadius)) {
            if (!(entity instanceof LivingEntity target)) continue;
            if (target.getUniqueId().equals(owner.getUniqueId())) continue;
            if (hitTargets.contains(target.getUniqueId())) continue;
            if (target instanceof Player targetPlayer && RelicUtils.isSameTeam(owner, targetPlayer)) continue;

            Location[] points = {target.getLocation(),
                    target.getLocation().add(0.0, target.getHeight() * 0.5, 0.0), target.getEyeLocation()};
            boolean hit = false;
            for (Location point : points) {
                Vector offset = point.toVector().subtract(startVector);
                double along = offset.dot(direction);
                if (along < -hitRadius || along > length + hitRadius) continue;
                Vector perpendicular = offset.clone().subtract(direction.clone().multiply(along));
                if (perpendicular.lengthSquared() <= hitRadius * hitRadius) {
                    hit = true;
                    break;
                }
            }
            if (!hit) continue;

            double targetDamage = baseDamage * Math.pow(1.0 - decay, hitTargets.size());
            hitTargets.add(target.getUniqueId());

            // Hybrid-Schaden: 60% Normal + 50% True Damage (Gebuffter Durchschlag!)
            double normalPart = targetDamage * 0.60;
            double truePart = targetDamage * 0.50;
            RelicUtils.applyHybridDamage(target, owner, normalPart, truePart);

            owner.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, target.getEyeLocation(),
                    8, 0.18, 0.25, 0.18, 0.08);
            hits++;
        }
        return hits;
    }

    private void drawBeam(World world, List<Location> path, Particle.DustOptions dust, double spacing) {
        for (int i = 0; i + 1 < path.size(); i++) {
            Location from = path.get(i);
            Location to = path.get(i + 1);
            Vector delta = to.toVector().subtract(from.toVector());
            double length = delta.length();
            if (length < 0.01) continue;
            Vector step = delta.normalize().multiply(spacing);
            Location cursor = from.clone();
            int count = Math.max(1, (int) Math.ceil(length / spacing));
            for (int j = 0; j <= count; j++) {
                world.spawnParticle(Particle.DUST, cursor, 1, 0.0, 0.0, 0.0, 0.0, dust);
                if (j % 3 == 0) world.spawnParticle(Particle.END_ROD, cursor, 1,
                        0.01, 0.01, 0.01, 0.0);
                cursor.add(step);
            }
        }
    }

    private void drawVoidRift(World world, Location center, double radius, int tick) {
        for (int i = 0; i < 40; i++) {
            double angle = Math.PI * 2.0 * i / 40.0 + tick * 0.15;
            double y = Math.sin(angle * 3.0 + tick * 0.08) * radius * 0.7;
            Location point = center.clone().add(Math.cos(angle) * radius, y,
                    Math.sin(angle) * radius);
            world.spawnParticle(Particle.REVERSE_PORTAL, point, 2, 0.08, 0.08, 0.08, 0.18);
            world.spawnParticle(Particle.DUST, point, 1, 0.03, 0.03, 0.03, 0.02, SHADOW_DUST);
        }
        world.spawnParticle(Particle.SQUID_INK, center, 8, radius * 0.45, radius * 0.35,
                radius * 0.45, 0.02);
    }

    private Location targetPoint(Player player, Location origin, Vector direction, double range) {
        RayTraceResult trace = player.getWorld().rayTraceBlocks(origin, direction, range,
                org.bukkit.FluidCollisionMode.NEVER, true);
        if (trace != null && trace.getHitPosition() != null) {
            Vector hit = trace.getHitPosition();
            return new Location(player.getWorld(), hit.getX(), hit.getY(), hit.getZ())
                    .subtract(direction.clone().multiply(0.2));
        }
        return origin.clone().add(direction.clone().multiply(range));
    }

    private boolean isAllowedTarget(Player owner, LivingEntity target) {
        if (target.isDead() || !target.isValid()) return false;
        if (target.getUniqueId().equals(owner.getUniqueId())) return false;
        return !(target instanceof Player player) || !RelicUtils.isSameTeam(owner, player);
    }

    private void sendCooldown(Player player, String ability, long remainingMs) {
        player.sendActionBar(MM.deserialize(
                "<gradient:#762cff:#d7aaff><bold>" + ability + "</bold> <dark_gray>» <red>lädt noch <white>"
                        + String.format("%.1f", remainingMs / 1000.0) + "s <red>auf."));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        normalCooldowns.remove(id);
        voidRiftCooldowns.remove(id);
        shadowBarrageCooldowns.remove(id);
        BukkitTask rift = activeRifts.remove(id);
        if (rift != null) rift.cancel();
    }

    private static Vector reflect(Vector direction, Vector normal) {
        return direction.clone().subtract(normal.clone().multiply(2.0 * direction.dot(normal)));
    }

    private static Vector rotateAroundY(Vector vector, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new Vector(
                vector.getX() * cos + vector.getZ() * sin,
                vector.getY(),
                -vector.getX() * sin + vector.getZ() * cos
        ).normalize();
    }
}
