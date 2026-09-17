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
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Magical Harp abilities:
 * <ul>
 *     <li>Rechtsklick: einzelne, abprallende Note</li>
 *     <li>Linksklick: Note Storm</li>
 *     <li>Sneak + Rechtsklick: Grand Finale</li>
 * </ul>
 * Alle Noten bleiben echte Minecraft-NOTE-Partikel.
 */
public class MagicalHarpListener implements Listener {

    private static final String RELIC_ID = "magical_harp";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** Note-Block-Noten 0–24; die Werte werden als NOTE-Partikel-Farbe kodiert. */
    private static final int[] HARP_NOTE_SEQUENCE = {4, 7, 11, 14, 17, 21, 24};

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Map<UUID, Long> normalCooldowns = new HashMap<>();
    private final Map<UUID, Long> noteStormCooldowns = new HashMap<>();
    private final Map<UUID, Long> grandFinaleCooldowns = new HashMap<>();
    private final Map<UUID, Set<BukkitTask>> activeTasks = new HashMap<>();

    public MagicalHarpListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isMagicalHarp(ItemStack stack) {
        return stack != null && factory.isRelic(stack)
                && RELIC_ID.equals(factory.getRelicId(stack));
    }

    private boolean isMagicalHarpInHand(Player player) {
        return isMagicalHarp(player.getInventory().getItemInMainHand())
                || isMagicalHarp(player.getInventory().getItemInOffHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isMagicalHarp(event.getItem()) && !isMagicalHarpInHand(player)) return;

        event.setCancelled(true);
        if (player.isSneaking()) activateGrandFinale(player);
        else activateNormalNote(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isMagicalHarp(event.getItem()) && !isMagicalHarpInHand(player)) return;

        event.setCancelled(true);
        activateNoteStorm(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeftClickAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (isMagicalHarpInHand(player)) activateNoteStorm(player);
    }

    private void activateNormalNote(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("harp-cooldown-ms", 160.0);
        Long until = normalCooldowns.get(player.getUniqueId());
        if (until != null && now < until) return;
        normalCooldowns.put(player.getUniqueId(), now + cooldownMs);
        fireNote(player, def);
    }

    private void activateNoteStorm(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("note-storm-cooldown-ms", 10000.0);
        Long until = noteStormCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Note Storm", until - now);
            return;
        }
        noteStormCooldowns.put(player.getUniqueId(), now + cooldownMs);

        int count = Math.max(1, def.getEffectInt("note-storm-count", 14));
        double spread = def.getEffectDouble("note-storm-spread-degrees", 34.0);
        World world = player.getWorld();
        Location start = player.getEyeLocation().clone();
        Vector forward = start.getDirection().clone().normalize();
        world.playSound(start, Sound.BLOCK_NOTE_BLOCK_HARP, 1.4f, 0.65f);
        world.spawnParticle(Particle.NOTE, start, 0, 0.2, 0.0, 0.0);
        for (int i = 0; i < count; i++) {
            double offset = count == 1 ? 0.0 : i - (count - 1) / 2.0;
            Vector direction = rotateAroundY(forward, Math.toRadians(offset * spread / 2.0));
            spawnNote(player, def, start.clone().add(direction.clone().multiply(0.35)), direction,
                    i % HARP_NOTE_SEQUENCE.length, true);
        }
        player.sendActionBar(MM.deserialize(
                "<gradient:#be41ff:#f0c2ff><bold>Note Storm</bold> <dark_gray>» <light_purple>Die Melodie entfesselt sich!"));
    }

    private void activateGrandFinale(Player player) {
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        long now = System.currentTimeMillis();
        long cooldownMs = (long) def.getEffectDouble("grand-finale-cooldown-ms", 18000.0);
        Long until = grandFinaleCooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            sendCooldown(player, "Grand Finale", until - now);
            return;
        }
        grandFinaleCooldowns.put(player.getUniqueId(), now + cooldownMs);

        World world = player.getWorld();
        Location center = player.getLocation().clone().add(0, 1.0, 0);
        int duration = Math.max(1, def.getEffectInt("grand-finale-duration-ticks", 120));
        int pulseInterval = Math.max(1, def.getEffectInt("grand-finale-pulse-interval", 3));
        int afterglowTicks = Math.max(1, def.getEffectInt("grand-finale-afterglow-ticks", 20));
        double radius = def.getEffectDouble("grand-finale-radius", 8.0);
        double damage = def.getEffectDouble("grand-finale-damage", 5.0);
        double pullStrength = def.getEffectDouble("grand-finale-pull-strength", 1.05);
        double pullLift = def.getEffectDouble("grand-finale-pull-lift", 0.28);
        world.playSound(center, Sound.BLOCK_NOTE_BLOCK_HARP, 2.0f, 0.55f);
        world.playSound(center, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.5f, 0.8f);
        world.playSound(center, Sound.BLOCK_NOTE_BLOCK_BELL, 1.2f, 0.45f);

        final int[] elapsed = {0};
        final Set<UUID> hitThisPulse = new HashSet<>();
        final BukkitTask[] taskHolder = new BukkitTask[1];
        final int totalTicks = duration + afterglowTicks;
        BukkitRunnable finale = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || elapsed[0] >= totalTicks) {
                    finish(player.getUniqueId(), taskHolder[0]);
                    return;
                }

                int pulseCount = elapsed[0] / pulseInterval;
                if (elapsed[0] < duration) {
                    drawFinale(world, center, radius, elapsed[0]);
                } else {
                    // Afterglow: leise klingende Harfennoten statt vollem Effekt
                    drawFinaleAfterglow(world, center, radius, elapsed[0] - duration);
                }
                if (elapsed[0] % pulseInterval == 0) {
                    hitThisPulse.clear();
                    // Melodie: jede 2. Pulse steigt einen Ton in der Tonleiter hoch
                    int noteIdx = pulseCount % HARP_NOTE_SEQUENCE.length;
                    int nextIdx = (pulseCount + 1) % HARP_NOTE_SEQUENCE.length;
                    for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
                        if (!(entity instanceof LivingEntity target) || !isAllowedTarget(player, target)) continue;
                        if (!hitThisPulse.add(target.getUniqueId())) continue;
                        target.damage(damage, player);
                        // Grand Finale zieht Ziele zum Caster (umgekehrte Richtung)
                        Vector pull = center.toVector().subtract(target.getLocation().toVector());
                        double distSq = pull.lengthSquared();
                        if (distSq > 1.0e-6) {
                            // Stärkerer Pull wenn weiter weg, gedeckelt durch pullStrength
                            double dist = Math.sqrt(distSq);
                            double strength = Math.min(pullStrength, pullStrength * (dist / 6.0));
                            Vector pullDir = pull.multiply(1.0 / dist);
                            target.setVelocity(pullDir.multiply(strength).setY(pullLift));
                        } else {
                            target.setVelocity(new Vector(0, pullLift, 0));
                        }
                        // Note-Partikel direkt auf jedem getroffenen Ziel
                        spawnNoteParticle(world, target.getEyeLocation(), HARP_NOTE_SEQUENCE[noteIdx]);
                    }
                    // Sichtbarer Noten-Emitter in der Mitte – wechselt pro Pulse
                    spawnNoteParticle(world, center.clone().add(0, 1.2, 0), HARP_NOTE_SEQUENCE[noteIdx]);
                    world.spawnParticle(Particle.NOTE, center.clone().add(0, 0.4, 0),
                            1, radius * 0.4, 0.2, radius * 0.4, 0.0);
                    // Schnelle, monoton ansteigende Melodie (0.05 pro Pulse)
                    float pitch = 0.55f + pulseCount * 0.05f;
                    if (pitch > 1.9f) pitch = 1.9f;
                    world.playSound(center, Sound.BLOCK_NOTE_BLOCK_HARP, 1.1f, pitch);
                    world.playSound(center, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f,
                            0.7f + (pulseCount % 5) * 0.06f);
                    // Höhepunkt: alle 8 Pulse ein Glockenton + kräftigere Note
                    if (pulseCount % 8 == 7) {
                        world.playSound(center, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0.95f);
                        spawnNoteParticle(world, center.clone().add(0, 1.4, 0),
                                HARP_NOTE_SEQUENCE[nextIdx]);
                    }
                    if (elapsed[0] < duration) {
                        world.spawnParticle(Particle.SONIC_BOOM, center, 1, 0.0, 0.0, 0.0, 0.0);
                    }
                }
                elapsed[0]++;
            }
        };
        taskHolder[0] = finale.runTaskTimer(plugin, 0L, 1L);
        track(player.getUniqueId(), taskHolder[0]);
        player.sendActionBar(MM.deserialize(
                "<gradient:#be41ff:#f0c2ff><bold>Grand Finale</bold> <dark_gray>» <light_purple>Der Schlussakkord erschallt!"));
    }

    private void fireNote(Player owner, RelicDefinition def) {
        Location start = owner.getEyeLocation().clone();
        Vector direction = start.getDirection().clone();
        if (direction.lengthSquared() < 1.0e-8) return;
        direction.normalize();

        World world = owner.getWorld();
        world.playSound(start, Sound.BLOCK_NOTE_BLOCK_HARP, 0.85f,
                0.75f + (float) (Math.random() * 0.65));
        spawnNoteParticles(world, start, 4);
        spawnNote(owner, def, start.clone().add(direction.clone().multiply(0.35)), direction, 0, false);
    }

    private void spawnNote(Player owner, RelicDefinition def, Location start, Vector direction,
                           int noteOffset, boolean storm) {
        World world = owner.getWorld();
        spawnNoteParticles(world, start, noteOffset);
        final UUID ownerId = owner.getUniqueId();
        final double speed = def.getEffectDouble(storm ? "note-storm-speed" : "harp-note-speed",
                storm ? 19.0 : 16.0);
        final double range = def.getEffectDouble(storm ? "note-storm-range" : "harp-note-range",
                storm ? 30.0 : 48.0);
        final double damage = def.getEffectDouble(storm ? "note-storm-damage" : "harp-note-damage",
                storm ? 4.0 : 6.0);
        final double hitRadius = def.getEffectDouble("harp-note-hit-radius", 0.8);
        final int maxHits = Math.max(1, def.getEffectInt(storm ? "note-storm-max-hits" : "harp-note-max-hits",
                storm ? 2 : 5));
        final int maxBounces = Math.max(0, def.getEffectInt("harp-note-max-bounces", 24));
        final int lifetimeTicks = Math.max(1, def.getEffectInt(storm ? "note-storm-lifetime-ticks" : "harp-note-lifetime-ticks",
                storm ? 40 : 60));
        final double distancePerTick = speed * 0.05;
        final Set<UUID> hitTargets = new HashSet<>();
        final Vector velocity = direction.clone().normalize().multiply(distancePerTick);
        final Location[] position = {start.clone()};
        final double[] travelled = {0.0};
        final int[] frame = {0};
        final int[] bounceCount = {0};
        final BukkitTask[] taskHolder = new BukkitTask[1];

        BukkitRunnable flight = new BukkitRunnable() {
            @Override
            public void run() {
                if (!owner.isOnline() || owner.isDead() || !owner.isValid()
                        || frame[0]++ >= lifetimeTicks || travelled[0] >= range) {
                    finish(ownerId, taskHolder[0]);
                    return;
                }

                int subSteps = Math.max(1, (int) Math.ceil(velocity.length() / 0.22));
                Vector subStep = velocity.clone().multiply(1.0 / subSteps);
                boolean stop = false;
                for (int i = 0; i < subSteps && !stop; i++) {
                    Location next = position[0].clone().add(subStep);
                    travelled[0] += subStep.length();
                    Block block = next.getBlock();
                    if (isSolid(block)) {
                        if (bounceCount[0]++ >= maxBounces) {
                            stop = true;
                            break;
                        }
                        Vector normal = faceNormal(position[0], next);
                        Vector reflected = reflect(velocity, normal);
                        if (reflected.lengthSquared() < 1.0e-8) {
                            stop = true;
                            break;
                        }
                        velocity.copy(reflected.normalize().multiply(distancePerTick));
                        position[0] = position[0].clone().add(normal.multiply(0.12));
                        spawnNoteParticles(world, position[0], 12 + noteOffset);
                        continue;
                    }

                    position[0] = next;
                    int noteIndex = Math.floorMod((int) Math.floor(travelled[0]) + noteOffset,
                            HARP_NOTE_SEQUENCE.length);
                    spawnNoteParticle(world, position[0], HARP_NOTE_SEQUENCE[noteIndex]);
                    hitTargetsAt(owner, position[0], hitRadius, damage, maxHits, hitTargets, storm, velocity);
                    if (hitTargets.size() >= maxHits) stop = true;
                }

                if (stop) finish(ownerId, taskHolder[0]);
            }
        };

        taskHolder[0] = flight.runTaskTimer(plugin, 1L, 1L);
        track(ownerId, taskHolder[0]);
    }

    private void hitTargetsAt(Player owner, Location location, double radius, double baseDamage,
                              int maxHits, Set<UUID> hitTargets) {
        hitTargetsAt(owner, location, radius, baseDamage, maxHits, hitTargets, false, null);
    }

    private void hitTargetsAt(Player owner, Location location, double radius, double baseDamage,
                              int maxHits, Set<UUID> hitTargets, boolean storm, Vector noteVelocity) {
        if (hitTargets.size() >= maxHits) return;
        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        for (Entity entity : owner.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target)) continue;
            if (hitTargets.contains(target.getUniqueId())) continue;
            if (!isAllowedTarget(owner, target)) continue;

            int hitIndex = hitTargets.size();
            double dealt = baseDamage * Math.pow(0.95, hitIndex);
            hitTargets.add(target.getUniqueId());
            target.damage(dealt, owner);
            spawnNoteParticles(owner.getWorld(), target.getEyeLocation(), hitIndex + 18);
            owner.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                    target.getEyeLocation(), 5, 0.2, 0.2, 0.2, 0.06);
            owner.getWorld().playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP,
                    0.4f, 1.1f + hitIndex * 0.08f);
            // Note Storm (Linksklick) = sehr starker Knockback in Flugrichtung;
            // einzelne Note (Rechtsklick) = sanfter Tritt in Flugrichtung.
            double knock = storm
                    ? def.getEffectDouble("note-storm-knockback", 1.45)
                    : def.getEffectDouble("harp-note-knockback", 0.45);
            Vector push = noteVelocity != null && noteVelocity.lengthSquared() > 1.0e-8
                    ? noteVelocity.clone().normalize().multiply(knock)
                    : target.getLocation().toVector().subtract(owner.getLocation().toVector())
                            .normalize().multiply(knock);
            if (push.lengthSquared() > 1.0e-6) {
                if (storm) push.setY(0.30);
                target.setVelocity(push);
            }
            if (hitTargets.size() >= maxHits) return;
        }
    }

    private boolean isAllowedTarget(Player owner, LivingEntity target) {
        if (target.isDead() || !target.isValid()) return false;
        if (target.getUniqueId().equals(owner.getUniqueId())) return false;
        return !(target instanceof Player player) || !RelicUtils.isSameTeam(owner, player);
    }

    private void drawFinale(World world, Location center, double radius, int tick) {
        for (int i = 0; i < 48; i++) {
            double angle = Math.PI * 2.0 * i / 48.0 + tick * 0.12;
            double ringRadius = radius * (0.45 + 0.55 * ((i % 4) / 3.0));
            Location point = center.clone().add(Math.cos(angle) * ringRadius,
                    0.4 + Math.sin(angle * 2.0 + tick * 0.08) * 0.6,
                    Math.sin(angle) * ringRadius);
            spawnNoteParticle(world, point, (i + tick / 2) % 25);
        }
        spawnNoteParticles(world, center.clone().add(0, 0.8, 0), tick / 2);
        world.spawnParticle(Particle.SONIC_BOOM, center, 1, 0.0, 0.0, 0.0, 0.0);
    }

    private void drawFinaleAfterglow(World world, Location center, double radius, int tick) {
        // Leiser Nachhall: nur noch ein paar Notenpartikel spiralförmig nach oben
        for (int i = 0; i < 10; i++) {
            double angle = Math.PI * 2.0 * i / 10.0 + tick * 0.25;
            double r = radius * (0.2 + tick * 0.04);
            Location point = center.clone().add(Math.cos(angle) * r, 0.4 + tick * 0.06,
                    Math.sin(angle) * r);
            spawnNoteParticle(world, point, (i + tick) % 25);
        }
    }

    private void sendCooldown(Player player, String ability, long remainingMs) {
        player.sendActionBar(MM.deserialize(
                "<gradient:#be41ff:#f0c2ff><bold>" + ability + "</bold> <dark_gray>» <red>lädt noch <white>"
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
        if (tasks != null) {
            for (BukkitTask task : new HashSet<>(tasks)) task.cancel();
        }
        normalCooldowns.remove(playerId);
        noteStormCooldowns.remove(playerId);
        grandFinaleCooldowns.remove(playerId);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cleanupPlayer(event.getPlayer().getUniqueId());
    }

    private static void spawnNoteParticle(World world, Location location, int noteId) {
        int clampedNote = Math.floorMod(noteId, 25);
        world.spawnParticle(Particle.NOTE, location, 0,
                clampedNote / 24.0, 0.0, 0.0);
    }

    private static void spawnNoteParticles(World world, Location location, int sequenceOffset) {
        int index = Math.floorMod(sequenceOffset, HARP_NOTE_SEQUENCE.length);
        spawnNoteParticle(world, location, HARP_NOTE_SEQUENCE[index]);
        spawnNoteParticle(world, location.clone().add(0.0, 0.08, 0.0),
                HARP_NOTE_SEQUENCE[(index + 2) % HARP_NOTE_SEQUENCE.length]);
        spawnNoteParticle(world, location.clone().add(0.0, 0.16, 0.0),
                HARP_NOTE_SEQUENCE[(index + 4) % HARP_NOTE_SEQUENCE.length]);
    }

    private static boolean isSolid(Block block) {
        Material material = block.getType();
        if (material.isAir() || material == Material.WATER || material == Material.LAVA) return false;
        return !block.isPassable();
    }

    private static Vector faceNormal(Location oldPosition, Location newPosition) {
        Vector travel = newPosition.toVector().subtract(oldPosition.toVector()).normalize();
        double x = Math.abs(travel.getX());
        double y = Math.abs(travel.getY());
        double z = Math.abs(travel.getZ());
        if (x >= y && x >= z) return new Vector(-Math.signum(travel.getX()), 0, 0);
        if (y >= x && y >= z) return new Vector(0, -Math.signum(travel.getY()), 0);
        return new Vector(0, 0, -Math.signum(travel.getZ()));
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
