package com.notschie.relics.listener;

import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent;
import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gameplay-Effekte der Relikte.
 *
 * - Schwert des Feuers: zusätzlicher Brand + Extra-Damage beim Treffer
 * - Spitzhacke des Donnerrers: Haste + Speed beim Halten, Blitz beim Treffer, 25% Double-Drop
 * - Schild des Atlas: Schadensreduktion + Resistance beim Tragen
 * - Bogen der Artemis: Extra-Damage, Speed beim Halten
 * - Runan's Hurricane: Multishot + Auto-Refire
 * - Drachenei: Double-Jump (Inventar-Passiv)
 *
 * Außerdem: ActionBar-Hinweis beim Item-Wechsel
 */
public class RelicEffectListener implements Listener {

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Map<UUID, String> lastHeldRelic = new HashMap<>();
    /** Double-Jump Cooldowns pro Spieler (ms). */
    private final Map<UUID, Long> doubleJumpCooldowns = new HashMap<>();
    /** Welche Spieler gerade AllowFlight aktiviert haben wegen Drachenei. */
    private final Map<UUID, Boolean> dragonEggAllowFlight = new HashMap<>();
    /** Chain-Zustand der Hurricane-Auto-Refire-Kette pro Spieler. */
    private final Map<UUID, ChainState> hurricaneChains = new HashMap<>();
    /** Alle Hurricane-Pfeile pro Shooter (UUIDs) — für Homing-Ability. */
    private final Map<UUID, List<UUID>> hurricaneArrows = new HashMap<>();
    /** Homing-Cooldown pro Spieler (ms) — Hurricane-Ability. */
    private final Map<UUID, Long> homingCooldowns = new HashMap<>();
    /** Blitz-Cooldowns pro Spieler (ms) — Spitzhacke des Donnerrers. */
    private final Map<UUID, Long> lightningCooldowns = new HashMap<>();

    private static final MiniMessage MM_HURRICANE = MiniMessage.miniMessage();

    private static final class ChainState {
        int refireTaskId = -1; // -1 = keine Refire-Aufgabe geplant
    }

    // Tag für alle Hurricane-Pfeile (Haupt-, Extra- und Refire-Pfeile):
    // Trifft so ein Pfeil, wird die Auto-Refire-Kette fortgesetzt; geht er daneben, stoppt sie.
    private static final String HURRICANE_ARROW_TAG = "relics_hurricane_arrow";
    // Tag für Extra-/Salvo-Pfeile (kein erneutes Multishot-Event auslösen).
    private static final String HURRICANE_EXTRA_TAG = "relics_hurricane_extra";
    // Tag das GESETZT wird während der Particle-Trail eines Hurricane-Pfeils noch läuft.
    // Homing akzeptiert NUR Pfeile mit diesem Tag — nach Ablauf (15s) wird er entfernt
    // und der Pfeil kann nicht mehr redirected werden (somit ist die Homing-Ability ein
    // timed Buff, kein dauerhaftes One-Shot-Werkzeug).
    private static final String HURRICANE_PARTICLE_TAG = "relics_hurricane_particle";
    // Tag das GESETZT wird nachdem ein Hurricane-Pfeil einmal via Homing redirected wurde.
    // Verhindert dass derselbe Pfeil MEHRFACH redirected werden kann (= keine endlose
    // Homing-Kette auf einen Pfeil, weil Homing-Schaden eh gedeckelt ist).
    private static final String HURRICANE_HOMED_TAG = "relics_hurricane_homed";

    /** Pro Entity kumulierter Homing-Schaden — sobald 30 erreicht wird, setzen wir
     *  den Damage-Event auf 0. So kann man kein Ziel via Homing-Spam oneshotten. */
    private final Map<UUID, Double> homingDamageByTarget = new HashMap<>();
    /** Maximale kumulierte Homing-Schadenspunktzahl pro Entity (User-Vorgabe: 30). */
    private static final double HOMING_DAMAGE_CAP = 30.0;

    public RelicEffectListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    /**
     * Wenn ein Spieler (Nahkampf) ODER ein Pfeil eines Spielers (Bogen) ein Entity
     * trifft, prüfe ob das genutzte Item ein Relikt ist und wende die Effekte an.
     * Gilt für ALLE LivingEntities (Spieler UND Mobs) — nicht nur Spieler.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        // Fähigkeits- und Magieschaden niemals abfangen oder modifizieren
        if (event.getCause() == EntityDamageEvent.DamageCause.MAGIC) return;
        double extra = 0;
        if (event.getDamager() instanceof Player attacker) {
            // Nahkampf: Relikt in der Hand
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            if (weapon == null || !factory.isRelic(weapon)) return;
            String relicId = factory.getRelicId(weapon);
            if (relicId == null) return;
            RelicDefinition def = plugin.getDefinition(relicId);
            if (def == null) return;

            extra = def.getEffectDouble("on-hit-extra-damage", 0);

            // Vampiric Knives: Nahkampf-Lifesteal. Bei jedem Nahkampf-Hit regeneriert
            // der Spieler 0.5 HP. Sichtbar als +1/2 Herz, kein Sound.
            // Greift NUR bei Vampiric-Knives (keine Extra-Effekte bei anderen Relikten).
            // Tot-Schutz: keine Heilung wenn der Spieler bereits gestorben ist.
            // Overflow-Kaskade: HP voll → FoodLevel dazu, FoodLevel voll → Saturation.
            if ("vampire_knives".equals(relicId)) {
                double lifesteal = def.getEffectDouble("vampire-lifesteal-melee", 0.5);
                if (lifesteal > 0 && !attacker.isDead() && attacker.getHealth() > 0.0) {
                    double remaining = lifesteal;
                    // 1) HP auffüllen (bis maxHealth)
                    if (attacker.getHealth() < attacker.getMaxHealth()) {
                        double newHp = Math.min(attacker.getMaxHealth(), attacker.getHealth() + remaining);
                        remaining -= (newHp - attacker.getHealth());
                        attacker.setHealth(newHp);
                        attacker.getWorld().spawnParticle(Particle.HEART,
                                attacker.getEyeLocation().add(0, -0.3, 0),
                                1, 0.0, 0.0, 0.0, 0.0);
                    }
                    // 2) Overflow → FoodLevel (bis 20)
                    if (remaining > 0 && attacker.getFoodLevel() < 20) {
                        int oldFood = attacker.getFoodLevel();
                        int newFood = Math.min(20, oldFood + (int) Math.ceil(remaining));
                        remaining -= (newFood - oldFood);
                        attacker.setFoodLevel(newFood);
                    }
                    // 3) Overflow → Saturation (bis 20)
                    if (remaining > 0 && attacker.getSaturation() < 20.0f) {
                        float oldSat = attacker.getSaturation();
                        float newSat = Math.min(20.0f, oldSat + (float) remaining);
                        attacker.setSaturation(newSat);
                    }
                }
            }

            // Feuer (Schwert des Feuers)
            int igniteSec = def.getEffectInt("on-hit-ignite", 0);
            if (igniteSec > 0 && event.getEntity() instanceof LivingEntity target) {
                target.setFireTicks(igniteSec * 20);
            }

            // Feuerschwert bricht Fire-Resistance: jede getroffene LivingEntity verliert
            // ihren Fire-Resistance-Effekt (no-op wenn nicht vorhanden).
            if (def.getEffectBoolean("on-hit-remove-fire-resistance", true)
                    && event.getEntity() instanceof LivingEntity target) {
                target.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
            }

            // Blitz (Spitzhacke des Donnerrers) — nur bei Nahkampf-Treffer,
            // mit 5s-Cooldown pro Spieler. Schaden: 4 Herzen DURCH Rüstung.
            if (def.getEffectBoolean("on-hit-lightning", false) && event.getEntity() instanceof LivingEntity le) {
                long now = System.currentTimeMillis();
                long cdMs = (long) def.getEffectDouble("lightning-cooldown-ms", 5000.0);
                long last = lightningCooldowns.getOrDefault(attacker.getUniqueId(), 0L);
                if (now - last >= cdMs) {
                    lightningCooldowns.put(attacker.getUniqueId(), now);
                    strikeLightningDamage(le, attacker, def.getEffectDouble("lightning-damage", 8.0));
                }
            }
        } else if (event.getDamager() instanceof AbstractArrow arrow
                && arrow.getShooter() instanceof Player shooter) {
            // Pfeil eines Spielers: Bogen in Haupt- oder Nebenhand prüfen.
            // So gilt der Extra-Schaden von Runan's Hurricane / Artemis auch gegen Mobs.
            ItemStack bow = shooter.getInventory().getItemInMainHand();
            if (!factory.isRelic(bow)) bow = shooter.getInventory().getItemInOffHand();
            if (!factory.isRelic(bow)) return;
            String relicId = factory.getRelicId(bow);
            if (relicId == null) return;
            RelicDefinition def = plugin.getDefinition(relicId);
            if (def == null) return;
            extra = def.getEffectDouble("on-hit-extra-damage", 0);
        }

        // Extra Damage (Nahkampf UND Pfeil)
        if (extra > 0) {
            event.setDamage(event.getDamage() + extra);
        }



        // === HURRICANE-HOMING DAMAGE-CAP ===
        // Wenn der Schaden von einem Homing-redirecteten Hurricane-Pfeil kommt:
        //  1) Schaden halbieren (User-Vorgabe)
        //  2) Pro Ziel kumuliert maximal 30 Schaden (User-Vorgabe). Wenn der Cap
        //     erreicht ist, wird der Damage-Event komplett auf 0 gesetzt —
        //     der Pfeil richtet KEINEN weiteren Schaden an.
        // So kann man mit Homing-Spam kein Ziel oneshotten.
        if (event.getDamager() instanceof AbstractArrow capArrow
                && capArrow.getScoreboardTags().contains(HURRICANE_HOMED_TAG)
                && event.getEntity() instanceof LivingEntity capTarget) {
            // Schaden halbieren
            double halved = event.getDamage() * 0.5;
            event.setDamage(halved);

            // Cap-Tracking pro Ziel
            UUID tid = capTarget.getUniqueId();
            double alreadyDone = homingDamageByTarget.getOrDefault(tid, 0.0);
            double newTotal = alreadyDone + halved;
            if (newTotal > HOMING_DAMAGE_CAP) {
                // Über dem Cap — wie viel Schaden passt noch rein?
                double remaining = HOMING_DAMAGE_CAP - alreadyDone;
                if (remaining <= 0.0) {
                    // Schon voll — KEIN Schaden mehr.
                    event.setDamage(0.0);
                    event.setCancelled(true);
                } else {
                    // Cap erreichen, Rest-Cap als Schaden, Rest canceln.
                    event.setDamage(remaining);
                    homingDamageByTarget.put(tid, HOMING_DAMAGE_CAP);
                }
            } else {
                homingDamageByTarget.put(tid, newTotal);
            }
        }
    }

    /**
     * Visueller Blitz + 8 Schaden (4 Herzen), der NICHT durch Rüstung reduziert
     * wird. Das Event wird normal gecallt (andere Plugins/Schild-Relikt können
     * es sehen/modifizieren), der tatsächliche Schaden geht aber direkt ans Herz.
     */
    private void strikeLightningDamage(LivingEntity target, Player attacker, double damage) {
        target.getWorld().strikeLightningEffect(target.getLocation());
        // MAGIC-Damage via RelicUtils.applyMagicDamage → DamageCause MAGIC → bypassed Rüstung.
        // Plus zusätzliche MAGIC-Partikel als visuelles Feedback, damit klar ist,
        // dass dies kein physischer Schaden ist.
        target.getWorld().spawnParticle(Particle.ENTITY_EFFECT, target.getLocation().add(0, 1.0, 0),
                12, 0.3, 0.5, 0.3, 0.05, Color.fromRGB(180, 220, 255));
        RelicUtils.applyMagicDamage(target, attacker, damage);
    }

    /**
     * Wenn ein Projektil (Pfeil) eine Entity trifft → Extra-Damage + Pierce (Bogen der Artemis).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        Projectile proj = event.getEntity();
        if (!(proj.getShooter() instanceof Player shooter)) return;
        if (!(event.getHitEntity() instanceof LivingEntity target)) return;

        ItemStack bow = shooter.getInventory().getItemInMainHand();
        if (!factory.isRelic(bow)) {
            bow = shooter.getInventory().getItemInOffHand();
            if (!factory.isRelic(bow)) return;
        }
        String relicId = factory.getRelicId(bow);
        if (relicId == null) return;
        RelicDefinition def = plugin.getDefinition(relicId);
        if (def == null) return;

        if (def.getEffectBoolean("on-shoot-pierce", false) && proj instanceof AbstractArrow arrow) {
            // Pfeil geht durch Mobs durch (Piercing-Level 2)
            arrow.setPierceLevel(Math.max(arrow.getPierceLevel(), 2));
        }

        // Hurricane: Nach einem Pfeiltreffer die kurze Unverwundbarkeit des Ziels
        // zurücksetzen, damit die schnellen Salven-Pfeile der Kette nicht von
        // noDamageTicks geschluckt werden (jeder treffende Pfeil verursacht Schaden).
        if (def.getEffectBoolean("reset-no-damage-ticks", false)
                && target instanceof LivingEntity le) {
            le.setNoDamageTicks(0);
        }
    }

    /**
     * Block abbauen mit der Donnerrer-Spitzhacke: 25% Chance auf doppelten Drop.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        if (!factory.isRelic(tool)) return;
        String relicId = factory.getRelicId(tool);
        if (relicId == null) return;
        RelicDefinition def = plugin.getDefinition(relicId);
        if (def == null) return;

        double chance = def.getEffectDouble("on-mine-double-drop-chance", 0);
        if (chance > 0 && event.getBlock().getDrops(tool).size() > 0) {
            if (Math.random() * 100 < chance) {
                for (ItemStack drop : event.getBlock().getDrops(tool)) {
                    HashMap<Integer, ItemStack> leftover = event.getPlayer().getInventory().addItem(drop.clone());
                    leftover.values().forEach(it -> event.getPlayer().getWorld().dropItemNaturally(
                            event.getBlock().getLocation(), it));
                }
            }
        }
    }

    /**
     * Drachenei darf NIEMALS platziert werden (sonst wäre das Item weg).
     * Alle Platzier-/Interaktions-Aktionen mit dem Ei werden gecancelt.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (event.isCancelled()) return;
        ItemStack inHand = event.getItemInHand();
        if (inHand == null) return;
        if (factory.isRelic(inHand) && "dragon_egg".equals(factory.getRelicId(inHand))) {
            event.setCancelled(true);
            return;
        }
        // Locator-Störsender ist ein mechanisches Modul, kein Block — darf
        // ebenfalls nicht platziert werden.
        if (factory.isRelic(inHand) && "waypoint_jammer".equals(factory.getRelicId(inHand))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.isCancelled()) return;
        ItemStack item = event.getItem();
        if (item == null) return;
        if (!factory.isRelic(item)) return;
        if (!"dragon_egg".equals(factory.getRelicId(item))) return;

        // Platzieren über Rechtsklick SOFORT blocken (gilt für Block-Ziele UND Luft)
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR) {
            event.setCancelled(true);
        }
    }

    /**
     * Auch Rechtsklick auf Entities (Armor-Stand, Dorfbewohner, etc.) mit dem Ei blocken.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (event.isCancelled()) return;
        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        if (!factory.isRelic(item) || !"dragon_egg".equals(factory.getRelicId(item))) {
            item = event.getPlayer().getInventory().getItemInOffHand();
            if (!factory.isRelic(item) || !"dragon_egg".equals(factory.getRelicId(item))) return;
        }
        event.setCancelled(true);
    }

    /**
     * Elytra-Item-Schaden (Riss beim Fliegen) wird gecancelt, solange das
     * Drachenei im Inventar ist. Das Elytra bleibt dadurch dauerhaft intakt.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(org.bukkit.event.player.PlayerItemDamageEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        if (hasDragonEgg(p)) {
            event.setCancelled(true);
        }
    }

    /**
     * Schaden am Spieler reduzieren wenn ein Relikt im Inventar ist (Schild des Atlas).
     * Zusätzlich: Kein Fallschaden solange das Drachenei im Inventar ist.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;

        // Fallschaden-Immunität durch das Drachenei (solange im Inventar)
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && hasDragonEgg(p)) {
            event.setCancelled(true);
            return;
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.STARVATION) return;

        int reduction = 0;
        int resistance = 0;
        boolean active = false;
        for (ItemStack stack : p.getInventory().getContents()) {
            if (stack == null || !factory.isRelic(stack)) continue;
            String relicId = factory.getRelicId(stack);
            if (relicId == null) continue;
            RelicDefinition def = plugin.getDefinition(relicId);
            if (def == null) continue;
            int r = def.getEffectInt("held-damage-reduction", 0);
            if (r > 0) { reduction += r; active = true; }
            int res = def.getEffectInt("held-resistance", 0);
            if (res > 0) { resistance = Math.max(resistance, res); active = true; }
        }

        if (!active) return;
        if (reduction > 0) {
            double finalDmg = event.getDamage() * (1.0 - reduction / 100.0);
            event.setDamage(finalDmg);
        }
        if (resistance > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, resistance - 1, true, false));
        }
    }

    /**
     * Atlas-Shield Final-Damage-Cap: wenn ein Spieler mit dem Schild des Atlas
     * Schaden erleidet, wird der FINALE Damage (nach Rüstung/Potions/Protection)
     * auf 4 Herzen (8 HP) gecappt. Iterative Convergence weil
     * {@code setDamage()} nur den RAW-Wert setzt, {@code getFinalDamage()} aber den
     * fertig reduzierten Wert zurückgibt.
     *
     * Priority MONITOR: greift NACH allen anderen Damage-Modifier-Listenern,
     * damit getFinalDamage() den wirklich finalen Damage reflektiert.
     *
     * Ziel: One-Shot-Schutz. Egal wie viel raw damage ankommt — der Spieler
     * verliert nie mehr als cap HP pro Schlag.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamageFinalCap(EntityDamageEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.STARVATION) return;

        // Hat Atlas-Schild?
        RelicDefinition atlasDef = null;
        for (ItemStack stack : p.getInventory().getContents()) {
            if (stack == null || !factory.isRelic(stack)) continue;
            String relicId = factory.getRelicId(stack);
            if ("shield_of_atlas".equals(relicId)) {
                atlasDef = plugin.getDefinition(relicId);
                break;
            }
        }
        if (atlasDef == null) return;

        double cap = atlasDef.getEffectDouble("shield-damage-cap", 8.0); // 4 Herzen
        if (event.getFinalDamage() <= cap) return; // nichts zu tun

        // Iterative Convergence: setDamage() setzt RAW, getFinalDamage() ist FINAL.
        // Rüstungs-Faktor = final / raw. Neuer raw = cap / Faktor → neuer final = cap.
        for (int i = 0; i < 10; i++) {
            double curFinal = event.getFinalDamage();
            if (curFinal <= cap) break;
            double curRaw = event.getDamage();
            if (curRaw <= 0.0001) break;
            double armorFactor = curFinal / curRaw;
            if (armorFactor <= 0) armorFactor = 1.0;
            double newRaw = cap / armorFactor;
            event.setDamage(newRaw);
        }
    }

    // ==================== RUNAN'S HURRICANE ====================

    /**
     * Multishot: Beim Abschuss eines Pfeils aus Runan's Hurricane werden 2 zusätzliche
     * Pfeile links/rechts gespawned (Spread konfigurierbar). Alle 3 Pfeile sind
     * „Hurricane-Pfeile“ (Tag), d. h. jeder Treffer setzt die Auto-Refire-Kette fort.
     *
     * Wichtig: früher via shooter.launchProjectile() gespawnte Pfeile lösten rekursiv
     * {@code ProjectileLaunchEvent} aus und crashten den Server (StackOverflow).
     * Jetzt: über {@code EntityShootBowEvent}, das pro Abschuss nur einmal feuert,
     * und Spawn per World#spawn ohne neues Bow-Event.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter)) return;
        // Vom Plugin gespawnte Salvo/Extra-Pfeile lösen KEIN weiteres Multishot aus.
        if (event.getProjectile() instanceof AbstractArrow proto
                && proto.getScoreboardTags().contains(HURRICANE_EXTRA_TAG)) {
            return;
        }
        ItemStack bow = event.getBow();
        if (bow == null || !factory.isRelic(bow)) return;
        String relicId = factory.getRelicId(bow);
        if (relicId == null) return;
        RelicDefinition def = plugin.getDefinition(relicId);
        if (def == null) return;

        int count = def.getEffectInt("multishot-count", 0);
        if (count <= 1) return;
        double spreadDeg = def.getEffectDouble("multishot-spread-degrees", 15.0);
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;

        // Haupt-Pfeil als Hurricane-Pfeil markieren (Kette kann starten).
        arrow.addScoreboardTag(HURRICANE_ARROW_TAG);
        // 15s Particle-Trail-Fenster (Homing-Trigger).
        arrow.addScoreboardTag(HURRICANE_PARTICLE_TAG);
        // Homing-Tracking: alle Hurricane-Pfeile pro Shooter speichern.
        hurricaneArrows.computeIfAbsent(shooter.getUniqueId(), k -> new ArrayList<>())
                .add(arrow.getUniqueId());

        Vector baseVelocity = arrow.getVelocity();
        double baseSpeed = baseVelocity.length();
        int extraArrows = count - 1;
        Location shootLoc = arrow.getLocation().clone();

        boolean critical = arrow.isCritical();
        double arrowDamage = arrow.getDamage();
        int fireTicks = arrow.getFireTicks();
        int pierce = arrow.getPierceLevel();
        int knockback = arrow.getKnockbackStrength();
        ItemStack arrowItem = arrow.getItemStack() != null ? arrow.getItemStack().clone() : null;

        for (int i = 0; i < extraArrows; i++) {
            boolean right = (i % 2 == 0);
            double angle = spreadDeg * Math.PI / 180.0;
            if (!right) angle = -angle;
            Vector rotated = rotateAroundY(baseVelocity, angle);
            // Direkt per World spawn, NICHT über launchProjectile (sonst neue Events/Rekursion).
            AbstractArrow extra = (AbstractArrow) shootLoc.getWorld().spawnEntity(shootLoc, arrow.getType());
            extra.setVelocity(rotated.normalize().multiply(baseSpeed));
            extra.setShooter(shooter);
            extra.setCritical(critical);
            extra.setDamage(arrowDamage);
            extra.setFireTicks(fireTicks);
            extra.setPierceLevel(pierce);
            try { extra.setKnockbackStrength(knockback); } catch (Throwable ignored) {}
            extra.setPersistent(false);
            if (arrowItem != null) {
                try { extra.setItemStack(arrowItem.clone()); } catch (Throwable ignored) {}
            }
            try { extra.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY); } catch (Throwable ignored) {}
            extra.addScoreboardTag(HURRICANE_ARROW_TAG);
            extra.addScoreboardTag(HURRICANE_EXTRA_TAG);
            // 15s Particle-Trail-Fenster (Homing-Trigger).
            extra.addScoreboardTag(HURRICANE_PARTICLE_TAG);
            // Homing-Tracking: Salvo-Pfeile pro Shooter.
            hurricaneArrows.computeIfAbsent(shooter.getUniqueId(), k -> new ArrayList<>())
                    .add(extra.getUniqueId());
        }
    }

    private Vector rotateAroundY(Vector v, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return new Vector(v.getX() * cos + v.getZ() * sin,
                          v.getY(),
                          -v.getX() * sin + v.getZ() * cos).normalize().multiply(v.length());
    }

    /** Entfernt einen Hurricane-Pfeil aus der Homing-Tracking-Liste des Shooters. */
    private void removeTrackedArrow(UUID shooterId, UUID arrowId) {
        List<UUID> list = hurricaneArrows.get(shooterId);
        if (list == null) return;
        list.remove(arrowId);
        if (list.isEmpty()) hurricaneArrows.remove(shooterId);
    }

    /**
     * Auto-Refire-Kette: Trifft ein Hurricane-Pfeil (egal ob Haupt-, Extra- oder
     * Refire-Pfeil) eine Entity, wird nach kurzer Verzögerung ein neuer 3-Pfeil-
     * Salve-Schuss direkt auf das getroffene Ziel abgefeuert (mittig + links/rechts
     * versetzt, wie Multishot). Die Kette läuft so lange, bis irgendein Hurricane-
     * Pfeil danebengeht (siehe onArrowMiss).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowHit(ProjectileHitEvent event) {
        if (event.getHitEntity() == null) return; // Miss -> onArrowMiss
        Projectile proj = event.getEntity();
        if (!(proj instanceof AbstractArrow arrow)) return;
        if (!arrow.getScoreboardTags().contains(HURRICANE_ARROW_TAG)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;
        if (!(event.getHitEntity() instanceof LivingEntity target)) return;
        // Homing-Tracking: getroffener Pfeil wird bewusst NICHT aus der Liste entfernt.
        // Begr\u00fcndung: Ein erfolgreich getroffener Pfeil ist bereits in der Welt despawnt
        // (oder verschwindet beim Treffer), der Cleanup-Task (alle 5s) findet das selbst her.
        // removeTrackedArrow(shooter.getUniqueId(), arrow.getUniqueId());  -- DEAKTIVIERT

        ItemStack bow = shooter.getInventory().getItemInMainHand();
        if (!factory.isRelic(bow)) bow = shooter.getInventory().getItemInOffHand();
        if (!factory.isRelic(bow)) return;
        RelicDefinition def = plugin.getDefinition(factory.getRelicId(bow));
        if (def == null) return;
        if (!def.getEffectBoolean("auto-refire", false)) return;

        double spreadDeg = def.getEffectDouble("multishot-spread-degrees", 15.0);
        int delayTicks = def.getEffectInt("auto-refire-delay-ticks", 10);
        double maxRange = def.getEffectDouble("auto-refire-max-range", 64.0);
        final ItemStack bowRef = bow;
        final double damageHold = arrow.getDamage();
        final org.bukkit.entity.EntityType arrowType = arrow.getType();
        final UUID shooterId = shooter.getUniqueId();

        // Nur EIN geplanter Refire pro Runde — mehrere Treffer derselben Salve
        // dürfen nicht mehrere Refires stapeln.
        ChainState state = hurricaneChains.computeIfAbsent(shooterId, k -> new ChainState());
        if (state.refireTaskId != -1) return;

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ChainState st = hurricaneChains.get(shooterId);
            if (st != null) st.refireTaskId = -1; // Runde beendet -> bereit für nächste
            if (!shooter.isOnline() || shooter.isDead() || !shooter.isValid()) return;
            if (!target.isValid() || target.isDead()) return;
            // Bogen noch in der Hand?
            ItemStack currentBow = shooter.getInventory().getItemInMainHand();
            if (!factory.isRelic(currentBow) || !factory.getRelicUuid(currentBow).equals(factory.getRelicUuid(bowRef))) return;

            // Reichweiten-Check: Ziel muss noch in shooter.max-range sein,
            // sonst feuert die Salve nicht (verhindert Treffer aus absurd weiter Distanz).
            if (shooter.getLocation().distanceSquared(target.getLocation()) > maxRange * maxRange) return;

            // 3 Pfeile: mittig aufs Ziel + links/rechts versetzt (wie Multishot).
            // Spawn-Punkt NICHT im Kopf (Eye-Location steckt oft im Block und die
            // Pfeile bleiben sofort stecken) → 0.4 Blöcke vor dem Auge in Blickrichtung.
            Location from = shooter.getEyeLocation().add(shooter.getLocation().getDirection().multiply(0.4));
            Location to = target.getEyeLocation();

            // === AIM MIT GRAVITATIONS-LEAD ===
            // Vanilla-Arrows werden von Gravitation (0.05 m/tick²) nach unten gezogen.
            // Bei großen Distanzen trifft ein direkter Aim immer UNTER das Ziel.
            // Wir berechnen den Drop = 0.5 * g * t² und kompensieren den Y-Wert.
            //
            // Annahmen:
            //  - Vanilla-Arrow-Speed: ~3.0 Blöcke/Tick (variabel je nach Bow-Charge, aber
            //    Hurricane-Bow hat Power IV + Infinity + kein Punch → konsistent ~3.0).
            //  - Gravitation: 0.05 m/tick² (Paper-Standard für Arrows).
            //  - Drag: 0.99 pro Tick → wird hier vernachlässigt (Fehler < 0.5 Blöcke bei 64m).
            //
            // Formel:
            //   horizontalDistance = sqrt(dx² + dz²)
            //   t = horizontalDistance / arrowSpeed
            //   drop = 0.5 * 0.05 * t² = 0.025 * t²
            //   aim_y_offset = +drop (wir zielen HÖHER, um den Drop zu kompensieren)
            //
            // Bei 64 Blöcken horizontal:
            //   t = 64 / 3.0 ≈ 21.3 Ticks
            //   drop = 0.025 * 21.3² ≈ 11.4 Blöcke (!)
            // Wir würden also 11 Blöcke über das Ziel zielen — der Pfeil trifft es.
            //
            // WICHTIG: Da wir die `aim`-`-`Magnitude (2.2) als Speed an spawnHurricaneSalvo
            // geben, müssen wir die Speed konsistent halten. Wir lassen die Aim-Magnitude
            // unverändert und kompensieren nur die Y-Richtung.
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);

            final double ARROW_SPEED = 3.0;       // Blöcke pro Tick (Hurricane-Bow: Power IV)
            final double GRAVITY = 0.05;          // m/tick² (Vanilla-Standard für Arrows)

            double timeOfFlight = horizontalDist / ARROW_SPEED;
            double drop = 0.5 * GRAVITY * timeOfFlight * timeOfFlight;

            // Ziel-Punkt mit Gravitations-Lead: y-Komponente nach oben verschieben.
            Location aimTarget = to.clone().add(0, drop, 0);

            Vector aim = aimTarget.toVector().subtract(from.toVector()).normalize().multiply(2.2);
            spawnHurricaneSalvo(shooter, from, aim, arrowType, damageHold, spreadDeg);
        }, delayTicks);
        state.refireTaskId = task.getTaskId();
    }

    /**
     * Der Salven-Spawn-Punkt darf nie in einem soliden Block liegen (sonst bleiben
     * die Pfeile sofort stecken). Falls doch, wird die Position schrittweise nach
     * oben verschoben, bis eine blockfreie Stelle gefunden ist.
     */
    private Location safeArrowSpawn(Location loc) {
        Location candidate = loc.clone();
        for (int i = 0; i < 4; i++) {
            if (candidate.getBlock().getType().isAir()
                    || !candidate.getBlock().getType().isSolid()) {
                return candidate;
            }
            candidate.add(0, 0.5, 0);
        }
        return loc.clone(); // Fallback: Original (sollte nicht passieren)
    }

    /**
     * Ein Hurricane-Pfeil ist daneben gegangen (in Boden/Wand eingeschlagen ohne
     * Entity-Treffer). Die Kette wird NUR beendet, wenn gerade KEIN Refire aus einem
     * Treffer geplant ist — bei einer 3er-Salve gehen oft 1-2 Pfeile daneben, während
     * andere treffen; das darf die Kette nicht abbrechen (sonst „passiert nichts").
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowMiss(ProjectileHitEvent event) {
        if (event.getHitEntity() != null) return;
        Projectile proj = event.getEntity();
        if (!(proj instanceof AbstractArrow arrow)) return;
        if (!arrow.getScoreboardTags().contains(HURRICANE_ARROW_TAG)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;
        // Homing-Tracking: danebengegangener Pfeil wird BEWUSST NICHT entfernt.
        // Wir wollen dass der Spieler auch liegen-gebliebene / gesteckte Pfeile per
        // Homing wieder fliegen lassen kann. Cleanup-Task (alle 5s) r\u00e4umt verwaiste
        // Eintr\u00e4ge auf. removeTrackedArrow(shooter.getUniqueId(), arrow.getUniqueId());

        ChainState state = hurricaneChains.get(shooter.getUniqueId());
        if (state == null) return;
        // Ein Treffer hat bereits einen Refire geplant -> Kette weiterlaufen lassen.
        if (state.refireTaskId != -1) return;
        // Kein Treffer in dieser Runde -> alle Pfeile sind daneben -> Kette beenden.
        hurricaneChains.remove(shooter.getUniqueId());
    }

    // ============ HURRICANE HOMING-ABILITY (Linksklick) ============

    /**
     * Homing-Ability: Linksklick → alle Hurricane-Pfeile des Shooters werden auf
     * das Ziel (1.5×1.5 Hitbox-Raycast in 32m Sichtlinie) umgelenkt mit
     * Gravitations-Lead.
     *
     * Trigger: DREI Events parallel, weil keines zuverlässig alle Fälle abdeckt:
     *  1. {@code PlayerInteractEvent} (LEFT_CLICK_AIR) — feuert bei Linksklick in Luft
     *  2. {@code EntityDamageByEntityEvent} (damager == player) — feuert wenn Spieler
     *     direkt auf eine Entity klickt (sogar ohne Schaden wenn invulnerable)
     *  3. {@code PlayerAnimationEvent} — universeller Arm-Schwingen-Fallback
     *
     * Cooldown: 8 Sekunden (default). Wird NICHT verbraucht wenn keine Pfeile existieren.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClickAir(org.bukkit.event.player.PlayerInteractEvent event) {
        org.bukkit.event.block.Action a = event.getAction();
        if (a != org.bukkit.event.block.Action.LEFT_CLICK_AIR) return;
        tryHoming(event.getPlayer(), "PlayerInteractEvent/LEFT_CLICK_AIR");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeftClickEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        // Wird NUR ausgelöst wenn der Spieler direkt auf eine Entity Rechtsklick macht.
        // Wir wollen LINKSklick auf Entity — also prüfen wir ob es ein Linksklick ist.
        // PlayerInteractEntityEvent feuert bei beiden Klicks.
        Player p = event.getPlayer();
        if (!isHurricaneBowInHand(p)) return;
        // Cooldown-Check IN DIESEM Listener weil sonst kein Logging beim ersten Linksklick.
        UUID pid = p.getUniqueId();
        Long until = homingCooldowns.get(pid);
        long now = System.currentTimeMillis();
        if (until == null || now >= until) {
            plugin.getLogger().info("[Hurricane-Homing] PlayerInteractEntityEvent from " + p.getName() + " — attempting homing");
        }
        tryHoming(p, "PlayerInteractEntityEvent");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeftClickEntityDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        if (!isHurricaneBowInHand(p)) return;
        UUID pid = p.getUniqueId();
        Long until = homingCooldowns.get(pid);
        long now = System.currentTimeMillis();
        if (until == null || now >= until) {
            plugin.getLogger().info("[Hurricane-Homing] EntityDamageByEntityEvent from " + p.getName() + " — attempting homing");
        }
        tryHoming(p, "EntityDamageByEntityEvent");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeftClickAnim(org.bukkit.event.player.PlayerAnimationEvent event) {
        Player p = event.getPlayer();
        if (!isHurricaneBowInHand(p)) return;
        tryHoming(p, "PlayerAnimationEvent");
    }

    /** Helper: ist Hurricane-Bow in Main- oder Offhand? */
    private boolean isHurricaneBowInHand(Player p) {
        return findHurricaneBowInHand(p) != null;
    }

    /**
     * Geteilte Homing-Logik. Wird von allen drei Trigger-Events aufgerufen.
     * Idempotent: Cooldown verhindert Mehrfach-Auslösung innerhalb 8s.
     */
    private void tryHoming(Player p, String source) {
        if (!isHurricaneBowInHand(p)) return;

        RelicDefinition def = plugin.getDefinition("runans_hurricane");
        if (def == null || !def.getEffectBoolean("homing-ability", true)) {
            plugin.getLogger().warning("[Hurricane-Homing] tryHoming called but ability disabled or def null (source=" + source + ")");
            return;
        }

        long cdMs = (long) def.getEffectDouble("homing-cooldown-ms", 8000.0);
        long now = System.currentTimeMillis();
        UUID pid = p.getUniqueId();
        Long until = homingCooldowns.get(pid);
        if (until != null && now < until) {
            double secLeft = (until - now) / 1000.0;
            p.sendActionBar(MM_HURRICANE.deserialize(
                "<gradient:#00ffff:#ff00ff><bold>Hurricane</bold> <dark_gray>» <red>Homing lädt noch <white>"
                + String.format("%.1f", secLeft) + "s <red>auf."));
            return;
        }

        double maxRange = def.getEffectDouble("homing-max-range", 32.0);
        double maxRangeSq = maxRange * maxRange;
        // Konus-Winkel: 30° vom Spieler-Blick aus — wir nehmen das näheste LivingEntity
        // innerhalb dieses Sichtkegels (NICHT die Raycast-Ray weil die bei größeren
        // Mobs wie Zombies (Hitbox 0.6×1.95) zu oft danebentreibt).
        double coneAngleDeg = def.getEffectDouble("homing-cone-angle-deg", 45.0);
        double coneCos = Math.cos(Math.toRadians(coneAngleDeg));
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        LivingEntity target = null;
        double bestDistSq = maxRangeSq;
        for (Entity e : p.getWorld().getNearbyEntities(eye, maxRange, maxRange, maxRange)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le.getUniqueId().equals(pid)) continue;
            if (le.isDead() || !le.isValid()) continue;
            if (!le.getWorld().equals(p.getWorld())) continue;
            if (le instanceof Player tp && RelicUtils.isSameTeam(p, tp)) continue;

            // Distanz Spieler-Augen -> Entity-BBox-Mitte
            Location entLoc = le.getLocation().add(0, le.getHeight() * 0.5, 0);
            double dSq = eye.distanceSquared(entLoc);
            if (dSq > maxRangeSq) continue;
            if (dSq == 0) continue; // eigene Position, kann nicht passieren

            // Konus-Test: Winkel zwischen Blickrichtung und Richtung zum Entity
            Vector toEnt = entLoc.toVector().subtract(eye.toVector());
            double dot = toEnt.normalize().dot(dir);
            if (dot < coneCos) continue; // außerhalb Sichtkegel

            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                target = le;
            }
        }

        List<UUID> arrowIds = hurricaneArrows.get(pid);
        int arrowCount = arrowIds == null ? 0 : arrowIds.size();

        if (target == null) {
            plugin.getLogger().info("[Hurricane-Homing] " + p.getName() + " via " + source
                + " — no target in " + maxRange + "m (arrows tracked: " + arrowCount + ")");
            if (arrowCount > 0) {
                p.sendActionBar(MM_HURRICANE.deserialize(
                    "<gradient:#00ffff:#ff00ff><bold>Hurricane</bold> <dark_gray>» <gray>Kein Ziel in 32m Sichtlinie ("
                    + arrowCount + " Pfeile)."));
            }
            return;
        }

        int redirected = redirectArrowsToTarget(p, target);
        plugin.getLogger().info("[Hurricane-Homing] " + p.getName() + " via " + source
            + " — target=" + target.getName() + " redirected=" + redirected
            + " (tracked before=" + arrowCount + ")");
        if (redirected > 0) {
            homingCooldowns.put(pid, now + cdMs);
            p.sendActionBar(MM_HURRICANE.deserialize(
                "<gradient:#00ffff:#ff00ff><bold>Hurricane</bold> <dark_gray>» <aqua>"
                + redirected + " Pfeile auf <white>" + target.getName() + "<aqua> gelenkt."));
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.5f);
        } else {
            // Keine Pfeile vorhanden → Cooldown nicht verbrauchen.
            p.sendActionBar(MM_HURRICANE.deserialize(
                "<gradient:#00ffff:#ff00ff><bold>Hurricane</bold> <dark_gray>» <gray>Keine Pfeile in der Luft."));
        }
    }

    /** Hurricane-Bow (in Haupt- oder Nebenhand) finden. */
    private ItemStack findHurricaneBowInHand(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (main != null && factory.isRelic(main) && "runans_hurricane".equals(factory.getRelicId(main))) {
            return main;
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off != null && factory.isRelic(off) && "runans_hurricane".equals(factory.getRelicId(off))) {
            return off;
        }
        return null;
    }

    /**
     * Lenkt alle Hurricane-Pfeile des Shooters (fliegende + steckende) auf das Ziel.
     *
     * Strategie: Wir versuchen NICHT, einen toten / in-der-Wand-steckenden Pfeil
     * mit Reflection wiederzubeleben (geht nicht — Vanilla markiert ihn als dead,
     * und selbst wenn man die Flags umgeht, würde der Pfeil keine Pfeil-Hitbox
     * mehr haben und keine Pfeil-Trefferlogik durchlaufen). Stattdessen:
     *  - Wir entfernen jeden gefundenen Hurricane-Pfeil aus der Welt.
     *  - An der GLEICHEN Position spawnen wir einen FRISCHEN Hurricane-Pfeil mit
     *    identischen Stats (setShooter=shooter, setDamage, setCritical, Tag).
     *  - Velocity ist direkt Richtung Ziel (mit leichtem Y-Boost wenn Ziel h\u00f6her).
     *  - Wir geben dem neuen Pfeil zus\u00e4tzlich den EXTRA-Tag, damit der Salven-
     *    Tracker ihn als Refire-Pfeil erkennt und KEINE erneute Salve ausl\u00f6st.
     *
     * So bekommt der Spieler das gew\u00fcnschte Verhalten: in-Wand-steckende
     * Hurricane-Pfeile werden auf das Ziel umgelenkt und k\u00f6nnen treffen.
     */
    private int redirectArrowsToTarget(Player shooter, LivingEntity target) {
        List<UUID> arrowIds = hurricaneArrows.get(shooter.getUniqueId());
        if (arrowIds == null) return 0;
        RelicDefinition def = plugin.getDefinition("runans_hurricane");
        double arrowMaxRange = def != null ? def.getEffectDouble("homing-arrow-max-range", 64.0) : 64.0;
        double arrowMaxRangeSq = arrowMaxRange * arrowMaxRange;
        Location targetLoc = target.getLocation();
        int redirected = 0;
        int dead = 0;
        int rangeSkipped = 0;
        int invalid = 0;
        int expired = 0; // Pfeile deren Particle-Tag abgelaufen ist (15s)

        // Homing-Schaden halbiert (User-Vorgabe)
        double baseDamage = def != null ? def.getEffectDouble("on-hit-extra-damage", 8.0) + 1.0 : 9.0;
        double damage = baseDamage * 0.5;

        // Snapshot der Liste machen, damit wir die Original-Liste w\u00e4hrend der Iteration
        // sauber l\u00f6schen k\u00f6nnen.
        List<UUID> snapshot = new ArrayList<>(arrowIds);
        arrowIds.clear();

        for (UUID id : snapshot) {
            Entity e = Bukkit.getEntity(id);
            if (e == null) {
                invalid++;
                continue;
            }
            if (!(e instanceof Arrow oldArrow)) {
                // Sollte nicht vorkommen — alles andere ignorieren
                invalid++;
                continue;
            }
            // Original-Position merken, BEVOR wir den Pfeil entfernen
            Location from = oldArrow.getLocation().clone();
            // Original-Typ (Arrow, SpectralArrow, ...) \u00fcbernehmen
            org.bukkit.entity.EntityType arrowType = oldArrow.getType();

            // Homing-Trigger-Filter: nur Pfeile mit aktiven Particle-Tag d\u00fcrfen redirected
            // werden. Nach 15s wird der Tag vom Lifetime-Task entfernt.
            if (!oldArrow.getScoreboardTags().contains(HURRICANE_PARTICLE_TAG)) {
                expired++;
                // Aus Liste entfernen — kann nie mehr redirected werden.
                continue;
            }
            // Pro Pfeil nur EINMAL redirecten (keine endlosen Ketten).
            if (oldArrow.getScoreboardTags().contains(HURRICANE_HOMED_TAG)) {
                expired++;
                continue;
            }

            // Distanz-Filter: Pfeile au\u00dferhalb 64 Bl\u00f6cke werden NICHT redirected.
            if (from.distanceSquared(targetLoc) > arrowMaxRangeSq) {
                rangeSkipped++;
                // NICHT clearen — bleibt in der Liste f\u00fcr sp\u00e4tere Versuche
                arrowIds.add(id);
                continue;
            }

            // TOT oder INVALID: Pfeil entfernen (egal ob am Boden, in der Wand, oder unsichtbar)
            // Wir k\u00f6nnen ihn sowieso nicht redirected — daher wird er durch einen
            // FRISCHEN Pfeil an gleicher Position ersetzt.
            boolean needsRespawn = !oldArrow.isValid() || oldArrow.isDead() || oldArrow.isOnGround();
            oldArrow.remove();
            dead++;

            // === FRISCHEN PFEIL AN GLEICHER POSITION SPAWNEN ===
            Location spawnLoc = from.clone();
            // Aim Richtung Ziel-Eye
            Vector baseDir = target.getEyeLocation().toVector().subtract(spawnLoc.toVector());
            double horizDist = Math.sqrt(baseDir.getX() * baseDir.getX() + baseDir.getZ() * baseDir.getZ());
            // Y-Kompensation: Pfeile fallen mit Gravitation, wir zielen etwas h\u00f6her wenn weit weg
            double travelSeconds = horizDist / 2.2 / 20.0;
            double drop = 0.5 * 0.05 * Math.pow(travelSeconds * 20.0, 2);
            Vector aim = baseDir.clone();
            aim.setY(aim.getY() + Math.max(0, drop));
            aim = aim.normalize().multiply(2.2);

            AbstractArrow newArrow = (AbstractArrow) spawnLoc.getWorld().spawnEntity(spawnLoc, arrowType);
            if (newArrow == null) continue;
            newArrow.setVelocity(aim);
            newArrow.setShooter(shooter);      // OWNER korrekt setzen → Kill-Credit geht auf shooter
            newArrow.setDamage(damage);
            newArrow.setCritical(true);
            newArrow.setPersistent(false);
            newArrow.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
            newArrow.setFallDistance(0);
            try { newArrow.setKnockbackStrength(0); } catch (Throwable ignored) {}
            newArrow.addScoreboardTag(HURRICANE_ARROW_TAG);
            newArrow.addScoreboardTag(HURRICANE_EXTRA_TAG); // EXTRA → löst KEINE erneute Salve aus
            // Homing wurde einmal benutzt — neuen Pfeil als "bereits homed" markieren,
            // damit er NICHT nochmal redirected werden kann.
            newArrow.addScoreboardTag(HURRICANE_HOMED_TAG);
            // KEIN HURRICANE_PARTICLE_TAG → keine erneute Homing möglich (auch wenn er das Ziel verfehlt)
            // ticksLived auf 0 zurücksetzen (Reflection)
            try {
                java.lang.reflect.Field f = newArrow.getClass().getSuperclass().getDeclaredField("ticksLived");
                f.setAccessible(true);
                f.setInt(newArrow, 0);
            } catch (Throwable ignored) {}

            arrowIds.add(newArrow.getUniqueId());
            redirected++;
        }

        plugin.getLogger().info("[Hurricane-Homing] redirectArrowsToTarget: snapshot=" + snapshot.size()
            + " redirected=" + redirected + " dead(respawned)=" + dead
            + " rangeSkipped=" + rangeSkipped + " invalid=" + invalid
            + " expired(no particle tag)=" + expired
            + " (target=" + target.getName() + ", shooter=" + shooter.getName() + ")");
        if (arrowIds.isEmpty()) hurricaneArrows.remove(shooter.getUniqueId());
        return redirected;
    }

    /**
     * Spawnt 3 Hurricane-Pfeile (1 mittig + 2 versetzt links/rechts) vom Shooter
     * in Richtung aim. Alle tragen den Hurricane-Tag; die versetzten zusätzlich
     * den Extra-Tag (kein erneutes Multishot-Event).
     */
    private void spawnHurricaneSalvo(Player shooter, Location from, Vector aim,
                                     org.bukkit.entity.EntityType type, double damage, double spreadDeg) {
        // Blockfreien Spawn-Punkt ermitteln (sonst stecken die Pfeile sofort fest).
        Location spawnLoc = safeArrowSpawn(from);
        for (int i = 0; i < 3; i++) {
            double angle = 0.0;
            if (i == 1) angle = spreadDeg * Math.PI / 180.0;
            if (i == 2) angle = -spreadDeg * Math.PI / 180.0;
            Vector dir = rotateAroundY(aim, angle).normalize().multiply(aim.length());

            AbstractArrow a = (AbstractArrow) spawnLoc.getWorld().spawnEntity(spawnLoc, type);
            a.setVelocity(dir);
            a.setShooter(shooter);
            a.setCritical(true);
            a.setDamage(damage);
            a.setPersistent(false);
            a.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
            a.addScoreboardTag(HURRICANE_ARROW_TAG);
            // 15s Particle-Trail aktiv (Homing-Trigger-Fenster)
            a.addScoreboardTag(HURRICANE_PARTICLE_TAG);
            if (i > 0) a.addScoreboardTag(HURRICANE_EXTRA_TAG);
            // Homing-Tracking: Refire-Pfeile pro Shooter (alle, falls auf anderes Ziel redirected).
            hurricaneArrows.computeIfAbsent(shooter.getUniqueId(), k -> new ArrayList<>())
                    .add(a.getUniqueId());
        }
    }

    /**
     * Beim Item-Wechsel: passive Boni anwenden (Haste, Speed) und ActionBar-Hinweis zeigen.
     */
    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player p = event.getPlayer();
        ItemStack hand = p.getInventory().getItem(event.getNewSlot());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            applyHeldEffects(p);
            showHeldMessage(p, hand);
        }, 1L);
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) applyHeldEffects(event.getPlayer());
        }, 20L);
    }

    /**
     * Separater Handler für Waypoint-Jammer: direkt beim Join prüfen wir den
     * WAYPOINT_TRANSMIT_RANGE-Wert und setzen ihn auf den korrekten Zustand:
     *  - Spieler OHNE Jammer UND auf 0 hängend → auf Vanilla-Default restaurieren
     *  - Spieler MIT Jammer → auf 0 zwingen
     * Das fixt Spieler, die zwischen Server-Restarts auf 0 hängen blieben.
     */
    @EventHandler
    public void onJoinWaypointJammer(org.bukkit.event.player.PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = event.getPlayer();
            if (!p.isOnline()) return;
            AttributeInstance attr = p.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
            if (attr == null) return;
            boolean hasItem = hasWaypointJammer(p);
            boolean hasPumpkin = p.getInventory().getHelmet() != null
                    && p.getInventory().getHelmet().getType() == org.bukkit.Material.CARVED_PUMPKIN;
            double current = attr.getBaseValue();
            RelicDefinition def = plugin.getDefinition("waypoint_jammer");
            if (def == null) return;
            double target = def.getEffectDouble("waypoint-transmit-range", 0.0);
            // Server-Soll-Wert für Spieler ohne Jammer (siehe Sync-Task für Begründung).
            final double RESTORE_VALUE = 60_000_000.0;

            if (hasItem && !hasPumpkin) {
                // Jammer aktiv → auf Target setzen.
                if (Math.abs(current - target) > 0.0001) {
                    attr.setBaseValue(target);
                    plugin.getLogger().info("[WP-JAMMER-JOIN] " + p.getName()
                            + " baseValue " + current + " -> " + target
                            + " (hasItem=true, hasPumpkin=false)");
                }
            } else if (current < 0.5) {
                // Kein Jammer / Kürbis, Wert hängt auf 0 → auf Soll-Wert restaurieren.
                attr.setBaseValue(RESTORE_VALUE);
                plugin.getLogger().info("[WP-JAMMER-JOIN] " + p.getName()
                        + " RESTORED baseValue 0 -> " + RESTORE_VALUE
                        + " (hasItem=" + hasItem + ", hasPumpkin=" + hasPumpkin + ")");
            }
        }, 40L);  // 2s nach Join — nach dem ersten Sync-Tick (20L)
    }

    /**
     * Aufräumen beim Verlassen: AllowFlight aus, Cooldowns raus, damit nichts leakt.
     */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        doubleJumpCooldowns.remove(id);
        dragonEggAllowFlight.remove(id);
        lightningCooldowns.remove(id);
        homingCooldowns.remove(id);
        hurricaneArrows.remove(id);
        ChainState state = hurricaneChains.remove(id);
        if (state != null && state.refireTaskId != -1) {
            Bukkit.getScheduler().cancelTask(state.refireTaskId);
        }
        // Waypoint-Jammer: WAYPOINT_TRANSMIT_RANGE nur dann auf Vanilla-Default
        // zurücksetzen, wenn der Spieler das Jammer im Inventar hatte. Für
        // Spieler ohne Jammer fassen wir den Wert nicht an (anderes Plugin
        // könnte ihn aktiv verwalten).
        Player p = event.getPlayer();
        if (hasWaypointJammer(p)) {
            AttributeInstance attr = p.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
            if (attr != null) {
                attr.setBaseValue(attr.getDefaultValue());
            }
        }
    }

    // ==================== STILLER STERN (Waypoint-Jammer) ====================

    /**
     * True, wenn der Spieler den „Stiller Stern" (waypoint_jammer) im
     * PlayerInventory hat (Hauptinv + Hotbar). NICHT in Enderchest / Truhen.
     */
    private boolean hasWaypointJammer(Player p) {
        for (ItemStack stack : p.getInventory().getContents()) {
            if (stack == null) continue;
            if (!factory.isRelic(stack)) continue;
            if (!"waypoint_jammer".equals(factory.getRelicId(stack))) continue;
            return true;
        }
        return false;
    }

    /**
     * Periodischer Sync (1s): Solange der „Stiller Stern" im PlayerInventory ist,
     * wird der Spieler-Attribut {@link Attribute#WAYPOINT_TRANSMIT_RANGE} auf den
     * konfigurierten Wert (Default 0) gezwungen. Verlässt das Item das Inventar,
     * wird der Vanilla-Default wiederhergestellt.
     *
     * Pattern nach Vorbild von {@link #startDoubleJumpSync()} — robust gegen
     * Drop / Pickup / Truhen-Klicks / Tod / etc., ohne Event-Hook-Marathon.
     */
    public void startWaypointJammerSync() {
        // Wir cachen den „letzten bekannten" baseValue pro Spieler.
        //
        // Drei Regeln:
        //  1) Spieler MIT Jammer → Wert aktiv auf Target (0) halten, jede
        //     externe Änderung wird binnen 1s überschrieben.
        //  2) Spieler OHNE Jammer → Wert NICHT anfassen, solange er ungleich 0
        //     ist. ABER: wenn er auf 0 hängt, restaurieren wir einmalig auf den
        //     Server-Soll-Wert (RESTORE_VALUE). Das fixt Spieler, die durch den
        //     früheren Bug auf 0 hängen blieben.
        //  3) Kürbis auf dem Kopf → verhält sich wie „kein Jammer" (kein Eingriff).
        //     So kann ein Admin / Mod den Locator „retten", indem er einfach
        //     einen Kürbis aufsetzt (oder der Spieler sich selbst einen holt).
        //
        // RESTORE_VALUE: Der typische Paper-Wert, der beim Spieler-Spawn einmal
        // gesetzt wird (per Log verifiziert: 6.0E7 = 60.000.000 für aktive
        // Locator-Bar-Spieler). Wir nutzen den hardcoded Wert, weil die Bukkit/
        // Paper-API für `attr.getDefaultValue()` 0 zurückgibt — also hilft uns
        // der „offizielle Default" nicht weiter.
        final double RESTORE_VALUE = 60_000_000.0;
        java.util.Map<UUID, Double> lastKnown = new java.util.HashMap<>();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            RelicDefinition def = plugin.getDefinition("waypoint_jammer");
            if (def == null) return;
            double target = def.getEffectDouble("waypoint-transmit-range", 0.0);
            for (Player p : Bukkit.getOnlinePlayers()) {
                AttributeInstance attr = p.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
                if (attr == null) continue;
                boolean hasItem = hasWaypointJammer(p);
                boolean hasPumpkin = p.getInventory().getHelmet() != null
                        && p.getInventory().getHelmet().getType() == org.bukkit.Material.CARVED_PUMPKIN;
                UUID pid = p.getUniqueId();
                double current = attr.getBaseValue();

                if (hasItem && !hasPumpkin) {
                    // (1) Jammer aktiv → auf Target halten.
                    if (Math.abs(current - target) > 0.0001) {
                        attr.setBaseValue(target);
                        plugin.getLogger().info("[WP-JAMMER] " + p.getName()
                                + " CHANGED baseValue " + current + " -> " + target);
                    }
                    lastKnown.put(pid, target);
                } else {
                    // (2+3) Kein Jammer oder Kürbis. Nur restaurieren wenn der
                    // Wert auf 0 hängt — dann auf den Server-Soll-Wert setzen.
                    // (Fixt Spieler, die durch den früheren Bug auf 0 hängen
                    // blieben.)
                    if (current < 0.5) {
                        attr.setBaseValue(RESTORE_VALUE);
                        plugin.getLogger().info("[WP-JAMMER] " + p.getName()
                                + " RESTORED baseValue 0 -> " + RESTORE_VALUE
                                + " (hasItem=" + hasItem + ", hasPumpkin=" + hasPumpkin + ")");
                    }
                    lastKnown.remove(pid);  // Cache freigeben
                }
            }
        }, 20L, 20L);
    }

    private void applyHeldEffects(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        boolean hasRelic = factory.isRelic(hand);

        int haste = 0, speed = 0;
        if (hasRelic) {
            RelicDefinition def = plugin.getDefinition(factory.getRelicId(hand));
            if (def != null) {
                haste = def.getEffectInt("held-haste", 0);
                speed = def.getEffectInt("held-speed", 0);
            }
        }

        if (haste > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 80, haste - 1, true, false));
        } else {
            p.removePotionEffect(PotionEffectType.HASTE);
        }
        if (speed > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, speed - 1, true, false));
        } else {
            p.removePotionEffect(PotionEffectType.SPEED);
        }
    }

    private void showHeldMessage(Player p, ItemStack hand) {
        if (hand == null || !factory.isRelic(hand)) return;
        RelicDefinition def = plugin.getDefinition(factory.getRelicId(hand));
        if (def == null || def.heldMessage() == null) return;

        // MiniMessage-Parsing (Gradients, Farben, &-Codes)
        p.sendActionBar(factory.parse(def.heldMessage()));
    }

    // ==================== DRACHENEI (Double-Jump) ====================

    /**
     * Prüft, ob der Spieler das Drachenei im Inventar hat (irgendein Slot).
     */
    private boolean hasDragonEgg(Player p) {
        for (ItemStack stack : p.getInventory().getContents()) {
            if (stack == null) continue;
            if (!factory.isRelic(stack)) continue;
            if (!"dragon_egg".equals(factory.getRelicId(stack))) continue;
            return true;
        }
        return false;
    }

    /**
     * Periodischer Sync: Solange das Drachenei im Inventar ist, allowFlight=true,
     * damit der Spieler in Survival per Leertaste einen „zweiten Sprung“ auslösen kann.
     * Verlässt das Ei das Inventar, wird allowFlight wieder deaktiviert.
     *
     * Alle 20 Ticks (1 Sekunde). Außerdem ActionBar-Cooldown-Anzeige.
     */
    public void startDoubleJumpSync() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean hasEgg = hasDragonEgg(p);
                Boolean prev = dragonEggAllowFlight.get(p.getUniqueId());

                if (hasEgg) {
                    long now = System.currentTimeMillis();
                    Long until = doubleJumpCooldowns.get(p.getUniqueId());
                    boolean onCooldown = until != null && now < until;

                    if (onCooldown) {
                        // Cooldown aktiv → KEIN AllowFlight. Erst wenn der Cooldown
                        // vorbei ist, bekommt der Spieler den Flug wieder (siehe unten).
                        if (prev != null && prev) {
                            p.setAllowFlight(false);
                            p.setFlying(false);
                            dragonEggAllowFlight.put(p.getUniqueId(), false);
                        }
                    } else {
                        // Kein Cooldown → Double-Jump bereit, Flug erlauben.
                        // (Nur setzen, falls noch nicht gesetzt — vermeidet unnötige Pakete.)
                        if (prev == null || !prev) {
                            p.setAllowFlight(true);
                            dragonEggAllowFlight.put(p.getUniqueId(), true);
                        }
                        // Falls der Spieler durch anderes Plugin in echten Flugmodus kam → aus.
                        if (!p.getGameMode().name().equals("CREATIVE") && !p.getGameMode().name().equals("SPECTATOR")) {
                            if (p.isFlying()) p.setFlying(false);
                        }
                    }

                    // ActionBar-Cooldown anzeigen, falls aktiv
                    if (until != null) {
                        if (onCooldown) {
                            double secLeft = (until - now) / 1000.0;
                            p.sendActionBar(MiniMessage.miniMessage().deserialize(
                                    "<gradient:#b04dff:#ff77ff>Drachenei</gradient> <dark_gray>» <gray>Nächster Flügelschlag in <white>"
                                            + String.format("%.1f", secLeft) + "s"));
                        } else {
                            doubleJumpCooldowns.remove(p.getUniqueId());
                            p.sendActionBar(MiniMessage.miniMessage().deserialize(
                                    "<gradient:#b04dff:#ff77ff>Drachenei</gradient> <dark_gray>» <gray>Bereit zum Flug! Drücke <white>Leertaste</white> in der Luft."));
                        }
                    }
                } else {
                    // Ei nicht (mehr) im Inventar → Flug ggf. wieder entziehen.
                    if (prev != null && prev) {
                        p.setAllowFlight(false);
                        p.setFlying(false);
                        dragonEggAllowFlight.remove(p.getUniqueId());
                        doubleJumpCooldowns.remove(p.getUniqueId());
                    }
                }
            }
        }, 20L, 20L);
    }

    /**
     * Startet den Hurricane-Pfeil-Particle-Trail-Tick-Task: alle 2 Ticks wird
     * für jeden aktiven Hurricane-Pfeil mit HURRICANE_PARTICLE_TAG ein kleiner
     * Cyan-Magenta-Partikel gespawnt. Der Tag wird nach {@code particle-lifetime-ticks}
     * (Default 300 = 15s) entfernt — danach kann der Pfeil nicht mehr per Homing
     * redirected werden.
     *
     * Außerdem wird der Damage-Cap zurückgesetzt, wenn das Ziel stirbt oder die
     * Map zu groß wird.
     */
    public void startHurricaneParticleTasks() {
        RelicDefinition def = plugin.getDefinition("runans_hurricane");
        final int lifetimeTicks = def != null ? def.getEffectInt("particle-lifetime-ticks", 300) : 300;
        final double trailRadius = def != null ? def.getEffectDouble("particle-trail-radius", 0.15) : 0.15;
        final int trailCount = def != null ? def.getEffectInt("particle-trail-count", 2) : 2;
        final Particle.DustOptions dust = new Particle.DustOptions(
                Color.fromRGB(0, 255, 255), 1.4f); // Cyan, gut sichtbar

        // 1) Trail-Task: alle 2 Ticks Partikel an Pfeilen mit aktivem Particle-Tag
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (List<UUID> list : hurricaneArrows.values()) {
                for (UUID id : list) {
                    Entity e = Bukkit.getEntity(id);
                    if (!(e instanceof Arrow a)) continue;
                    if (!a.getScoreboardTags().contains(HURRICANE_PARTICLE_TAG)) continue;
                    if (!a.isValid() || a.isDead()) continue;
                    Location loc = a.getLocation();
                    a.getWorld().spawnParticle(Particle.DUST, loc, trailCount,
                            trailRadius, trailRadius, trailRadius, 0.0, dust);
                    // Zusätzlich: ein kleines Magenta-Twinkle für mehr Sichtbarkeit
                    a.getWorld().spawnParticle(Particle.END_ROD, loc, 1,
                            0.05, 0.05, 0.05, 0.02);
                }
            }
        }, 2L, 2L);

        // 2) Lifetime-Task: alle 20 Ticks (1s) prüfen ob Pfeil-Particle-Tag entfernt
        // werden muss. lifetimeTicks = 15s Default.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long nowTick = Bukkit.getCurrentTick();
            for (List<UUID> list : hurricaneArrows.values()) {
                for (UUID id : list) {
                    Entity e = Bukkit.getEntity(id);
                    if (!(e instanceof Arrow a)) continue;
                    if (!a.getScoreboardTags().contains(HURRICANE_PARTICLE_TAG)) continue;
                    // Wir nutzen den Bukkit-Tick als "Alter"-Quelle: lifetimeTicks = 15s
                    // Der Tag wird gesetzt BEIM SCHUSS, hier prüfen wir ob (jetzt - spawnTick) > 300.
                    // Da wir den spawnTick nicht direkt speichern, nutzen wir ticksLived des Pfeils.
                    int ticksLived;
                    try {
                        java.lang.reflect.Field f = a.getClass().getSuperclass().getDeclaredField("ticksLived");
                        f.setAccessible(true);
                        ticksLived = f.getInt(a);
                    } catch (Throwable t) {
                        ticksLived = 0;
                    }
                    if (ticksLived >= lifetimeTicks) {
                        // Tag entfernen — Homing akzeptiert diesen Pfeil nicht mehr
                        a.removeScoreboardTag(HURRICANE_PARTICLE_TAG);
                        a.setGlowing(false);
                        // Optional: subtiler "Ablauf"-Effekt
                        a.getWorld().spawnParticle(Particle.SMOKE,
                                a.getLocation(), 6, 0.1, 0.1, 0.1, 0.02);
                    }
                }
            }
        }, 20L, 20L);

        // 3) Damage-Cap-Reset-Task: alle 60 Sekunden die homingDamageByTarget-Map
        // aufräumen (Ziele die länger als 60s keinen Homing-Schaden mehr bekommen
        // haben werden zurückgesetzt, damit sie wieder voll "angreifbar" sind).
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Sehr einfache Strategie: Map alle 60s komplett leeren.
            // In der Praxis bekommt ein Ziel in 60s eh 30 Homing-Schaden, dann
            // ist der Cap eh voll.
            homingDamageByTarget.clear();
        }, 1200L, 1200L);
    }

    /**
     * Startet den Cleanup-Task für verwaiste Hurricane-Pfeil-UUIDs (alle 100 Ticks = 5s).
     * Pfeile die despawnt/gelöscht sind würden sonst ewig in der Liste bleiben.
     * WICHTIG: Pfeile am Boden (isOnGround=true) werden NICHT entfernt — sie sollen
     * per Homing wieder fliegen können.
     */
    public void startHurricaneCleanupTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            hurricaneArrows.entrySet().removeIf(entry -> {
                entry.getValue().removeIf(id -> {
                    Entity e = Bukkit.getEntity(id);
                    if (e == null || !e.isValid()) return true;
                    if (e instanceof Arrow a && a.isOnGround()) return false; // am Boden → behalten
                    return e.isDead();
                });
                return entry.getValue().isEmpty();
            });
        }, 100L, 100L);
    }

    /**
     * Spieler drückt im Survival „Leertaste“ in der Luft → dieses Event feuert.
     * Wir unterdrücken den eigentlichen Flug und wenden stattdessen unseren Double-Jump an.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player p = event.getPlayer();
        // Nur wenn wir den Flug erlaubt haben (Drachenei vorhanden) und es ein echter
        // Survival-Sprung ist — also der Spieler gerade NICHT schon fliegt.
        if (!hasDragonEgg(p)) return;
        // Im Creative/Spectator lassen wir das normale Verhalten durch.
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE
                || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }

        // Doppel-Sprung auslösen: Event abbrechen, damit der Spieler nicht real fliegt.
        event.setCancelled(true);
        // Sicherheits-Halber: isFlying-Status zurück setzen (manche Paper-Versionen
        // setzen das trotz cancel auf true, was zu glitchendem Flugverhalten führt).
        if (p.isFlying()) p.setFlying(false);

        // Cooldown prüfen
        long now = System.currentTimeMillis();
        Long until = doubleJumpCooldowns.get(p.getUniqueId());
        if (until != null && now < until) {
            double secLeft = (until - now) / 1000.0;
            p.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>Drachenei lädt noch <white>" + String.format("%.1f", secLeft)
                            + "s <red>auf."));
            return;
        }

        // Definition aus dem Inventar holen
        RelicDefinition def = null;
        for (ItemStack stack : p.getInventory().getContents()) {
            if (stack == null) continue;
            if (!factory.isRelic(stack)) continue;
            if (!"dragon_egg".equals(factory.getRelicId(stack))) continue;
            def = plugin.getDefinition("dragon_egg");
            break;
        }
        if (def == null) return;

        long cooldownMs = (long) def.getEffectDouble("double-jump-cooldown-ms", 7000.0);
        double vertical = def.getEffectDouble("double-jump-vertical-boost", 0.9);
        double horizontal = def.getEffectDouble("double-jump-horizontal-boost", 1.2);

        doubleJumpCooldowns.put(p.getUniqueId(), now + cooldownMs);

        // Cooldown läuft → sofort AllowFlight entziehen. Der Sync-Task
        // aktiviert es erst wieder, wenn der Cooldown abgelaufen ist.
        p.setAllowFlight(false);
        p.setFlying(false);
        dragonEggAllowFlight.put(p.getUniqueId(), false);

        // Velocity-Boost: oben + in Blickrichtung des Spielers
        org.bukkit.util.Vector lookDir = p.getEyeLocation().getDirection().normalize();
        Vector boost = new Vector(
                lookDir.getX() * horizontal,
                vertical,
                lookDir.getZ() * horizontal);
        p.setVelocity(boost);

        // Sound: Ender-Drachen-Flügelschlag, an Spieler-Location für alle in Reichweite
        if (def.getEffectBoolean("double-jump-sound", true)) {
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP,
                    1.0f, 1.0f);
        }

        // Partikel: Portal-Spirale + Dragon-Breath
        if (def.getEffectBoolean("double-jump-particles", true)) {
            Location loc = p.getLocation().add(0, 0.5, 0);
            p.getWorld().spawnParticle(Particle.PORTAL, loc, 60, 0.6, 0.6, 0.6, 0.5);
            // DRAGON_BREATH braucht auf Paper 26.2 explizit ein Float-Data (sonst
            // IllegalArgumentException „missing required data class java.lang.Float").
            p.getWorld().spawnParticle(Particle.DRAGON_BREATH, loc, 30, 0.4, 0.4, 0.4, 0.02, 1.0f);
        }
    }
}
