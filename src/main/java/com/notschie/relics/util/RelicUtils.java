package com.notschie.relics.util;

import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Kleinere Helfer-Funktionen, die in mehreren Listenern genutzt werden.
 */
public final class RelicUtils {

    private RelicUtils() {}

    /**
     * True, wenn beide Spieler im selben Scoreboard-Team sind (Vanilla-/Tab-Team).
     * Spieler ohne Team → niemals „same team" (außer sie sind identisch).
     *
     * Wird für PvP-Filterung genutzt: AOE-Effekte (Feuerschwert, Eisgrab, Schild-Dash)
     * sollen Partner im selben Team nicht treffen.
     */
    public static boolean isSameTeam(Player a, Player b) {
        if (a == null || b == null) return false;
        if (a.getUniqueId().equals(b.getUniqueId())) return true;
        Scoreboard sb = Bukkit.getScoreboardManager() != null ? Bukkit.getScoreboardManager().getMainScoreboard() : null;
        if (sb == null) return false;
        Team ta = sb.getEntryTeam(a.getName());
        Team tb = sb.getEntryTeam(b.getName());
        return ta != null && ta.equals(tb);
    }

    /**
     * Wendet Direktschaden am Herzen an, UMGEHT aber Rüstung und Protection-Enchantments.
     *
     * Im Gegensatz zu einer manuellen `target.setHealth(getHealth() - dmg)`-Variante
     * läuft der Schaden hier durch die volle Minecraft-Schadens-Pipeline:
     *  - EntityDamageByEntityEvent wird gefeuert (andere Plugins sehen den Hit)
     *  - Vanilla-Schadens-Partikel + Sounds spielen
     *  - Knockback wird ausgelöst
     *  - Schutz-Items (Totem of Undying) greifen
     *  - Tod wird korrekt behandelt
     *
     * „Rüstungs-bypass" wird erreicht, indem der Schaden via DamageSource mit
     * {@link DamageType# MAGIC} appliziert wird. MAGIC-Schaden wird in Vanilla
     * NICHT durch Rüstungs-Punkte oder Protection-Enchantments reduziert.
     *
     * ACHTUNG: `Damageable.damage(double, Entity)` würde intern
     * `DamageCause.ENTITY_ATTACK` verwenden — das lässt Protection greifen.
     * Wir nutzen daher `damage(double, DamageSource)` mit explizitem MAGIC-Type.
     *
     * @param target das getroffene Entity (LivingEntity, da Damageable)
     * @param attacker der ausführende Spieler (oder null für neutral)
     * @param damage Schaden in HP (kann 0 sein, no-op)
     */
    public static void applyMagicDamage(LivingEntity target, Player attacker, double damage) {
        if (damage <= 0 || target == null) return;
        // Invulnerability-Ticks vor dem Hit auf 0 setzen, damit Minecrafts i-Frames
        // den Schaden nicht abblocken (insb. bei Multi-Hits und Combos).
        target.setNoDamageTicks(0);

        // 1) Event feuern — andere Plugins können canceln / modifizieren.
        EntityDamageByEntityEvent ev = new EntityDamageByEntityEvent(
                attacker, target, EntityDamageEvent.DamageCause.MAGIC, damage);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return;

        // 2) Tatsächlichen Schaden via DamageSource mit MAGIC-Type applizieren.
        //    DamageType.MAGIC umgeht Rüstung und Protection komplett.
        double finalDmg = Math.max(0, ev.getFinalDamage());
        if (finalDmg <= 0) return;
        DamageSource magic = DamageSource.builder(DamageType.MAGIC)
                .withCausingEntity(attacker)
                .withDirectEntity(attacker)
                .build();
        target.damage(finalDmg, magic);

        // Direkt wieder auf 0 setzen für sofortige Folgehits (z.B. Doppel-Shuriken Synergie)
        target.setNoDamageTicks(0);
    }

    /**
     * Wendet Hybrid-Schaden an:
     * 1. Hit: Normaler Schaden (wird regulär durch Rüstung / Protection gedämpft)
     * Nach dem ersten Hit: target.setNoDamageTicks(0), damit der Folge-Hit nicht
     * durch Minecrafts i-Frames (Invulnerability-Ticks) geschluckt wird!
     * 2. Hit: Echter True Damage (DamageType.MAGIC / Direct), der Rüstung komplett umgeht!
     * Abschließend: target.setNoDamageTicks(0), damit nachfolgende Angriffe nicht blockiert werden.
     *
     * @param target das getroffene Entity
     * @param attacker der Verursacher
     * @param normalDamage normaler Schaden (in HP)
     * @param trueDamage echter Direktschaden (in HP, umgeht Rüstung)
     */
    public static void applyHybridDamage(LivingEntity target, Player attacker, double normalDamage, double trueDamage) {
        if (target == null || target.isDead()) return;

        // 1. Normaler physischer Schaden (Rüstung / Protection greift)
        if (normalDamage > 0) {
            target.setNoDamageTicks(0);
            target.damage(normalDamage, attacker);
        }

        if (target.isDead()) return;

        // Nach dem 1. Hit: Unverwundbarkeit auf 0 setzen, damit beide Hits durchgehen
        target.setNoDamageTicks(0);

        // 2. True Damage (geht direkt durch Rüstung)
        if (trueDamage > 0) {
            applyMagicDamage(target, attacker, trueDamage);
        }

        // Abschließend erneut zurücksetzen
        target.setNoDamageTicks(0);
    }

    /**
     * Verleiht dem Spieler temporäre Absorptions-Herzen (z. B. durch Combos oder Parries).
     * @param player der Spieler
     * @param amount HP-Menge (z. B. 4.0 für 2 goldene Herzen)
     * @param durationTicks Dauer in Ticks (z. B. 100 Ticks = 5s), danach wird die Menge abgezogen.
     */
    public static void giveAbsorption(Player player, double amount, int durationTicks) {
        if (player == null || amount <= 0) return;
        double current = player.getAbsorptionAmount();
        double target = Math.min(20.0, current + amount);
        player.setAbsorptionAmount(target);
        player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_ARMOR_EQUIP_GOLD, 1.0f, 1.5f);
        player.spawnParticle(org.bukkit.Particle.HEART, player.getLocation().add(0, 1.2, 0), 3, 0.3, 0.3, 0.3, 0);

        if (durationTicks > 0) {
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        double now = player.getAbsorptionAmount();
                        player.setAbsorptionAmount(Math.max(0, now - amount));
                    }
                }
            }.runTaskLater(com.notschie.relics.RelicsPlugin.getInstance(), durationTicks);
        }
    }
}
