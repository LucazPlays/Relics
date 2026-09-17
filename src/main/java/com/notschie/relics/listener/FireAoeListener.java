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
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Feuerschwert: Rechtsklick → ringförmige Feuer-Explosion.
 *
 *  - Kugel mit fire-aoe-radius um den Spieler.
 *  - Alle Mobs + nicht-teamgebundene Spieler in Reichweite: angezündet + Direktschaden am Herzen.
 *  - Partikel-Ringe (FLAME + LAVA + SMOKE) + Sound (BLAZE_SHOOT).
 *  - 12s Cooldown pro Spieler.
 */
public class FireAoeListener implements Listener {

    private static final String RELIC_ID = "sword_of_fire";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public FireAoeListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isFireSword(ItemStack stack) {
        if (stack == null || !factory.isRelic(stack)) return false;
        return RELIC_ID.equals(factory.getRelicId(stack));
    }

    private ItemStack findFireSword(Player p) {
        if (isFireSword(p.getInventory().getItemInMainHand())) {
            return p.getInventory().getItemInMainHand();
        }
        if (isFireSword(p.getInventory().getItemInOffHand())) {
            return p.getInventory().getItemInOffHand();
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        GameMode gm = event.getPlayer().getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

        Player p = event.getPlayer();
        ItemStack item = findFireSword(p);
        if (item == null) return;

        // Vanilla-Rechtsklick unterdrücken.
        event.setCancelled(true);

        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;

        long now = System.currentTimeMillis();
        Long until = cooldowns.get(p.getUniqueId());
        if (until != null && now < until) {
            double secLeft = (until - now) / 1000.0;
            p.sendActionBar(MM.deserialize(
                    "<gradient:#ff2200:#ffaa00><bold>Feuerklinge</bold> <dark_gray>» <red>lädt noch <white>"
                            + String.format("%.1f", secLeft) + "s <red>auf."));
            return;
        }

        int radius = def.getEffectInt("fire-aoe-radius", 5);
        long cdMs = (long) def.getEffectDouble("fire-aoe-cooldown-ms", 12000.0);
        int igniteSec = def.getEffectInt("fire-aoe-ignite-seconds", 6);
        double flatDamage = def.getEffectDouble("fire-aoe-flat-damage", 4.0);
        int particleCount = def.getEffectInt("fire-aoe-particle-count", 60);
        boolean sounds = def.getEffectBoolean("fire-aoe-sound", true);
        boolean stripFireRes = def.getEffectBoolean("fire-aoe-remove-fire-resistance", true);
        // Persistent-Burn: nach dem Sturm folgen weitere AOE-Wellen am Auslöseort.
        int burnRadius = def.getEffectInt("fire-aoe-burn-radius", 10);
        double perTickDmg = def.getEffectDouble("fire-aoe-damage-per-tick", 2.0);
        int burnIterations = def.getEffectInt("fire-aoe-iterations", 10);
        long burnInterval = Math.max(1L, (long) def.getEffectDouble("fire-aoe-tick-interval", 10.0));

        cooldowns.put(p.getUniqueId(), now + cdMs);

        explodeFire(p, radius, igniteSec, flatDamage, particleCount, sounds, stripFireRes,
                burnRadius, perTickDmg, burnIterations, burnInterval);
    }

    private void explodeFire(Player p, int radius, int igniteSec, double flatDamage,
                             int particleCount, boolean sounds, boolean stripFireRes,
                             int burnRadius, double perTickDmg, int burnIterations, long burnInterval) {
        World world = p.getWorld();
        Location c = p.getLocation();

        if (sounds) {
            world.playSound(c, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.8f);
        }

        // ANIMATION: Sturm-Stil-Partikel im vollen Burn-Radius (10 Blöcke).
        // Dauer: 5 Sekunden (= 100 Ticks, Frame alle 2 Ticks = 50 Frames).
        // Während dieser 5s laufen die Damage-Wellen separat alle 1s (5 Wellen).
        startFireStorm(world, c, burnRadius, sounds, 5.0);

        // Initialer AOE-Schaden am Auslöseort im kompakten Partikel-Radius.
        burnAt(world, c, radius, flatDamage, igniteSec, stripFireRes, p);

        // Persistenter Burn-Loop: alle burnInterval-Ticks (default 1s) erneut AOE
        // im großen Burn-Radius am Auslöseort. Läuft burnIterations-mal (=5s gesamt),
        // cancelt sich selbst. Position bleibt am Auslöseort, NICHT am Spieler —
        // wer die Position verlässt, entkommt (strategisches Element).
        startPersistentBurn(world, c, burnRadius, igniteSec, perTickDmg, stripFireRes,
                burnIterations, burnInterval, p, sounds);
    }

    /**
     * Eine einzelne AOE-Welle: alle LivingEntity im Radius werden angezündet
     * (Fire-Resistance gebrochen, falls Flag aktiv) und kriegen normalen
     * Vanilla-Melee-Schaden (Rüstung und Protection reduzieren wie üblich).
     * Self und Team-Spieler werden übersprungen.
     */
    private void burnAt(World world, Location center, int radius, double damage,
                        int igniteSec, boolean stripFireRes, Player owner) {
        double radiusSq = (double) radius * radius;
        for (LivingEntity target : world.getLivingEntities()) {
            if (target.getUniqueId().equals(owner.getUniqueId())) continue;
            if (target instanceof Player tp && RelicUtils.isSameTeam(owner, tp)) continue;
            if (target.getLocation().distanceSquared(center) > radiusSq) continue;

            if (stripFireRes) {
                target.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
            }
            target.setFireTicks(igniteSec * 20);
            // Volle Vanilla-Schadens-Pipeline statt setHealth-Hack: Partikel, Sounds,
            // Knockback und Totem-of-Undying funktionieren damit normal.
            // DamageCause ist ENTITY_ATTACK (vom owner) → Rüstung und Protection
            // reduzieren den Schaden ganz normal. KEIN Magic-Bypass mehr.
            target.damage(damage, owner);
        }
    }

    /**
     * Startet einen Task, der alle burnInterval-Ticks eine AOE-Welle am Auslöseort
     * (NICHT am Spieler) ausführt. Läuft burnIterations-mal und cancelt sich selbst.
     * Zusätzlich werden pro Welle Burst-Partikel im Burn-Radius gespawnt — kein Pentagramm,
     * nur Flammen und Lava, damit es wie ein lodernder Brandherd wirkt.
     */
    private void startPersistentBurn(World world, Location origin, int burnRadius,
                                     int igniteSec, double perTickDmg, boolean stripFireRes,
                                     int iterations, long intervalTicks,
                                     Player owner, boolean sounds) {
        final int[] remaining = {iterations};
        final int[] taskIdHolder = {0};
        Runnable body = new Runnable() {
            @Override
            public void run() {
                // Eine Welle AOE-Schaden am Auslöseort (ortsfest, nicht am Spieler).
                burnAt(world, origin, burnRadius, perTickDmg, igniteSec, stripFireRes, owner);

                // Partikel werden NICHT hier gespawnt — die durchgehende Sturm-Animation
                // (startFireStorm, läuft 5s) übernimmt die Visualisierung der gesamten
                // Brandzone. Hier nur Sound-Feedback.
                if (sounds && remaining[0] % 2 == 0) {
                    world.playSound(origin, Sound.BLOCK_FIRE_AMBIENT, 1.0f, 0.8f);
                }

                if (--remaining[0] <= 0) {
                    // Finaler „Asche"-Burst + Task canceln.
                    world.spawnParticle(Particle.LARGE_SMOKE, origin.clone().add(0, 0.5, 0),
                            40, burnRadius * 0.6, 1.0, burnRadius * 0.6, 0.05);
                    Bukkit.getScheduler().cancelTask(taskIdHolder[0]);
                }
            }
        };
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, body, intervalTicks, intervalTicks);
        taskIdHolder[0] = task.getTaskId();
    }

    /**
     * Spawnt einen lodernden Ring aus FLAME-Partikeln im Burn-Radius.
     * Etwa 2 Partikel pro Block Radius + ein paar LAVA-Knoten.
     */
    private void spawnBurnRing(World world, Location center, int radius) {
        int steps = Math.max(16, radius * 2);
        for (int i = 0; i < steps; i++) {
            double a = (Math.PI * 2.0 * i) / steps;
            double x = Math.cos(a) * radius;
            double z = Math.sin(a) * radius;
            world.spawnParticle(Particle.FLAME,
                    center.clone().add(x, 0.2, z),
                    2, 0.0, 0.0, 0.0, 0.0);
            if (i % 4 == 0) {
                world.spawnParticle(Particle.LAVA,
                        center.clone().add(x, 0.2, z),
                        1, 0.05, 0.05, 0.05, 0.0);
            }
        }
        // Innere Glut
        world.spawnParticle(Particle.SMOKE, center.clone().add(0, 0.3, 0),
                15, radius * 0.4, 0.5, radius * 0.4, 0.02);
    }

    /**
     * Spawnt einen horizontalen Ring von Partikeln auf der Höhe der Location,
     * plus einen vertikalen Ring, damit die "Welle" 3D wirkt.
     */
    private void spawnRing(Location center, int radius, Particle particle, int count) {
        World w = center.getWorld();
        if (w == null) return;
        Vector yAxis = new Vector(0, 1, 0);

        int steps = Math.max(20, count);
        // Horizontaler Ring
        for (int i = 0; i < steps; i++) {
            double angle = (Math.PI * 2.0 * i) / steps;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location loc = center.clone().add(x, 0.2, z);
            w.spawnParticle(particle, loc, 1, 0.0, 0.0, 0.0, 0.0);
        }
        // Vertikaler Ring (Y-Ebene)
        for (int i = 0; i < steps / 2; i++) {
            double angle = (Math.PI * 2.0 * i) / (steps / 2);
            double x = Math.cos(angle) * radius;
            double y = Math.sin(angle) * radius;
            Location loc = center.clone().add(x, y + 0.5, 0);
            w.spawnParticle(particle, loc, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * Startet einen durchgehenden Sturm-Partikel-Task, der die volle Burn-Zone
     * abdeckt. Dauer ist parametrierbar (default 5s = die Dauer der Damage-Phase).
     * Pro Frame (2 Ticks) wird das rotierende Pentagramm + Doppelhelix + Ringe
     * neu gezeichnet, damit die Animation die gesamte Damage-Phase begleitet.
     */
    private void startFireStorm(World world, Location center, int radius, boolean sounds, double durationSeconds) {
        DustOptions orangeDust = new DustOptions(Color.fromRGB(255, 120, 30), 1.6f);

        // Task-ID wird per Trick (final array) festgehalten, damit der Runnable
        // sich selbst canceln kann. Sauberer als Reflection auf BukkitTaskManager.
        final int[] taskIdHolder = new int[1];
        // Anzahl Frames = Dauer / 0.1s (Frame-Intervall = 2 Ticks = 100ms).
        final int maxFrames = Math.max(1, (int) (durationSeconds * 10));
        Runnable body = new Runnable() {
            int frame = 0;
            final double groundY = center.getY();
            @Override
            public void run() {
                // Initial-Burst beim ersten Frame.
                if (frame == 0) {
                    world.spawnParticle(Particle.EXPLOSION, center, 4, 0.6, 0.2, 0.6, 0.0);
                    world.spawnParticle(Particle.FLAME, center, 30, radius * 0.3, 0.5, radius * 0.3, 0.05);
                    if (sounds) {
                        world.playSound(center, Sound.BLOCK_FIRE_AMBIENT, 1.0f, 1.2f);
                    }
                }

                // Soundschleife: alle 3 Frames ein BLAZE-BURN.
                if (sounds && frame % 3 == 0) {
                    world.playSound(center, Sound.ENTITY_BLAZE_BURN, 0.7f, 0.9f);
                }

                double t = frame / (double) maxFrames;
                double rot = frame * (Math.PI / 5.0); // 36° pro Frame — sichtbare Rotation.

                // === PENTAGRAMM am Boden (voller Burn-Radius) ===
                drawPentagram(world, center, radius * 0.95, groundY, rot, orangeDust);

                // === FLAME-RING (rotierend) am vollen Burn-Radius ===
                for (int i = 0; i < 24; i++) {
                    double a = (Math.PI * 2.0 * i) / 24.0 + rot;
                    double x = Math.cos(a) * radius;
                    double z = Math.sin(a) * radius;
                    world.spawnParticle(Particle.FLAME,
                            center.clone().add(x, 0.1, z),
                            1, 0.0, 0.0, 0.0, 0.0);
                }

                // === DOPPELHELIX aufsteigend (voller Burn-Radius) ===
                drawDoubleHelix(world, center, radius * 0.55, groundY, t, rot, orangeDust);

                // === Zusätzlicher innerer Glut-Kern (Match äußere Partikel-Dichte) ===
                // Wir füllen die Mitte mit dichten Flammen + Lava-Knoten auf, damit der
                // Sturm in der Mitte genauso präsent wirkt wie außen.
                for (int i = 0; i < 16; i++) {
                    double a = (Math.PI * 2.0 * i) / 16.0 + rot * 1.5;
                    double x = Math.cos(a) * radius * 0.35;
                    double z = Math.sin(a) * radius * 0.35;
                    world.spawnParticle(Particle.FLAME,
                            center.clone().add(x, 0.2, z),
                            1, 0.0, 0.0, 0.0, 0.0);
                }
                for (int i = 0; i < 8; i++) {
                    double a = (Math.PI * 2.0 * i) / 8.0 + rot * 0.7;
                    double x = Math.cos(a) * radius * 0.7;
                    double z = Math.sin(a) * radius * 0.7;
                    world.spawnParticle(Particle.LAVA,
                            center.clone().add(x, 0.2, z),
                            1, 0.0, 0.0, 0.0, 0.0);
                }

                // === Aufsteigender SMOKE-Schweif (atmosphärisch) ===
                if (frame % 2 == 0) {
                    world.spawnParticle(Particle.SMOKE,
                            center.clone().add(0, 2.0, 0),
                            10, radius * 0.4, 0.6, radius * 0.4, 0.03);
                }

                // Ende: canceln + Final-Burst.
                if (++frame >= maxFrames) {
                    world.spawnParticle(Particle.LARGE_SMOKE,
                            center.clone().add(0, 0.5, 0),
                            25, radius * 0.5, 0.5, radius * 0.5, 0.05);
                    Bukkit.getScheduler().cancelTask(taskIdHolder[0]);
                }
            }
        };
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, body, 0L, 2L);
        taskIdHolder[0] = task.getTaskId();
    }

    /**
     * Zeichnet ein 5-zackiges Pentagramm am Boden (Höhe = groundY).
     * Das Pentagramm ist ein Stern: 5 Linien zwischen den 5 Eckpunkten eines
     * regelmäßigen Fünfecks, jede Ecke überspringt eine Position (z. B. 0→2→4→1→3→0).
     * Die Linien werden durch interpolierte Partikel als FLAME gezeichnet;
     * die Eckpunkte bekommen zusätzlich LAVA und ORANGE DUST für mehr Glow.
     */
    private void drawPentagram(World world, Location center, double radius,
                               double groundY, double rot, DustOptions dustOpts) {
        // 5 Eckpunkte eines regelmäßigen Fünfecks, um 'rot' gedreht.
        double[] xs = new double[5];
        double[] zs = new double[5];
        for (int i = 0; i < 5; i++) {
            double a = (Math.PI * 2.0 * i) / 5.0 - Math.PI / 2.0 + rot;
            xs[i] = Math.cos(a) * radius;
            zs[i] = Math.sin(a) * radius;
        }
        // Stern-Indizes: 0→2→4→1→3→0.
        int[] order = {0, 2, 4, 1, 3, 0};
        for (int k = 0; k < order.length - 1; k++) {
            int from = order[k];
            int to = order[k + 1];
            // Interpoliere 14 Partikel zwischen den beiden Ecken.
            int steps = 14;
            for (int s = 1; s <= steps; s++) {
                double frac = s / (double) steps;
                double x = xs[from] + (xs[to] - xs[from]) * frac;
                double z = zs[from] + (zs[to] - zs[from]) * frac;
                world.spawnParticle(Particle.FLAME,
                        center.clone().add(x, groundY - center.getY() + 0.15, z),
                        1, 0.0, 0.0, 0.0, 0.0);
            }
        }
        // Glow an den Eckpunkten.
        for (int i = 0; i < 5; i++) {
            world.spawnParticle(Particle.LAVA,
                    center.clone().add(xs[i], groundY - center.getY() + 0.2, zs[i]),
                    2, 0.05, 0.05, 0.05, 0.0);
            world.spawnParticle(Particle.DUST,
                    center.clone().add(xs[i], groundY - center.getY() + 0.4, zs[i]),
                    1, 0.0, 0.0, 0.0, 0.0,
                    dustOpts);
        }
    }

    /**
     * Zeichnet zwei gegenläufige Helices, die aus dem Boden aufsteigen.
     * Helix 1: Winkel = t*4 + i*deltaPhi.
     * Helix 2: Winkel = -t*4 + i*deltaPhi + PI.
     * Höhe steigt mit t; Partikelhöhe i*stepY = 0..3 Blöcke.
     */
    private void drawDoubleHelix(World world, Location center, double radius,
                                  double groundY, double t, double rot, DustOptions dustOpts) {
        int heightSteps = 18;       // Anzahl der „Umdrehungen" entlang der Y-Achse
        double helixHeight = 3.0;   // Blöcke
        double helixRotSpeed = 4.0; // rad pro Frame
        double baseAngle = rot * 0.5;
        double groundRel = groundY - center.getY() + 0.2;
        for (int i = 0; i <= heightSteps; i++) {
            double frac = i / (double) heightSteps;
            double y = groundRel + frac * helixHeight;
            // Helix 1
            double a1 = baseAngle + t * helixRotSpeed + i * 0.6;
            double x1 = Math.cos(a1) * radius;
            double z1 = Math.sin(a1) * radius;
            world.spawnParticle(Particle.FLAME,
                    center.clone().add(x1, y, z1),
                    1, 0.0, 0.0, 0.0, 0.0);
            world.spawnParticle(Particle.DUST,
                    center.clone().add(x1, y + 0.1, z1),
                    1, 0.0, 0.0, 0.0, 0.0, dustOpts);
            // Helix 2 (gegenläufig, um PI versetzt)
            double a2 = -baseAngle - t * helixRotSpeed + i * 0.6 + Math.PI;
            double x2 = Math.cos(a2) * radius;
            double z2 = Math.sin(a2) * radius;
            world.spawnParticle(Particle.FLAME,
                    center.clone().add(x2, y, z2),
                    1, 0.0, 0.0, 0.0, 0.0);
            world.spawnParticle(Particle.DUST,
                    center.clone().add(x2, y + 0.1, z2),
                    1, 0.0, 0.0, 0.0, 0.0, dustOpts);
        }
    }
}
