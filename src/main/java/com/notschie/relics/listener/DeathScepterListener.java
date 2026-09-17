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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
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
 * Szepter des Todesreichs (death_scepter) - Mordekaiser inspiriert:
 * - Shift + Rechtsklick: Reich der Toten (Death Realm / 1v1 Domain, 10m Radius für 6s, absolutes 1v1, stiehlt 15% Stats, behält Stats für 15s bei Kill, Cooldown 40s)
 * - Rechtsklick: Auslöschung (Obliterate Slam, 6m Schneise, +100% Schaden bei isoliertem Einzelziel, Cooldown 6s)
 * - Shift + Linksklick: Todesgriff (Death's Grasp, 10m Reichweite, zieht alle Feinde heran + Slowness, Cooldown 12s)
 * - Passiv: Aufsteigende Dunkelheit (Nach 3 Treffern entsteht ein 4m Seelen-Mahlstrom, der kontinuierlich Schaden austeilt und +15% Speed gibt)
 */
public class DeathScepterListener implements Listener {

    private static final String RELIC_ID = "death_scepter";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Long> realmCooldowns = new HashMap<>();
    private final Map<UUID, Long> obliterateCooldowns = new HashMap<>();
    private final Map<UUID, Long> graspCooldowns = new HashMap<>();

    // Passive Darkness Rise Stacks & Active Maelstroms
    private final Map<UUID, Integer> passiveHitStacks = new HashMap<>();
    private final Map<UUID, Long> lastHitTimestamp = new HashMap<>();
    private final Map<UUID, MaelstromSession> activeMaelstroms = new HashMap<>();

    // Active Death Realms
    private static final List<DeathRealm> activeRealms = new ArrayList<>();

    public DeathScepterListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
        startRealmTicker();
    }

    private boolean isDeathScepter(ItemStack item) {
        if (item == null) return false;
        if (factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item))) {
            if (item.getType() == Material.MACE) {
                item.setType(Material.NETHERITE_SWORD);
            }
            return true;
        }
        return false;
    }

    private boolean isDeathScepterInHand(Player player) {
        return isDeathScepter(player.getInventory().getItemInMainHand())
                || isDeathScepter(player.getInventory().getItemInOffHand());
    }

    // Auto-Migration & Mace-Block: Altes Mace-Item wandelt sich sofort in ein Netherite-Schwert
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreDamageMigrate(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker) {
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            if (weapon != null && factory.isRelic(weapon) && RELIC_ID.equals(factory.getRelicId(weapon))) {
                if (weapon.getType() == Material.MACE) {
                    weapon.setType(Material.NETHERITE_SWORD);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItem(event.getNewSlot());
        if (item != null && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item))) {
            if (item.getType() == Material.MACE) {
                item.setType(Material.NETHERITE_SWORD);
            }
        }
    }

    // ==========================================
    // 0. REALM TICKER & BARRIER ENFORCEMENT
    // ==========================================
    private void startRealmTicker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<DeathRealm> iter = activeRealms.iterator();

                while (iter.hasNext()) {
                    DeathRealm realm = iter.next();
                    if (now > realm.expiryTime) {
                        realm.end(false);
                        iter.remove();
                        continue;
                    }

                    World world = realm.center.getWorld();
                    if (world == null) continue;

                    // Barriere-Partikel (Zylinder)
                    Particle.DustOptions tealDust = new Particle.DustOptions(Color.fromRGB(0, 255, 170), 1.3f);
                    Particle.DustOptions darkDust = new Particle.DustOptions(Color.fromRGB(10, 30, 20), 1.5f);

                    for (int i = 0; i < 24; i++) {
                        double angle = (2 * Math.PI / 24) * i;
                        double x = Math.cos(angle) * realm.radius;
                        double z = Math.sin(angle) * realm.radius;
                        for (double y = 0.2; y <= 3.5; y += 1.5) {
                            world.spawnParticle(Particle.DUST, realm.center.getX() + x, realm.center.getY() + y, realm.center.getZ() + z, 1, 0, 0, 0, 0, (i % 2 == 0) ? tealDust : darkDust);
                        }
                    }

                    // Enforce 1v1 Boundaries
                    for (Entity entity : world.getNearbyEntities(realm.center, realm.radius + 6.0, realm.radius + 4.0, realm.radius + 6.0)) {
                        if (entity.equals(realm.caster) || entity.equals(realm.target)) {
                            // Caster & Target dürfen die Sphäre nicht verlassen (Rubberband nach innen)
                            double dist = entity.getLocation().distance(realm.center);
                            if (dist > realm.radius) {
                                Vector snap = realm.center.toVector().subtract(entity.getLocation().toVector()).normalize().multiply(1.1).setY(0.2);
                                entity.setVelocity(snap);
                            }
                        } else if (entity instanceof Player other) {
                            // Externe Spieler dürfen nicht hinein (Repel nach außen)
                            double dist = other.getLocation().distance(realm.center);
                            if (dist < realm.radius) {
                                Vector repel = other.getLocation().toVector().subtract(realm.center.toVector()).normalize().multiply(1.3).setY(0.2);
                                other.setVelocity(repel);
                                other.sendActionBar(MiniMessage.miniMessage().deserialize("<red>⛔ Reich der Toten: Kein Zutritt!</red>"));
                            }
                        } else if (entity instanceof Projectile proj) {
                            // Externe Projektile in der Sphäre vernichten
                            double dist = proj.getLocation().distance(realm.center);
                            if (dist < realm.radius) {
                                proj.remove();
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    // Schutz vor Schaden von außen
    @EventHandler(priority = EventPriority.LOWEST)
    public void onRealmDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        Entity attacker = event.getDamager();

        for (DeathRealm realm : activeRealms) {
            boolean victimIn = victim.equals(realm.caster) || victim.equals(realm.target);
            boolean attackerIn = attacker.equals(realm.caster) || attacker.equals(realm.target);

            if (victimIn != attackerIn) {
                // Einer ist im Reich, der andere draußen -> Schaden blockieren!
                event.setCancelled(true);
                if (attacker instanceof Player p) {
                    p.sendActionBar(MiniMessage.miniMessage().deserialize("<red>⛔ Das Reich der Toten schützt das Duell!</red>"));
                }
                return;
            }
        }
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
        if (!isDeathScepter(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerDeathRealm(player);
        } else {
            triggerObliterate(player);
        }
    }

    // --- A) REICH DER TOTEN (Shift + Rechtsklick) ---
    private void triggerDeathRealm(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = realmCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 40000L;

        if (now - lastUse < cdMs) {
            double remaining = (cdMs - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>👑 Reich der Toten Cooldown: <yellow>" + String.format(Locale.US, "%.1f", remaining) + "s</yellow></red>"
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
            player.sendActionBar(MiniMessage.miniMessage().deserialize("<gray>Kein Ziel für das Reich der Toten anvisiert (max 15m).</gray>"));
            return;
        }

        realmCooldowns.put(uuid, now);

        Location center = player.getLocation().add(target.getLocation()).multiply(0.5);
        world.playSound(center, Sound.ENTITY_WARDEN_ROAR, 1.5f, 0.6f);
        world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.8f, 0.5f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#00ff88:#004422><bold>👑 WILLKOMMEN IM REICH DER TOTEN!</bold></gradient> <gray>(6s Duell)</gray>"
        ));
        if (target instanceof Player tp) {
            tp.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#ff2200:#00ff88><bold>👑 DU WURDEST INS REICH DER TOTEN GEZOGEN!</bold></gradient>"
            ));
        }

        // Stats-Steal: Caster erhält Stärke + Resistenz, Target erhält Schwäche + Slowness
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 120, 0, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 120, 0, false, true, true));
        target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 120, 0, false, true, true));
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 120, 0, false, true, true));

        DeathRealm realm = new DeathRealm(player, target, center, 10.0, now + 6000L);
        activeRealms.add(realm);
    }

    // --- B) AUSLÖSCHUNG (Obliterate Slam - Rechtsklick) ---
    private void triggerObliterate(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = obliterateCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 6000L;

        if (now - lastUse < cdMs) {
            double remaining = (cdMs - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>🔨 Auslöschung Cooldown: <yellow>" + String.format(Locale.US, "%.1f", remaining) + "s</yellow></red>"
            ));
            return;
        }

        obliterateCooldowns.put(uuid, now);

        Location loc = player.getLocation();
        Vector dir = loc.getDirection().setY(0).normalize();
        World world = loc.getWorld();
        if (world == null) return;

        world.playSound(loc, Sound.BLOCK_ANVIL_LAND, 1.4f, 0.6f);
        world.playSound(loc, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 1.5f, 1.1f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#00ff88:#004422><bold>🔨 AUSLÖSCHUNG!</bold></gradient>"
        ));

        // 6 Blöcke lange Schneise
        List<LivingEntity> hitList = new ArrayList<>();
        Particle.DustOptions smashDust = new Particle.DustOptions(Color.fromRGB(0, 255, 150), 1.5f);

        for (double d = 1.0; d <= 6.0; d += 0.8) {
            Location stepLoc = loc.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.DUST, stepLoc.clone().add(0, 0.2, 0), 6, 0.3, 0.1, 0.3, 0, smashDust);
            world.spawnParticle(Particle.BLOCK, stepLoc.clone().add(0, 0.2, 0), 4, 0.2, 0.1, 0.2, 0, Material.OBSIDIAN.createBlockData());

            for (Entity e : world.getNearbyEntities(stepLoc, 1.2, 1.5, 1.2)) {
                if (e instanceof LivingEntity target && !e.getUniqueId().equals(uuid)) {
                    if (target instanceof Player tp && (tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR)) continue;
                    if (RelicUtils.isSameTeam(player, (Player) (target instanceof Player ? target : null))) continue;

                    if (!hitList.contains(target)) {
                        hitList.add(target);
                    }
                }
            }
        }

        // ISOLATIONS-CHECK (GENERGTER MAGIC DAMAGE): 1 Ziel = 8 HP (+100%), mehrere = 4.5 HP
        if (hitList.size() == 1) {
            LivingEntity isolated = hitList.get(0);
            RelicUtils.applyMagicDamage(isolated, player, 8.0);
            isolated.getWorld().playSound(isolated.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.7f);
            isolated.getWorld().spawnParticle(Particle.FLASH, isolated.getLocation().add(0, 1, 0), 2, 0.2, 0.2, 0.2, 0, Color.WHITE);

            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00ff88:#ffffff><bold>👑 ISOLATIONS-TREFFER!</bold></gradient> <yellow>+100% Bonusschaden (8 HP)!</yellow>"
            ));
        } else {
            for (LivingEntity target : hitList) {
                RelicUtils.applyMagicDamage(target, player, 4.5);
            }
        }
    }

    // ==========================================
    // 2. SHIFT + LINKSKLICK: TODESGRIFF (Death's Grasp)
    // ==========================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGraspAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isDeathScepterInHand(player)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = graspCooldowns.getOrDefault(uuid, 0L);
        long cdMs = 12000L;

        if (now - lastUse < cdMs) return;
        graspCooldowns.put(uuid, now);

        Location loc = player.getLocation();
        Vector dir = loc.getDirection().setY(0).normalize();
        World world = loc.getWorld();
        if (world == null) return;

        world.playSound(loc, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.5f, 0.5f);
        world.playSound(loc, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.2f, 1.4f);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#00ff88:#004422><bold>👑 TODESGRIFF ENTFELLSELT!</bold></gradient>"
        ));

        // Klaue zieht bis zu 10 Blöcke weit heran
        double maxDist = 10.0;
        Particle.DustOptions clawDust = new Particle.DustOptions(Color.fromRGB(0, 255, 170), 1.4f);

        for (double d = 1.5; d <= maxDist; d += 1.0) {
            Location pLoc = loc.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.DUST, pLoc.add(0, 0.2, 0), 5, 0.4, 0.2, 0.4, 0, clawDust);
            world.spawnParticle(Particle.SCULK_SOUL, pLoc, 2, 0.2, 0.2, 0.2, 0.02);
        }

        for (Entity e : world.getNearbyEntities(loc.clone().add(dir.clone().multiply(maxDist * 0.5)), maxDist * 0.6, 2.5, maxDist * 0.6)) {
            if (e instanceof LivingEntity target && !e.getUniqueId().equals(uuid)) {
                if (target instanceof Player tp && (tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR)) continue;
                if (RelicUtils.isSameTeam(player, (Player) (target instanceof Player ? target : null))) continue;

                // Heranziehen
                Vector pull = loc.toVector().subtract(target.getLocation().toVector()).normalize().multiply(1.25).setY(0.32);
                target.setVelocity(pull);

                // 4.0 HP Schaden + Slowness I für 1.5s
                RelicUtils.applyMagicDamage(target, player, 2.5);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0, false, true, true));
                world.playSound(target.getLocation(), Sound.BLOCK_CHAIN_FALL, 1.2f, 1.6f);
            }
        }
    }

    // ==========================================
    // 3. PASSIV: AUFSTEIGENDE DUNKELHEIT (Darkness Rise)
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isDeathScepter(weapon)) return;

        UUID uuid = attacker.getUniqueId();
        long now = System.currentTimeMillis();

        // Prüfen, ob Treffer-Stacks abgelaufen sind (5s Fenster)
        long lastHit = lastHitTimestamp.getOrDefault(uuid, 0L);
        if (now - lastHit > 5000L) {
            passiveHitStacks.put(uuid, 0);
        }
        lastHitTimestamp.put(uuid, now);

        // Prüfen, ob bereits Mahlstrom aktiv ist -> Dauer auffrischen
        MaelstromSession currentSession = activeMaelstroms.get(uuid);
        if (currentSession != null) {
            currentSession.expiryTime = now + 5000L;
            return;
        }

        int stacks = passiveHitStacks.getOrDefault(uuid, 0) + 1;
        passiveHitStacks.put(uuid, stacks);

        if (stacks >= 3) {
            passiveHitStacks.put(uuid, 0);
            triggerMaelstrom(attacker);
        } else {
            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#00ff88:#004422><bold>👑 Aufsteigende Dunkelheit:</bold></gradient> <green>[" + "◆".repeat(stacks) + "◇".repeat(3 - stacks) + "]</green>"
            ));
            attacker.playSound(attacker.getLocation(), Sound.BLOCK_SCULK_CATALYST_BLOOM, 1.0f, 1.0f + 0.3f * stacks);
        }
    }

    private void triggerMaelstrom(Player player) {
        UUID uuid = player.getUniqueId();
        long expiry = System.currentTimeMillis() + 5000L;

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.4f, 1.3f);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f, 1.8f);

        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0, false, true, true));

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gradient:#00ff88:#ffffff><bold>👑 AUFSTEIGENDE DUNKELHEIT AKTIV!</bold></gradient> <gray>(4m Seelen-Aura + 15% Speed)</gray>"
        ));

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                MaelstromSession session = activeMaelstroms.get(uuid);
                if (!player.isOnline() || session == null || System.currentTimeMillis() > session.expiryTime) {
                    cancel();
                    activeMaelstroms.remove(uuid);
                    return;
                }

                Location pLoc = player.getLocation();
                World world = pLoc.getWorld();
                if (world == null) return;

                // Wirbelnde Partikel im 4m Radius
                Particle.DustOptions soulDust = new Particle.DustOptions(Color.fromRGB(0, 255, 170), 1.2f);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.random() * 2 * Math.PI;
                    double r = 1.0 + Math.random() * 3.0;
                    double x = Math.cos(angle) * r;
                    double z = Math.sin(angle) * r;
                    world.spawnParticle(Particle.DUST, pLoc.getX() + x, pLoc.getY() + 0.3 + Math.random() * 1.5, pLoc.getZ() + z, 1, 0, 0, 0, 0, soulDust);
                }

                // Kontinuierlicher Schaden alle 10 Ticks (0.5s = 1.0 HP / 2 HP pro Sekunde)
                for (Entity e : world.getNearbyEntities(pLoc, 4.0, 2.5, 4.0)) {
                    if (e instanceof LivingEntity target && !e.getUniqueId().equals(uuid)) {
                        if (target instanceof Player tp && (tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR)) continue;
                        if (RelicUtils.isSameTeam(player, (Player) (target instanceof Player ? target : null))) continue;

                        RelicUtils.applyMagicDamage(target, player, 0.5);
                    }
                }
            }
        }.runTaskTimer(plugin, 10L, 10L);

        activeMaelstroms.put(uuid, new MaelstromSession(task, expiry));
    }

    // ==========================================
    // 4. STAT STEAL BEI KILL IM REALM
    // ==========================================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        Iterator<DeathRealm> iter = activeRealms.iterator();

        while (iter.hasNext()) {
            DeathRealm realm = iter.next();
            if (victim.equals(realm.target)) {
                // Target im Reich gestorben! Caster behält gestohlene Stats für 15 Sekunden (300 Ticks)
                Player caster = realm.caster;
                if (caster != null && caster.isOnline()) {
                    caster.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 300, 0, false, true, true));
                    caster.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 300, 0, false, true, true));

                    caster.getWorld().playSound(caster.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.5f, 0.5f);
                    caster.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, caster.getLocation().add(0, 1, 0), 40, 0.5, 0.5, 0.5, 0.2);

                    caster.sendActionBar(MiniMessage.miniMessage().deserialize(
                            "<gradient:#00ff88:#ffffff><bold>👑 SEELE EROBERT!</bold></gradient> <yellow>Gestohlene Macht für 15s erhalten!</yellow>"
                    ));
                }
                realm.end(true);
                iter.remove();
                break;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        realmCooldowns.remove(uuid);
        obliterateCooldowns.remove(uuid);
        graspCooldowns.remove(uuid);
        passiveHitStacks.remove(uuid);
        MaelstromSession session = activeMaelstroms.remove(uuid);
        if (session != null && session.task != null) session.task.cancel();
    }

    private static class DeathRealm {
        final Player caster;
        final LivingEntity target;
        final Location center;
        final double radius;
        final long expiryTime;

        DeathRealm(Player caster, LivingEntity target, Location center, double radius, long expiryTime) {
            this.caster = caster;
            this.target = target;
            this.center = center;
            this.radius = radius;
            this.expiryTime = expiryTime;
        }

        void end(boolean victory) {
            World w = center.getWorld();
            if (w != null) {
                w.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.2f, 0.8f);
            }
        }
    }

    private static class MaelstromSession {
        final BukkitTask task;
        long expiryTime;

        MaelstromSession(BukkitTask task, long expiryTime) {
            this.task = task;
            this.expiryTime = expiryTime;
        }
    }
}
