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
 * Poison Staff abilities:
 * <ul>
 *     <li>Rechtsklick: normale Giftzahn-Salve</li>
 *     <li>Sneak + Rechtsklick: Toxic Rain</li>
 *     <li>Linksklick: Venom Bloom</li>
 * </ul>
 */
public class PoisonStaffListener implements Listener {

    private static final String RELIC_ID = "poison_staff";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Particle.DustOptions POISON_DUST =
            new Particle.DustOptions(Color.fromRGB(80, 220, 40), 1.0f);

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Map<UUID, Long> normalCooldowns = new HashMap<>();
    private final Map<UUID, Long> toxicRainCooldowns = new HashMap<>();
    private final Map<UUID, Long> venomBloomCooldowns = new HashMap<>();
    private final Map<UUID, Set<BukkitTask>> activeTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> activeRain = new HashMap<>();
    private final Map<UUID, BukkitTask> activeBlooms = new HashMap<>();

    public PoisonStaffListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isPoisonStaff(ItemStack stack) {
        return stack != null && factory.isRelic(stack)
                && RELIC_ID.equals(factory.getRelicId(stack));
    }

    private boolean isPoisonStaffInHand(Player player) {
        return isPoisonStaff(player.getInventory().getItemInMainHand())
                || isPoisonStaff(player.getInventory().getItemInOffHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isPoisonStaff(event.getItem()) && !isPoisonStaffInHand(player)) return;

        event.setCancelled(true);
        if (player.isSneaking()) activateToxicRain(player);
        else activateNormalFangs(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isPoisonStaff(event.getItem()) && !isPoisonStaffInHand(player)) return;

        event.setCancelled(true);
        activateVenomBloom(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeftClickAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (isPoisonStaffInHand(player)) activateVenomBloom(player);
    }

    private void activateNormalFangs(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("poison-cooldown-ms", 1000.0);
        Long until = normalCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Poison Staff", until - now);
            return;
        }
        normalCooldowns.put(player.getUniqueId(), now + cooldownMs);

        int count = Math.max(3, Math.min(4, def.getEffectInt("poison-fang-count", 3)));
        double spreadDegrees = def.getEffectDouble("poison-spread-degrees", 30.0);
        Location start = player.getEyeLocation().clone();
        Vector forward = start.getDirection().clone().normalize();
        World world = player.getWorld();
        world.playSound(start, Sound.ENTITY_WITCH_THROW, 0.9f, 1.15f);
        world.spawnParticle(Particle.DUST, start, 8, 0.12, 0.12, 0.12, 0.05, POISON_DUST);
        for (int i = 0; i < count; i++) {
            double offset = count == 1 ? 0.0 : i - (count - 1) / 2.0;
            Vector direction = rotateAroundY(forward, Math.toRadians(offset * spreadDegrees / 2.0));
            spawnFang(player, def, start.clone().add(direction.clone().multiply(0.3)), direction);
        }
        player.sendActionBar(MM.deserialize(
                "<gradient:#66ff22:#ccff66><bold>Poison Staff</bold> <dark_gray>» <green>Giftzähne!"));
    }

    private void activateToxicRain(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("toxic-rain-cooldown-ms", 14000.0);
        Long until = toxicRainCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Toxic Rain", until - now);
            return;
        }
        toxicRainCooldowns.put(player.getUniqueId(), now + cooldownMs);

        Location origin = player.getEyeLocation().clone();
        Vector direction = origin.getDirection().clone().normalize();
        Location center = targetPoint(player, origin, direction,
                def.getEffectDouble("toxic-rain-range", 26.0));
        World world = player.getWorld();
        double radius = def.getEffectDouble("toxic-rain-radius", 6.0);
        int duration = Math.max(1, def.getEffectInt("toxic-rain-duration-ticks", 100));
        int pulseInterval = Math.max(1, def.getEffectInt("toxic-rain-pulse-interval", 10));
        double damage = def.getEffectDouble("toxic-rain-damage", 2.5);
        int poisonTicks = Math.max(1, def.getEffectInt("toxic-rain-poison-ticks", 100));
        // Schaden beginnt erst, wenn die Partikel Zeit hatten zu landen (Fallzeit),
        // und läuft nach Ende der Partikelphase noch 3s weiter. Während der
        // "Schwebe-Phase" bleiben zusätzliche grüne Restnebel-Partikel sichtbar.
        int damageDelayTicks = 60;   // 3s Vorlauf – nur Partikel, noch kein Damage
        int damageTailTicks = 60;    // 3s Damage nach Partikel-Stop
        int particleTailTicks = 60;  // 3s grüne Restnebel-Partikel nach Regen-Stop
        world.playSound(center, Sound.ENTITY_WITCH_CELEBRATE, 1.1f, 0.65f);
        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, center, 80, radius, 1.2, radius, 0.04);

        BukkitTask old = activeRain.remove(player.getUniqueId());
        if (old != null) old.cancel();
        final int[] elapsed = {0};
        final BukkitTask[] taskHolder = new BukkitTask[1];
        final int totalTicks = duration + particleTailTicks; // Gesamtlaufzeit inkl. Schwebe
        BukkitRunnable rain = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || elapsed[0] >= totalTicks) {
                    activeRain.remove(player.getUniqueId());
                    finish(player.getUniqueId(), taskHolder[0]);
                    return;
                }
                // Phase 1 (0 .. duration-1): fallende Regentropfen
                if (elapsed[0] < duration) {
                    drawToxicRain(world, center, radius, elapsed[0]);
                } else {
                    // Phase 2 (Schwebe): grüne Restnebel-Partikel langsam schwebend
                    drawToxicRainResidue(world, center, radius);
                }
                // Schaden erst nach Fallverzögerung, dafür bis 3s nach Partikel-Stop
                boolean damageActive = elapsed[0] >= damageDelayTicks
                        && elapsed[0] < duration + damageTailTicks;
                if (damageActive && elapsed[0] % pulseInterval == 0) {
                    for (int i = 0; i < 16; i++) {
                    double x = center.getX() + (Math.random() * 2.0 - 1.0) * radius;
                    double z = center.getZ() + (Math.random() * 2.0 - 1.0) * radius;
                    Location drop = highestPassable(world, x, center.getY() + 14.0, z);
                    world.spawnParticle(Particle.DUST, drop, 14, 0.15, 0.25, 0.15, 0.45, POISON_DUST);
                }
                    for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
                        if (!(entity instanceof LivingEntity target) || !isAllowedTarget(player, target)) continue;
                        target.damage(damage, player);
                        target.addPotionEffect(new PotionEffect(PotionEffectType.POISON,
                                poisonTicks, def.getEffectInt("poison-amplifier", 0), true, true, true));
                    }
                }
                elapsed[0]++;
            }
        };
        taskHolder[0] = rain.runTaskTimer(plugin, 0L, 1L);
        activeRain.put(player.getUniqueId(), taskHolder[0]);
        track(player.getUniqueId(), taskHolder[0]);
        player.sendActionBar(MM.deserialize(
                "<gradient:#66ff22:#ccff66><bold>Toxic Rain</bold> <dark_gray>» <green>Der Giftregen fällt!"));
    }

    private void activateVenomBloom(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("venom-bloom-cooldown-ms", 11000.0);
        Long until = venomBloomCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Venom Bloom", until - now);
            return;
        }
        venomBloomCooldowns.put(player.getUniqueId(), now + cooldownMs);

        Location origin = player.getEyeLocation().clone();
        Vector direction = origin.getDirection().clone().normalize();
        Location center = targetPoint(player, origin, direction,
                def.getEffectDouble("venom-bloom-range", 22.0));
        World world = player.getWorld();
        double radius = def.getEffectDouble("venom-bloom-radius", 5.0);
        int duration = Math.max(1, def.getEffectInt("venom-bloom-duration-ticks", 90));
        int pulseInterval = Math.max(1, def.getEffectInt("venom-bloom-pulse-interval", 8));
        double damage = def.getEffectDouble("venom-bloom-damage", 3.0);
        int poisonTicks = Math.max(1, def.getEffectInt("venom-bloom-poison-ticks", 140));
        world.playSound(center, Sound.BLOCK_CAVE_VINES_PLACE, 1.4f, 0.55f);
        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, center, 100, radius * 0.65, 0.8,
                radius * 0.65, 0.08);

        BukkitTask old = activeBlooms.remove(player.getUniqueId());
        if (old != null) old.cancel();
        final int[] elapsed = {0};
        final BukkitTask[] taskHolder = new BukkitTask[1];
        BukkitRunnable bloom = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || elapsed[0] >= duration) {
                    activeBlooms.remove(player.getUniqueId());
                    finish(player.getUniqueId(), taskHolder[0]);
                    return;
                }
                drawVenomBloom(world, center, radius, elapsed[0]);
                if (elapsed[0] % pulseInterval == 0) {
                    for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
                        if (!(entity instanceof LivingEntity target) || !isAllowedTarget(player, target)) continue;
                        target.damage(damage, player);
                        target.addPotionEffect(new PotionEffect(PotionEffectType.POISON,
                                poisonTicks, def.getEffectInt("poison-amplifier", 0), true, true, true));
                    }
                }
                elapsed[0]++;
            }
        };
        taskHolder[0] = bloom.runTaskTimer(plugin, 0L, 1L);
        activeBlooms.put(player.getUniqueId(), taskHolder[0]);
        track(player.getUniqueId(), taskHolder[0]);
        player.sendActionBar(MM.deserialize(
                "<gradient:#66ff22:#ccff66><bold>Venom Bloom</bold> <dark_gray>» <green>Die Giftblüte erwacht!"));
    }

    private void spawnFang(Player owner, RelicDefinition def, Location start, Vector direction) {
        World world = owner.getWorld();
        world.spawnParticle(Particle.DUST, start, 8, 0.08, 0.08, 0.08, 0.05, POISON_DUST);
        final double speedPerTick = def.getEffectDouble("poison-fang-speed", 24.0) * 0.05;
        final double range = def.getEffectDouble("poison-fang-range", 31.0);
        final double damage = def.getEffectDouble("poison-fang-damage", 7.0);
        final double hitRadius = def.getEffectDouble("poison-fang-hit-radius", 0.65);
        final int maxHits = Math.max(1, def.getEffectInt("poison-fang-max-targets", 3));
        final int poisonTicks = Math.max(1, def.getEffectInt("poison-duration-ticks", 600));
        final int poisonAmplifier = Math.max(0, def.getEffectInt("poison-amplifier", 0));
        final int maxFrames = Math.max(1, (int) Math.ceil(range / Math.max(0.05, speedPerTick)) + 8);
        final Set<UUID> hitTargets = new HashSet<>();
        final Vector velocity = direction.clone().normalize().multiply(speedPerTick);
        final Location[] position = {start.clone()};
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
                int subSteps = Math.max(1, (int) Math.ceil(velocity.length() / 0.22));
                Vector subStep = velocity.clone().multiply(1.0 / subSteps);
                boolean stop = false;
                for (int i = 0; i < subSteps && !stop; i++) {
                    Location next = position[0].clone().add(subStep);
                    travelled[0] += subStep.length();
                    if (isSolid(next.getBlock())) {
                        stop = true;
                        break;
                    }
                    position[0] = next;
                    world.spawnParticle(Particle.DUST, position[0], 2,
                            0.04, 0.04, 0.04, 0.03, POISON_DUST);
                    hitTargetsAt(owner, position[0], hitRadius, damage, maxHits,
                            poisonTicks, poisonAmplifier, hitTargets);
                    if (hitTargets.size() >= maxHits) stop = true;
                }
                if (stop) {
                    world.spawnParticle(Particle.DUST, position[0], 10,
                            0.15, 0.15, 0.15, 0.08, POISON_DUST);
                    finish(owner.getUniqueId(), taskHolder[0]);
                }
            }
        };
        taskHolder[0] = flight.runTaskTimer(plugin, 1L, 1L);
        track(owner.getUniqueId(), taskHolder[0]);
    }

    private void hitTargetsAt(Player owner, Location location, double radius, double baseDamage,
                              int maxHits, int poisonTicks, int poisonAmplifier,
                              Set<UUID> hitTargets) {
        if (hitTargets.size() >= maxHits) return;
        for (Entity entity : owner.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || hitTargets.contains(entity.getUniqueId())) continue;
            if (!isAllowedTarget(owner, target)) continue;
            int hitIndex = hitTargets.size();
            hitTargets.add(target.getUniqueId());
            target.damage(baseDamage * Math.pow(0.75, hitIndex), owner);
            target.addPotionEffect(new PotionEffect(PotionEffectType.POISON,
                    poisonTicks, poisonAmplifier, true, true, true));
            owner.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                    target.getEyeLocation(), 4, 0.2, 0.2, 0.2, 0.08);
            owner.getWorld().playSound(target.getLocation(), Sound.ENTITY_SPIDER_HURT, 0.45f, 1.35f);
            if (hitTargets.size() >= maxHits) return;
        }
    }

    private boolean isAllowedTarget(Player owner, LivingEntity target) {
        if (target.isDead() || !target.isValid()) return false;
        if (target.getUniqueId().equals(owner.getUniqueId())) return false;
        return !(target instanceof Player player) || !RelicUtils.isSameTeam(owner, player);
    }

    private void drawToxicRain(World world, Location center, double radius, int tick) {
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2.0 * i / 24.0 + tick * 0.08;
            Location edge = center.clone().add(Math.cos(angle) * radius, 0.15,
                    Math.sin(angle) * radius);
            world.spawnParticle(Particle.DUST, edge, 2, 0.05, 0.1, 0.05, 0.03, POISON_DUST);
        }
        for (int i = 0; i < 20; i++) {
            double x = center.getX() + (Math.random() * 2.0 - 1.0) * radius;
            double z = center.getZ() + (Math.random() * 2.0 - 1.0) * radius;
            // Höher spawnen + FALLING_HONEY = sichtbar schneller fallende Regentropfen
            Location drop = center.clone().add(x - center.getX(), 9.0 + Math.random() * 7.0,
                    z - center.getZ());
            world.spawnParticle(Particle.FALLING_HONEY, drop, 2, 0.05, 0.2, 0.05, 0.75);
        }
    }

    private void drawToxicRainResidue(World world, Location center, double radius) {
        // Nachlauf-Phase: grüne Nebelwolken + langsam schwebende Tropfen bleiben sichtbar
        for (int i = 0; i < 18; i++) {
            double angle = Math.PI * 2.0 * i / 18.0 + Math.random() * 0.4;
            double r = radius * (0.4 + Math.random() * 0.7);
            double yOff = 0.4 + Math.random() * 2.2;
            Location point = center.clone().add(Math.cos(angle) * r, yOff, Math.sin(angle) * r);
            world.spawnParticle(Particle.DUST, point, 1, 0.02, 0.02, 0.02, 0.01, POISON_DUST);
            world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, point, 1, 0.0, 0.05, 0.0, 0.005);
        }
    }

    private void drawVenomBloom(World world, Location center, double radius, int tick) {
        for (int i = 0; i < 32; i++) {
            double angle = Math.PI * 2.0 * i / 32.0 + tick * 0.1;
            double ring = radius * (0.45 + 0.55 * ((i % 3) / 2.0));
            Location point = center.clone().add(Math.cos(angle) * ring,
                    0.2 + Math.sin(angle * 3.0 + tick * 0.12) * 0.55,
                    Math.sin(angle) * ring);
            world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, point, 1,
                    0.04, 0.04, 0.04, 0.02);
            world.spawnParticle(Particle.DUST, point, 1, 0.02, 0.02, 0.02, 0.02, POISON_DUST);
        }
        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, center, 8,
                radius * 0.45, 0.5, radius * 0.45, 0.04);
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

    private Location highestPassable(World world, double x, double y, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int blockY = Math.min(world.getMaxHeight() - 1, Math.max(world.getMinHeight(), (int) Math.floor(y)));
        while (blockY > world.getMinHeight() && !world.getBlockAt(blockX, blockY, blockZ).isPassable()) blockY--;
        return new Location(world, x, blockY + 1.0, z);
    }

    private void sendCooldown(Player player, String ability, long remainingMs) {
        player.sendActionBar(MM.deserialize(
                "<gradient:#66ff22:#ccff66><bold>" + ability + "</bold> <dark_gray>» <red>lädt noch <white>"
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
        BukkitTask rain = activeRain.remove(playerId);
        if (rain != null) rain.cancel();
        BukkitTask bloom = activeBlooms.remove(playerId);
        if (bloom != null) bloom.cancel();
        normalCooldowns.remove(playerId);
        toxicRainCooldowns.remove(playerId);
        venomBloomCooldowns.remove(playerId);
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
