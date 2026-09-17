package com.notschie.relics.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Repräsentiert den aktuellen Status eines einzelnen Relikt-Items im Spiel.
 * Wird vom RelicTracker verwaltet (Single-Source-of-Truth).
 *
 * Felder:
 *   relicId       -> welche Relikt-ID (z.B. "sword_of_fire")
 *   itemUuid      -> UUID des ItemStacks (PDC-Marker)
 *   ownerUuid     -> UUID des Spielers der das Item gerade hält (null = in Chest / auf Boden)
 *   worldName     -> Welt-Name
 *   x, y, z       -> Position (Spieler-Position, Block-Position oder Drop-Position)
 *   containerX/Y/Z-> Block-Position falls Item in einem Container ist, sonst null/-1
 *   containerSlot -> Slot im Container
 *   lastActivity  -> Zeitstempel der letzten Bewegung (epoch millis)
 *   inInventory   -> true wenn im Spieler-Inventar
 *   onGround      -> true wenn auf dem Boden (dropped)
 *   inContainer   -> true wenn in einem Chest etc.
 */
public record RelicState(
        String relicId,
        UUID itemUuid,
        UUID ownerUuid,
        String worldName,
        double x,
        double y,
        double z,
        int containerX,
        int containerY,
        int containerZ,
        int containerSlot,
        long lastActivity,
        boolean inInventory,
        boolean onGround,
        boolean inContainer
) {
    public Location toLocation() {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return null;
        return new Location(w, x, y, z);
    }

    /**
     * MiniMessage-formatierter Tracker-Status-Eintrag für den Owner.
     * Die Legacy-§-Codes wurden entfernt, weil MiniMessage 5.x sie ablehnt.
     * Mapping: §7 -> <gray>, §f -> <white>, §8 -> <dark_gray>, §a -> <green>,
     *          §c -> <red>, §e -> <yellow>.
     */
    public static String statusString(RelicState s) {
        StringBuilder sb = new StringBuilder();
        sb.append("<gray>").append(s.relicId()).append("<white>");
        sb.append(" <dark_gray>|<gray> item-uuid: <white>").append(s.itemUuid().toString().substring(0, 8));
        sb.append(" <dark_gray>|<gray> ");
        if (s.inInventory() && s.ownerUuid() != null) {
            String name = null;
            org.bukkit.entity.Player online = Bukkit.getPlayer(s.ownerUuid());
            if (online != null) {
                name = online.getName();
            } else {
                org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(s.ownerUuid());
                name = offline.getName();
            }
            if (name == null || name.isBlank()) {
                name = s.ownerUuid().toString().substring(0, 8);
            }
            sb.append("<green>Inventar <gray>von <yellow><bold>").append(name).append("</bold></yellow>");
        } else if (s.inContainer()) {
            sb.append("<yellow>Container <gray>@ <white>").append(s.worldName())
                    .append(" <gray>(").append((int) s.containerX())
                    .append("/").append((int) s.containerY())
                    .append("/").append((int) s.containerZ())
                    .append(", Slot ").append(s.containerSlot()).append(")");
        } else if (s.onGround()) {
            sb.append("<red>Boden <gray>@ <white>").append(s.worldName())
                    .append(" <gray>(").append((int) s.x())
                    .append("/").append((int) s.y())
                    .append("/").append((int) s.z()).append(")");
        } else {
            sb.append("<dark_gray>unbekannt <gray>@ <white>").append(s.worldName());
        }
        sb.append(" <dark_gray>|<gray> last: <white>").append((System.currentTimeMillis() - s.lastActivity()) / 1000).append("s");
        return sb.toString();
    }
}