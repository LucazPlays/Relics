package com.notschie.relics.util;

import com.notschie.relics.RelicsPlugin;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Resource-Pack-Manager für das Relics-Plugin.
 *
 * Aufgaben:
 *  - Eingebettetes ZIP aus src/main/resources/resourcepack.zip nach plugins/Relics/ entpacken.
 *  - SHA1 hashen.
 *  - Optional das ZIP über einen lokalen Mini-HTTP-Server ausliefern (Auto-Host).
 *  - An alle Online-Spieler per {@link Player#setResourcePack(UUID, String, byte[], Component, boolean)} pushen.
 *  - server.properties (resource-pack / resource-pack-sha1 / require-resource-pack / resource-pack-prompt)
 *    mitschreiben, damit neue Spieler den Pack-Prompt ebenfalls sehen.
 *  - Beim PlayerJoinEvent das aktuelle Pack an den frischen Spieler senden.
 *
 * Persistenz: plugins/Relics/pack.yml.
 *
 * Public-API ist absichtlich klein gehalten (Hardcode-First-Stil des Plugins):
 *  - enable()                  -> lädt Config, extrahiert ZIP, startet Host falls gewünscht
 *  - disable()                 -> speichert, stoppt Host
 *  - setUrl(String)            -> URL setzen, an alle pushen, in server.properties schreiben
 *  - clearUrl()                -> URL entfernen, an alle unloadResourcePacks
 *  - startHost()/stopHost()    -> HTTP-Server (de)aktivieren
 *  - pushAll() / pushTo(Player)-> aktuelles Pack an Spieler senden
 *  - status()                  -> Diagnose-Text
 */
public class ResourcePackManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String EMBEDDED_RESOURCE_PATH = "resourcepack.zip";
    private static final String PACK_FILE_NAME = "Relics-ResourcePack.zip";
    private static final String PACK_YML_NAME = "pack.yml";
    private static final String CONFIG_PACK_URL = "pack-url";
    private static final String CONFIG_PACK_REQUIRED = "pack-required";
    private static final String CONFIG_PACK_PROMPT = "pack-prompt";
    private static final String CONFIG_HOST_ENABLED = "host-enabled";
    private static final String CONFIG_HOST_PORT = "host-port";
    private static final String CONFIG_AUTO_PUSH_ON_JOIN = "auto-push-on-join";

    private final RelicsPlugin plugin;
    private final File dataFolder;
    private final File packFile;
    private final File packYml;

    private String url = "";
    private boolean required = true;
    private Component prompt = MM.deserialize("<gold>Relics SMP</gold> <gray>benötigt ein Resource-Pack für die custom Items.</gray>");
    private boolean hostEnabled = true;
    private int hostPort = 0; // 0 = zufälliger freier Port
    private boolean autoPushOnJoin = true;

    private String localUrl = "";     // vom HTTP-Server (z.B. http://localhost:51234/...)
    private String sha1Hex = "";      // SHA1 von packFile, hex
    private byte[] sha1Bytes = new byte[20];

    private HttpServer httpServer;

    /** Pack-Version als Marker; falls sich die eingebettete ZIP ändert, wird sie neu extrahiert. */
    private final AtomicReference<UUID> currentPackId = new AtomicReference<>(new UUID(0L, 0L));

    public ResourcePackManager(RelicsPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder();
        this.packFile = new File(dataFolder, PACK_FILE_NAME);
        this.packYml = new File(dataFolder, PACK_YML_NAME);
    }

    public void enable() {
        try {
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                plugin.getLogger().warning("Konnte Plugin-DataFolder nicht anlegen: " + dataFolder);
            }
            extractEmbeddedPackIfNeeded();
            loadConfig();
            // Hash korrekt wählen: bei externer URL muss vom externen Content gehasht werden,
            // bei interner URL (lokaler Host) vom lokalen ZIP. Bei leerer URL bleibt der Hash leer
            // und der Manager tut nichts.
            if (url != null && !url.isBlank()) {
                if (!recomputeHashFromRemote(url)) {
                    plugin.getLogger().warning("Externer SHA1 konnte beim Start nicht berechnet werden — Spieler werden das Pack ablehnen.");
                }
            } else {
                recomputeHash();
            }
            if (hostEnabled) {
                startHost();
            }
            // Falls eine externe URL konfiguriert ist ODER der lokale Server läuft, push beim Join erlauben.
            plugin.getLogger().info("ResourcePackManager aktiv. url='" + effectiveUrl() + "' sha1=" + sha1Hex + " host=" + (httpServer != null));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "ResourcePackManager.enable fehlgeschlagen", e);
        }
    }

    public void disable() {
        saveConfig();
        stopHost();
    }

    // -------------------- Public API --------------------

    public boolean hasPack() {
        return !effectiveUrl().isEmpty() && !sha1Hex.isEmpty();
    }

    public String effectiveUrl() {
        if (url != null && !url.isBlank()) return url;
        return localUrl;
    }

    public String sha1Hex() {
        return sha1Hex;
    }

    public byte[] sha1Bytes() {
        return sha1Bytes.clone();
    }

    public boolean isRequired() {
        return required;
    }

    public Component prompt() {
        return prompt;
    }

    public boolean isHostRunning() {
        return httpServer != null;
    }

    public int hostPort() {
        return hostPort;
    }

    public boolean autoPushOnJoin() {
        return autoPushOnJoin;
    }

    public File packFile() {
        return packFile;
    }

    public UUID currentPackId() {
        return currentPackId.get();
    }

    /**
     * Setzt eine externe URL (oder "internal", um den eingebetteten Server zu nutzen) und pusht an alle Online-Spieler.
     * Schreibt zusätzlich server.properties.
     *
     * @param newUrl Wenn leer → URL entfernen + unloadResourcePacks.
     *               Wenn "internal" → URL leeren, dafür localUrl greifen lassen.
     *               Sonst → exakte URL übernehmen.
     */
    public void setUrl(String newUrl) {
        if (newUrl == null) newUrl = "";
        newUrl = newUrl.trim();
        if (newUrl.equalsIgnoreCase("internal") || newUrl.equalsIgnoreCase("local")) {
            url = "";
            // Bei interner URL: SHA1 aus der lokalen Datei (eingebettetes ZIP auf Disk)
            recomputeHash();
        } else if (newUrl.isEmpty()) {
            url = "";
            // Leere URL: Hash bleibt erhalten (für Status), aber wir entladen unten.
        } else {
            url = newUrl;
            // Externe URL: SHA1 muss vom externen Content stammen, sonst lehnt der Client ab.
            boolean ok = recomputeHashFromRemote(newUrl);
            if (!ok) {
                plugin.getLogger().warning("SHA1-Berechnung von der externen URL fehlgeschlagen — push wird wahrscheinlich fehlschlagen.");
            }
        }
        saveConfig();
        writeServerProperties();
        if (url.isEmpty()) {
            // Nur lokaler Server aktiv → kein Push nötig (lokaler Server versorgt schon beim Join).
            // Trotzdem an alle einmal frisch senden, damit ein neuer Hash sofort ankommt.
            pushAll();
        } else {
            pushAll();
        }
    }

    /** Push an einen einzelnen Spieler (z. B. nach manuellem `/relic pack send <player>`). */
    public void pushTo(Player player) {
        if (!hasPack()) return;
        try {
            player.setResourcePack(currentPackId(), effectiveUrl(), sha1Bytes, prompt, required);
        } catch (Throwable t) {
            plugin.getLogger().warning("setResourcePack für " + player.getName() + " fehlgeschlagen: " + t.getMessage());
        }
    }

    /** Push an alle Online-Spieler. */
    public void pushAll() {
        if (!hasPack()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            pushTo(p);
        }
    }

    /** Entfernt das Pack bei allen Online-Spielern. */
    public void unloadAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                p.removeResourcePacks();
            } catch (Throwable t) {
                plugin.getLogger().warning("removeResourcePacks für " + p.getName() + " fehlgeschlagen: " + t.getMessage());
            }
        }
    }

    public boolean startHost() {
        if (httpServer != null) return true;
        try {
            int port = hostPort > 0 ? hostPort : 0;
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            // Pfad: /Relics-ResourcePack.zip (Client erwartet eine URL, Pfad ist egal)
            server.createContext("/" + PACK_FILE_NAME, exchange -> {
                if (!packFile.exists()) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] bytes = Files.readAllBytes(packFile.toPath());
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(bytes.length));
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            // Optional: Root-Index mit Info
            server.createContext("/", exchange -> {
                String body = "Relics Resource Pack Host. File: /" + PACK_FILE_NAME;
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, b.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(b);
                }
            });
            server.start();
            this.httpServer = server;
            int bound = server.getAddress().getPort();
            this.hostPort = bound;
            this.localUrl = "http://127.0.0.1:" + bound + "/" + PACK_FILE_NAME;
            plugin.getLogger().info("ResourcePack-HTTP gestartet auf Port " + bound + " → " + localUrl);
            // Falls keine externe URL gesetzt ist, ist das nun die Default-URL.
            // Server.properties mitschreiben, falls required+url wirken sollen.
            saveConfig();
            writeServerProperties();
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "HTTP-Host konnte nicht starten", e);
            this.httpServer = null;
            return false;
        }
    }

    public boolean stopHost() {
        if (httpServer == null) return true;
        try {
            httpServer.stop(0);
        } catch (Throwable ignored) {}
        httpServer = null;
        plugin.getLogger().info("ResourcePack-HTTP gestoppt.");
        return true;
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("Pack-File: ").append(packFile.exists() ? "OK" : "MISSING").append(" (").append(packFile.getAbsolutePath()).append(")\n");
        sb.append("SHA1: ").append(sha1Hex.isEmpty() ? "<nicht berechnet>" : sha1Hex).append("\n");
        sb.append("Konfigurierte URL: ").append(url.isEmpty() ? "<leer → lokaler Host>" : url).append("\n");
        sb.append("Effektive URL: ").append(hasPack() ? effectiveUrl() : "<nichts>").append("\n");
        sb.append("Required: ").append(required).append("\n");
        sb.append("Auto-Push-on-Join: ").append(autoPushOnJoin).append("\n");
        sb.append("HTTP-Host: ").append(httpServer != null ? "running (port " + hostPort + ")" : "stopped").append("\n");
        sb.append("Prompt: ").append(GsonComponentSerializer.gson().serialize(prompt));
        return sb.toString();
    }

    // -------------------- Internes --------------------

    private void extractEmbeddedPackIfNeeded() throws IOException {
        try (InputStream in = plugin.getResource(EMBEDDED_RESOURCE_PATH)) {
            if (in == null) {
                plugin.getLogger().warning("Eingebettetes " + EMBEDDED_RESOURCE_PATH + " fehlt im JAR! Pack-System ist funktionslos.");
                return;
            }
            byte[] embedded = in.readAllBytes();
            byte[] onDisk = packFile.exists() ? Files.readAllBytes(packFile.toPath()) : new byte[0];
            String embeddedHash = sha1HexOf(embedded);
            String onDiskHash = onDisk.length == 0 ? "" : sha1HexOf(onDisk);
            if (!embeddedHash.equals(onDiskHash)) {
                try (FileOutputStream out = new FileOutputStream(packFile)) {
                    out.write(embedded);
                }
                plugin.getLogger().info("Eingebettetes Resource-Pack extrahiert (" + embedded.length + " bytes, sha1=" + embeddedHash + ").");
            }
        }
    }

    private void recomputeHash() {
        try {
            if (!packFile.exists()) {
                plugin.getLogger().warning("Pack-File existiert nicht: " + packFile.getAbsolutePath());
                return;
            }
            byte[] bytes = Files.readAllBytes(packFile.toPath());
            this.sha1Bytes = sha1BytesOf(bytes);
            this.sha1Hex = HexFormat.of().formatHex(this.sha1Bytes);
            this.currentPackId.set(UUID.nameUUIDFromBytes((PACK_FILE_NAME + ":" + sha1Hex).getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Hash-Berechnung fehlgeschlagen", e);
        }
    }

    /**
     * L\u00e4dt den Inhalt einer externen URL herunter und berechnet SHA1 vom tats\u00e4chlich heruntergeladenen Content.
     * Wichtig: Der Vanilla-Minecraft-Client lehnt das Pack mit FAILED_DOWNLOAD ab, wenn der vom Server mitgesendete
     * SHA1 nicht zu den Bytes passt, die der Client tats\u00e4chlich l\u00e4dt. Wir m\u00fcssen also die Bytes der externen
     * URL hashen, nicht die unserer eingebetteten ZIP.
     *
     * Implementiert mit java.net.HttpURLConnection (kein neuer Dependency).
     * HTTP/1.1 statt HTTP/2, weil der Vanilla-Client kein HTTP/2 nutzt und wir konsistent bleiben wollen.
     *
     * @return true wenn ein Hash berechnet wurde, false wenn der Download fehlgeschlagen ist.
     */
    private boolean recomputeHashFromRemote(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return false;
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(remoteUrl);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            // Vanilla-Client-User-Agent — Cloudflare/mc-packs.net lassen alles durch, aber wir bleiben konsistent.
            conn.setRequestProperty("User-Agent", "Minecraft Java/21");
            conn.setInstanceFollowRedirects(true); // 302 mcpacks.dev → fsn1.your-objectstorage.com folgen wir beim SHA1-Prefetch
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code != 200) {
                plugin.getLogger().warning("Externer Pack-Download: HTTP " + code + " (" + remoteUrl + ")");
                return false;
            }
            int contentLength = conn.getContentLength();
            try (InputStream in = conn.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(contentLength, 0))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                byte[] bytes = out.toByteArray();
                this.sha1Bytes = sha1BytesOf(bytes);
                this.sha1Hex = HexFormat.of().formatHex(this.sha1Bytes);
                this.currentPackId.set(UUID.nameUUIDFromBytes((remoteUrl + ":" + sha1Hex).getBytes(StandardCharsets.UTF_8)));
                plugin.getLogger().info("Externes Pack heruntergeladen (" + bytes.length + " bytes, sha1=" + sha1Hex + ").");
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Externer Pack-Download fehlgeschlagen: " + e.getMessage(), e);
            return false;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String sha1HexOf(byte[] data) {
        return HexFormat.of().formatHex(sha1BytesOf(data));
    }

    private static byte[] sha1BytesOf(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return md.digest(data);
        } catch (Exception e) {
            throw new RuntimeException("SHA-1 unavailable", e);
        }
    }

    private void loadConfig() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(packYml);
        url = cfg.getString(CONFIG_PACK_URL, url);
        required = cfg.getBoolean(CONFIG_PACK_REQUIRED, required);
        hostEnabled = cfg.getBoolean(CONFIG_HOST_ENABLED, hostEnabled);
        hostPort = cfg.getInt(CONFIG_HOST_PORT, hostPort);
        autoPushOnJoin = cfg.getBoolean(CONFIG_AUTO_PUSH_ON_JOIN, autoPushOnJoin);
        String promptStr = cfg.getString(CONFIG_PACK_PROMPT, null);
        if (promptStr != null && !promptStr.isBlank()) {
            try {
                prompt = MM.deserialize(promptStr);
            } catch (Throwable t) {
                plugin.getLogger().warning("Prompt-MiniMessage ungültig, behalte Default: " + t.getMessage());
            }
        }
    }

    public void saveConfig() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set(CONFIG_PACK_URL, url);
        cfg.set(CONFIG_PACK_REQUIRED, required);
        cfg.set(CONFIG_PACK_PROMPT, GsonComponentSerializer.gson().serialize(prompt));
        cfg.set(CONFIG_HOST_ENABLED, hostEnabled);
        cfg.set(CONFIG_HOST_PORT, hostPort);
        cfg.set(CONFIG_AUTO_PUSH_ON_JOIN, autoPushOnJoin);
        try {
            cfg.save(packYml);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "pack.yml speichern fehlgeschlagen", e);
        }
    }

    /**
     * Schreibt resource-pack / resource-pack-sha1 / require-resource-pack / resource-pack-prompt in
     * server.properties (im CWD des Servers), damit auch Spieler, die NICHT durch unseren Join-Listener
     * gehen (z.B. Vanilla-Clients ohne /relic pack), den korrekten Hash bekommen.
     *
     * Wichtig: Paper liest server.properties beim Server-Start. Für Live-Änderungen braucht es einen
     * Server-Reload ODER wir setzen die Werte nur zur Information. Wir setzen sie trotzdem — der
     * nächste Restart übernimmt sie.
     */
    public void writeServerProperties() {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path propsFile = cwd.resolve("server.properties");
        if (!Files.exists(propsFile)) {
            plugin.getLogger().info("server.properties nicht in " + cwd + " gefunden — übersprungen.");
            return;
        }
        try {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(propsFile)) {
                props.load(in);
            }
            // Nur schreiben, wenn wir etwas zu melden haben.
            if (hasPack()) {
                props.setProperty("resource-pack", effectiveUrl());
                props.setProperty("resource-pack-sha1", sha1Hex);
                props.setProperty("require-resource-pack", String.valueOf(required));
                props.setProperty("resource-pack-prompt", GsonComponentSerializer.gson().serialize(prompt));
            } else {
                props.setProperty("resource-pack", "");
                props.setProperty("resource-pack-sha1", "");
                props.setProperty("require-resource-pack", "false");
            }
            try (OutputStream out = Files.newOutputStream(propsFile)) {
                props.store(out, "Minecraft server properties (updated by Relics-Plugin)");
            }
            plugin.getLogger().info("server.properties aktualisiert (resource-pack / sha1 / required / prompt).");
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "server.properties schreiben fehlgeschlagen", e);
        }
    }
}