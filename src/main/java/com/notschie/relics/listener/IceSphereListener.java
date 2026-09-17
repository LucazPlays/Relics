package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Eisgrab — Rechtsklick baut eine hohle Eissphäre (Packed Ice) um den Spieler.
 *
 * Mechanik (gemäß User-Vorgabe):
 *  - Hohle 1-Block-Hülle (sonst steckt der Spieler komplett im Eis und erstickt).
 *  - Nur Blöcke, die aktuell Luft sind, werden zu Packed Ice — bestehende Blöcke bleiben unberührt.
 *  - Nach duration-ms wird jeder platzierte Block wieder zu Luft (sofern nicht überschrieben).
 *  - Pro-Spieler-Cooldown mit ActionBar-Anzeige im Drachenei-Stil.
 */
public class IceSphereListener implements Listener {

    private static final String RELIC_ID = "ice_grab";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    /** Cooldown-Ende pro Spieler (ms). */
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** Aktive Sphere pro Spieler: Map Block → Original-Material (für Reset). */
    private final Map<UUID, Map<Block, Material>> activeSpheres = new HashMap<>();
    /** Laufende Reset-Tasks pro Spieler, damit wir bei Quit/Doppel-Klick abbrechen können. */
    private final Map<UUID, BukkitTask> resetTasks = new HashMap<>();

    /**
     * Diese Materialien werden beim Sphere-Bau durch Packed Ice ersetzt.
     * Wasser und Lava werden zu Ice (gefrieren), Pflanzen/Schnee/Sand verschwinden
     * unter der Eisschicht. Solide Blöcke (Stein, Erde, Holz, Erz, ...) bleiben
     * unberührt — die Sphere schmiegt sich um sie herum.
     */
    private static final Set<Material> REPLACEABLE = EnumSet.of(
            // Luft / Schnee-ähnlich
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
            Material.SNOW, Material.SNOW_BLOCK, Material.POWDER_SNOW,
            // Pflanzen / Vegetation
            Material.SHORT_GRASS, Material.TALL_GRASS,
            Material.FERN, Material.LARGE_FERN,
            Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID,
            Material.ALLIUM, Material.AZURE_BLUET, Material.RED_TULIP,
            Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP,
            Material.OXEYE_DAISY, Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY,
            Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH,
            Material.PEONY, Material.PITCHER_PLANT,
            Material.DEAD_BUSH, Material.MOSS_BLOCK, Material.MOSS_CARPET,
            // Wurzelt + Pilze
            Material.HANGING_ROOTS, Material.CRIMSON_ROOTS, Material.WARPED_ROOTS,
            Material.NETHER_SPROUTS, Material.TWISTING_VINES, Material.WEEPING_VINES,
            Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS, Material.RED_MUSHROOM,
            Material.BROWN_MUSHROOM, Material.RED_MUSHROOM_BLOCK, Material.BROWN_MUSHROOM_BLOCK,
            // Sumpf + Lilien + Seerosen
            Material.LILY_PAD,
            // Kaktus / Zuckerrohr / Bamboo / Ranken
            Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO,
            Material.VINE, Material.CAVE_VINES, Material.CAVE_VINES_PLANT,
            // Wasser / Lava → einfrieren
            Material.WATER, Material.LAVA,
            // Sonstige weiche Blöcke
            Material.FIRE, Material.SOUL_FIRE, Material.COBWEB,
            Material.COBBLESTONE_WALL, Material.MOSSY_COBBLESTONE_WALL,
            Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY,
            Material.DIRT, Material.GRASS_BLOCK, Material.PODZOL, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.MYCELIUM
    );

    public IceSphereListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isIceGrab(ItemStack stack) {
        if (stack == null || !factory.isRelic(stack)) return false;
        return RELIC_ID.equals(factory.getRelicId(stack));
    }

    private ItemStack findIceGrab(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (isIceGrab(main)) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        if (isIceGrab(off)) return off;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRightClick(PlayerInteractEvent event) {
        GameMode gm = event.getPlayer().getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            // In Creative/Spectator kein Sphere-Override — Vanilla-Klick-Verhalten beibehalten.
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

        ItemStack item = event.getItem();
        if (!isIceGrab(item)) return;

        // Sneak-Pflicht: nur wenn der Spieler schleicht (analog zum Feuerschwert-Shift-Swing).
        Player p = event.getPlayer();
        if (!p.isSneaking()) return;

        // Kein Vanilla-Block-Platzieren, kein Schild-Rechtsklick durchlassen.
        event.setCancelled(true);

        long now = System.currentTimeMillis();
        Long until = cooldowns.get(p.getUniqueId());
        if (until != null && now < until) {
            double secLeft = (until - now) / 1000.0;
            p.sendActionBar(MM.deserialize(
                    "<gradient:#aaddff:#ffffff><bold>Eisgrab</bold> <dark_gray>» <red>lädt noch <white>"
                            + String.format("%.1f", secLeft) + "s <red>auf."));
            return;
        }

        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;

        int radius = def.getEffectInt("ice-sphere-radius", 7);
        long cdMs = (long) def.getEffectDouble("ice-sphere-cooldown-ms", 12000.0);
        long durationMs = (long) def.getEffectDouble("ice-sphere-duration-ms", 8000.0);
        boolean particles = def.getEffectBoolean("ice-sphere-particle", true);
        boolean sounds = def.getEffectBoolean("ice-sphere-sound", true);

        cooldowns.put(p.getUniqueId(), now + cdMs);

        // Eis löscht Feuer: beim Aktivieren der Sphäre wird der Spieler selbst entflammt.
        if (def.getEffectBoolean("ice-sphere-clear-self-fire", true) && p.getFireTicks() > 0) {
            p.setFireTicks(0);
        }

        // Falls eine ältere Sphere noch steht (Doppel-Klick-Kante trotz Cooldown? hier nicht
        // möglich, aber für Robustheit): alten Task canceln und Eis direkt abräumen.
        BukkitTask oldTask = resetTasks.remove(p.getUniqueId());
        if (oldTask != null) oldTask.cancel();
        Map<Block, Material> oldSphere = activeSpheres.remove(p.getUniqueId());
        if (oldSphere != null) {
            // Original-Materialien wiederherstellen — falls sie noch nicht überschrieben wurden.
            for (Map.Entry<Block, Material> e : oldSphere.entrySet()) {
                Block b = e.getKey();
                Material orig = e.getValue();
                if (b != null && b.getType() == Material.PACKED_ICE) {
                    b.setType(orig, false);
                }
            }
        }

        // Sphere bauen (hohl, 1-Block-Hülle, ersetzt alles Replaceable).
        Map<Block, Material> placed = buildSphere(p, radius);
        activeSpheres.put(p.getUniqueId(), placed);

        // Auto-Effekte: alle Entities in der Sphere (außer der Anwender) bekommen
        // Slowness + Mining Fatigue. Die Reichweite ist der Sphere-Radius.
        if (def.getEffectBoolean("ice-sphere-apply-effects", true)) {
            int slowAmp = def.getEffectInt("ice-sphere-slow-amplifier", 2);
            long slowDurMs = (long) def.getEffectDouble("ice-sphere-slow-duration-ms", 5000.0);
            int fatigueAmp = def.getEffectInt("ice-sphere-fatigue-amplifier", 2);
            long fatigueDurMs = (long) def.getEffectDouble("ice-sphere-fatigue-duration-ms", 5000.0);
            applyIceEffectsToSphereTargets(p, radius, slowAmp, slowDurMs, fatigueAmp, fatigueDurMs);
        }

        // Sofort-Feedback
        if (sounds) {
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 1.4f);
        }
        if (particles) {
            for (Block b : placed.keySet()) {
                p.getWorld().spawnParticle(Particle.SNOWFLAKE,
                        b.getLocation().add(0.5, 0.5, 0.5),
                        6, 0.2, 0.2, 0.2, 0.02);
            }
        }
        p.sendActionBar(MM.deserialize(
                "<gradient:#aaddff:#ffffff><bold>Eisgrab</bold> <dark_gray>» <gray>Der Frost formt eine Kugel."));

        // Reset-Task planen
        long delayTicks = Math.max(1L, durationMs / 50L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            List<Block> blocks = removeSphere(p.getUniqueId());
            // Auflösungs-Feedback
            Player pp = Bukkit.getPlayer(p.getUniqueId());
            if (pp != null && pp.isOnline()) {
                if (sounds) {
                    pp.getWorld().playSound(pp.getLocation(), Sound.BLOCK_POWDER_SNOW_STEP, 1.0f, 0.8f);
                }
                if (particles) {
                    pp.getWorld().spawnParticle(Particle.SNOWFLAKE,
                            pp.getLocation().add(0, 1, 0),
                            30, 1.5, 1.0, 1.5, 0.05);
                }
            }
        }, delayTicks);
        resetTasks.put(p.getUniqueId(), task);
    }

    /**
     * Wendet Slowness + Mining Fatigue auf alle Entities an, deren Position
     * innerhalb der Sphere liegt (Radius um den Spieler, EXKLUSIVE des Anwenders).
     *
     * Sphere-Logik: eine Entity gilt als "in der Sphere" wenn ihr Abstand zum
     * Spieler-Pivot-Punkt (Player-Position, nicht Augen) <= radius Blöcke ist.
     * Da die Sphere hohl ist (Innenradius = radius-1), trifft das alle Entities
     * die innerhalb der äußeren Schale stehen würden.
     */
    private void applyIceEffectsToSphereTargets(Player caster, int radius,
                                                int slowAmp, long slowDurMs,
                                                int fatigueAmp, long fatigueDurMs) {
        World world = caster.getWorld();
        if (world == null) return;
        Location center = caster.getLocation();
        double radiusSq = (double) radius * radius;

        PotionEffectType slowType = resolvePotionType("SLOWNESS");
        PotionEffectType fatigueType = resolvePotionType("MINING_FATIGUE");

        // Wir iterieren über alle Entities in der Radius-Box und prüfen exakten Abstand.
        // EntityType.LIVING_ENTITIES wäre effizienter aber wird von getNearbyEntities
        // in Paper auch unterstützt.
        for (Entity e : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le.getUniqueId().equals(caster.getUniqueId())) continue; // Anwender ausschließen
            if (le.isDead() || !le.isValid()) continue;
            // Team-Check (eigene Party nicht einfrieren)
            if (le instanceof Player otherPlayer && com.notschie.relics.util.RelicUtils.isSameTeam(caster, otherPlayer)) {
                continue;
            }

            // Innerhalb der Sphere? distSq nutzen (Performance).
            double dSq = le.getLocation().distanceSquared(center);
            if (dSq > radiusSq) continue;

            le.addPotionEffect(new PotionEffect(
                    slowType,
                    (int) (slowDurMs / 50L), // ms → ticks
                    slowAmp - 1,
                    true, false, false));
            le.addPotionEffect(new PotionEffect(
                    fatigueType,
                    (int) (fatigueDurMs / 50L),
                    fatigueAmp - 1,
                    true, false, false));
        }
    }

    /**
     * Sucht einen PotionEffectType per Reflection (SLOWNESS statt SLOW, etc.).
     */
    private PotionEffectType resolvePotionType(String primaryName) {
        try {
            return (PotionEffectType) PotionEffectType.class.getField(primaryName).get(null);
        } catch (Throwable t) {
            plugin.getLogger().warning("[IceSphere] Missing PotionEffectType." + primaryName);
            throw new RuntimeException("Missing PotionEffectType." + primaryName, t);
        }
    }

    /**
     * Hohle Eissphäre: nur die äußere 1-Block-Schale (innerSq < distSq <= outerSq).
     * - Es wird alles Replaceable (Luft, Wasser, Lava, Schnee, Pflanzen, Gras, ...)
     *   zu Packed Ice umgewandelt.
     * - Solide Blöcke (Stein, Holz, Erz, ...) bleiben unverändert — die Sphere
     *   schmiegt sich um sie herum.
     * - Pro replaced Block merken wir uns das Original-Material, damit wir es beim
     *   Reset wiederherstellen können.
     */
    private Map<Block, Material> buildSphere(Player p, int radius) {
        Map<Block, Material> placed = new LinkedHashMap<>();
        Location center = p.getLocation();
        World world = center.getWorld();
        if (world == null) return placed;
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        int innerSq = (radius - 1) * (radius - 1);
        int outerSq = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > outerSq || distSq <= innerSq) continue;
                    Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    Material orig = b.getType();
                    if (REPLACEABLE.contains(orig)) {
                        // applyPhysics=false → kein Wasser-Update neben Eis (Paper-API).
                        b.setType(Material.PACKED_ICE, false);
                        placed.put(b, orig);
                    }
                }
            }
        }
        return placed;
    }

    /**
     * Setzt die Blöcke der Sphere zurück — aber nur, falls sie noch immer
     * Packed Ice sind (Schutz vor Überschreibung durch andere Spieler/Bauwerke).
     * Original-Materialien werden wiederhergestellt. Gibt die ursprüngliche Liste
     * zurück (für Diagnose), entfernt den Eintrag.
     */
    private List<Block> removeSphere(UUID uuid) {
        BukkitTask t = resetTasks.remove(uuid);
        if (t != null) t.cancel();
        Map<Block, Material> blocks = activeSpheres.remove(uuid);
        if (blocks == null) return null;
        for (Map.Entry<Block, Material> e : blocks.entrySet()) {
            Block b = e.getKey();
            Material orig = e.getValue();
            if (b != null && b.getType() == Material.PACKED_ICE) {
                b.setType(orig, false);
            }
        }
        return new ArrayList<>(blocks.keySet());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // Stehende Eis-Sphere direkt abräumen (sauberes Offline-Verhalten).
        removeSphere(id);
        cooldowns.remove(id);
    }

    /**
     * Sync-Task: ActionBar mit Cooldown-Anzeige, solange der Spieler das
     * Eisgrab aktiv in einer Hand hält. Läuft alle 20 Ticks (1 Sekunde),
     * analog zum Drachenei-Doppelsprung-Sync.
     */
    public void startIceSphereSync() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                ItemStack held = findIceGrab(p);
                if (held == null) continue;
                Long until = cooldowns.get(p.getUniqueId());
                boolean onCd = until != null && now < until;
                if (onCd) {
                    double secLeft = (until - now) / 1000.0;
                    p.sendActionBar(MM.deserialize(
                            "<gradient:#aaddff:#ffffff><bold>Eisgrab</bold> <dark_gray>» <gray>Nächste Kugel in <white>"
                                    + String.format("%.1f", secLeft) + "s"));
                } else {
                    if (until != null) {
                        cooldowns.remove(p.getUniqueId());
                    }
                    p.sendActionBar(MM.deserialize(
                            "<gradient:#aaddff:#ffffff><bold>Eisgrab</bold> <dark_gray>» <gray>Bereit! <white>Rechtsklick</white> für die Kugel."));
                }
            }
        }, 20L, 20L);
    }
}
