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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Chronos Taschenuhr (chrono_stopwatch):
 * - Rechtsklick: Temporale Rückspulung (speichert Pos + HP mit Partikel-Uhr, warp nach 5s oder bei 2. Klick)
 * - Shift + Rechtsklick: Stasis-Feld (Packet-basierte Zeitverlangsamung ClientboundTickingStatePacket auf 6 TPS)
 * - Passiv: Notfall-Zeitsprung bei tödlichem Schaden (<= 2 Herzen) mit Regeneration II
 */
public class ChronoStopwatchListener implements Listener {

    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    // Cooldowns
    private final Map<UUID, Long> recallCooldowns = new HashMap<>();
    private final Map<UUID, Long> bubbleCooldowns = new HashMap<>();
    private final Map<UUID, Long> cheatDeathCooldowns = new HashMap<>();

    // Active Recall Anchors
    private final Map<UUID, RecallAnchor> activeAnchors = new HashMap<>();

    // Rolling Position History for Cheat-Death (Player UUID -> list of last locations)
    private final Map<UUID, Deque<Location>> positionHistory = new HashMap<>();

    // Packet Reflection Cache
    private static Class<?> packetClass = null;
    private static Constructor<?> packetConstructor = null;
    private static Method getHandleMethod = null;
    private static Field connectionField = null;
    private static Method sendMethod = null;
    private static boolean packetAvailable = false;

    static {
        try {
            packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundTickingStatePacket");
            packetConstructor = packetClass.getConstructor(float.class, boolean.class);

            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            getHandleMethod = craftPlayerClass.getMethod("getHandle");

            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            connectionField = serverPlayerClass.getField("connection");

            Class<?> connectionClass = connectionField.getType();
            for (Method m : connectionClass.getMethods()) {
                if (m.getName().equals("send") && m.getParameterCount() == 1) {
                    sendMethod = m;
                    break;
                }
            }
            packetAvailable = (packetConstructor != null && sendMethod != null);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[Relics] ClientboundTickingStatePacket reflection: " + t.getMessage());
        }
    }

    public ChronoStopwatchListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
        startHistoryTracker();
    }

    private static void sendTickingState(Player player, float tickRate, boolean isFrozen) {
        if (!packetAvailable || player == null || !player.isOnline()) return;
        try {
            Object packet = packetConstructor.newInstance(tickRate, isFrozen);
            Object serverPlayer = getHandleMethod.invoke(player);
            Object connection = connectionField.get(serverPlayer);
            sendMethod.invoke(connection, packet);
        } catch (Throwable ignored) {}
    }

    private void startHistoryTracker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (hasStopwatch(player)) {
                        Deque<Location> history = positionHistory.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
                        history.addLast(player.getLocation().clone());
                        // 10 snapshots a 5 Ticks = 50 Ticks = 2.5 Sekunden History
                        if (history.size() > 10) {
                            history.removeFirst();
                        }
                    } else {
                        positionHistory.remove(player.getUniqueId());
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 5L);
    }

    private boolean isStopwatch(ItemStack item) {
        if (item == null || item.getType() != Material.CLOCK) return false;
        String id = factory != null ? factory.getRelicId(item) : null;
        return "chrono_stopwatch".equals(id);
    }

    private boolean hasStopwatch(Player player) {
        if (isStopwatch(player.getInventory().getItemInMainHand())) return true;
        if (isStopwatch(player.getInventory().getItemInOffHand())) return true;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isStopwatch(item)) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!isStopwatch(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerChronoBubble(player);
        } else {
            triggerRecall(player);
        }
    }

    // ==========================================
    // 1. RECALL ABILITY (Rechtsklick)
    // ==========================================
    private void triggerRecall(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Prüfen, ob bereits ein Anchor aktiv ist -> dann 2. Klick = sofortiges Rückspulen
        RecallAnchor anchor = activeAnchors.get(uuid);
        if (anchor != null) {
            executeRecallTeleport(player, anchor);
            return;
        }

        // Cooldown prüfen (28s)
        long lastUse = recallCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 28000L;
        if (now - lastUse < cdMs) {
            double remaining = (cdMs - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>⏳ Rückspulung Cooldown: <yellow>" + String.format(Locale.US, "%.1f", remaining) + "s</yellow></red>"
            ));
            return;
        }

        // 1. Klick: Anchor setzen
        Location loc = player.getLocation().clone();
        double health = player.getHealth();

        RecallAnchor newAnchor = new RecallAnchor(loc, health, now + 5000L);
        activeAnchors.put(uuid, newAnchor);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#ffd700:#00ffff><bold>⏳ Zeitanker gesetzt!</bold></gradient> <gray>Klicke erneut zum Zurückspulen (5s)...</gray>"
        ));
        player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_HAT, 1.5f, 2.0f);
        player.playSound(loc, Sound.BLOCK_BEACON_AMBIENT, 0.8f, 1.5f);

        // Partikel-Task für rotierende Uhr am Boden (KEIN Klon)
        newAnchor.task = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || !activeAnchors.containsKey(uuid)) {
                    cancel();
                    return;
                }

                ticks += 2;
                if (ticks >= 100) { // 5 Sekunden erreicht -> Auto-Warp
                    cancel();
                    if (activeAnchors.containsKey(uuid)) {
                        executeRecallTeleport(player, activeAnchors.get(uuid));
                    }
                    return;
                }

                // Partikel-Uhr am Boden zeichnen
                drawClockAnchor(loc, ticks);

                // Tickendes Geräusch alle 10 Ticks (0.5s)
                if (ticks % 10 == 0) {
                    loc.getWorld().playSound(loc, Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.8f);
                    int remainingSec = (100 - ticks) / 20 + 1;
                    player.sendActionBar(MiniMessage.miniMessage().deserialize(
                            "<gradient:#ffd700:#00ffff><bold>⏳ Zeitanker aktiv!</bold></gradient> <gray>(" + remainingSec + "s verbleibend - Rechtsklick zum Warpen)</gray>"
                    ));
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void drawClockAnchor(Location loc, int ticks) {
        World world = loc.getWorld();
        if (world == null) return;

        double radius = 1.8;
        Particle.DustOptions goldDust = new Particle.DustOptions(Color.fromRGB(255, 215, 0), 0.9f);
        Particle.DustOptions cyanDust = new Particle.DustOptions(Color.fromRGB(0, 220, 255), 0.9f);

        // Äußerer Uhrenring (16 Punkte)
        for (int i = 0; i < 16; i++) {
            double angle = (2 * Math.PI / 16) * i;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            world.spawnParticle(Particle.DUST, loc.getX() + x, loc.getY() + 0.1, loc.getZ() + z, 1, 0, 0, 0, 0, (i % 2 == 0) ? goldDust : cyanDust);
        }

        // Rotierende Zeiger: Minutenzeiger (schnell), Stundenzeiger (langsam)
        double minuteAngle = (ticks * 0.15) % (2 * Math.PI);
        double hourAngle = (ticks * 0.04) % (2 * Math.PI);

        // Minutenzeiger (4 Punkte)
        for (double d = 0.3; d <= 1.4; d += 0.35) {
            world.spawnParticle(Particle.DUST, loc.getX() + Math.cos(minuteAngle) * d, loc.getY() + 0.12, loc.getZ() + Math.sin(minuteAngle) * d, 1, 0, 0, 0, 0, cyanDust);
        }
        // Stundenzeiger (3 Punkte)
        for (double d = 0.3; d <= 0.9; d += 0.3) {
            world.spawnParticle(Particle.DUST, loc.getX() + Math.cos(hourAngle) * d, loc.getY() + 0.12, loc.getZ() + Math.sin(hourAngle) * d, 1, 0, 0, 0, 0, goldDust);
        }

        // Zentrum & Aufsteigende Lichtfäden
        world.spawnParticle(Particle.END_ROD, loc.getX(), loc.getY() + 0.2, loc.getZ(), 1, 0.05, 0.1, 0.05, 0.01);
    }

    private void executeRecallTeleport(Player player, RecallAnchor anchor) {
        UUID uuid = player.getUniqueId();
        activeAnchors.remove(uuid);
        if (anchor.task != null) {
            anchor.task.cancel();
        }

        // Cooldown setzen
        recallCooldowns.put(uuid, System.currentTimeMillis());

        Location targetLoc = anchor.location;
        World world = targetLoc.getWorld();

        // Vorherige Partikel
        if (player.getWorld() != null) {
            player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation(), 40, 0.4, 0.8, 0.4, 0.2);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.3f);
        }

        // Teleport
        player.teleport(targetLoc);

        // HP-Wiederherstellung (falls alte HP höher waren)
        double currentHp = player.getHealth();
        if (anchor.health > currentHp) {
            double maxHp = 20.0;
            if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
                maxHp = player.getAttribute(Attribute.MAX_HEALTH).getValue();
            }
            player.setHealth(Math.min(maxHp, anchor.health));
        }

        // Ziel-Partikel & Sounds
        if (world != null) {
            world.spawnParticle(Particle.FLASH, targetLoc.clone().add(0, 1, 0), 2, 0.2, 0.2, 0.2, 0, Color.WHITE);
            world.spawnParticle(Particle.PORTAL, targetLoc.clone().add(0, 1, 0), 50, 0.5, 0.8, 0.5, 0.4);
            world.playSound(targetLoc, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.8f);
            world.playSound(targetLoc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.6f);
        }

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#ffd700:#00ffff><bold>⏳ ZEITRÜCKSPRUNG!</bold></gradient> <gray>Position & HP erfolgreich wiederhergestellt.</gray>"
        ));
    }

    // ==========================================
    // 2. STASIS-FELD (Shift + Rechtsklick)
    // ==========================================
    private void triggerChronoBubble(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        long lastUse = bubbleCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 20000L;
        if (now - lastUse < cdMs) {
            double remaining = (cdMs - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>⏳ Stasis-Feld Cooldown: <yellow>" + String.format(Locale.US, "%.1f", remaining) + "s</yellow></red>"
            ));
            return;
        }

        bubbleCooldowns.put(uuid, now);

        Location center = player.getLocation().clone();
        World world = center.getWorld();
        if (world == null) return;

        world.playSound(center, Sound.BLOCK_BEACON_POWER_SELECT, 1.5f, 0.6f);
        world.playSound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 1.2f, 1.5f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#ffd700:#00ffff><bold>⏳ STASIS-FELD AKTIV!</bold></gradient> <gray>(4s Zeitlupe für Gegner)</gray>"
        ));

        double radius = 6.0;
        int durationTicks = 80; // 4.0 Sekunden
        Set<UUID> slowedPlayers = new HashSet<>();

        new BukkitRunnable() {
            int tickCount = 0;

            @Override
            public void run() {
                tickCount += 2;
                if (tickCount >= durationTicks) {
                    // Ende des Feldes -> allen verlangsamten Spielern Normalzeit zurückgeben
                    for (UUID slowedId : slowedPlayers) {
                        Player p = Bukkit.getPlayer(slowedId);
                        if (p != null && p.isOnline()) {
                            sendTickingState(p, 20.0f, false);
                            p.sendActionBar(MiniMessage.miniMessage().deserialize("<green>⏳ Zeitfluss normalisiert.</green>"));
                        }
                    }
                    slowedPlayers.clear();
                    world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.4f);
                    cancel();
                    return;
                }

                // Sphäre-Partikel zeichnen
                drawBubbleParticles(center, radius, tickCount);

                // Entities prüfen
                Set<UUID> currentlyInside = new HashSet<>();
                for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
                    if (entity.getLocation().distanceSquared(center) > radius * radius) continue;

                    if (entity instanceof Player target && !target.getUniqueId().equals(uuid)) {
                        if (target.getGameMode() == GameMode.CREATIVE || target.getGameMode() == GameMode.SPECTATOR) {
                            continue;
                        }

                        currentlyInside.add(target.getUniqueId());

                        // Wenn neu im Feld: Packet-Slowmotion senden (6 TPS)
                        if (!slowedPlayers.contains(target.getUniqueId())) {
                            slowedPlayers.add(target.getUniqueId());
                            sendTickingState(target, 6.0f, false);
                            target.sendActionBar(MiniMessage.miniMessage().deserialize(
                                    "<gradient:#ff5555:#ffd700><bold>⏳ ZEIT-STASIS!</bold></gradient> <gray>Deine Zeit wurde auf 30% gedrosselt!</gray>"
                            ));
                            target.playSound(target.getLocation(), Sound.BLOCK_CONDUIT_DEACTIVATE, 1.0f, 0.5f);
                        }

                        // Server-seitiges Dampening zur Absicherung gegen Desyncs
                        Vector v = target.getVelocity();
                        target.setVelocity(v.multiply(0.45));
                        target.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 25, 2, false, false, false));
                    } else if (entity instanceof Projectile proj) {
                        // Projektile im Feld abbremsen
                        proj.setVelocity(proj.getVelocity().multiply(0.2));
                    } else if (entity instanceof LivingEntity mob && !(entity instanceof Player)) {
                        mob.setVelocity(mob.getVelocity().multiply(0.3));
                    }
                }

                // Spieler, die das Feld verlassen haben -> Normalzeit wiederherstellen
                Iterator<UUID> iter = slowedPlayers.iterator();
                while (iter.hasNext()) {
                    UUID id = iter.next();
                    if (!currentlyInside.contains(id)) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null && p.isOnline()) {
                            sendTickingState(p, 20.0f, false);
                            p.sendActionBar(MiniMessage.miniMessage().deserialize("<green>⏳ Stasis-Feld verlassen - Zeit normal.</green>"));
                        }
                        iter.remove();
                    }
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void drawBubbleParticles(Location center, double radius, int ticks) {
        World world = center.getWorld();
        if (world == null) return;

        Particle.DustOptions goldDust = new Particle.DustOptions(Color.fromRGB(255, 215, 0), 1.0f);
        Particle.DustOptions cyanDust = new Particle.DustOptions(Color.fromRGB(0, 220, 255), 1.0f);

        // Horizontale rotierende Ringe
        double angleOffset = (ticks * 0.08) % (2 * Math.PI);
        for (int i = 0; i < 20; i++) {
            double angle = (2 * Math.PI / 20) * i + angleOffset;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            world.spawnParticle(Particle.DUST, center.getX() + x, center.getY() + 0.3, center.getZ() + z, 1, 0, 0, 0, 0, goldDust);
        }

        // Schwebende Uhren-Anomalie-Punkte im Innenbereich
        for (int i = 0; i < 3; i++) {
            double rx = (Math.random() - 0.5) * radius * 1.5;
            double ry = Math.random() * 2.5;
            double rz = (Math.random() - 0.5) * radius * 1.5;
            world.spawnParticle(Particle.DUST, center.getX() + rx, center.getY() + ry, center.getZ() + rz, 1, 0, 0, 0, 0, cyanDust);
        }
    }

    // ==========================================
    // 3. PASSIV: NOTFALL-ZEITSPRUNG (Cheat-Death Reflex)
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!hasStopwatch(player)) return;

        double finalDmg = event.getFinalDamage();
        double currentHp = player.getHealth();

        // Löst aus, wenn der Treffer den Spieler auf <= 4.0 HP (2 Herzen) bringen würde
        if (currentHp - finalDmg > 4.0) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastTrigger = cheatDeathCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 90000L; // 90s Cooldown

        if (now - lastTrigger < cdMs) return;

        // Trigger Cheat-Death
        cheatDeathCooldowns.put(uuid, now);

        // Tödlichen Schaden auf 0 setzen
        event.setDamage(0);

        // Position aus der History (~2.5s zurück)
        Deque<Location> history = positionHistory.get(uuid);
        Location rewindLoc = (history != null && !history.isEmpty()) ? history.peekFirst() : player.getLocation();

        // Teleport nach hinten
        if (rewindLoc != null) {
            player.teleport(rewindLoc);
        }

        // Mindest-HP absichern (auf mind. 6 HP / 3 Herzen)
        player.setHealth(Math.max(player.getHealth(), 6.0));

        // Regeneration II für 5 Sekunden (User-Wunsch!)
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1, false, true, true));
        // Bonus: Speed II + Resistance I für 3s
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, 0, false, true, true));

        // Visuelle Effekte & Sound
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.BLOCK_BELL_RESONATE, 2.0f, 1.2f);
        world.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.8f);
        world.spawnParticle(Particle.FLASH, player.getLocation().clone().add(0, 1, 0), 2, 0.3, 0.3, 0.3, 0, Color.WHITE);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().clone().add(0, 1, 0), 35, 0.5, 0.5, 0.5, 0.2);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#ffd700:#ff5555><bold>⏳ NOTFALL-ZEITSPRUNG!</bold></gradient> <gray>Schaden abgewendet + Regeneration aktiv!</gray>"
        ));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        RecallAnchor anchor = activeAnchors.remove(uuid);
        if (anchor != null && anchor.task != null) {
            anchor.task.cancel();
        }
        positionHistory.remove(uuid);
        sendTickingState(event.getPlayer(), 20.0f, false);
    }

    private static class RecallAnchor {
        final Location location;
        final double health;
        final long expiryTime;
        BukkitTask task;

        RecallAnchor(Location location, double health, long expiryTime) {
            this.location = location;
            this.health = health;
            this.expiryTime = expiryTime;
        }
    }
}
