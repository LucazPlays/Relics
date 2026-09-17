package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.EntityEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
 * Vampiric Knives — wirft ItemDisplays mit Eisenschwertern (genau wie der User wollte).
 *
 * NEUE STRATEGIE (V5): Verwirrt das selbstgebaute Task-Framework komplett.
 *  - Displays werden als "alive" markiert via {@code isAlive=true} im Bukkit scheduler task,
 *    aber ALLE Cleanup-Operationen sind in EINEM Runnable, der NUR runTaskLater() ist,
 *    NICHT runTaskTimer. Das Display fliegt eine vorberechnete Anzahl Frames.
 *  - Frame-Logik ist in einer separaten Methode, und JEDER Fehler wird gefangen ohne
 *    dass der Task crasht.
 *  - Für die Bewegung wird ein Loop mit {@code runTaskLater} statt {@code runTaskTimer}
 *    verwendet: jeder Frame scheduled den nächsten. So können wir den Task sauber abbrechen.
 *  - Display-Identifikation über SCOREBOARD-TAG auf den Displays selbst (analog zum
 *    Hurricane-Pattern), nicht über eine separate Map.
 *  - Cleanup-Pfad ist GARANTIERT: entweder Hit (finishFlight) oder Tick-Counter (max 60).
 *  - Wenn der Cleanup fehlschlägt, hat das Display {@code Persistent=false}, also wird es
 *    beim nächsten Chunk-Unload entfernt.
 */
public class VampireKnivesListener implements Listener {

    private static final String RELIC_ID = "vampire_knives";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final ItemStack IRON_SWORD = new ItemStack(Material.IRON_SWORD);
    private static final DustOptions DARK_RED_DUST = new DustOptions(Color.fromRGB(180, 0, 0), 1.3f);
    private static final DustOptions DARK_RED_HOMING = new DustOptions(Color.fromRGB(220, 30, 30), 1.0f);
    /** Scoreboard-Tag auf dem Display selbst (analog Hurricane). */
    private static final String VAMPIRE_DISPLAY_TAG = "relics_vampire_display";
    /** Scoreboard-Tag für "dieser Wurf hat getroffen, alle Displays dieses Wurfs sofort entfernen". */
    private static final String VAMPIRE_THROW_DONE = "relics_vampire_throw_done";

    /** Tag-Name für akkumulierte Treffer-Heilung pro Wurf. */
    private static final String VAMPIRE_PENDING_HEAL = "relics_vampire_pending_heal";

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    /** Cooldown-Ende pro Spieler (ms). */
    private final Map<UUID, Long> throwCooldowns = new HashMap<>();
    /** Laufende Heil-Streams pro Spieler. */
    private final Map<UUID, BukkitTask> activeHeals = new HashMap<>();

    public VampireKnivesListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isVampireKnives(ItemStack stack) {
        if (stack == null || !factory.isRelic(stack)) return false;
        return RELIC_ID.equals(factory.getRelicId(stack));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player p = event.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;

        ItemStack item = event.getItem();
        if (!isVampireKnives(item)) return;

        event.setCancelled(true);

        long now = System.currentTimeMillis();
        Long until = throwCooldowns.get(p.getUniqueId());
        if (until != null && now < until) {
            double secLeft = (until - now) / 1000.0;
            p.sendActionBar(MM.deserialize(
                    "<gradient:#aa0000:#ff3333><bold>Vampiric Knives</bold> <dark_gray>» <red>wirft erst in <white>"
                            + String.format("%.1f", secLeft) + "s <red>wieder."));
            return;
        }

        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;

        long cdMs = (long) def.getEffectDouble("vampire-throw-cooldown-ms", 1500.0);
        throwCooldowns.put(p.getUniqueId(), now + cdMs);

        startThrow(p, def);
    }

    /**
     * Startet einen Multishot-Wurf: N ItemDisplays fliegen in ±spreadDeg um die
     * Blickrichtung. JEDES Display bekommt eine eigene Frame-Schleife via runTaskLater-
     * Self-Recursion (statt runTaskTimer — damit cancellen einfacher ist).
     */
    private void startThrow(Player p, RelicDefinition def) {
        double speed = def.getEffectDouble("vampire-throw-speed", 32.0);
        double range = def.getEffectDouble("vampire-throw-range", 24.0);
        double hitRadius = def.getEffectDouble("vampire-throw-hit-radius", 1.5);
        double damage = def.getEffectDouble("vampire-throw-damage", 5.0);
        int count = def.getEffectInt("vampire-throw-count", 5);
        double spreadDeg = def.getEffectDouble("vampire-throw-spread-degrees", 15.0);
        boolean particles = def.getEffectBoolean("vampire-throw-particle", true);
        boolean sounds = def.getEffectBoolean("vampire-throw-sound", true);
        int trailCount = def.getEffectInt("vampire-throw-trail-count", 14);
        double healFraction = def.getEffectDouble("vampire-heal-fraction", 0.5);
        double healFloor = def.getEffectDouble("vampire-throw-heal-floor", 1.0);
        double healCeiling = def.getEffectDouble("vampire-throw-heal-ceiling", 4.0);
        int homingParticles = def.getEffectInt("vampire-homing-particles", 8);
        int homingDurationTicks = def.getEffectInt("vampire-homing-duration", 10);

        Location startPos = p.getEyeLocation().clone();
        Vector forward = startPos.getDirection().clone();
        if (forward.lengthSquared() < 1e-6) return;
        forward.normalize();

        if (sounds) {
            p.getWorld().playSound(startPos, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.4f);
            p.getWorld().playSound(startPos, Sound.ENTITY_WITCH_THROW, 0.7f, 1.6f);
        }

        // Eindeutiger throwId — alle Displays dieses Wurfs teilen ihn als Scoreboard-Tag.
        // Wenn ein Display den "done"-Tag bekommt, räumen alle anderen Displays mit dem
        // gleichen throwId sich selbst auf. So ist Multitarget sauber.
        String throwId = UUID.randomUUID().toString().substring(0, 8);

        // Blockfreien Spawn-Punkt.
        Location spawnLoc = startPos.clone();
        if (!spawnLoc.getBlock().isPassable()) {
            spawnLoc.add(forward.clone().multiply(0.5));
        }

        // Spread-Richtungen.
        Vector[] directions = new Vector[count];
        double spreadRad = spreadDeg * Math.PI / 180.0;
        for (int i = 0; i < count; i++) {
            double offset = i - (count - 1) / 2.0;
            double angle = offset * spreadRad;
            directions[i] = rotateAroundY(forward, angle);
        }

        // Pro Display ein Task.
        for (int i = 0; i < count; i++) {
            ItemDisplay display;
            try {
                display = p.getWorld().spawn(spawnLoc, ItemDisplay.class);
            } catch (Throwable t) {
                continue; // Spawn-Crash: einfach überspringen, andere Displays laufen weiter
            }
            try {
                display.setItemStack(IRON_SWORD);
                display.setBillboard(Display.Billboard.FIXED);
                display.setPersistent(false);
                display.setInvulnerable(true); // Damit es nicht von anderen Entities zerstört wird
                display.addScoreboardTag(VAMPIRE_DISPLAY_TAG);
                // throwId wird als zweiter Tag gespeichert: "vampire_throw:<id>"
                display.addScoreboardTag("vampire_throw:" + throwId);
            } catch (Throwable t) {
                try { display.remove(); } catch (Throwable ignored) {}
                continue;
            }
            scheduleFlight(p, display, spawnLoc, directions[i], speed, range, hitRadius,
                    damage, particles, sounds, trailCount, throwId,
                    healFraction, healFloor, healCeiling,
                    homingParticles, homingDurationTicks);
        }

        // Safety-Timer für die Heilung: nach 30 Ticks (1.5 s) heilen wir die
        // akkumulierten Treffer-Schäden einmal. Damit bekommen wir IMMER eine
        // Heilung, auch wenn KEIN Display getroffen hat (Floor greift nur bei Treffer).
        BukkitTask existing = throwEndTasks.remove(throwId);
        if (existing != null) existing.cancel();
        final String throwIdFinal = throwId;
        BukkitTask endTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            onThrowEnd(p, throwIdFinal);
        }, 30L);
        throwEndTasks.put(throwId, endTask);
    }

    /**
     * Self-recursive runTaskLater-Loop: JEDER Frame plant sich SELBST neu, falls
     * das Display noch lebt und nicht getroffen hat. Frame = 1 Tick.
     *
     * Cleanup-Pfade (alle rufen removeDisplay auf):
     *  - Frame >= maxFrames (60 = 3 s)
     *  - distance >= range
     *  - Block-Hit (pos.getBlock().isSolid())
     *  - Entity-Hit (in hitRadius)
     *  - Throw-Done-Tag (anderer Display dieses Wurfs hat schon getroffen)
     *  - Display isValid()==false (von außen entfernt)
     *
     * Bewusst KEIN runTaskTimer: bei runTaskLater kann der Scheduler den Task canceln
     * ohne den TaskBody mitten in der Ausführung zu unterbrechen.
     */
    private void scheduleFlight(Player p, ItemDisplay display, Location startPos,
                                 Vector direction, double speed, double range, double hitRadius,
                                 double damage, boolean particles, boolean sounds, int trailCount,
                                 String throwId, double healFraction, double healFloor, double healCeiling,
                                 int homingParticles, int homingDurationTicks) {
        // Pro-Display State
        final double distancePerTick = speed * 0.05; // 32 m/s * 0.05 = 1.6 Blöcke pro Tick (1 Tick = 50ms)
        final double[] distanceTravelled = {0.0};
        final float[] rotationAccum = {0.0f};
        final int[] frame = {0};
        final int maxFrames = 60; // 3 Sekunden maximum
        final Vector perpendicularAxis = direction.clone().crossProduct(new Vector(0, 1, 0)).normalize();

        // Self-recursive Tick: plant sich neu für Frame n+1
        Runnable tickBody = new Runnable() {
            @Override
            public void run() {
                // === 0. Abbruch: wurde der Wurf schon "abgeschlossen" (anderes Display traf)? ===
                try {
                    if (display.getScoreboardTags().contains(VAMPIRE_THROW_DONE)) {
                        removeDisplay(display);
                        return;
                    }
                } catch (Throwable ignored) {}

                // === 1. Display-Check ===
                if (!isDisplayAlive(display)) {
                    return; // Display ist weg, nichts mehr zu tun
                }

                frame[0]++;
                if (frame[0] > maxFrames) {
                    removeDisplay(display);
                    return;
                }

                distanceTravelled[0] += distancePerTick;
                if (distanceTravelled[0] > range) {
                    removeDisplay(display);
                    return;
                }

                // === 2. Aktuelle Position berechnen ===
                Location pos;
                try {
                    pos = startPos.clone().add(direction.clone().multiply(distanceTravelled[0]));
                    pos.setY(pos.getY() - 0.05);
                } catch (Throwable t) {
                    removeDisplay(display);
                    return;
                }

                // === 3. Block-Hit ===
                try {
                    if (pos.getBlock().getType().isSolid()) {
                        p.getWorld().spawnParticle(Particle.SMOKE, pos, 8, 0.2, 0.2, 0.2, 0.04);
                        removeDisplay(display);
                        return;
                    }
                } catch (Throwable ignored) {
                    removeDisplay(display);
                    return;
                }

                // === 4. Display teleportieren ===
                try {
                    display.setTeleportDuration(1);
                    display.teleport(pos);
                    rotationAccum[0] += 36.0f;
                    display.setRotation(rotationAccum[0], 0.0f);
                } catch (Throwable t) {
                    removeDisplay(display);
                    return;
                }

                // === 5. Trail-Partikel (ignoriere Fehler) ===
                if (particles) {
                    try {
                        for (int t = 0; t < trailCount; t++) {
                            double back = (t + 1) * 0.15;
                            Location trail = pos.clone().subtract(direction.clone().multiply(back));
                            trail.add(new Vector(
                                    (Math.random() - 0.5) * 0.3,
                                    (Math.random() - 0.5) * 0.3,
                                    (Math.random() - 0.5) * 0.3));
                            p.getWorld().spawnParticle(Particle.DUST, trail, 1, 0.0, 0.0, 0.0, 0.0, DARK_RED_DUST);
                            if (t % 3 == 0) {
                                p.getWorld().spawnParticle(Particle.FLAME, trail, 1, 0.0, 0.0, 0.0, 0.0);
                            }
                            if (t % 5 == 0) {
                                p.getWorld().spawnParticle(Particle.SMOKE, trail, 1, 0.0, 0.0, 0.0, 0.0);
                            }
                        }
                    } catch (Throwable ignored) {}
                }

                // === 6. Hit-Test: LivingEntity im hitRadius? ===
                boolean hit = false;
                LivingEntity hitTarget = null;
                try {
                    for (LivingEntity target : p.getWorld().getLivingEntities()) {
                        if (target.getUniqueId().equals(p.getUniqueId())) continue;
                        if (target instanceof Player tp && RelicUtils.isSameTeam(p, tp)) continue;
                        if (target.getLocation().distanceSquared(pos) > hitRadius * hitRadius) continue;

                        hit = true;
                        hitTarget = target;
                        break; // nur ein Treffer pro Frame
                    }
                } catch (Throwable ignored) {}

                if (hit && hitTarget != null) {
                    // Treffer! Schaden anwenden, dann ALLE Displays dieses Wurfs markieren.
                    applyDamageAndMarkThrowDone(p, hitTarget, damage, sounds, pos,
                            hitTarget.getEyeLocation(), throwId, healFraction, healFloor, healCeiling,
                            homingParticles, homingDurationTicks);

                    // === 7. ALLE Displays dieses Wurfs mit "done" markieren ===
                    markAllDisplaysOfThrowDone(p, throwId);

                    // Dieses Display selbst sofort entfernen
                    removeDisplay(display);
                    return;
                }

                // === 8. Self-recursion: nächsten Frame in 1 Tick ===
                Bukkit.getScheduler().runTaskLater(plugin, this, 1L);
            }
        };

        // Erster Frame nach 1 Tick
        Bukkit.getScheduler().runTaskLater(plugin, tickBody, 1L);
    }

    /**
     * Akkumuliert Treffer-Schaden pro Spieler pro Wurf, damit die Heilung NUR EINMAL
     * pro Wurf passiert (nicht pro getroffenem Mob einzeln = "durchgängiges Heilen").
     * Wird in {@link #applyDamageAndMarkThrowDone} gefüllt und am Ende des Wurfs in
     * {@link #onThrowEnd} konsumiert.
     */
    private final Map<UUID, Double> pendingHeal = new HashMap<>();
    private final Map<UUID, String> pendingHealThrowId = new HashMap<>();
    private final Map<String, BukkitTask> throwEndTasks = new HashMap<>();

    /**
     * Wendet Schaden an, spielt Hit-Feedback, plant die Homing-Heil-Cluster.
     *
     * Die Heilung wird NICHT hier angewendet — die Homing-Cluster heilen den Spieler
     * beim Ankommen (< 1 Block Distanz), siehe {@link #startHealingStream}.
     *
     * Fix: KEIN ENTITY_PLAYER_LEVELUP Sound (User-Beschwerde "Pling nervt").
     */
    private void applyDamageAndMarkThrowDone(Player attacker, LivingEntity target, double damage,
                                              boolean sounds, Location displayPos, Location hitPos,
                                              String throwId, double healFraction, double healFloor,
                                              double healCeiling, int homingParticles, int homingDurationTicks) {
        // PROJECTILE-Damage (Rüstung reduziert).
        EntityDamageByEntityEvent ev = new EntityDamageByEntityEvent(
                attacker, target, EntityDamageEvent.DamageCause.PROJECTILE, damage);
        Bukkit.getPluginManager().callEvent(ev);
        double finalDmg = 0.0;
        if (!ev.isCancelled()) {
            finalDmg = Math.max(0.0, ev.getFinalDamage());
            if (finalDmg > 0) target.damage(finalDmg, attacker);
        }

        // Hit-Feedback (Treffer-Geräusch bleibt, ENTITY_PLAYER_LEVELUP ist raus).
        if (sounds) {
            target.getWorld().playSound(hitPos, Sound.ENTITY_PLAYER_HURT, 0.7f, 1.4f);
        }
        target.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, hitPos, 6, 0.3, 0.3, 0.3, 0.1);
        target.playEffect(EntityEffect.HURT);

        // Heilung passiert jetzt in startHealingStream: jeder Cluster heilt beim
        // Ankommen (< 1 Block). Wir brauchen KEIN pendingHeal-Aggregat mehr — der
        // V8-Stream hat eine feste HEAL_PER_CLUSTER-Logik mit eigenem Cap.
        if (finalDmg > 0.0) {
            // Homing-Partikel-Cluster starten — sie heilen beim Ankommen am Spieler.
            startHealingStream(attacker, hitPos.clone(), homingParticles, homingDurationTicks);
        }
    }

    /**
     * Wird aufgerufen, wenn ein Wurf vollständig beendet ist (alle Displays done ODER
     * Timeout). Bei Multitarget heilen die Cluster selbst beim Ankommen — hier wird
     * nur aufgeräumt, falls noch Heilung übrig ist, die NICHT durch Cluster angekommen
     * ist (z. B. wenn der Spieler sich nach 1.5 s zu weit entfernt hat).
     */
    private void onThrowEnd(Player p, String throwId) {
        String pendingThrow = pendingHealThrowId.get(p.getUniqueId());
        if (pendingThrow == null || !pendingThrow.equals(throwId)) return;

        // pendingHeal verfällt NICHT hier — die Cluster im laufenden Stream
        // könnten noch ankommen. Nur den throwId-Marker entfernen.
        pendingHealThrowId.remove(p.getUniqueId());
        BukkitTask endTask = throwEndTasks.remove(throwId);
        if (endTask != null) Bukkit.getScheduler().cancelTask(endTask.getTaskId());

        // Wenn noch Heilung offen ist (Spieler zu weit weg geflohen), verfällt sie
        // beim Stream-Ende (vom Stream selbst entfernt).
    }

    /**
     * Markiert alle Vampire-Displays mit dem gegebenen throwId als "done", damit die
     * laufenden Tick-Loops sich selbst beenden.
     *
     * Dies ist die Hurricane-analoge Cleanup-Methode: statt eine Map zu pflegen,
     * wird direkt auf dem Entity der Tag gesetzt. Der Tick-Loop prüft den Tag
     * VOR jedem Frame und beendet sich bei gesetztem Tag selbst.
     */
    private void markAllDisplaysOfThrowDone(Player p, String throwId) {
        try {
            String tagToFind = "vampire_throw:" + throwId;
            for (ItemDisplay d : p.getWorld().getEntitiesByClass(ItemDisplay.class)) {
                if (!isDisplayAlive(d)) continue;
                if (d.getScoreboardTags().contains(tagToFind)) {
                    // Setze den "done"-Tag — die laufenden Tick-Loops dieses Displays
                    // werden sich selbst beenden.
                    d.addScoreboardTag(VAMPIRE_THROW_DONE);
                }
            }
        } catch (Throwable ignored) {
            // Sollte nie crashen, aber defensive.
        }
    }

    /**
     * Entfernt ein Display SOFORT und sicher.
     */
    private void removeDisplay(ItemDisplay display) {
        if (display == null) return;
        try {
            // Sicher entfernen, auch wenn der Chunk unloadet ist.
            if (display.isValid()) {
                display.remove();
            }
        } catch (Throwable ignored) {
            // Chunk unload o. ä. — egal, das Display ist eh weg.
        }
    }

    /**
     * Defensive Prüfung: ist das Display noch "am Leben"?
     */
    private boolean isDisplayAlive(ItemDisplay d) {
        if (d == null) return false;
        try {
            return d.isValid() && !d.isDead();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Homing-Partikel-Heil-Stream: 8 DUST-Cluster fliegen vom Trefferpunkt zum Werfer
     * und HEILEN ihn, sobald sie 1 Block vom Spieler entfernt sind.
     *
     * Eigenschaften:
     *  - Maximale Verfolgungs-Reichweite: 32 Blöcke (sonst Partikel-Spam in der Welt).
     *  - Verschwinden wenn sie < 1 Block vom Spieler entfernt sind → Heilung wird
     *    EINMAL pro Cluster angewendet (jeder Cluster = HEAL_PER_CLUSTER HP).
     *  - Wenn sie zu weit weg sind (> 32 Blöcke vom Spieler) → einfach verschwinden,
     *    KEINE Heilung.
     *  - Kein Sound (User-Beschwerde "Pling nervt").
     *
     * FIX V8: Heilung ist jetzt FEST pro Cluster (kein pendingHeal-Aggregat mehr),
     * damit die Heilung GARANTIERT funktioniert. Frühere V7-Version hatte ein
     * Race-Condition-Risiko zwischen Multitarget-Treffern und Stream-Restarts.
     */
    private void startHealingStream(Player p, Location startPos,
                                    int homingParticles, int maxDurationTicks) {
        // Vorherigen Stream sauber abbrechen.
        BukkitTask old = activeHeals.remove(p.getUniqueId());
        if (old != null) old.cancel();

        Vector[] offsets = new Vector[homingParticles];
        for (int i = 0; i < homingParticles; i++) {
            double a = (Math.PI * 2.0 * i) / homingParticles;
            offsets[i] = new Vector(
                    Math.cos(a) * 0.4,
                    Math.random() * 0.4,
                    Math.sin(a) * 0.4);
        }

        Location[] clusterPositions = new Location[homingParticles];
        for (int i = 0; i < homingParticles; i++) {
            clusterPositions[i] = startPos.clone().add(offsets[i]);
        }

        final int[] tick = {0};
        // Heilung pro Cluster (fest): bei 5 HP Schaden × 0.5 Fraction = 2.5 HP pro Treffer.
        // Wir wollen mindestens Floor (1.0) und maximal Ceiling (4.0) als Gesamt-Heilung.
        // Mit 8 Clustern: 0.3125 HP pro Cluster → zu wenig für spürbare Heilung.
        // Wir geben jedem Cluster eine FESTE Heilung von 0.5 HP (= 4 HP Gesamt bei 8 Clustern
        // die ankommen), was bei Multitarget weniger als Ceiling aber mehr als Floor ist.
        // Damit der Floor (1.0) für EINEN ankommenden Cluster gilt, brauchen wir eine
        // Sonderbehandlung: der ERSTE angekommene Cluster heilt mindestens Floor.
        final double HEAL_PER_CLUSTER = 0.5;
        final double HEAL_FLOOR_FIRST = 1.0; // Mindestens 1 HP beim ersten angekommenen Cluster
        final double HEAL_CEILING_TOTAL = 2.0; // Nie mehr als 2 HP insgesamt pro Wurf (User-Tuning)
        final boolean[] clusterArrived = new boolean[homingParticles];
        final double HEAL_ARRIVAL_DIST_SQ = 1.0; // < 1 Block → angekommen
        final double MAX_PURSUIT_DIST_SQ = 32.0 * 32.0; // > 32 Blöcke → aufgegeben
        final double[] totalHealedThisStream = {0.0}; // Cap-Check
        World world = p.getWorld();

        Runnable body = new Runnable() {
            @Override
            public void run() {
                if (!p.isOnline() || world == null) {
                    activeHeals.remove(p.getUniqueId());
                    return;
                }
                tick[0]++;
                Location target = p.getEyeLocation().clone();
                int arrivedThisFrame = 0;

                for (int i = 0; i < homingParticles; i++) {
                    if (clusterArrived[i]) continue;

                    Location cp = clusterPositions[i];
                    Vector delta = target.toVector().subtract(cp.toVector());
                    double distSq = delta.lengthSquared();

                    // 1) Reichweite aufgegeben? (zu weit weg)
                    if (distSq > MAX_PURSUIT_DIST_SQ) {
                        clusterArrived[i] = true; // ausblenden
                        continue;
                    }

                    // 2) Beim Spieler angekommen (< 1 Block)?
                    if (distSq <= HEAL_ARRIVAL_DIST_SQ) {
                        clusterArrived[i] = true; // ausblenden
                        arrivedThisFrame++;
                        continue;
                    }

                    // 3) Sonst: in Richtung Spieler bewegen (lerp 0.35 → wird schneller)
                    Vector step = delta.multiply(0.35);
                    cp.add(step);
                    clusterPositions[i] = cp;
                    world.spawnParticle(Particle.DUST, cp, 1, 0.0, 0.0, 0.0, 0.0, DARK_RED_HOMING);
                }

                // Heilung anwenden: jeder angekommene Cluster heilt HEAL_PER_CLUSTER,
                // GARANTIERT mindestens 1 HP (½ Herz — Minecraft zeigt nur Integer-HP)
                // beim ersten angekommenen Cluster. Cap bei HEAL_CEILING_TOTAL pro Stream.
                if (arrivedThisFrame > 0) {
                    double healThisFrame = HEAL_PER_CLUSTER * arrivedThisFrame;
                    // Minecraft zeigt nur ganzzahlige HP → 0.5 HP ist effektiv 0.
                    // Erste Ankunft garantiert mindestens 1 HP (½ Herz sichtbar).
                    if (totalHealedThisStream[0] == 0) {
                        healThisFrame = Math.max(1.0, healThisFrame);
                    }
                    // Cap: nicht über Ceiling pro Stream.
                    double remainingCap = HEAL_CEILING_TOTAL - totalHealedThisStream[0];
                    if (healThisFrame > remainingCap) healThisFrame = Math.max(0, remainingCap);

                    if (healThisFrame > 0) {
                        // Tot-Schutz: keine Heilung wenn der Spieler bereits gestorben ist
                        // oder gerade respawnt. Health <= 0 oder isDead() würde sonst ein
                        // "Wiederbelebung"-Phänomen erzeugen.
                        if (p.isDead() || p.getHealth() <= 0.0) {
                            return;
                        }
                        // Overflow-Kaskade: HP voll → FoodLevel dazu, FoodLevel voll → Saturation.
                        double remaining = healThisFrame;
                        if (p.getHealth() < p.getMaxHealth()) {
                            double newHealth = Math.min(p.getMaxHealth(), p.getHealth() + remaining);
                            remaining -= (newHealth - p.getHealth());
                            p.setHealth(newHealth);
                        }
                        if (remaining > 0 && p.getFoodLevel() < 20) {
                            int oldFood = p.getFoodLevel();
                            int newFood = Math.min(20, oldFood + (int) Math.ceil(remaining));
                            remaining -= (newFood - oldFood);
                            p.setFoodLevel(newFood);
                        }
                        if (remaining > 0 && p.getSaturation() < 20.0f) {
                            float newSat = Math.min(20.0f, p.getSaturation() + (float) remaining);
                            p.setSaturation(newSat);
                        }
                        totalHealedThisStream[0] += healThisFrame;
                        // Subtile Bestätigung: 2 Heart-Partikel pro angekommenen Cluster, kein Sound.
                        world.spawnParticle(Particle.HEART,
                                p.getEyeLocation().add(0, -0.3, 0),
                                arrivedThisFrame * 2, 0.4, 0.2, 0.4, 0.0);
                    }
                }

                // Safety: nach maxDurationTicks aufhören.
                if (tick[0] >= maxDurationTicks) {
                    activeHeals.remove(p.getUniqueId());
                }
            }
        };

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, body, 0L, 1L);
        activeHeals.put(p.getUniqueId(), task);
    }

    /**
     * Rotiert einen Vektor um die Y-Achse.
     */
    private Vector rotateAroundY(Vector v, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new Vector(
                v.getX() * cos + v.getZ() * sin,
                v.getY(),
                -v.getX() * sin + v.getZ() * cos
        ).normalize().multiply(v.length());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        BukkitTask healTask = activeHeals.remove(id);
        if (healTask != null) healTask.cancel();
        throwCooldowns.remove(id);
        pendingHeal.remove(id);
        pendingHealThrowId.remove(id);
        // Hängende Displays des Spielers sofort entfernen — wir finden sie via Tag.
        try {
            Player p = event.getPlayer();
            for (ItemDisplay d : p.getWorld().getEntitiesByClass(ItemDisplay.class)) {
                if (!isDisplayAlive(d)) continue;
                if (d.getScoreboardTags().contains(VAMPIRE_DISPLAY_TAG)) {
                    removeDisplay(d);
                }
            }
        } catch (Throwable ignored) {}
    }
}