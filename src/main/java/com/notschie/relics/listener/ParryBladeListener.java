package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class ParryBladeListener implements Listener {

    private static final String RELIC_ID = "parry_blade";
    private final RelicsPlugin plugin;
    private final RelicFactory factory;

    private final Map<UUID, Long> parryCooldowns = new HashMap<>();
    private final Map<UUID, Long> activeParries = new HashMap<>();
    private final Map<UUID, Long> riposteCritReady = new HashMap<>();
    private final Map<UUID, Long> lungeCooldowns = new HashMap<>();

    public ParryBladeListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.factory = plugin.getRelicFactory();
    }

    private boolean isParryBlade(ItemStack item) {
        return item != null && factory.isRelic(item) && RELIC_ID.equals(factory.getRelicId(item));
    }

    // ==========================================
    // 1. RECHTSKLICK: 0.5s PARRY & SHIFT-LUNGE
    // ==========================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isParryBlade(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            triggerLunge(player);
        } else {
            triggerParry(player);
        }
    }

    private void triggerParry(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = parryCooldowns.getOrDefault(uuid, 0L);
        long cd = 9000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>⚔ Parry Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }

        parryCooldowns.put(uuid, now);
        activeParries.put(uuid, now + 500L); // Präzises 0.5s Fenster

        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.2f, 1.8f);
        player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1.2, 0), 10, 0.3, 0.3, 0.3, 0.1);

        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gold><bold>⚔ PARRY BEREIT (0.5s)!</bold></gold> <gray>Wehre im exakten Moment ab!</gray>"
        ));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onIncomingDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        UUID uuid = victim.getUniqueId();
        long now = System.currentTimeMillis();

        Long parryUntil = activeParries.get(uuid);
        if (parryUntil == null || now > parryUntil) return;

        // Perfekter Parry!
        event.setCancelled(true);
        activeParries.remove(uuid);

        // Cooldown auf 4s reduzieren
        parryCooldowns.put(uuid, now - 5000L); // 9s - 5s = 4s

        // +2 Absorptions-Herzen
        RelicUtils.giveAbsorption(victim, 4.0, 120);
        riposteCritReady.put(uuid, now + 2500L);

        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_ANVIL_USE, 1.4f, 1.8f);
        victim.getWorld().spawnParticle(Particle.FLASH, victim.getLocation().add(0, 1, 0), 2, 0.2, 0.2, 0.2, 0, Color.WHITE);

        // Angreifer stunnen & Schild brechen
        if (event.getDamager() instanceof LivingEntity attacker) {
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 24, 9, false, true, true));
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 24, 200, false, true, true));
            if (attacker instanceof Player pAttacker) {
                pAttacker.setCooldown(Material.SHIELD, 50);
            }
        }

        victim.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gold><bold>⚔ PERFEKTER PARRY!</bold></gold> <green>Feind betäubt + 2 Absorptions-Herzen + Riposte (+6 HP Crit)!</green>"
        ));
    }

    // Riposte-Schlag ausführen
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRiposteAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player tp && RelicUtils.isSameTeam(attacker, tp)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isParryBlade(weapon)) return;

        UUID uuid = attacker.getUniqueId();
        long now = System.currentTimeMillis();

        Long critReady = riposteCritReady.remove(uuid);
        if (critReady != null && now <= critReady) {
            // Riposte Crit: +6.0 HP Direktschaden!
            RelicUtils.applyMagicDamage(target, attacker, 6.0);
            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.4f, 1.6f);
            target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 20, 0.4, 0.4, 0.4, 0.2);

            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<gradient:#ffffff:#aaccff><bold>⚔ RIPOSTE-TREFFER!</bold></gradient> <green>+6 HP Direktschaden versenkt!</green>"
            ));
        }
    }

    private void triggerLunge(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = lungeCooldowns.getOrDefault(uuid, 0L);
        long cd = 7000L;

        if (now - lastUse < cd) {
            double rem = (cd - (now - lastUse)) / 1000.0;
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>⚔ Ausfallschritt Cooldown: <yellow>" + String.format(Locale.US, "%.1f", rem) + "s</yellow></red>"
            ));
            return;
        }
        lungeCooldowns.put(uuid, now);

        Location loc = player.getLocation();
        Vector dir = loc.getDirection().setY(0).normalize();

        // 1. Zurückweichen
        player.setVelocity(dir.clone().multiply(-0.8).setY(0.2));
        player.getWorld().playSound(loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.5f);

        // 2. Nach 4 Ticks nach vorne stoßen
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                Vector thrust = player.getLocation().getDirection().setY(0).normalize().multiply(1.3).setY(0.15);
                player.setVelocity(thrust);
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 1.0f, 1.8f);

                RayTraceResult rt = player.getWorld().rayTraceEntities(player.getEyeLocation(), thrust, 7.0, 1.2, e -> {
                    if (e.equals(player) || !(e instanceof LivingEntity)) return false;
                    return !(e instanceof Player tp && RelicUtils.isSameTeam(player, tp));
                });

                if (rt != null && rt.getHitEntity() instanceof LivingEntity target) {
                    target.damage(5.0, player);
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 2, false, true, true));
                    player.sendActionBar(MiniMessage.miniMessage().deserialize(
                            "<gradient:#ffffff:#aaccff><bold>⚔ AUSFALLSCHRITT GETROFFEN!</bold></gradient> <aqua>Ziel verlangsamt (Slowness III)!</aqua>"
                    ));
                }
            }
        }.runTaskLater(plugin, 4L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        parryCooldowns.remove(uuid);
        activeParries.remove(uuid);
        riposteCritReady.remove(uuid);
        lungeCooldowns.remove(uuid);
    }
}
