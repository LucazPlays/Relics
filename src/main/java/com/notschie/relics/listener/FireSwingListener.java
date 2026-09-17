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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Feuerschwert-Schwung (Shift+Linksklick): wandernde Cone-Hitbox in Blickrichtung.
 *
 *  - Reichweite: fire-swing-range (Default 16 Blöcke).
 *  - Geschwindigkeit: fire-swing-speed (Default 16 Blöcke/s = 1s Flug).
 *  - Cone: voller Winkel fire-swing-cone-angle (Default 30°), in echte Blickrichtung
 *    (inkl. Y-Komponente — Spieler schaut nach oben/unten → Strahl folgt).
 *  - Schaden: fire-swing-damage (Default 5 HP) Direktschaden am Herzen.
 *  - Pro Entity genau 1 Treffer pro Schwung (Scoreboard-Tag).
 *  - 8s Cooldown pro Spieler (eigene Map).
 *  - Partikel: SWEEP_ATTACK + FLAME + LAVA + ORANGE DUST, mit der Hitbox mitbewegt.
 *  - Sounds: BLAZE_SHOOT beim Start, BLAZE_DEATH beim Ende, FIRE_AMBIENT zwischendurch.
 */
public class FireSwingListener implements Listener {

    private static final String RELIC_ID = "sword_of_fire";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** Aktive Swing-Tasks pro Spieler (zum sauberen Abbrechen). */
    private final Map<UUID, BukkitTask> activeTasks = new HashMap<>();

    public FireSwingListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isFireSword(ItemStack stack) {
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

        // Feuerschwert in der Hand?
        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack off = p.getInventory().getItemInOffHand();
        if (!isFireSword(main) && !isFireSword(off)) return;

        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null || !def.getEffectBoolean("fire-swing-enabled", true)) return;

        // Vanilla-Schwung unterdrücken, damit kein normaler Hit-Schaden ausgelöst wird.
        event.setCancelled(true);

        long now = System.currentTimeMillis();
        Long until = cooldowns.get(p.getUniqueId());
        if (until != null && now < until) {
            double secLeft = (until - now) / 1000.0;
            p.sendActionBar(MM.deserialize(
                    "<gradient:#ff2200:#ffaa00><bold>Feuerklinge</bold> <dark_gray>» <red>Schwung lädt noch <white>"
                            + String.format("%.1f", secLeft) + "s <red>auf."));
            return;
        }

        double range = def.getEffectDouble("fire-swing-range", 16.0);
        double coneAngleDeg = def.getEffectDouble("fire-swing-cone-angle", 30.0);
        double speed = def.getEffectDouble("fire-swing-speed", 8.0);
        double damage = def.getEffectDouble("fire-swing-damage", 9.0);
        long cdMs = (long) def.getEffectDouble("fire-swing-cooldown-ms", 8000.0);
        boolean particles = def.getEffectBoolean("fire-swing-particle", true);
        boolean sounds = def.getEffectBoolean("fire-swing-sound", true);

        cooldowns.put(p.getUniqueId(), now + cdMs);

        startSwing(p, range, coneAngleDeg, speed, damage, particles, sounds);
    }

    /**
     * Startet den wandernden Cone-Schwung in der Blickrichtung des Spielers.
     */
    private void startSwing(Player p, double range, double coneAngleDeg, double speed,
                            double damage, boolean particles, boolean sounds) {
        // Start = Augenposition, Forward = echte 3D-Blickrichtung (inkl. Y).
        Location startPos = p.getEyeLocation().clone();
        Vector forward = startPos.getDirection().clone();
        if (forward.lengthSquared() < 1e-6) return; // Richtung Null → kein Schwung
        forward.normalize();

        double coneHalfRad = (coneAngleDeg / 2.0) * Math.PI / 180.0;

        // Pro Schwung eindeutige Tag-ID (verhindert Mehrfach-Hit pro Entity).
        String swingTag = "fire-swing-hit-" + UUID.randomUUID().toString().substring(0, 8);

        if (sounds) {
            p.getWorld().playSound(startPos, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.0f);
            p.getWorld().playSound(startPos, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.9f);
        }

        final double[] currentDistance = {0.0};
        // Pro Tick: speed (Blöcke/s) * 0.05 (s pro Tick) = speed/20 Blöcke.
        final double distancePerTick = speed * 0.05;

        DustOptions orangeDust = new DustOptions(Color.fromRGB(255, 140, 40), 1.4f);

        // final array trick, damit der Runnable seine eigene Task-ID kennt.
        final int[] taskIdHolder = new int[1];
        Runnable body = new Runnable() {
            @Override
            public void run() {
                currentDistance[0] += distancePerTick;

                // Aktuelle Hitbox-Position.
                Location hitPos = startPos.clone().add(forward.clone().multiply(currentDistance[0]));

                // 1) Partikel an der Hitbox.
                if (particles) {
                    drawSwingParticles(p.getWorld(), hitPos, forward, currentDistance[0] / range, orangeDust);
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
                    applyHitDamage(target, p, damage);
                }

                if (sounds && ((int) (currentDistance[0] / distancePerTick)) % 5 == 0) {
                    p.getWorld().playSound(hitPos, Sound.BLOCK_FIRE_AMBIENT, 0.6f, 1.0f);
                }

                if (currentDistance[0] >= range) {
                    if (sounds) {
                        p.getWorld().playSound(hitPos, Sound.ENTITY_BLAZE_DEATH, 0.7f, 1.4f);
                        p.getWorld().spawnParticle(Particle.LARGE_SMOKE,
                                hitPos.clone().add(0, 0.3, 0), 15, 0.5, 0.5, 0.5, 0.03);
                    }
                    activeTasks.remove(p.getUniqueId());
                    Bukkit.getScheduler().runTask(plugin, () -> cleanupSwingTag(swingTag));
                    Bukkit.getScheduler().cancelTask(taskIdHolder[0]);
                }
            }
        };

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, body, 0L, 1L);
        taskIdHolder[0] = task.getTaskId();

        // Alte Task canceln, falls noch aktiv (z. B. Doppelklick innerhalb Cooldown-Fenster).
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
     * Zeichnet das „Schwert-Strahl"-Gefühl an der wandernden Hitbox-Position.
     * Partikel bewegen sich mit der Hitbox mit.
     */
    private void drawSwingParticles(World world, Location hitPos, Vector forward, double t, DustOptions dustOpts) {
        // 1) Zentrum: SWEEP_ATTACK (Vanilla-Schwertschwung)
        world.spawnParticle(Particle.SWEEP_ATTACK, hitPos, 2, 0.0, 0.0, 0.0, 0.0);

        // 2) Flame-Wolke drumherum (8 Partikel, leicht verteilt)
        for (int i = 0; i < 8; i++) {
            double angle = (Math.PI * 2.0 * i) / 8.0;
            double ox = Math.cos(angle) * 0.6;
            double oz = Math.sin(angle) * 0.6;
            world.spawnParticle(Particle.FLAME,
                    hitPos.clone().add(ox, 0.1, oz),
                    1, 0.0, 0.0, 0.0, 0.0);
        }

        // 3) 2× LAVA als Knoten
        world.spawnParticle(Particle.LAVA,
                hitPos.clone().add(0, 0.2, 0),
                2, 0.1, 0.1, 0.1, 0.0);

        // 4) ORANGE DUST als leuchtende Kante
        world.spawnParticle(Particle.DUST,
                hitPos.clone().add(0, 0.4, 0),
                4, 0.3, 0.3, 0.3, 0.0, dustOpts);

        // 5) Trail nach hinten: kleine FLAME-Spur entlang -forward (5 Partikel)
        for (int i = 1; i <= 5; i++) {
            Location trail = hitPos.clone().subtract(forward.clone().multiply(i * 0.3));
            world.spawnParticle(Particle.FLAME,
                    trail.add(0, 0.1, 0),
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * Entfernt den swingTag von allen Entities, die ihn haben.
     * Wird beim Schwung-Ende aufgerufen, damit der Tag nicht ewig kleben bleibt.
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
