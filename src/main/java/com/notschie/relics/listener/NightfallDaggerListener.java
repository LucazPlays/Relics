package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Schattenklinge der Nacht (nightfall_dagger):
 * - Shift + Rechtsklick: Schattenschleier (True Stealth für 4s: hidePlayer, Invisibility, Speed II, Enchant-Partikel, bricht bei Aktion/Hit ab, Cooldown 16s)
 * - Rechtsklick: Schattenbombe (12m Reichweite, 6x6 Rauchwand für 5s, Darkness + Silence für alle Feinde im Rauch, Cooldown 12s)
 * - Passiv / On-Hit: Meuchelmord (Backstab < 60°: +50% Schaden + 4s Wither II; aus Stealth: zusätzlich 1.5s Slowness V)
 */
public class NightfallDaggerListener implements Listener {

    private final Set<UUID> activeHybridHits = new HashSet<>();

    private static final String RELIC_ID = "nightfall_dagger";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Long> stealthCooldowns = new HashMap<>();
    private final Map<UUID, Long> smokeCooldowns = new HashMap<>();

    // Active Stealth Players
    private final Map<UUID, StealthSession> activeStealth = new HashMap<>();

    // Active Smoke Zones (Center, Radius, ExpiryTime)
    private static final List<SmokeZone> activeSmokeZones = new ArrayList<>();

    public NightfallDaggerListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
        startSmokeTicker();
    }

    private boolean isNightfallDagger(ItemStack item) {
        return item != null && item.getType() == Material.NETHERITE_SWORD
                && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    private void startSmokeTicker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                activeSmokeZones.removeIf(zone -> now > zone.expiryTime);

                for (SmokeZone zone : activeSmokeZones) {
                    World world = zone.center.getWorld();
                    if (world == null) continue;

                    // Dichte Rauchwand zeichnen
                    for (int i = 0; i < 12; i++) {
                        double ox = (Math.random() - 0.5) * zone.radius * 2.0;
                        double oy = Math.random() * 2.5;
                        double oz = (Math.random() - 0.5) * zone.radius * 2.0;
                        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, zone.center.getX() + ox, zone.center.getY() + oy, zone.center.getZ() + oz, 1, 0, 0, 0, 0.02);
                        world.spawnParticle(Particle.SQUID_INK, zone.center.getX() + ox, zone.center.getY() + oy, zone.center.getZ() + oz, 1, 0, 0, 0, 0.01);
                    }

                    // Darkness + Blindness für alle Enemies im Rauch
                    for (Entity entity : world.getNearbyEntities(zone.center, zone.radius, 3.0, zone.radius)) {
                        if (entity instanceof LivingEntity target && !entity.getUniqueId().equals(zone.creatorId)) {
                            target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 40, 0, false, false, false));
                            target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false, false));
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 4L, 4L);
    }

    // ==========================================
    // 0. SILENCE CHECK (Gegner im Rauch können keine Relikte rechtsklicken)
    // ==========================================
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnyRelicInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        Location pLoc = player.getLocation();
        long now = System.currentTimeMillis();

        for (SmokeZone zone : activeSmokeZones) {
            if (now <= zone.expiryTime && zone.center.getWorld().equals(pLoc.getWorld())) {
                if (zone.center.distanceSquared(pLoc) <= zone.radius * zone.radius) {
                    // Befindet sich im Rauch
                    if (!player.getUniqueId().equals(zone.creatorId)) {
                        event.setCancelled(true);
                        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                                "<red>🔇 Du bist im Schattennebel verstummt! (Keine Fähigkeiten)</red>"
                        ));
                        player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_DEACTIVATE, 0.8f, 1.8f);
                        return;
                    }
                }
            }
        }
    }

    // ==========================================
    // 1. RECHTSKLICK & SHIFT-RECHTSKLICK
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onDaggerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!isNightfallDagger(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerSchattenschleier(player);
        } else {
            triggerSchattenbombe(player);
        }
    }

    // --- A) SCHATTENSCHLEIER (Shift + Rechtsklick) ---
    private void triggerSchattenschleier(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = stealthCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 16000L;

        if (now - lastUse < cdMs) {
            double remaining = (cdMs - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🗡️ Schattenschleier Cooldown: <yellow>" + String.format(Locale.US, "%.1f", remaining) + "s</yellow></red>"
            ));
            return;
        }

        stealthCooldowns.put(uuid, now);

        // Vorherige Session beenden falls vorhanden
        breakStealth(player, false);

        // Hide Player für alle anderen Online-Spieler (verbirgt Rüstung & Entity)
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                other.hidePlayer(plugin, player);
            }
        }

        // Effekte: Invisibility + Speed II
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 80, 0, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 1, false, false, false));

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 0.6f);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PHANTOM_SWOOP, 1.0f, 1.6f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#220033:#aa00ff><bold>🗡️ SCHATTENSCHLEIER AKTIV!</bold></gradient> <gray>(4s Unsichtbarkeit - bricht bei Aktion ab)</gray>"
        ));

        // Partikel-Task: Enchant-Partikel am Spieler
        BukkitTask task = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks += 2;
                if (!player.isOnline() || ticks >= 80) {
                    cancel();
                    breakStealth(player, true);
                    return;
                }

                // Subtile Enchant-Partikel an der Position
                Location pLoc = player.getLocation().add(0, 0.8, 0);
                pLoc.getWorld().spawnParticle(Particle.ENCHANT, pLoc, 4, 0.25, 0.4, 0.25, 0.15);
            }
        }.runTaskTimer(plugin, 2L, 2L);

        activeStealth.put(uuid, new StealthSession(task, now + 4000L));
    }

    private void breakStealth(Player player, boolean naturallyExpired) {
        UUID uuid = player.getUniqueId();
        StealthSession session = activeStealth.remove(uuid);
        if (session != null) {
            if (session.task != null) session.task.cancel();
        }

        // Spieler wieder für alle sichtbar machen
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                other.showPlayer(plugin, player);
            }
        }

        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.removePotionEffect(PotionEffectType.SPEED);

        if (!naturallyExpired) {
            player.sendActionBar(MiniMessage.miniMessage().deserialize("<gray>🗡️ Schattenschleier beendet.</gray>"));
        } else {
            player.sendActionBar(MiniMessage.miniMessage().deserialize("<gray>🗡️ Schattenschleier abgelaufen.</gray>"));
        }
    }

    // --- B) SCHATTENBOMBE (Rechtsklick) ---
    private void triggerSchattenbombe(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = smokeCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 12000L;

        if (now - lastUse < cdMs) {
            double remaining = (cdMs - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>💣 Schattenbombe Cooldown: <yellow>" + String.format(Locale.US, "%.1f", remaining) + "s</yellow></red>"
            ));
            return;
        }

        smokeCooldowns.put(uuid, now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        world.playSound(eye, Sound.ENTITY_WITCH_THROW, 1.2f, 0.7f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#220033:#aa00ff><bold>💣 SCHATTENBOMBE GEWORFEN!</bold></gradient>"
        ));

        // Ziel-Ort (max 12 Blöcke)
        RayTraceResult rt = world.rayTraceBlocks(eye, dir, 12.0, FluidCollisionMode.NEVER, true);
        Location target;
        if (rt != null && rt.getHitPosition() != null) {
            target = rt.getHitPosition().toLocation(world);
        } else {
            target = eye.clone().add(dir.clone().multiply(12.0));
        }

        // Bomben-Flug Animation
        new BukkitRunnable() {
            int step = 0;
            final Location current = eye.clone();
            final double distance = eye.distance(target);
            final int totalSteps = Math.max(2, (int) (distance / 2.0));

            @Override
            public void run() {
                step++;
                current.add(dir.clone().multiply(distance / totalSteps));

                world.spawnParticle(Particle.SQUID_INK, current, 3, 0.1, 0.1, 0.1, 0);
                world.spawnParticle(Particle.SMOKE, current, 3, 0.1, 0.1, 0.1, 0.02);

                if (step >= totalSteps) {
                    cancel();
                    // Zündung der 6x6 Rauchwand
                    detonateSmokeBomb(player, target);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void detonateSmokeBomb(Player caster, Location target) {
        World world = target.getWorld();
        if (world == null) return;

        world.playSound(target, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.6f);
        world.playSound(target, Sound.BLOCK_FIRE_EXTINGUISH, 1.5f, 0.5f);

        // Explosions-Stoß
        world.spawnParticle(Particle.EXPLOSION, target, 2, 0.2, 0.2, 0.2, 0);
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, target, 40, 1.5, 0.8, 1.5, 0.05);

        // Hybrid-Explosions-Schaden an Feinden im Explosionsradius (3.0m)
        for (Entity e : world.getNearbyEntities(target, 3.0, 3.0, 3.0)) {
            if (e instanceof LivingEntity living && !e.getUniqueId().equals(caster.getUniqueId())) {
                if (living instanceof Player tp && (tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR)) continue;
                if (RelicUtils.isSameTeam(caster, (Player) (living instanceof Player ? living : null))) continue;

                RelicUtils.applyHybridDamage(living, caster, 3.0, 3.0);
                living.getWorld().spawnParticle(Particle.CRIT, living.getLocation().add(0, 1, 0), 10, 0.2, 0.2, 0.2, 0.1);
            }
        }

        // 6x6 Rauchwand (Radius 3.0) für 5 Sekunden (5000ms)
        activeSmokeZones.add(new SmokeZone(caster.getUniqueId(), target, 3.0, System.currentTimeMillis() + 5000L));
    }

    // ==========================================
    // 2. STEALTH-BREAK BEI AKTIONEN
    // ==========================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (activeStealth.containsKey(event.getPlayer().getUniqueId())) {
            breakStealth(event.getPlayer(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (activeStealth.containsKey(event.getPlayer().getUniqueId())) {
            breakStealth(event.getPlayer(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (activeStealth.containsKey(player.getUniqueId())) {
                breakStealth(player, false);
            }
        }
    }

    // ==========================================
    // 3. PASSIV / ON-HIT: MEUCHELMORD (Backstab)
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.MAGIC) return;
        if (activeHybridHits.contains(attacker.getUniqueId())) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isNightfallDagger(weapon)) return;

        boolean wasInStealth = activeStealth.containsKey(attacker.getUniqueId());
        // Stealth bricht sofort beim Angriff ab
        if (wasInStealth) {
            breakStealth(attacker, false);
        }

        // Winkelberechnung für Backstab (< 60°)
        Vector aDir = attacker.getLocation().getDirection().setY(0).normalize();
        Vector tDir = target.getLocation().getDirection().setY(0).normalize();
        double dot = aDir.dot(tDir);

        // dot > 0.5 entspricht einem Winkel < 60° (Angriff von hinten)
        if (dot > 0.5) {
            // MEUCHELMORD (Backstab): Hybrid-Schaden (Normaler Schaden + Massiver True Damage)
            double currentDmg = event.getDamage();
            double normalPart = Math.min(14.0, currentDmg * 1.2);
            double truePart = wasInStealth ? 9.0 : 6.5;

            event.setDamage(normalPart);

            // True Damage Hit direkt nach dem ersten Hit mit i-Frame Reset:
            activeHybridHits.add(attacker.getUniqueId());
            try {
                target.setNoDamageTicks(0);
                RelicUtils.applyMagicDamage(target, attacker, truePart);
                target.setNoDamageTicks(0);
            } finally {
                activeHybridHits.remove(attacker.getUniqueId());
            }

            // 4s DoT (Wither II = 80 Ticks, Amplifier 1)
            target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 80, 1, false, true, true));

            // Falls direkt aus dem Schattenschleier: 1.5s Slowness V (30 Ticks, Amplifier 4)
            if (wasInStealth) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 4, false, true, true));
            }

            // Sounds & Partikel
            World world = target.getWorld();
            Location hitLoc = target.getLocation().add(0, 1.0, 0);
            world.playSound(hitLoc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 0.7f);
            world.playSound(hitLoc, Sound.ENTITY_PHANTOM_BITE, 1.4f, 1.4f);
            world.playSound(hitLoc, Sound.ITEM_TRIDENT_HIT, 1.0f, 0.6f);

            Particle.DustOptions bloodPurple = new Particle.DustOptions(Color.fromRGB(120, 0, 40), 1.5f);
            world.spawnParticle(Particle.DUST, hitLoc, 25, 0.3, 0.4, 0.3, 0, bloodPurple);
            world.spawnParticle(Particle.CRIT, hitLoc, 20, 0.4, 0.4, 0.4, 0.3);
            world.spawnParticle(Particle.SQUID_INK, hitLoc, 15, 0.3, 0.3, 0.3, 0.05);

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#7700aa:#ff0055><bold>🗡️ HYBRID-MEUCHELMORD!</bold></gradient> <yellow>"
                            + String.format(Locale.US, "%.1f", normalPart) + " Phys</yellow> + <red><bold>+"
                            + String.format(Locale.US, "%.1f", truePart) + " True Dmg!</bold></red>"
                            + (wasInStealth ? " <yellow>[SLOWNESS V]</yellow>" : "")
            ));
        } else {
            // Normaler Treffer: Hybrid-Schaden (Sword Normal + 3.5 True Damage)
            double normalPart = event.getDamage();
            double truePart = 3.5;

            event.setDamage(normalPart);

            activeHybridHits.add(attacker.getUniqueId());
            try {
                target.setNoDamageTicks(0);
                RelicUtils.applyMagicDamage(target, attacker, truePart);
                target.setNoDamageTicks(0);
            } finally {
                activeHybridHits.remove(attacker.getUniqueId());
            }

            // Normaler Treffer-Sound & Partikel
            World world = target.getWorld();
            Location hitLoc = target.getLocation().add(0, 1.0, 0);
            world.playSound(hitLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.2f);
            world.spawnParticle(Particle.SWEEP_ATTACK, hitLoc, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.SQUID_INK, hitLoc, 6, 0.2, 0.2, 0.2, 0.02);
            world.spawnParticle(Particle.DUST, hitLoc, 8, 0.2, 0.2, 0.2, 0, new Particle.DustOptions(Color.fromRGB(120, 0, 160), 1.2f));

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#aa00ff:#7700aa><bold>🗡️ SCHATTEN-SCHNITT</bold></gradient> <gray>+<red>"
                            + String.format(Locale.US, "%.1f", truePart) + " True Dmg</red></gray>"
            ));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        breakStealth(event.getPlayer(), false);
        stealthCooldowns.remove(event.getPlayer().getUniqueId());
        smokeCooldowns.remove(event.getPlayer().getUniqueId());
    }

    private static class StealthSession {
        final BukkitTask task;
        final long expiryTime;

        StealthSession(BukkitTask task, long expiryTime) {
            this.task = task;
            this.expiryTime = expiryTime;
        }
    }

    private static class SmokeZone {
        final UUID creatorId;
        final Location center;
        final double radius;
        final long expiryTime;

        SmokeZone(UUID creatorId, Location center, double radius, long expiryTime) {
            this.creatorId = creatorId;
            this.center = center;
            this.radius = radius;
            this.expiryTime = expiryTime;
        }
    }
}
