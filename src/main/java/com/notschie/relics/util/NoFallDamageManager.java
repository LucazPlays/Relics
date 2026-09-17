package com.notschie.relics.util;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * NoFallDamage-Manager: gibt einem Spieler für eine konfigurierbare Dauer Immunität
 * gegen Fall-Damage. Reusable für Dash- und Jump-Abilities (Atlas-Dash,
 * Artemis-Jump-Boost, etc.).
 *
 * Verwendung:
 *   manager.grant(player, 10_000L); // 10 Sekunden NoFall
 *
 * Wird automatisch im EntityDamageEvent (Cause = FALL) konsumiert.
 */
public class NoFallDamageManager implements Listener {

    private final Map<UUID, Long> untilMs = new HashMap<>();

    /** Aktiviert NoFall für Spieler für durationMs Millisekunden. */
    public void grant(Player player, long durationMs) {
        untilMs.put(player.getUniqueId(), System.currentTimeMillis() + durationMs);
    }

    /** Prüft ob Spieler aktuell NoFall hat. */
    public boolean isActive(Player player) {
        Long until = untilMs.get(player.getUniqueId());
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            untilMs.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (isActive(p)) {
            event.setCancelled(true);
        }
    }

    /** Cleanup bei Quit. */
    public void onQuit(UUID id) {
        untilMs.remove(id);
    }
}
