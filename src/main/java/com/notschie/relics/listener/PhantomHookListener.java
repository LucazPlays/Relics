package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.NoFallDamageManager;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Phantomschlinge (phantom_hook):
 * - Rechtsklick: Seelenkette (Dual Grapple & Reel, 24m Reichweite, zieht an Blöcke mit NoFall 4s ODER reißt Gegner 10m heran + 1.2s Root + 5 HP Dmg, Cooldown 9s)
 * - Shift + Rechtsklick: Spektral-Leash (Anti-Escape Anker für 4s, 6m Radius, zieht Fliehende per Rubberband zurück, Cooldown 18s)
 * - Linksklick (Nahkampf < 3.5m): Spektraler Überwurf (Schleudert Gegner 8m hinter dich in den Boden, 6 HP Aufprallschaden, Slowness II, Cooldown 12s)
 * - Passiv: Ätherisches Momentum (Speed II nach Grapple) + Seelenbeute (+4 HP Heilung + Reset wenn gezogenes Ziel innerhalb von 4s stirbt)
 */
public class PhantomHookListener implements Listener {

    private static final String RELIC_ID = "phantom_hook";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final NoFallDamageManager noFallManager;

    private final Map<UUID, Long> grappleCooldowns = new HashMap<>();
    private final Map<UUID, Long> leashCooldowns = new HashMap<>();
    private final Map<UUID, Long> slamCooldowns = new HashMap<>();

    // Tagged Targets for Passive Soul Catch (Target UUID -> Expiry Time)
    private final Map<UUID, TaggedData> taggedTargets = new HashMap<>();
    private final Map<UUID, Long> phantomDoubleJumpEligible = new HashMap<>();

    public PhantomHookListener(RelicsPlugin plugin, NoFallDamageManager noFallManager) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
        this.noFallManager = noFallManager;
    }

    private boolean isPhantomHook(ItemStack item) {
        return item != null && item.getType() == Material.FISHING_ROD
                && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    private boolean isPhantomHookInHand(Player player) {
        return isPhantomHook(player.getInventory().getItemInMainHand())
                || isPhantomHook(player.getInventory().getItemInOffHand());
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
        if (!isPhantomHook(item)) return;

        // Abfangen der Vanilla-Fishing-Rod Mechanik
        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerSpectralLeash(player);
        } else {
            triggerSoulGrapple(player);
        }
    }

    // --- A) SEELENKETTE (Rechtsklick) ---
    private void triggerSoulGrapple(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = grappleCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 4500L;

        if (now - lastUse < cdMs) {
            double remaining = (cdMs - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🪝 Seelenkette Cooldown: <yellow>" + String.format(Locale.US, "%.1f", remaining) + "s</yellow></red>"
            ));
            return;
        }

        grappleCooldowns.put(uuid, now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.2f, 0.7f);
        world.playSound(eye, Sound.BLOCK_CHAIN_PLACE, 1.5f, 1.8f);

        double maxRange = 24.0;

        // Zuerst nach Entities entlang des Strahls raytracen
        RayTraceResult entityTrace = world.rayTraceEntities(eye, dir, maxRange, 1.2, e -> {
            if (e.getUniqueId().equals(player.getUniqueId())) return false;
            if (!(e instanceof LivingEntity living)) return false;
            if (e instanceof Player tp && (tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR)) return false;
            return !RelicUtils.isSameTeam(player, (Player) (e instanceof Player ? e : null));
        });

        // Block-Raytrace
        RayTraceResult blockTrace = world.rayTraceBlocks(eye, dir, maxRange, FluidCollisionMode.NEVER, true);

        // Fall 1: Entity getroffen (vor einem Block)
        if (entityTrace != null && entityTrace.getHitEntity() instanceof LivingEntity target
                && (blockTrace == null || eye.distanceSquared(entityTrace.getHitPosition().toLocation(world)) < eye.distanceSquared(blockTrace.getHitPosition().toLocation(world)))) {

            Location hitLoc = entityTrace.getHitPosition().toLocation(world);
            renderChain(eye, hitLoc);

            world.playSound(hitLoc, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.5f, 0.7f);
            world.playSound(hitLoc, Sound.BLOCK_CHAIN_BREAK, 1.4f, 1.2f);
            world.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_SWOOP, 1.2f, 1.6f);

            // Reißt Gegner bis zu 10 Blöcke heran
            Vector pull = player.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(1.6).setY(0.38);
            target.setVelocity(pull);

            // 5 HP Durchschlagsschaden (Magic)
            RelicUtils.applyMagicDamage(target, player, 5.0);

            // 1.2s Root (Slowness VI + Jump Boost 250)
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 24, 5, false, true, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 24, 250, false, false, false));

            // Markieren für Seelenbeute (4s Zeitfenster)
            taggedTargets.put(target.getUniqueId(), new TaggedData(player.getUniqueId(), now + 4000L));

            // Ethereal Flow (+25% Speed II für 3.5s)
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 70, 1, false, true, true));

            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00f5d4:#0077b6><bold>🪝 GEHAKT!</bold></gradient> <gray>Ziel herangezogen & gerootet!</gray>"
            ));
            return;
        }

        // Fall 2: Block getroffen -> Spider-Man Grapple
        if (blockTrace != null && blockTrace.getHitPosition() != null) {
            Location hitLoc = blockTrace.getHitPosition().toLocation(world);
            renderChain(eye, hitLoc);

            world.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_2, 1.3f, 1.5f);
            world.playSound(hitLoc, Sound.BLOCK_CHAIN_FALL, 1.5f, 1.4f);

            // Katapultiert den Spieler zum Zielort
            Vector pull = hitLoc.toVector().subtract(player.getLocation().toVector());
            double dist = pull.length();
            Vector vel = pull.normalize().multiply(Math.min(2.3, 0.7 + dist * 0.08)).setY(Math.min(1.3, 0.45 + pull.normalize().getY() * 0.8));
            player.setVelocity(vel);

            // 4s NoFall-Damage
            if (noFallManager != null) {
                noFallManager.grant(player, 4000L);
            }

            // In-Air Double Jump freischalten (3.5s Zeitfenster während des Fluges)
            phantomDoubleJumpEligible.put(player.getUniqueId(), System.currentTimeMillis() + 3500L);
            player.setAllowFlight(true);

            // Ethereal Flow (+25% Speed II für 3.5s)
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 70, 1, false, true, true));

            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00f5d4:#00b4d8><bold>🪝 SEELENSCHWUNG!</bold></gradient> <gray>Herangezogen + 4s No-Fall aktiv!</gray>"
            ));
            return;
        }

        // Nichts getroffen: Kette ins Leere schießen
        Location missEnd = eye.clone().add(dir.clone().multiply(maxRange));
        renderChain(eye, missEnd);
    }

    private void renderChain(Location from, Location to) {
        World world = from.getWorld();
        if (world == null) return;

        double distance = from.distance(to);
        Vector step = to.toVector().subtract(from.toVector()).normalize().multiply(0.5);
        Location cur = from.clone();

        Particle.DustOptions cyanDust = new Particle.DustOptions(Color.fromRGB(0, 245, 212), 1.1f);
        Particle.DustOptions darkCyanDust = new Particle.DustOptions(Color.fromRGB(0, 119, 182), 1.1f);

        int count = (int) (distance / 0.5);
        for (int i = 0; i < count; i++) {
            cur.add(step);
            world.spawnParticle(Particle.DUST, cur, 1, 0, 0, 0, 0, (i % 2 == 0) ? cyanDust : darkCyanDust);
            if (i % 4 == 0) {
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, cur, 1, 0.02, 0.02, 0.02, 0.01);
            }
        }
    }

    // --- B) SPEKTRAL-LEASH (Shift + Rechtsklick) ---
    private void triggerSpectralLeash(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = leashCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 18000L;

        if (now - lastUse < cdMs) {
            double remaining = (cdMs - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>⛓️ Spektral-Leash Cooldown: <yellow>" + String.format(Locale.US, "%.1f", remaining) + "s</yellow></red>"
            ));
            return;
        }

        leashCooldowns.put(uuid, now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        // Zielanker ermitteln (max 14 Blöcke)
        RayTraceResult rt = world.rayTraceBlocks(eye, dir, 14.0, FluidCollisionMode.NEVER, true);
        Location anchorLoc;
        if (rt != null && rt.getHitPosition() != null) {
            anchorLoc = rt.getHitPosition().toLocation(world);
        } else {
            anchorLoc = eye.clone().add(dir.clone().multiply(14.0));
        }

        world.playSound(anchorLoc, Sound.BLOCK_ANVIL_LAND, 1.2f, 1.8f);
        world.playSound(anchorLoc, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.4f, 1.3f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#00f5d4:#0077b6><bold>⛓️ SPEKTRAL-ANKER GESETZT!</bold></gradient> <gray>(4s Anti-Escape Leine)</gray>"
        ));

        double radius = 6.0;
        int durationTicks = 80; // 4 Sekunden

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks += 2;
                if (ticks >= durationTicks) {
                    world.playSound(anchorLoc, Sound.BLOCK_CHAIN_BREAK, 1.2f, 0.8f);
                    cancel();
                    return;
                }

                // Boden-Runenkreis zeichnen
                Particle.DustOptions runeDust = new Particle.DustOptions(Color.fromRGB(0, 245, 212), 1.0f);
                for (int i = 0; i < 16; i++) {
                    double angle = (2 * Math.PI / 16) * i;
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;
                    world.spawnParticle(Particle.DUST, anchorLoc.getX() + x, anchorLoc.getY() + 0.15, anchorLoc.getZ() + z, 1, 0, 0, 0, 0, runeDust);
                }
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, anchorLoc.clone().add(0, 0.3, 0), 2, 0.1, 0.2, 0.1, 0.02);

                // Gefangene Entities prüfen
                for (Entity entity : world.getNearbyEntities(anchorLoc, radius + 4.0, radius + 2.0, radius + 4.0)) {
                    if (entity instanceof LivingEntity target && !entity.getUniqueId().equals(uuid)) {
                        if (target instanceof Player tp && (tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR)) continue;
                        if (RelicUtils.isSameTeam(player, (Player) (target instanceof Player ? target : null))) continue;

                        target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20, 0, false, false, false));

                        double dist = target.getLocation().distance(anchorLoc);

                        // Kette vom Anker zum Ziel rendern
                        if (ticks % 4 == 0) {
                            renderChain(anchorLoc, target.getLocation().add(0, 0.5, 0));
                        }

                        // Rubberband-Effekt: Versucht der Gegner die 6m-Zone zu verlassen -> Zurückschnappen!
                        if (dist > radius) {
                            Vector snap = anchorLoc.toVector().subtract(target.getLocation().toVector()).normalize().multiply(1.4).setY(0.25);
                            target.setVelocity(snap);
                            world.playSound(target.getLocation(), Sound.BLOCK_CHAIN_BREAK, 1.2f, 0.7f);
                            target.sendActionBar(MiniMessage.miniMessage().deserialize(
                                    "<gradient:#00f5d4:#0077b6><bold>⛓️ SEELENLEINE!</bold></gradient> <gray>Entkommen unmöglich!</gray>"
                            ));
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    // ==========================================
    // 2. LINKSKLICK: SPEKTRALER ÜBERWURF (Judowurf)
    // ==========================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeftClickAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isPhantomHookInHand(player)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = slamCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 12000L;

        if (now - lastUse < cdMs) return;

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        // Prüfe ob ein Gegner direkt vor dem Spieler steht (< 3.5 Blöcke)
        RayTraceResult rt = world.rayTraceEntities(eye, dir, 3.5, 0.8, e -> {
            if (e.getUniqueId().equals(uuid)) return false;
            if (!(e instanceof LivingEntity living)) return false;
            if (e instanceof Player tp && (tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR)) return false;
            return !RelicUtils.isSameTeam(player, (Player) (e instanceof Player ? e : null));
        });

        if (rt == null || !(rt.getHitEntity() instanceof LivingEntity target)) return;

        slamCooldowns.put(uuid, now);

        world.playSound(player.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1.5f, 0.7f);
        world.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_BITE, 1.3f, 1.4f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#00f5d4:#0077b6><bold>🪝 SPEKTRALER ÜBERWURF!</bold></gradient>"
        ));

        // Schleudere Ziel 8 Blöcke hinter den Spieler
        Vector behind = player.getLocation().getDirection().setY(0).normalize().multiply(-1.55).setY(0.78);
        target.setVelocity(behind);

        // Nach 0.6s (12 Ticks): Aufprallschaden & Nausea/Slowness
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!target.isValid() || target.isDead()) return;

                Location impactLoc = target.getLocation();
                World w = impactLoc.getWorld();
                if (w != null) {
                    w.playSound(impactLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.8f);
                    w.playSound(impactLoc, Sound.BLOCK_ANVIL_LAND, 1.0f, 1.2f);
                    w.spawnParticle(Particle.FLASH, impactLoc, 2, 0.2, 0.2, 0.2, 0, Color.WHITE);
                    w.spawnParticle(Particle.SOUL_FIRE_FLAME, impactLoc, 25, 0.5, 0.5, 0.5, 0.05);
                }

                RelicUtils.applyMagicDamage(target, player, 6.0);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true, true));
                target.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0, false, true, true));
            }
        }.runTaskLater(plugin, 12L);
    }

    // ==========================================
    // 3. PASSIV: SOUL CATCH RESET
    // ==========================================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        UUID targetId = event.getEntity().getUniqueId();
        TaggedData tag = taggedTargets.remove(targetId);
        if (tag == null) return;

        long now = System.currentTimeMillis();
        if (now > tag.expiryTime) return;

        Player killer = Bukkit.getPlayer(tag.casterId);
        if (killer != null && killer.isOnline()) {
            // Seelenkette Cooldown sofort auf 0
            grappleCooldowns.remove(killer.getUniqueId());

            // +4 HP (2 Herzen) Heilung
            double maxHp = 20.0;
            if (killer.getAttribute(Attribute.MAX_HEALTH) != null) {
                maxHp = killer.getAttribute(Attribute.MAX_HEALTH).getValue();
            }
            killer.setHealth(Math.min(maxHp, killer.getHealth() + 4.0));

            // Sounds & Partikel
            Location kLoc = killer.getLocation();
            killer.getWorld().playSound(kLoc, Sound.ENTITY_PLAYER_LEVELUP, 1.4f, 1.8f);
            killer.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, kLoc.clone().add(0, 1, 0), 30, 0.5, 0.5, 0.5, 0.2);

            killer.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00f5d4:#00b4d8><bold>🪝 SEELENBEUTE!</bold></gradient> <gray>+4 HP geheilt & Seelenkette bereit!</gray>"
            ));
        }
    }


    // ==========================================
    // 2B. IN-AIR DOUBLE JUMP BEIM SEELENSCHWUNG
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        UUID uuid = player.getUniqueId();
        Long expiry = phantomDoubleJumpEligible.remove(uuid);
        if (expiry != null && System.currentTimeMillis() < expiry) {
            event.setCancelled(true);
            player.setAllowFlight(false);
            player.setFlying(false);

            // Mid-Air Double Jump: Vorwärts- & Aufwärts-Impuls
            Vector dir = player.getEyeLocation().getDirection().normalize();
            Vector boost = dir.multiply(1.35).setY(0.72);
            player.setVelocity(boost);

            if (noFallManager != null) {
                noFallManager.grant(player, 4000L);
            }

            World world = player.getWorld();
            world.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1.5f, 1.3f);
            world.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_ELYTRA, 1.3f, 1.5f);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, player.getLocation(), 20, 0.3, 0.2, 0.3, 0.08);
            world.spawnParticle(Particle.SCULK_SOUL, player.getLocation(), 12, 0.3, 0.2, 0.3, 0.04);

            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00f5d4:#ffffff><bold>🪝 GEISTERSPRUNG (DOUBLE JUMP)!</bold></gradient>"
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        UUID uuid = player.getUniqueId();
        Long expiry = phantomDoubleJumpEligible.get(uuid);
        if (expiry != null) {
            if (System.currentTimeMillis() > expiry || (player.isOnGround() && System.currentTimeMillis() > expiry - 3000L)) {
                phantomDoubleJumpEligible.remove(uuid);
                player.setAllowFlight(false);
                player.setFlying(false);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        phantomDoubleJumpEligible.remove(event.getPlayer().getUniqueId());
        UUID uuid = event.getPlayer().getUniqueId();
        grappleCooldowns.remove(uuid);
        leashCooldowns.remove(uuid);
        slamCooldowns.remove(uuid);
        taggedTargets.values().removeIf(t -> t.casterId.equals(uuid));
    }

    private static class TaggedData {
        final UUID casterId;
        final long expiryTime;

        TaggedData(UUID casterId, long expiryTime) {
            this.casterId = casterId;
            this.expiryTime = expiryTime;
        }
    }
}
