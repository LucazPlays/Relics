package com.notschie.relics.listener;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.util.ResourcePackManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.logging.Level;

/**
 * Listener für:
 *  - PlayerJoinEvent: pusht das aktuelle Pack automatisch an frisch eingeloggte Spieler,
 *    sofern auto-push-on-join aktiv ist UND ein Pack konfiguriert ist.
 *  - PlayerResourcePackStatusEvent: loggt Status + retry bei FAILED_DOWNLOAD / INVALID_URL
 *    (max 2 Retries, danach Stop, um Spieler nicht zu spammen).
 */
public class ResourcePackListener implements Listener {

    private final RelicsPlugin plugin;
    private final ResourcePackManager pack;

    /** Retry-Counter pro Spieler (UUID → Anzahl bisheriger Retries). */
    private final java.util.Map<java.util.UUID, Integer> retries = new java.util.HashMap<>();
    private static final int MAX_RETRIES = 2;

    public ResourcePackListener(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.pack = plugin.getResourcePackManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!pack.autoPushOnJoin()) return;
        if (!pack.hasPack()) return;
        // 1-Tick-Verzögerung, damit der Client den Join-Prompt verarbeitet hat,
        // bevor der Resource-Pack-Prompt reinkommt.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = event.getPlayer();
            if (p.isOnline()) {
                pack.pushTo(p);
            }
        }, 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Player p = event.getPlayer();
        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        String playerName = p.getName();
        String hash = event.getHash();

        switch (status) {
            case SUCCESSFULLY_LOADED -> {
                plugin.getLogger().info("ResourcePack von " + playerName + " geladen (" + shortHash(hash) + ").");
                retries.remove(p.getUniqueId());
            }
            case DECLINED -> {
                plugin.getLogger().warning("ResourcePack von " + playerName + " ABGELEHNT.");
                retries.remove(p.getUniqueId());
            }
            case FAILED_DOWNLOAD, INVALID_URL -> {
                int current = retries.getOrDefault(p.getUniqueId(), 0);
                if (current < MAX_RETRIES) {
                    retries.put(p.getUniqueId(), current + 1);
                    plugin.getLogger().warning("ResourcePack " + status + " für " + playerName + " — Retry " + (current + 1) + "/" + MAX_RETRIES);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (p.isOnline()) pack.pushTo(p);
                    }, 40L);
                } else {
                    plugin.getLogger().warning("ResourcePack " + status + " für " + playerName + " — keine weiteren Retries.");
                }
            }
            case ACCEPTED, DOWNLOADED -> {
                // Nur Info.
                plugin.getLogger().info("ResourcePack " + status + " für " + playerName + " (" + shortHash(hash) + ").");
            }
            case FAILED_RELOAD -> {
                plugin.getLogger().warning("ResourcePack Reload für " + playerName + " fehlgeschlagen.");
            }
            case DISCARDED -> {
                plugin.getLogger().warning("ResourcePack von " + playerName + " verworfen (client-seitig).");
            }
        }
    }

    /** Cleanup beim Quit, damit der Retry-Counter den Spieler nicht "verfolgt". */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        retries.remove(event.getPlayer().getUniqueId());
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.length() < 12) return String.valueOf(hash);
        return hash.substring(0, 12);
    }
}