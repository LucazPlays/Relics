package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.Sound;
import org.bukkit.World;
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
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Eisgrab-Schwung (Shift+Linksklick): wandernde Cone-Hitbox in Blickrichtung.
 *
 *  - Reichweite: ice-swing-range (Default 16 Blöcke).
 *  - Geschwindigkeit: ice-swing-speed (Default 16 Blöcke/s = 1s Flug).
 *  - Cone: voller Winkel ice-swing-cone-angle (Default 30°), in echte Blickrichtung.
 *  - Kein Direktschaden — nur Status-Effekte:
 *      - Slowness (Level aus ice-swing-slow-amplifier, Default I)
 *        f\u00fcr ice-swing-slow-duration-ms (Default 5000ms = 5s).
 *      - Mining Fatigue (Level aus ice-swing-fatigue-amplifier, Default I)
 *        f\u00fcr ice-swing-fatigue-duration-ms (Default 5000ms = 5s).
 *  - Pro Entity genau 1 Treffer pro Schwung (Scoreboard-Tag).
 *  - 8s Cooldown pro Spieler.
 *  - Partikel: SNOWFLAKE + ITEM_SNOWBALL + WHITE-BLUE DUST, mit der Hitbox mitbewegt.
 *  - Sounds: BLOCK_SNOW_BREAK beim Start, BLOCK_GLASS_BREAK zwischendurch.
 */
public class IceSwingListener implements Listener {

    private static final String RELIC_ID = "ice_grab";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** Aktive Swing-Tasks pro Spieler (zum sauberen Abbrechen). */
    private final Map<UUID, BukkitTask> activeTasks = new HashMap<>();

    public IceSwingListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isIceSword(ItemStack stack) {
        if (stack == null || !factory.isRelic(stack)) return false;
        return RELIC_ID.equals(factory.getRelicId(stack));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClickAir(PlayerInteractEvent event) {
        Action action = event.getAction();
        // Nur Linksklick in die Luft (Spieler „schlägt in die Luft"), nicht LEFT_CLICK_BLOCK
        // (das wäre ein normaler Angriff auf einen Block / gegen ein Entity).
        if (action != Action.LEFT_CLICK_AIR) return;

        Player p = event.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;

        // Sneak-Pflicht: nur wenn der Spieler schleicht.
        if (!p.isSneaking()) return;

        // Eis-Schwert in der Hand?
        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack off = p.getInventory().getItemInOffHand();
        if (!isIceSword(main) && !isIceSword(off)) return;

        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null || !def.getEffectBoolean("ice-swing-enabled", true)) return;

        // Vanilla-Schwung unterdrücken, damit kein normaler Hit-Schaden ausgelöst wird.
        event.setCancelled(true);

        long now = System.currentTimeMillis();
        Long until = cooldowns.get(p.getUniqueId());
        if (until != null && now < until) {
            double secLeft = (until - now) / 1000.0;
            p.sendActionBar(MM.deserialize(
                    "<gradient:#aaddff:#ffffff><bold>Eisgrab</bold> <dark_gray>» <aqua>Schwung lädt noch <white>"
                            + String.format("%.1f", secLeft) + "s <aqua>auf."));
            return;
        }

        double range = def.getEffectDouble("ice-swing-range", 16.0);
        double coneAngleDeg = def.getEffectDouble("ice-swing-cone-angle", 30.0);
        double speed = def.getEffectDouble("ice-swing-speed", 8.0);
        double damage = def.getEffectDouble("ice-swing-damage", 9.0);
        int slowAmp = def.getEffectInt("ice-swing-slow-amplifier", 1);
        long slowDurMs = (long) def.getEffectDouble("ice-swing-slow-duration-ms", 5000.0);
        int fatigueAmp = def.getEffectInt("ice-swing-fatigue-amplifier", 1);
        long fatigueDurMs = (long) def.getEffectDouble("ice-swing-fatigue-duration-ms", 5000.0);
        long cdMs = (long) def.getEffectDouble("ice-swing-cooldown-ms", 8000.0);
        boolean particles = def.getEffectBoolean("ice-swing-particle", true);
        boolean sounds = def.getEffectBoolean("ice-swing-sound", true);

        cooldowns.put(p.getUniqueId(), now + cdMs);

        startSwing(p, range, coneAngleDeg, speed, damage,
                   slowAmp, slowDurMs, fatigueAmp, fatigueDurMs,
                   particles, sounds);
    }

    /**
     * Startet den wandernden Cone-Schwung in der Blickrichtung des Spielers.
     */
    private void startSwing(Player p, double range, double coneAngleDeg, double speed, double damage,
                            int slowAmp, long slowDurMs,
                            int fatigueAmp, long fatigueDurMs,
                            boolean particles, boolean sounds) {
        Location startPos = p.getEyeLocation().clone();
        Vector forward = startPos.getDirection().clone();
        if (forward.lengthSquared() < 1e-6) return;
        forward.normalize();

        double coneHalfRad = (coneAngleDeg / 2.0) * Math.PI / 180.0;

        // Pro Schwung eindeutige Tag-ID (verhindert Mehrfach-Hit pro Entity).
        String swingTag = "ice-swing-hit-" + UUID.randomUUID().toString().substring(0, 8);

        if (sounds) {
            p.getWorld().playSound(startPos, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.4f);
            p.getWorld().playSound(startPos, Sound.BLOCK_SNOW_BREAK, 1.0f, 0.7f);
        }

        final double[] currentDistance = {0.0};
        final double distancePerTick = speed * 0.05;

        DustOptions cyanDust = new DustOptions(Color.fromRGB(180, 230, 255), 1.3f);
        DustOptions whiteDust = new DustOptions(Color.fromRGB(240, 250, 255), 1.2f);

        final int[] taskIdHolder = new int[1];
        Runnable body = new Runnable() {
            @Override
            public void run() {
                currentDistance[0] += distancePerTick;

                // Aktuelle Hitbox-Position.
                Location hitPos = startPos.clone().add(forward.clone().multiply(currentDistance[0]));

                // 1) Partikel an der Hitbox.
                if (particles) {
                    drawSwingParticles(p.getWorld(), hitPos, forward, currentDistance[0] / range,
                                       cyanDust, whiteDust);
                }

                // 2) Cone-Hit-Test.
                double checkRadius = Math.max(2.0, currentDistance[0] * Math.tan(coneHalfRad) + 1.0);
                for (LivingEntity target : p.getWorld().getLivingEntities()) {
                    if (target.getUniqueId().equals(p.getUniqueId())) continue;
                    if (target instanceof Player tp && RelicUtils.isSameTeam(p, tp)) continue;
                    if (target.getLocation().distanceSquared(hitPos) > checkRadius * checkRadius) continue;
                    if (target.getScoreboardTags().contains(swingTag)) continue;

                    Vector toTarget = target.getEyeLocation().toVector().subtract(startPos.toVector());
                    double along = toTarget.dot(forward);
                    if (along < 0 || along > currentDistance[0] + 0.5) continue;
                    Vector perp = toTarget.subtract(forward.clone().multiply(along));
                    double perpLen = perp.length();
                    double angle = Math.atan2(perpLen, along);
                    if (angle > coneHalfRad) continue;

                    target.addScoreboardTag(swingTag);
                    // Direktschaden am Herzen (umgeht Rüstung via MAGIC-Damage)
                    applyHitDamage(target, p, damage);
                    applyIceEffects(target, slowAmp, slowDurMs, fatigueAmp, fatigueDurMs);
                }

                if (sounds && ((int) (currentDistance[0] / distancePerTick)) % 5 == 0) {
                    p.getWorld().playSound(hitPos, Sound.BLOCK_GLASS_BREAK, 0.4f, 1.6f);
                }

                if (currentDistance[0] >= range) {
                    if (sounds) {
                        p.getWorld().playSound(hitPos, Sound.BLOCK_SNOW_BREAK, 0.7f, 1.2f);
                    }
                    activeTasks.remove(p.getUniqueId());
                    Bukkit.getScheduler().runTask(plugin, () -> cleanupSwingTag(swingTag));
                    Bukkit.getScheduler().cancelTask(taskIdHolder[0]);
                }
            }
        };

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, body, 0L, 1L);
        taskIdHolder[0] = task.getTaskId();

        // Alte Task canceln, falls noch aktiv.
        BukkitTask oldTask = activeTasks.remove(p.getUniqueId());
        if (oldTask != null) oldTask.cancel();
        activeTasks.put(p.getUniqueId(), task);
    }

    /**
     * Wendet normalen Vanilla-Schaden an (durch Rüstung reduzierbar).
     * Wir rufen {@code target.damage(amount, attacker)} auf — die normale
     * Damage-Pipeline (EntityDamageByEntityEvent mit Cause.MELEE) läuft
     * durch, Rüstung und Protection-Potions reduzieren wie üblich.
     */
    private void applyHitDamage(LivingEntity target, Player attacker, double damage) {
        if (damage <= 0) return;
        // Vanilla-Damage: läuft durch die Standard-Reduktions-Pipeline
        // (Rüstung, Protection, Absorption, ...). KEIN Magic-Bypass.
        target.damage(damage, attacker);
    }

    /**
     * Wendet Slowness + Mining Fatigue auf das Ziel an (5s Default).
     * Kein Direktschaden — Eisgrab ist ein reiner Debuff-Swing.
     *
     * Bukkit-API: in Paper 1.21+ heißt das Feld SLOWNESS (nicht mehr SLOW),
     * und Mining Fatigue heißt MINING_FATIGUE (nicht mehr SLOW_DIGGING).
     * Wir suchen per Reflection nach dem ersten vorhandenen Namen — Fallback
     * ist ein harter direkter Zugriff (sollte auf der aktuellen Server-Version
     * immer klappen, da wir auf Paper 1.21.4 laufen).
     */
    private void applyIceEffects(LivingEntity target, int slowAmp, long slowDurMs,
                                 int fatigueAmp, long fatigueDurMs) {
        PotionEffectType slowType = resolvePotionType("SLOWNESS");
        PotionEffectType fatigueType = resolvePotionType("MINING_FATIGUE");

        // Slowness (verlangsamt Bewegung).
        target.addPotionEffect(new PotionEffect(
                slowType,
                (int) (slowDurMs / 50L), // ms → ticks
                slowAmp - 1,              // Bukkit: 0 = Level I
                true, false, false));
        // Mining Fatigue (langsameres Abbauen von Blöcken).
        target.addPotionEffect(new PotionEffect(
                fatigueType,
                (int) (fatigueDurMs / 50L),
                fatigueAmp - 1,
                true, false, false));
    }

    /**
     * Sucht einen PotionEffectType anhand mehrerer möglicher Feldnamen (Reflection).
     * Erstes vorhandenes Feld wird zurückgegeben. Wenn keins gefunden wird, wird
     * ein NoSuchFieldError geworfen — der Caller muss den exakten Feldnamen kennen.
     */
    private PotionEffectType resolvePotionType(String primaryName) {
        try {
            return (PotionEffectType) PotionEffectType.class.getField(primaryName).get(null);
        } catch (Throwable t) {
            plugin.getLogger().warning("[IceSwing] Could not find PotionEffectType." + primaryName
                + " — server version may not support this potion effect name.");
            throw new RuntimeException("Missing PotionEffectType." + primaryName, t);
        }
    }

    /**
     * Zeichnet das „Eis-Strahl"-Gefühl an der wandernden Hitbox-Position.
     */
    private void drawSwingParticles(World world, Location hitPos, Vector forward, double t,
                                    DustOptions cyanDust, DustOptions whiteDust) {
        // 1) Zentrum: SWEEP_ATTACK (Vanilla-Schwertschwung) — wie beim Feuerschwert
        world.spawnParticle(Particle.SWEEP_ATTACK, hitPos, 2, 0.0, 0.0, 0.0, 0.0);

        // 2) Schneeflocken-Wolke drumherum (8 Partikel, leicht verteilt)
        for (int i = 0; i < 8; i++) {
            double angle = (Math.PI * 2.0 * i) / 8.0;
            double ox = Math.cos(angle) * 0.6;
            double oz = Math.sin(angle) * 0.6;
            world.spawnParticle(Particle.ITEM_SNOWBALL,
                    hitPos.clone().add(ox, 0.1, oz),
                    1, 0.0, 0.0, 0.0, 0.0);
        }

        // 3) 2× SNOWFLAKE als Knoten (langsam fallende Schneeflocken)
        try {
            world.spawnParticle(Particle.SNOWFLAKE,
                    hitPos.clone().add(0, 0.2, 0),
                    2, 0.1, 0.1, 0.1, 0.02);
        } catch (Throwable ignored) {
            // SNOWFLAKE existiert in manchen Paper-Versionen nicht
        }

        // 4) CYAN-WHITE DUST als leuchtende Kante
        world.spawnParticle(Particle.DUST,
                hitPos.clone().add(0, 0.4, 0),
                4, 0.3, 0.3, 0.3, 0.0, cyanDust);
        world.spawnParticle(Particle.DUST,
                hitPos.clone().add(0, 0.2, 0),
                3, 0.2, 0.2, 0.2, 0.0, whiteDust);

        // 5) Trail nach hinten: kleine Schnee-Spur entlang -forward (5 Partikel)
        for (int i = 1; i <= 5; i++) {
            Location trail = hitPos.clone().subtract(forward.clone().multiply(i * 0.3));
            world.spawnParticle(Particle.ITEM_SNOWBALL,
                    trail.add(0, 0.1, 0),
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * Entfernt den swingTag von allen Entities, die ihn haben.
     */
    private void cleanupSwingTag(String swingTag) {
        for (World w : Bukkit.getWorlds()) {
            for (LivingEntity e : w.getLivingEntities()) {
                if (e.getScoreboardTags().contains(swingTag)) {
                    e.removeScoreboardTag(swingTag);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        BukkitTask task = activeTasks.remove(id);
        if (task != null) task.cancel();
        cooldowns.remove(id);
    }
}
