package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mjölnir Smashdown-Combo — Sneak+Linksklick löst eine 3-Phasen-Kombo aus.
 *
 *  Phase 1 (Sneak+Linksklick Treffer auf Entity):
 *   - Cooldown 12s, Velocity +Y (1.4) + Vorwärts-Boost (1.2).
 *   - Gegner wird in der Spieler-Blickrichtung in die Luft geworfen.
 *   - Gegner-UUID wird im Set airTargets[attacker] gemerkt.
 *
 *  Phase 2 (Air-Hit auf gemerktes Ziel):
 *   - Wenn der gleiche Gegner im Set ist und vom Spieler getroffen wird:
 *   - Velocity nach unten (-2.0) + Blitz-Effekt am Gegner + MAGIC-Bypass (4 HP).
 *
 *  Phase 3 (Boden-Kontakt):
 *   - Per EntityToggleSwimEvent (Bill: ich nehme stattdessen PlayerMoveEvent
 *     wäre zu teuer) → wir nutzen den EntityDamageEvent mit Cause FALL auf das
 *     gemerkte Ziel: original Damage * combo-ground-fall-multiplier + Blitz am
 *     Boden + MAGIC-Bypass (6 HP).
 *
 * State:
 *   - cooldowns[attacker] → Long (bis-Cooldown-Endzeit)
 *   - airTargets[attacker] → Set<UUID> der gerade in der Luft befindlichen Mobs
 *
 * Wichtig: kein Eingriff in EntityDamageByEntityEvent wenn nicht der Spieler
 * der Damager ist (z. B. andere Spieler können die Mobs nicht „smashen").
 */
public class MjolnirComboListener implements Listener {

    private static final String RELIC_ID = "mjolnir";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RelicsPlugin plugin;
    private final RelicFactory factory;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** Pro Spieler die Mobs, die gerade in der Luft sind und zum Slamdown anstehen. */
    private final Map<UUID, Set<UUID>> airTargets = new HashMap<>();
    /**
     * Pro ZIEL-Spieler die Mobs, die ihn NICHT angreifen dürfen.
     * Wird gesetzt, wenn ein Mob per Smashdown in die Luft geboostet wird, und
     * bleibt so lange aktiv, bis der Mob wieder am Boden ist (Phase 3).
     * Greift auch wenn ein geboosteter Spieler andere Spieler angreift.
     */
    private final Map<UUID, Set<UUID>> airAttackBlock = new HashMap<>();

    public MjolnirComboListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isMjolnir(ItemStack stack) {
        if (stack == null || !factory.isRelic(stack)) return false;
        return RELIC_ID.equals(factory.getRelicId(stack));
    }

    /**
     * Phase 1: Sneak+Linksklick → Boost den Gegner in die Luft + merken.
     * Wir hängen uns in EntityDamageByEntityEvent ein (Treffer-Lambda ist immer
     * da, wenn der Spieler den Mob schlägt). Nur bei isSneaking + Linksklick-
     * Action links wird der Boost angewendet — sonst lassen wir den normalen
     * Schaden durch.
     *
     * Wichtig: Wir laufen auf HIGHEST mit ignoreCancelled=false, weil viele
     * Plugins (Anti-Cheat, NoDamage, PvP-Schutz) den Damage-Event auf
     * HIGHEST cancellen — bei MONITOR + ignoreCancelled=true kämen wir dann
     * nie zum Zug. Die Smashdown-Logik soll auch greifen, wenn der normale
     * Schaden unterdrückt wird.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHitCombo(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isMjolnir(hand)) return;
        if (!p.isSneaking()) return;

        // Re-Entry-Schutz: Wenn dieser Schaden durch unsere eigene applyMagicDamage
        // (Cause MAGIC) ausgelöst wurde, NICHT erneut die Combo-Logik triggern —
        // sonst Endlos-Rekursion (Phase 2 → applyMagicDamage → EntityDamageEvent →
        // Phase 2 → …).
        if (event.getCause() == EntityDamageEvent.DamageCause.MAGIC) return;

        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null || !def.getEffectBoolean("combo-enabled", true)) return;

        UUID pid = p.getUniqueId();
        UUID tid = target.getUniqueId();
        long now = System.currentTimeMillis();
        long cdMs = (long) def.getEffectDouble("combo-cooldown-ms", 12000.0);

        Set<UUID> airSet = airTargets.computeIfAbsent(pid, k -> new HashSet<>());
        boolean inAir = airSet.contains(tid);

        if (!inAir) {
            // === PHASE 1: Boost in die Luft ===
            Long until = cooldowns.get(pid);
            if (until != null && now < until) {
                double secLeft = (until - now) / 1000.0;
                p.sendActionBar(MM.deserialize(
                        "<gradient:#00d4ff:#ffff00><bold>Mjölnir-Smashdown</bold> <dark_gray>» <red>lädt noch <white>"
                                + String.format("%.1f", secLeft) + "s <red>auf."));
                return;
            }

            cooldowns.put(pid, now + cdMs);

            double launchY = def.getEffectDouble("combo-launch-velocity-y", 4.0);
            double launchForward = def.getEffectDouble("combo-launch-velocity-forward", 0.8);

            // Velocity: vertikal hoch + horizontal in Blickrichtung.
            Vector horizontal = p.getLocation().getDirection().setY(0).normalize();
            Vector vel = horizontal.multiply(launchForward).setY(launchY);
            target.setVelocity(vel);
            target.setFallDistance(0.0f);  // Reset Fall-Distance, sonst zählt Vanilla-Fallschaden

            // Re-apply einen Tick später — manche Plugins (Vanilla-Knockback,
            // RelicEffectListener) überschreiben die Velocity im selben Tick
            // erneut. Wir setzen sie nochmal im nächsten Tick, damit der Boost
            // garantiert ankommt.
            final Vector velFinal = vel.clone();
            final LivingEntity tgtFinal = target;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!tgtFinal.isValid() || tgtFinal.isDead()) return;
                tgtFinal.setVelocity(velFinal);
                tgtFinal.setFallDistance(0.0f);
            });

            // === Spieler mit dem Mob in die Luft katapultieren ===
            // Damit der Spieler den Mob oben treffen kann (Phase 2), bekommt er
            // selbst einen leicht schwächeren Velocity-Boost in Blickrichtung
            // + Jump Boost Potion für Air-Control + Fallschaden-Reset.
            double pLaunchY = def.getEffectDouble("combo-launch-player-velocity-y", 3.0);
            double pLaunchForward = def.getEffectDouble("combo-launch-player-velocity-forward", 1.0);
            Vector pHorizontal = p.getLocation().getDirection().setY(0).normalize();
            Vector pVel = pHorizontal.multiply(pLaunchForward).setY(pLaunchY);
            p.setVelocity(pVel);
            p.setFallDistance(0.0f);  // Spieler nimmt keinen Fallschaden bei Landung

            int jumpAmp = (int) def.getEffectDouble("combo-launch-jump-boost-amplifier", 3);
            int jumpTicks = (int) def.getEffectDouble("combo-launch-jump-boost-ticks", 60);
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.JUMP_BOOST,
                    jumpTicks, jumpAmp, true, true, true));

            // Re-apply Spieler-Velocity im nächsten Tick (manche Plugins
            // überschreiben auch die Spieler-Velocity).
            final Vector pVelFinal = pVel.clone();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                p.setVelocity(pVelFinal);
                p.setFallDistance(0.0f);
            });

            // Gegner in beide Sets merken (Slamdown + Angriffs-Block).
            airSet.add(tid);
            // Pro ZIEL-Spieler den Mob tracken, der ihn nicht angreifen darf.
            // Wir gehen konservativ: nur der Spieler der den Launch ausgelöst
            // hat wird blockiert (sein eigener Mob-Set). Andere Spieler werden
            // vom Angriffs-Block NICHT erfasst.
            airAttackBlock.computeIfAbsent(pid, k -> new HashSet<>()).add(tid);

            // Effekte (Mob).
            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.6f);
            target.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, target.getLocation(), 12, 0.3, 0.1, 0.3, 0.05);
            // Effekte (Spieler) — Feedback dass der Smashdown auch den Spieler erfasst.
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.8f);
            p.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, p.getLocation(), 12, 0.3, 0.1, 0.3, 0.05);
            p.sendActionBar(MM.deserialize(
                    "<gradient:#00d4ff:#ffff00><bold>Mjölnir-Smashdown</bold> <dark_gray>» <gray>Phase 1: <white>Launch <dark_gray>(<white>Du fliegst mit!<dark_gray>)"));
        } else {
            // === PHASE 2: Air-Hit auf bereits geboostetes Ziel → Slamdown ===
            double downVel = def.getEffectDouble("combo-air-downward-velocity", -2.0);
            double airDamage = def.getEffectDouble("combo-air-bolt-damage", 4.0);

            // Vor applyMagicDamage aus airSet entfernen — sonst feuert der
            // MAGIC-Schaden einen neuen EntityDamageByEntityEvent, der wegen
            // (isSneaking + airSet.contains) erneut Phase 2 auslöst → Rekursion.
            airSet.remove(tid);
            for (var blockSet : airAttackBlock.values()) {
                blockSet.remove(tid);
            }

            Vector slam = new Vector(0.0, downVel, 0.0);
            target.setVelocity(slam);

            // Kosmetischer Blitz + MAGIC-Bypass Damage.
            target.getWorld().strikeLightningEffect(target.getLocation());
            RelicUtils.applyMagicDamage(target, p, airDamage);

            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.5f, 1.2f);
            p.sendActionBar(MM.deserialize(
                    "<gradient:#00d4ff:#ffff00><bold>Mjölnir-Smashdown</bold> <dark_gray>» <gray>Phase 2: <white>Slam"));
        }
    }

    /**
     * Phase 3: Wenn ein gemerkter Mob Fall-Schaden nimmt (er ist gerade gelandet),
     * verstärken wir den Schaden und feuern einen Boden-Blitz.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        // Suche revers: gibt es einen Spieler, der den Mob im airSet hat?
        Player owner = null;
        for (var entry : airTargets.entrySet()) {
            if (entry.getValue().contains(target.getUniqueId())) {
                owner = Bukkit.getPlayer(entry.getKey());
                entry.getValue().remove(target.getUniqueId());
                break;
            }
        }
        if (owner == null) return;
        if (!owner.isOnline()) return;

        // Aus dem Angriffs-Block-Set entfernen — Mob ist wieder am Boden.
        for (var blockSet : airAttackBlock.values()) {
            blockSet.remove(target.getUniqueId());
        }

        RelicDefinition def = plugin.getDefinition(RELIC_ID);
        if (def == null) return;
        double mult = def.getEffectDouble("combo-ground-fall-multiplier", 3.0);
        double groundDamage = def.getEffectDouble("combo-ground-bolt-damage", 6.0);

        // Fallschaden multiplizieren.
        double orig = event.getDamage();
        event.setDamage(orig * mult);

        // Blitz am Boden + MAGIC-Bypass Bonus.
        Location ground = target.getLocation().clone();
        ground.setY(ground.getY() - 0.5);
        target.getWorld().strikeLightningEffect(ground);
        RelicUtils.applyMagicDamage(target, owner, groundDamage);

        target.getWorld().playSound(ground, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.8f, 0.8f);
        owner.sendActionBar(MM.deserialize(
                "<gradient:#00d4ff:#ffff00><bold>Mjölnir-Smashdown</bold> <dark_gray>» <gray>Phase 3: <white>Splat"));
    }

    /**
     * Cleanup: wenn ein geboosteter Mob stirbt (in der Luft oder beim Landen),
     * entfernen wir ihn aus allen Sets. Sonst bleibt er für immer als geblockt
     * registriert und würde eventuelle spätere Spawns blockieren (z. B. wenn ein
     * Spieler mit identischer UUID spawnt, sehr unwahrscheinlich, aber sauberer).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        UUID deadId = event.getEntity().getUniqueId();
        for (var blockSet : airAttackBlock.values()) {
            blockSet.remove(deadId);
        }
        for (var airSet : airTargets.values()) {
            airSet.remove(deadId);
        }
    }

    /**
     * Schaden-Block: Wenn ein geboosteter Mob einen Spieler angreifen will,
     * wird der Schaden gecanceld, solange der Mob in der Luft ist (Phase 1+2).
     * Wird in Phase 3 (Mob landet) wieder freigegeben.
     *
     * Wichtig: wir greifen VOR allen anderen Damage-Modifiern ein, damit auch
     * Sources wie Projectile (Pfeile, Tränke, etc.) und Feuer-Tick geblockt
     * werden — solange der Mob im Set steht, gilt „kann diesen Spieler nicht
     * angreifen".
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMobDamagePlayerWhileAir(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof LivingEntity attacker)) return;

        UUID attackerId = attacker.getUniqueId();
        UUID victimId = victim.getUniqueId();

        // Suche: ist der Mob in einem airAttackBlock-Set?
        // Wir blocken Damage, wenn der Mob im Set irgendeines Spielers steht.
        for (var entry : airAttackBlock.entrySet()) {
            Set<UUID> blocked = entry.getValue();
            if (blocked.contains(attackerId)) {
                // Cancel — der Mob ist geboostet und darf diesen Spieler
                // (oder überhaupt: alle Spieler) nicht angreifen.
                event.setCancelled(true);
                // Optionales Feedback für den Spieler (gedämpft, damit kein Spam).
                victim.sendActionBar(MM.deserialize(
                        "<gradient:#00d4ff:#ffff00><bold>Mjölnir</bold> <dark_gray>» <gray><i>Getroffener Mob kann dich nicht treffen, bis er landet."));
                return;
            }
        }

        // Auch wenn ein geboosteter Spieler ein anderer Spieler angreifen will:
        // (Edge case: Spieler A boostet Spieler B, B greift Spieler C an.)
        if (attacker instanceof Player attackerPlayer) {
            for (var entry : airAttackBlock.entrySet()) {
                Set<UUID> blocked = entry.getValue();
                if (blocked.contains(attackerPlayer.getUniqueId())) {
                    // Cancel ohne Feedback (würde beim Opfer nichts bringen).
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cooldowns.remove(id);
        airTargets.remove(id);
        airAttackBlock.remove(id);
    }
}