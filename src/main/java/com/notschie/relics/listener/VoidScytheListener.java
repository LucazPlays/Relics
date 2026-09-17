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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Sense der Leere (void_scythe):
 * - Rechtsklick: Ereignishorizont (Vortex Pull, 16m Distanz, 0.4s Zündung, 6m Radius, 6 HP Void-Schaden, Slowness II, Cooldown 13s)
 * - Shift + Rechtsklick: Dimensionsriss (180° Sweep, 8m Reichweite, Shield-Bypass 1.5s, 3m Heranziehen, 7 HP Schaden, Cooldown 8s)
 * - Passiv / On-Hit: Seelenernte (Stackt bis 5, bei 5 detoniert der nächste Treffer für 4 HP Magic Damage + Darkness für 2s)
 * - Normale Hits: Spezielle violette/schwarze Void-Partikel + tiefe Void-Impact Sounds
 */
public class VoidScytheListener implements Listener {

    private static final String RELIC_ID = "void_scythe";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Long> vortexCooldowns = new HashMap<>();
    private final Map<UUID, Long> cleaveCooldowns = new HashMap<>();
    private final Map<UUID, SoulHarvestData> soulStacks = new HashMap<>();

    public VoidScytheListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isVoidScythe(ItemStack item) {
        return item != null && item.getType() == Material.NETHERITE_HOE
                && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    // ==========================================
    // 1. RECHTSKLICK & SHIFT-RECHTSKLICK
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!isVoidScythe(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerVoidCleave(player);
        } else {
            triggerVortexPull(player);
        }
    }

    // --- A) EREIGNISHORIZONT (Vortex Pull) ---
    private void triggerVortexPull(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = vortexCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 13000L;

        if (now - lastUse < cdMs) {
            double remaining = (cdMs - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🕳️ Ereignishorizont Cooldown: <yellow>" + String.format(Locale.US, "%.1f", remaining) + "s</yellow></red>"
            ));
            return;
        }

        vortexCooldowns.put(uuid, now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        world.playSound(eye, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.2f, 0.6f);
        world.playSound(eye, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.5f, 1.4f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#aa00ff:#000000><bold>🕳️ EREIGNISHORIZONT GESCHOSSEN!</bold></gradient>"
        ));

        // Ermittle Zielposition (max 16 Blöcke, oder Block-Kollision)
        RayTraceResult rt = world.rayTraceBlocks(eye, dir, 16.0, FluidCollisionMode.NEVER, true);
        Location targetCenter;
        if (rt != null && rt.getHitPosition() != null) {
            targetCenter = rt.getHitPosition().toLocation(world);
        } else {
            targetCenter = eye.clone().add(dir.clone().multiply(16.0));
        }

        // Flug-Animation des Mini-Lochs
        new BukkitRunnable() {
            int step = 0;
            final Location current = eye.clone();
            final double distance = eye.distance(targetCenter);
            final int totalSteps = Math.max(2, (int) (distance / 2.0));

            @Override
            public void run() {
                step++;
                current.add(dir.clone().multiply(distance / totalSteps));

                // Schwarze & Violette Partikel
                Particle.DustOptions darkViolet = new Particle.DustOptions(Color.fromRGB(80, 0, 160), 1.2f);
                world.spawnParticle(Particle.DUST, current, 8, 0.15, 0.15, 0.15, 0, darkViolet);
                world.spawnParticle(Particle.SQUID_INK, current, 4, 0.1, 0.1, 0.1, 0.02);
                world.spawnParticle(Particle.PORTAL, current, 6, 0.2, 0.2, 0.2, 0.1);

                if (step >= totalSteps) {
                    cancel();
                    // Zündung nach 0.4s (8 Ticks)
                    igniteVortex(player, targetCenter);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void igniteVortex(Player caster, Location center) {
        World world = center.getWorld();
        if (world == null) return;

        // 0.4s Verzögerung vor dem ultimativen Sog
        new BukkitRunnable() {
            int prepTicks = 0;

            @Override
            public void run() {
                prepTicks += 2;
                // Ansaug-Partikel zur Mitte
                world.spawnParticle(Particle.REVERSE_PORTAL, center, 15, 1.5, 1.5, 1.5, 0.2);
                world.spawnParticle(Particle.SCULK_SOUL, center, 4, 0.5, 0.5, 0.5, 0.05);

                if (prepTicks >= 8) { // 0.4s erreicht -> ZÜNDUNG
                    cancel();
                    detonateVortexPull(caster, center);
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void detonateVortexPull(Player caster, Location center) {
        World world = center.getWorld();
        if (world == null) return;

        world.playSound(center, Sound.ENTITY_WARDEN_ROAR, 1.4f, 1.8f);
        world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.8f, 0.6f);
        world.playSound(center, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.0f, 0.8f);

        // Massive Druckwellen-Partikel
        Particle.DustOptions blackHole = new Particle.DustOptions(Color.fromRGB(20, 0, 40), 2.0f);
        Particle.DustOptions brightPurple = new Particle.DustOptions(Color.fromRGB(220, 50, 255), 1.5f);
        world.spawnParticle(Particle.DUST, center, 45, 1.2, 1.2, 1.2, 0, blackHole);
        world.spawnParticle(Particle.DUST, center, 35, 1.0, 1.0, 1.0, 0, brightPurple);
        world.spawnParticle(Particle.SQUID_INK, center, 30, 0.8, 0.8, 0.8, 0.1);
        world.spawnParticle(Particle.SONIC_BOOM, center, 1, 0, 0, 0, 0);

        double radius = 6.0;
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (entity.getLocation().distanceSquared(center) > radius * radius) continue;
            if (entity instanceof LivingEntity target && !entity.getUniqueId().equals(caster.getUniqueId())) {
                if (target instanceof Player targetPlayer) {
                    if (targetPlayer.getGameMode() == GameMode.CREATIVE || targetPlayer.getGameMode() == GameMode.SPECTATOR) {
                        continue;
                    }
                    if (RelicUtils.isSameTeam(caster, targetPlayer)) continue;
                }

                // Gewaltsamer Sog zur Mitte
                Vector pull = center.toVector().subtract(target.getLocation().toVector()).normalize().multiply(1.35).setY(0.35);
                target.setVelocity(pull);

                // 6 HP Void-Schaden
                RelicUtils.applyMagicDamage(target, caster, 6.0);

                // Slowness II für 2.5 Sekunden (50 Ticks)
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 1, false, true, true));

                // Treffer-Partikel am Target
                world.spawnParticle(Particle.SCULK_SOUL, target.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.05);
            }
        }
    }

    // --- B) DIMENSIONSRISS (Shift + Rechtsklick 180° Cleave) ---
    private void triggerVoidCleave(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = cleaveCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 8000L;

        if (now - lastUse < cdMs) {
            double remaining = (cdMs - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🕳️ Dimensionsriss Cooldown: <yellow>" + String.format(Locale.US, "%.1f", remaining) + "s</yellow></red>"
            ));
            return;
        }

        cleaveCooldowns.put(uuid, now);

        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.5f);
        world.playSound(loc, Sound.ENTITY_WITHER_SHOOT, 1.2f, 1.6f);
        world.playSound(loc, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.8f, 1.9f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#aa00ff:#ff00ff><bold>🕳️ DIMENSIONSRISS ENTFELLSELT!</bold></gradient>"
        ));

        // 180° Cleave-Partikelwelle fächert nach vorne
        Vector look = player.getEyeLocation().getDirection().setY(0).normalize();
        double playerYaw = Math.toRadians(-player.getLocation().getYaw());

        Particle.DustOptions violetDust = new Particle.DustOptions(Color.fromRGB(180, 20, 255), 1.4f);
        for (double r = 1.5; r <= 8.0; r += 1.2) {
            for (double angleDeg = -85; angleDeg <= 85; angleDeg += 10) {
                double rad = Math.toRadians(angleDeg);
                // Rotiere Vektor um die Y-Achse
                double cos = Math.cos(rad);
                double sin = Math.sin(rad);
                double vx = look.getX() * cos - look.getZ() * sin;
                double vz = look.getX() * sin + look.getZ() * cos;
                Vector sweepDir = new Vector(vx, 0, vz).normalize().multiply(r);
                Location pLoc = player.getEyeLocation().add(sweepDir).add(0, -0.2, 0);

                world.spawnParticle(Particle.DUST, pLoc, 1, 0, 0, 0, 0, violetDust);
                if (r >= 5.0 && Math.abs(angleDeg) % 20 == 0) {
                    world.spawnParticle(Particle.DRAGON_BREATH, pLoc, 1, 0, 0, 0, 0.02);
                }
            }
        }

        // Entity-Treffer im 8-Block Halbkreis vor dem Spieler
        double maxDist = 8.0;
        for (Entity entity : world.getNearbyEntities(loc, maxDist, 3.5, maxDist)) {
            if (entity instanceof LivingEntity target && !entity.getUniqueId().equals(player.getUniqueId())) {
                if (target instanceof Player targetPlayer) {
                    if (targetPlayer.getGameMode() == GameMode.CREATIVE || targetPlayer.getGameMode() == GameMode.SPECTATOR) {
                        continue;
                    }
                    if (RelicUtils.isSameTeam(player, targetPlayer)) continue;
                }

                Vector toTarget = target.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
                if (toTarget.length() > maxDist) continue;

                // Winkelprüfung: Liegt das Target im 180°-Blickfeld (dot-Produkt > 0)
                if (look.dot(toTarget.normalize()) < 0.05) continue;

                // 1) SHIELD-BYPASS: Deaktiviert Schilde für 1.5 Sekunden
                if (target instanceof Player targetPlayer) {
                    if (targetPlayer.isBlocking() || isHoldingShield(targetPlayer)) {
                        targetPlayer.setCooldown(Material.SHIELD, 30); // 1.5s
                        world.playSound(targetPlayer.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.2f, 1.0f);
                        targetPlayer.sendActionBar(MiniMessage.miniMessage().deserialize(
                                "<red>🛡️ Schild durch Dimensionsriss gebrochen! (1.5s)</red>"
                        ));
                    }
                }

                // 2) PULL: Zieht das Target 3 Blöcke näher an dich heran
                Vector pull = player.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(0.85).setY(0.25);
                target.setVelocity(pull);

                // 3) 7 HP Direktschaden
                RelicUtils.applyMagicDamage(target, player, 7.0);

                // Slowness I für 1.5s
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0, false, true, true));

                // Treffer-Effekt
                world.spawnParticle(Particle.SWEEP_ATTACK, target.getLocation().add(0, 1, 0), 2, 0.2, 0.2, 0.2, 0);
                world.spawnParticle(Particle.PORTAL, target.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.2);
            }
        }
    }

    private boolean isHoldingShield(Player p) {
        return (p.getInventory().getItemInMainHand() != null && p.getInventory().getItemInMainHand().getType() == Material.SHIELD)
                || (p.getInventory().getItemInOffHand() != null && p.getInventory().getItemInOffHand().getType() == Material.SHIELD);
    }

    // ==========================================
    // 2. NORMAL HITS: SPECIAL PARTICLES & SEELENERTE
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isVoidScythe(weapon)) return;

        World world = target.getWorld();
        Location hitLoc = target.getLocation().clone().add(0, 1.0, 0);

        // A) SPEZIELLE HIT-PARTIKEL & SOUNDS (Wichtig für User-Anforderung)
        world.playSound(hitLoc, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 1.3f, 1.4f);
        world.playSound(hitLoc, Sound.BLOCK_SCULK_CHARGE, 1.5f, 1.7f);
        world.playSound(hitLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.7f);

        Particle.DustOptions purpleDust = new Particle.DustOptions(Color.fromRGB(150, 0, 255), 1.3f);
        Particle.DustOptions darkDust = new Particle.DustOptions(Color.fromRGB(20, 0, 50), 1.5f);
        world.spawnParticle(Particle.DUST, hitLoc, 18, 0.35, 0.4, 0.35, 0, purpleDust);
        world.spawnParticle(Particle.DUST, hitLoc, 12, 0.3, 0.3, 0.3, 0, darkDust);
        world.spawnParticle(Particle.SCULK_SOUL, hitLoc, 6, 0.2, 0.3, 0.2, 0.04);
        world.spawnParticle(Particle.PORTAL, hitLoc, 25, 0.4, 0.5, 0.4, 0.3);

        // B) SEELENERTE (SOUL HARVEST) STACKS
        UUID uuid = attacker.getUniqueId();
        long now = System.currentTimeMillis();
        SoulHarvestData data = soulStacks.computeIfAbsent(uuid, k -> new SoulHarvestData());

        // Prüfen, ob Stacks abgelaufen sind (6 Sekunden Reset)
        if (now - data.lastHitTime > 6000L) {
            data.stacks = 0;
        }
        data.lastHitTime = now;

        if (data.stacks >= 5) {
            // DETONATION BEI 5 STACKS
            data.stacks = 0;

            world.playSound(hitLoc, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.9f, 1.7f);
            world.playSound(hitLoc, Sound.ENTITY_WITHER_HURT, 1.2f, 1.3f);
            world.spawnParticle(Particle.FLASH, hitLoc, 2, 0.2, 0.2, 0.2, 0, Color.WHITE);
            world.spawnParticle(Particle.SONIC_BOOM, hitLoc, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.SQUID_INK, hitLoc, 25, 0.4, 0.4, 0.4, 0.1);

            // 4 HP puren Magic Damage (umgeht Rüstung komplett)
            RelicUtils.applyMagicDamage(target, attacker, 4.0);

            // 2s Darkness
            target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 40, 0, false, true, true));

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#ff00ff:#5500aa><bold>🕳️ SEELEN-DETONATION!</bold></gradient> <gray>4 HP Magic-Schaden + Dunkelheit!</gray>"
            ));
        } else {
            // Stack erhöhen (1 bis 5)
            data.stacks++;
            world.playSound(attacker.getLocation(), Sound.BLOCK_SCULK_CATALYST_BLOOM, 0.9f, 1.0f + (0.18f * data.stacks));

            String activeGlyphs = "◆".repeat(data.stacks);
            String emptyGlyphs = "◇".repeat(5 - data.stacks);

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#aa00ff:#5500aa><bold>🕳️ Seelenernte:</bold></gradient> <light_purple>[" + activeGlyphs + emptyGlyphs + "]</light_purple>"
                            + (data.stacks == 5 ? " <yellow><bold>BEREIT ZUR DETONATION!</bold></yellow>" : "")
            ));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        soulStacks.remove(event.getPlayer().getUniqueId());
        vortexCooldowns.remove(event.getPlayer().getUniqueId());
        cleaveCooldowns.remove(event.getPlayer().getUniqueId());
    }

    private static class SoulHarvestData {
        int stacks = 0;
        long lastHitTime = 0L;
    }
}
