package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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

import java.util.*;

/**
 * Schattenkatalysator (shadow_catalyst) - Zed / LeBlanc inspiriert:
 * - Rechtsklick: Lebender Schatten (schießt Phantom bis zu 14m, 2. Klick tauscht Position, Cooldown 14s)
 * - Linksklick: Schatten-Doppelwurf (Spieler & Schatten werfen Shuriken; treffen beide dasselbe Ziel: +150% Bonusschaden, Cooldown 5s)
 * - Shift + Rechtsklick: Todesurteil (0.5s Unantastbarkeit, Teleport hinter Ziel, 2. Schatten hinterlassen, Mal detoniert nach 4s für 35% gespeicherten Schaden, Cooldown 30s)
 */
public class ShadowCatalystListener implements Listener {

    private static final String RELIC_ID = "shadow_catalyst";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Long> shadowCooldowns = new HashMap<>();
    private final Map<UUID, Long> shurikenCooldowns = new HashMap<>();
    private final Map<UUID, Long> deathMarkCooldowns = new HashMap<>();

    // Active Living Shadows per Player
    private final Map<UUID, ActiveShadow> activeShadows = new HashMap<>();

    // Active Death Marks (Target UUID -> DeathMarkData)
    private final Map<UUID, DeathMarkData> activeMarks = new HashMap<>();

    // Players currently invulnerable (0.5s during Death Mark cast)
    private final Set<UUID> invulnerablePlayers = new HashSet<>();

    public ShadowCatalystListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isShadowCatalyst(ItemStack item) {
        return item != null && item.getType() == Material.ECHO_SHARD
                && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    private boolean isShadowCatalystInHand(Player player) {
        return isShadowCatalyst(player.getInventory().getItemInMainHand())
                || isShadowCatalyst(player.getInventory().getItemInOffHand());
    }

    // ==========================================
    // 1. RECHTSKLICK & SHIFT + RECHTSKLICK
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!isShadowCatalyst(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerDeathMark(player);
        } else {
            triggerLivingShadow(player);
        }
    }

    // --- A) LEBENDER SCHATTEN (Rechtsklick) ---
    private void triggerLivingShadow(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // 2. Klick: Position mit Schatten tauschen falls vorhanden
        ActiveShadow shadow = activeShadows.get(uuid);
        if (shadow != null) {
            executeShadowSwap(player, shadow);
            return;
        }

        long lastUse = shadowCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 14000L;
        if (now - lastUse < cdMs) {
            double remaining = (cdMs - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>👥 Lebender Schatten Cooldown: <yellow>" + String.format(Locale.US, "%.1f", remaining) + "s</yellow></red>"
            ));
            return;
        }

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        // Schatten-Zielpunkt ermitteln (max 14 Blöcke)
        RayTraceResult rt = world.rayTraceBlocks(eye, dir, 14.0, FluidCollisionMode.NEVER, true);
        Location targetLoc;
        if (rt != null && rt.getHitPosition() != null) {
            targetLoc = rt.getHitPosition().toLocation(world);
        } else {
            targetLoc = eye.clone().add(dir.clone().multiply(14.0));
        }

        world.playSound(eye, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.4f, 1.2f);
        world.playSound(eye, Sound.ENTITY_PHANTOM_SWOOP, 1.0f, 1.8f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#aa00ff:#000000><bold>👥 LEBENDER SCHATTEN PLATZIERT!</bold></gradient> <gray>Klicke erneut zum Tauschen (5s)...</gray>"
        ));

        // Partikel-Task für den Schatten (Reine Partikel-Silhouette, kein NPC)
        ActiveShadow newShadow = new ActiveShadow(targetLoc, now + 5000L);
        activeShadows.put(uuid, newShadow);

        newShadow.task = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks += 2;
                if (!player.isOnline() || !activeShadows.containsKey(uuid) || ticks >= 50) {
                    cancel();
                    activeShadows.remove(uuid);
                    return;
                }

                // Schatten-Silhouette zeichnen (Dunkler Rauch + Portal + Augen)
                Particle.DustOptions darkDust = new Particle.DustOptions(Color.fromRGB(20, 0, 40), 1.3f);
                world.spawnParticle(Particle.DUST, targetLoc.getX(), targetLoc.getY() + 0.8, targetLoc.getZ(), 8, 0.25, 0.6, 0.25, 0, darkDust);
                world.spawnParticle(Particle.SQUID_INK, targetLoc.getX(), targetLoc.getY() + 0.9, targetLoc.getZ(), 4, 0.2, 0.4, 0.2, 0.01);
                world.spawnParticle(Particle.PORTAL, targetLoc.getX(), targetLoc.getY() + 0.5, targetLoc.getZ(), 6, 0.2, 0.3, 0.2, 0.1);

                if (ticks % 10 == 0) {
                    world.playSound(targetLoc, Sound.BLOCK_RESPAWN_ANCHOR_AMBIENT, 0.6f, 1.8f);
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void executeShadowSwap(Player player, ActiveShadow shadow) {
        UUID uuid = player.getUniqueId();
        activeShadows.remove(uuid);
        if (shadow.task != null) shadow.task.cancel();

        shadowCooldowns.put(uuid, System.currentTimeMillis());

        Location oldLoc = player.getLocation();
        Location newLoc = shadow.location;

        // Tausch
        player.getWorld().spawnParticle(Particle.PORTAL, oldLoc.add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.3);
        player.getWorld().playSound(oldLoc, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.4f, 1.4f);

        player.teleport(newLoc);

        player.getWorld().spawnParticle(Particle.FLASH, newLoc.clone().add(0, 1, 0), 2, 0.2, 0.2, 0.2, 0, Color.WHITE);
        player.getWorld().playSound(newLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 1.5f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#aa00ff:#ffffff><bold>👥 SCHATTEN-TAUSCH!</bold></gradient>"
        ));
    }

    // ==========================================
    // 2. LINKSKLICK: SCHATTEN-DOPPELWURF (Razor Shuriken)
    // ==========================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeftClickShuriken(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (player.isSneaking()) return; // Sneak-LeftClick für andere Fähigkeiten reserviert
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isShadowCatalystInHand(player)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = shurikenCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 5000L;

        if (now - lastUse < cdMs) return;
        shurikenCooldowns.put(uuid, now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        world.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 1.8f);
        world.playSound(eye, Sound.ITEM_TRIDENT_THROW, 1.2f, 1.9f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#aa00ff:#ff00ff><bold>🗡️ RAZOR-SHURIKEN!</bold></gradient>"
        ));

        // Ziel-Punkt ermitteln
        RayTraceResult aimTrace = world.rayTraceBlocks(eye, dir, 20.0, FluidCollisionMode.NEVER, true);
        Location targetPoint = (aimTrace != null && aimTrace.getHitPosition() != null)
                ? aimTrace.getHitPosition().toLocation(world)
                : eye.clone().add(dir.clone().multiply(20.0));

        // Spieler-Shuriken feuern
        Set<LivingEntity> playerHits = fireShuriken(player, eye, dir, 20.0);

        // Schatten-Shuriken feuern (falls ein aktiver Schatten existiert!)
        ActiveShadow shadow = activeShadows.get(uuid);
        if (shadow != null) {
            Location shadowOrigin = shadow.location.clone().add(0, 1.2, 0);
            Vector shadowDir = targetPoint.toVector().subtract(shadowOrigin.toVector()).normalize();
            world.playSound(shadowOrigin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 2.0f);

            Set<LivingEntity> shadowHits = fireShuriken(player, shadowOrigin, shadowDir, 20.0);

            // SYNERGIE-CHECK: Treffen beide Shurikens dasselbe Ziel? -> 150% Bonusschaden!
            for (LivingEntity victim : playerHits) {
                if (shadowHits.contains(victim)) {
                    RelicUtils.applyHybridDamage(victim, player, 5.0, 5.0); // Synergie-Schaden: 5.0 Normal + 5.0 True Dmg
                    victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.6f, 1.5f);
                    victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 30, 0.4, 0.5, 0.4, 0.3);
                    victim.getWorld().spawnParticle(Particle.PORTAL, victim.getLocation().add(0, 1, 0), 25, 0.4, 0.4, 0.4, 0.2);

                    player.sendActionBar(MiniMessage.miniMessage().deserialize(
                            "<gradient:#ff00ff:#ffffff><bold>👥 SYNERGIE-TREFFER!</bold></gradient> <yellow>+150% Doppel-Shuriken-Schaden!</yellow>"
                    ));
                }
            }
        }
    }

    private Set<LivingEntity> fireShuriken(Player caster, Location origin, Vector dir, double maxDist) {
        Set<LivingEntity> hit = new HashSet<>();
        World world = origin.getWorld();
        if (world == null) return hit;

        Particle.DustOptions shurikenDust = new Particle.DustOptions(Color.fromRGB(180, 0, 255), 1.2f);
        Location cur = origin.clone();

        for (double d = 0.5; d <= maxDist; d += 0.8) {
            cur.add(dir.clone().multiply(0.8));
            if (cur.getBlock().getType().isSolid()) break;
            world.spawnParticle(Particle.DUST, cur, 3, 0.1, 0.1, 0.1, 0, shurikenDust);
            world.spawnParticle(Particle.SWEEP_ATTACK, cur, 1, 0, 0, 0, 0);

            for (Entity e : world.getNearbyEntities(cur, 1.0, 1.0, 1.0)) {
                if (e instanceof LivingEntity target && !e.getUniqueId().equals(caster.getUniqueId())) {
                    if (target instanceof Player tp && (tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR)) continue;
                    if (RelicUtils.isSameTeam(caster, (Player) (target instanceof Player ? target : null))) continue;

                    if (!hit.contains(target)) {
                        hit.add(target);
                        RelicUtils.applyHybridDamage(target, caster, 4.0, 3.5);
                    }
                }
            }
        }
        return hit;
    }

    // ==========================================
    // 3. SHIFT + RECHTSKLICK: TODESURTEIL (Death Mark)
    // ==========================================
    private void triggerDeathMark(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = deathMarkCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 30000L;

        if (now - lastUse < cdMs) {
            double remaining = (cdMs - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>💀 Todesurteil Cooldown: <yellow>" + String.format(Locale.US, "%.1f", remaining) + "s</yellow></red>"
            ));
            return;
        }

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        // Ziel-Entity anvisieren (bis zu 15 Blöcke)
        RayTraceResult rt = world.rayTraceEntities(eye, dir, 15.0, 1.2, e -> {
            if (e.getUniqueId().equals(uuid)) return false;
            if (!(e instanceof LivingEntity living)) return false;
            if (e instanceof Player tp && (tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR)) return false;
            return !RelicUtils.isSameTeam(player, (Player) (e instanceof Player ? e : null));
        });

        if (rt == null || !(rt.getHitEntity() instanceof LivingEntity target)) {
            player.sendActionBar(MiniMessage.miniMessage().deserialize("<gray>Kein Ziel für das Todesurteil anvisiert (max 15m).</gray>"));
            return;
        }

        deathMarkCooldowns.put(uuid, now);

        Location castOrigin = player.getLocation().clone();

        // 0.5s Unantastbarkeit
        invulnerablePlayers.add(uuid);
        new BukkitRunnable() {
            @Override
            public void run() {
                invulnerablePlayers.remove(uuid);
            }
        }.runTaskLater(plugin, 10L);

        // Teleport direkt hinter das Ziel
        Vector targetLook = target.getLocation().getDirection().setY(0).normalize();
        Location behindLoc = target.getLocation().clone().subtract(targetLook.multiply(1.5));
        behindLoc.setDirection(targetLook);

        player.teleport(behindLoc);

        // Automatisch einen 2. Schatten an der ursprünglichen Position erzeugen
        ActiveShadow shadow = new ActiveShadow(castOrigin, now + 5000L);
        activeShadows.put(uuid, shadow);

        world.playSound(behindLoc, Sound.ENTITY_WARDEN_HEARTBEAT, 1.6f, 0.8f);
        world.playSound(behindLoc, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.4f, 1.5f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#ff0000:#aa00ff><bold>💀 TODESURTEIL GEWIRKT!</bold></gradient> <gray>(4s Zeitfenster für Burst!)</gray>"
        ));

        // Todesmal auf Target setzen
        DeathMarkData mark = new DeathMarkData(player.getUniqueId(), now + 4000L);
        activeMarks.put(target.getUniqueId(), mark);

        // Detonations-Task nach 4.0s (80 Ticks)
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks += 5;
                if (!target.isValid() || target.isDead() || !activeMarks.containsKey(target.getUniqueId())) {
                    cancel();
                    activeMarks.remove(target.getUniqueId());
                    return;
                }

                // Herzschlag-Sound alle 10 Ticks
                if (ticks % 10 == 0) {
                    target.getWorld().playSound(target.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.2f, 1.2f + (ticks * 0.01f));
                    target.getWorld().spawnParticle(Particle.DUST, target.getLocation().add(0, 1.2, 0), 10, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(Color.fromRGB(200, 0, 50), 1.4f));
                }

                if (ticks >= 80) { // 4.0s abgelaufen -> DETONATION
                    cancel();
                    activeMarks.remove(target.getUniqueId());
                    detonateDeathMark(player, target, mark);
                }
            }
        }.runTaskTimer(plugin, 5L, 5L);
    }

    private void detonateDeathMark(Player caster, LivingEntity target, DeathMarkData mark) {
        World world = target.getWorld();
        Location tLoc = target.getLocation().add(0, 1.0, 0);

        world.playSound(tLoc, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 1.8f);
        world.playSound(tLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.2f);
        world.spawnParticle(Particle.FLASH, tLoc, 3, 0.3, 0.3, 0.3, 0, Color.WHITE);
        world.spawnParticle(Particle.SONIC_BOOM, tLoc, 1, 0, 0, 0, 0);

        // 40% allen zugefügten Schadens (mindestens 4.0 HP Base) - gebufft von 35% / 3.5 HP
        double popDamage = Math.max(4.0, mark.accumulatedDamage * 0.40);

        // CAP: Todesurteil darf NIEMALS töten! Maximal auf 1 Herz (2.0 HP) runter
        double currentHealth = target.getHealth();
        double maxAllowedDamage = Math.max(0.0, currentHealth - 2.0);
        double finalDamage = Math.min(popDamage, maxAllowedDamage);

        if (finalDamage > 0.0) {
            double normalPart = finalDamage * 0.5;
            double truePart = finalDamage * 0.5;

            // 1. Hit: Normaler Schaden
            target.setNoDamageTicks(0);
            if (normalPart > 0.0) {
                target.damage(normalPart, caster);
            }

            // Nach dem ersten Hit i-Frames auf 0 setzen, damit beide durchgehen
            target.setNoDamageTicks(0);

            // 2. Hit: True Damage (Rüstungs-Bypass)
            if (truePart > 0.0) {
                RelicUtils.applyMagicDamage(target, caster, truePart);
            }

            // Abschließend i-Frames säubern
            target.setNoDamageTicks(0);
        }

        // Garantiere, dass das Ziel bei mindestens 2.0 HP (1 Herz) überlebt
        if (target.isDead() || target.getHealth() < 2.0) {
            target.setHealth(Math.min(target.getMaxHealth(), 2.0));
        }

        world.spawnParticle(Particle.SOUL, tLoc, 30, 0.5, 0.5, 0.5, 0.1);

        if (caster.isOnline()) {
            if (finalDamage < popDamage && currentHealth <= 2.0) {
                caster.sendActionBar(MiniMessage.miniMessage().deserialize(
                        "<gradient:#ff0000:#aa00ff><bold>💀 HYBRID-TODESURTEIL DETONIERT!</bold></gradient> <red>(Ziel bereits auf 1 Herz - kein Kill)</red>"
                ));
            } else if (finalDamage < popDamage) {
                caster.sendActionBar(MiniMessage.miniMessage().deserialize(
                        "<gradient:#ff0000:#aa00ff><bold>💀 HYBRID-TODESURTEIL DETONIERT!</bold></gradient> <yellow>" + String.format(Locale.US, "%.1f", finalDamage * 0.5) + " Phys</yellow> + <red><bold>+" + String.format(Locale.US, "%.1f", finalDamage * 0.5) + " True Dmg</bold></red> <red>(Gekappt auf 1 Herz!)</red>"
                ));
            } else {
                caster.sendActionBar(MiniMessage.miniMessage().deserialize(
                        "<gradient:#ff0000:#aa00ff><bold>💀 HYBRID-TODESURTEIL DETONIERT!</bold></gradient> <yellow>" + String.format(Locale.US, "%.1f", finalDamage * 0.5) + " Phys</yellow> + <red><bold>+" + String.format(Locale.US, "%.1f", finalDamage * 0.5) + " True Dmg!</bold></red>"
                ));
            }
        }
    }

    // Schaden akkumulieren für Todesurteil & Unantastbarkeit schützen
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageAccumulate(EntityDamageByEntityEvent event) {
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }

        if (attacker != null && event.getEntity() instanceof LivingEntity victim) {
            UUID targetId = victim.getUniqueId();
            DeathMarkData mark = activeMarks.get(targetId);
            if (mark != null && mark.casterId.equals(attacker.getUniqueId())) {
                // Tracking: Sowohl Roher Schaden als auch Final Damage berücksichtigen
                double dealt = Math.max(event.getDamage(), event.getFinalDamage());
                mark.accumulatedDamage += dealt;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvulnerableDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (invulnerablePlayers.contains(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        shadowCooldowns.remove(uuid);
        shurikenCooldowns.remove(uuid);
        deathMarkCooldowns.remove(uuid);
        ActiveShadow shadow = activeShadows.remove(uuid);
        if (shadow != null && shadow.task != null) shadow.task.cancel();
        invulnerablePlayers.remove(uuid);
    }

    private static class ActiveShadow {
        final Location location;
        final long expiryTime;
        BukkitTask task;

        ActiveShadow(Location location, long expiryTime) {
            this.location = location;
            this.expiryTime = expiryTime;
        }
    }

    private static class DeathMarkData {
        final UUID casterId;
        final long expiryTime;
        double accumulatedDamage = 0.0;

        DeathMarkData(UUID casterId, long expiryTime) {
            this.casterId = casterId;
            this.expiryTime = expiryTime;
        }
    }
}
