package com.notschie.relics.command;

import com.notschie.relics.RelicsPlugin;
import com.notschie.relics.util.ResourcePackManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Subcommand-Handler für /relic pack ... :
 *   pack status                       — Diagnose der aktuellen Pack-Konfig
 *   pack url <url|internal|"">         — Externe URL setzen (oder leeren / auf intern schalten)
 *   pack required <true|false>         — Pack-Pflicht toggeln
 *   pack prompt <MiniMessage...>       — Prompt-Text setzen
 *   pack host [enable|disable]         — Lokalen HTTP-Host (de)aktivieren
 *   pack host port <port>              — HTTP-Port setzen (0 = random)
 *   pack host start|stop               — HTTP-Server explizit starten/stoppen
 *   pack send [player]                 — Manuell an einen Spieler (oder alle) senden
 *   pack reload                        — ZIP neu hashen + an alle pushen
 *   pack unload                        — Pack bei allen entfernen
 *   pack autojoin <true|false>         — Auto-Push beim PlayerJoinEvent toggeln
 */
public class PackCommand {

    private final RelicsPlugin plugin;

    public PackCommand(RelicsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Holt den Manager bei jedem Aufruf frisch, weil er zur Konstruktor-Zeit von RelicCommand noch null sein kann. */
    private ResourcePackManager rp() {
        return plugin.getResourcePackManager();
    }

    public void run(CommandSender sender, String[] args) {
        if (!sender.hasPermission("relics.admin.pack")) {
            sender.sendMessage(Component.text("Keine Berechtigung für /relic pack ...", NamedTextColor.RED));
            return;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> handleStatus(sender);
            case "url" -> handleUrl(sender, args);
            case "required" -> handleRequired(sender, args);
            case "prompt" -> handlePrompt(sender, args);
            case "host" -> handleHost(sender, args);
            case "send" -> handleSend(sender, args);
            case "reload" -> handleReload(sender);
            case "unload" -> handleUnload(sender);
            case "autojoin" -> handleAutojoin(sender, args);
            default -> sendHelp(sender);
        }
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("status", "url", "required", "prompt", "host", "send", "reload", "unload", "autojoin")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "url" -> out.addAll(List.of("internal", "https://cdn.example.com/relics-pack.zip"));
                case "required", "autojoin" -> out.addAll(List.of("true", "false"));
                case "host" -> out.addAll(List.of("enable", "disable", "port", "start", "stop", "status"));
                case "send" -> {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                            out.add(p.getName());
                        }
                    }
                    out.add("all");
                }
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("host") && args[1].equalsIgnoreCase("port")) {
            out.addAll(List.of("0", "8080", "8123", "25575"));
        }
        return out;
    }

    // ----- Handler -----

    private void sendHelp(CommandSender s) {
        s.sendMessage(Component.text("=== /relic pack ===").color(NamedTextColor.GOLD));
        s.sendMessage(Component.text("/relic pack status").color(NamedTextColor.YELLOW)
                .append(Component.text(" — Diagnose der aktuellen Pack-Konfig").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic pack url <url|internal|\"\">").color(NamedTextColor.YELLOW)
                .append(Component.text(" — URL setzen / leeren / auf intern schalten; pusht live an alle Spieler").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic pack required <true|false>").color(NamedTextColor.YELLOW)
                .append(Component.text(" — Pack-Pflicht toggeln").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic pack prompt <MiniMessage...>").color(NamedTextColor.YELLOW)
                .append(Component.text(" — Prompt-Text setzen").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic pack host start|stop|enable|disable|port <port>").color(NamedTextColor.YELLOW)
                .append(Component.text(" — Lokalen HTTP-Server steuern").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic pack send [player|all]").color(NamedTextColor.YELLOW)
                .append(Component.text(" — Manuell an Spieler pushen").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic pack reload").color(NamedTextColor.YELLOW)
                .append(Component.text(" — ZIP neu hashen und an alle pushen").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic pack unload").color(NamedTextColor.YELLOW)
                .append(Component.text(" — Pack bei allen Online-Spielern entfernen").color(NamedTextColor.GRAY)));
        s.sendMessage(Component.text("/relic pack autojoin <true|false>").color(NamedTextColor.YELLOW)
                .append(Component.text(" — Auto-Push beim Login toggeln").color(NamedTextColor.GRAY)));
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage(Component.text("=== Pack-Status ===").color(NamedTextColor.GOLD));
        for (String line : rp().status().split("\n")) {
            sender.sendMessage(Component.text(line).color(NamedTextColor.GRAY));
        }
    }

    private void handleUrl(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /relic pack url <url|internal|\"\">", NamedTextColor.RED));
            return;
        }
        String joined = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        boolean wasRequired = rp().isRequired();
        rp().setUrl(joined);
        String eff = rp().effectiveUrl();
        if (eff.isEmpty()) {
            sender.sendMessage(Component.text("Pack-URL entfernt. Spieler behalten das aktuelle Pack, bis du es unloadest.", NamedTextColor.YELLOW));
        } else if (joined.equalsIgnoreCase("internal") || joined.isEmpty()) {
            sender.sendMessage(Component.text("Externe URL entfernt → lokaler Host aktiv: ", NamedTextColor.GREEN)
                    .append(Component.text(eff, NamedTextColor.AQUA)));
        } else {
            sender.sendMessage(Component.text("URL gesetzt: ", NamedTextColor.GREEN)
                    .append(Component.text(joined, NamedTextColor.AQUA)));
        }
        if (wasRequired != rp().isRequired()) {
            sender.sendMessage(Component.text("Required-Status: " + rp().isRequired(), NamedTextColor.GRAY));
        }
        sender.sendMessage(Component.text("server.properties wurde mitgeschrieben (für den nächsten Server-Reload).", NamedTextColor.GRAY));
    }

    private void handleRequired(CommandSender sender, String[] args) {
        if (args.length < 2 || !(args[1].equalsIgnoreCase("true") || args[1].equalsIgnoreCase("false"))) {
            sender.sendMessage(Component.text("Usage: /relic pack required <true|false>", NamedTextColor.RED));
            return;
        }
        boolean val = Boolean.parseBoolean(args[1]);
        // Direkter Zugriff über Config-Feld ist nicht public — wir nutzen saveConfig/writeServerProperties via setUrl-Workaround.
        // Wir machen das über einen schlanken internen Setter.
        plugin.setPackRequired(val);
        rp().saveConfig();
        rp().writeServerProperties();
        rp().pushAll();
        sender.sendMessage(Component.text("Required = " + val + ". An alle gepusht.", NamedTextColor.GREEN));
    }

    private void handlePrompt(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /relic pack prompt <MiniMessage...>", NamedTextColor.RED));
            return;
        }
        String joined = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        try {
            plugin.setPackPrompt(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(joined));
            rp().saveConfig();
            rp().writeServerProperties();
            rp().pushAll();
            sender.sendMessage(Component.text("Prompt aktualisiert. An alle gepusht.", NamedTextColor.GREEN));
        } catch (Throwable t) {
            sender.sendMessage(Component.text("Prompt-MiniMessage ungültig: " + t.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleHost(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Host: " + (rp().isHostRunning() ? "RUNNING (port " + rp().hostPort() + ")" : "STOPPED"), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("URL: " + (rp().effectiveUrl().isEmpty() ? "<nichts>" : rp().effectiveUrl()), NamedTextColor.GRAY));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "enable" -> {
                plugin.setPackHostEnabled(true);
                rp().startHost();
                sender.sendMessage(Component.text("Host aktiviert: " + rp().effectiveUrl(), NamedTextColor.GREEN));
            }
            case "disable" -> {
                plugin.setPackHostEnabled(false);
                rp().stopHost();
                sender.sendMessage(Component.text("Host gestoppt.", NamedTextColor.YELLOW));
            }
            case "start" -> {
                if (rp().startHost()) {
                    sender.sendMessage(Component.text("Host gestartet: " + rp().effectiveUrl(), NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Host-Start fehlgeschlagen (siehe Log).", NamedTextColor.RED));
                }
            }
            case "stop" -> {
                rp().stopHost();
                sender.sendMessage(Component.text("Host gestoppt.", NamedTextColor.YELLOW));
            }
            case "port" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Aktueller Port: " + rp().hostPort(), NamedTextColor.GRAY));
                    return;
                }
                try {
                    int port = Integer.parseInt(args[2]);
                    plugin.setPackHostPort(port);
                    boolean wasRunning = rp().isHostRunning();
                    rp().stopHost();
                    if (wasRunning) rp().startHost();
                    sender.sendMessage(Component.text("Port gesetzt: " + rp().hostPort() + ", Host " + (rp().isHostRunning() ? "läuft" : "gestoppt") + ".", NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Ungültiger Port: " + args[2], NamedTextColor.RED));
                }
            }
            case "status" -> handleStatus(sender);
            default -> sender.sendMessage(Component.text("Usage: /relic pack host <enable|disable|start|stop|port <port>|status>", NamedTextColor.RED));
        }
    }

    private void handleSend(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
            rp().pushAll();
            sender.sendMessage(Component.text("Pack an alle gepusht (" + Bukkit.getOnlinePlayers().size() + " Spieler).", NamedTextColor.GREEN));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Spieler nicht online: " + args[1], NamedTextColor.RED));
            return;
        }
        rp().pushTo(target);
        sender.sendMessage(Component.text("Pack an " + target.getName() + " gepusht.", NamedTextColor.GREEN));
    }

    private void handleReload(CommandSender sender) {
        // ZIP selbst wird beim Reload NICHT neu eingelesen (das wäre eine externe Datei-Änderung).
        // Wir hashen neu und pushen. Für eine echte ZIP-Aktualisierung: Server neu starten ODER
        // eine neue ZIP-Datei manuell nach plugins/Relics/Relics-ResourcePack.zip kopieren
        // und Relics neu laden.
        plugin.reloadResourcePackFromDisk();
        rp().pushAll();
        sender.sendMessage(Component.text("Pack neu eingelesen, SHA1 neu berechnet, an alle gepusht.", NamedTextColor.GREEN));
    }

    private void handleUnload(CommandSender sender) {
        rp().unloadAll();
        sender.sendMessage(Component.text("Pack bei allen Online-Spielern entfernt.", NamedTextColor.YELLOW));
    }

    private void handleAutojoin(CommandSender sender, String[] args) {
        if (args.length < 2 || !(args[1].equalsIgnoreCase("true") || args[1].equalsIgnoreCase("false"))) {
            sender.sendMessage(Component.text("Usage: /relic pack autojoin <true|false>", NamedTextColor.RED));
            return;
        }
        boolean val = Boolean.parseBoolean(args[1]);
        plugin.setPackAutoPushOnJoin(val);
        rp().saveConfig();
        sender.sendMessage(Component.text("Auto-Push on Join = " + val, NamedTextColor.GREEN));
    }
}