package com.notschie.relics;

import com.notschie.relics.command.PackCommand;
import com.notschie.relics.command.RelicCommand;
import com.notschie.relics.command.TestsCommand;
import com.notschie.relics.listener.*;
import com.notschie.relics.listener.AntiDupeListener;
import com.notschie.relics.listener.AquaScepterListener;
import com.notschie.relics.listener.ArtemisBowListener;
import com.notschie.relics.listener.EnderChestBlockListener;
import com.notschie.relics.listener.FireAoeListener;
import com.notschie.relics.listener.FireSwingListener;
import com.notschie.relics.listener.IceGrabOnHitListener;
import com.notschie.relics.listener.IceSphereListener;
import com.notschie.relics.listener.IceSwingListener;
import com.notschie.relics.listener.InfernoForkListener;
import com.notschie.relics.listener.LightningDashListener;
import com.notschie.relics.listener.MagicalHarpListener;
import com.notschie.relics.listener.MjolnirAuraListener;
import com.notschie.relics.listener.MjolnirComboListener;
import com.notschie.relics.listener.RelicEffectListener;
import com.notschie.relics.listener.RelicEnchantBlockListener;
import com.notschie.relics.listener.PoisonStaffListener;
import com.notschie.relics.listener.RelicMoveListener;
import com.notschie.relics.listener.ResourcePackListener;
import com.notschie.relics.listener.ShadowbeamStaffListener;
import com.notschie.relics.listener.ChronoStopwatchListener;
import com.notschie.relics.listener.VoidScytheListener;
import com.notschie.relics.listener.NightfallDaggerListener;
import com.notschie.relics.listener.PhantomHookListener;
import com.notschie.relics.listener.DeathScepterListener;
import com.notschie.relics.listener.ShadowCatalystListener;
import com.notschie.relics.listener.ShieldDashListener;
import com.notschie.relics.listener.VampireKnivesListener;
import com.notschie.relics.gui.RelicTrackerGUI;
import com.notschie.relics.model.RelicDefinition;
import com.notschie.relics.util.NoFallDamageManager;
import com.notschie.relics.util.RelicFactory;
import com.notschie.relics.util.RelicTracker;
import com.notschie.relics.util.ResourcePackManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RelicsPlugin extends JavaPlugin {

    private static RelicsPlugin instance;
    private RelicFactory relicFactory;
    private RelicTracker relicTracker;
    private ResourcePackManager resourcePackManager;
    private final Map<String, RelicDefinition> definitions = new HashMap<>();
    private UUID ownerUuid = null;

    @Override
    public void onEnable() {
        instance = this;

        relicFactory = new RelicFactory(this);
        relicTracker = new RelicTracker(this);

        loadDefinitions();
        relicTracker.load();

        // Owner-UUID ist HARDCODED: Luca_zPlays
        this.ownerUuid = UUID.fromString("99f75c4a-daec-476b-98da-d791b1faf60d");

        // Commands
        PluginCommand relicCmd = getCommand("relic");
        if (relicCmd != null) {
            RelicCommand handler = new RelicCommand(this);
            relicCmd.setExecutor(handler);
            relicCmd.setTabCompleter(handler);
        }

        // Listeners
        Bukkit.getPluginManager().registerEvents(new AntiDupeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new RelicMoveListener(this), this);
        RelicEffectListener effectListener = new RelicEffectListener(this);
        Bukkit.getPluginManager().registerEvents(effectListener, this);
        Bukkit.getPluginManager().registerEvents(new RelicTrackerGUI(this), this);

        // Drachenei Double-Jump Sync-Task starten (allowFlight + ActionBar-Cooldown)
        effectListener.startDoubleJumpSync();
        // Stiller Stern (Waypoint-Jammer) Sync-Task: WAYPOINT_TRANSMIT_RANGE = 0
        // solange waypoint_jammer im PlayerInventory ist.
        effectListener.startWaypointJammerSync();
        // Hurricane: Particle-Trail-Task + Lifetime-Task + Damage-Cap-Reset
        effectListener.startHurricaneParticleTasks();
        // Hurricane: Cleanup-Task für verwaiste Pfeil-UUIDs
        effectListener.startHurricaneCleanupTask();

        // Eisgrab Sphere-Listener (Rechtsklick → hohle Eissphäre, 8s/12s)
        IceSphereListener iceListener = new IceSphereListener(this);
        Bukkit.getPluginManager().registerEvents(iceListener, this);
        iceListener.startIceSphereSync();

        // Eisgrab Per-Hit-Effekte (Slowness/Mining-Fatigue-Stacken + Feuer-Reset + Schneepartikel)
        Bukkit.getPluginManager().registerEvents(new IceGrabOnHitListener(this), this);

        // Enderchest-Block: kein Relikt darf in eine Enderchest gelegt werden
        Bukkit.getPluginManager().registerEvents(new EnderChestBlockListener(this), this);

        // Feuerschwert Rechtsklick-AOE + Schild-Dash-on-Block
        Bukkit.getPluginManager().registerEvents(new FireAoeListener(this), this);
        // NoFallDamage-Manager (global, wird von Atlas-Dash + Artemis-Jump genutzt).
        NoFallDamageManager noFallManager = new NoFallDamageManager();
        Bukkit.getPluginManager().registerEvents(noFallManager, this);
        ShieldDashListener shieldDash = new ShieldDashListener(this, noFallManager);
        Bukkit.getPluginManager().registerEvents(shieldDash, this);
        shieldDash.startSync(); // Polling-Trigger: Sneak+Block → Dash (KEIN Schlag nötig)
        Bukkit.getPluginManager().registerEvents(new ArtemisBowListener(this, noFallManager), this);
        Bukkit.getPluginManager().registerEvents(new PhantomHookListener(this, noFallManager), this);

        // Verzauberungs-Block: Relikte dürfen NICHT extern verzaubert werden
        // (Enchanting Table + Anvil + Smaragden + Bücher).
        Bukkit.getPluginManager().registerEvents(new RelicEnchantBlockListener(this), this);

        // Feuerschwert Shift+Linksklick: wandernde Cone-Hitbox (16 Blöcke, 30°, 8 m/s)
        Bukkit.getPluginManager().registerEvents(new FireSwingListener(this), this);
        // Eisgrab Shift+Linksklick: wandernde Cone-Hitbox mit Slowness + Mining Fatigue
        Bukkit.getPluginManager().registerEvents(new IceSwingListener(this), this);
        // Donnerkatana Shift+Rechtsklick: Teleport-Dash in Blickrichtung
        Bukkit.getPluginManager().registerEvents(new LightningDashListener(this), this);
        // Mjölnir Shift+Rechtsklick: 5s Donner-Aura (90°-Cone, 8-Block-Radius, alle 2s Blitz)
        Bukkit.getPluginManager().registerEvents(new MjolnirAuraListener(this), this);
        // Mjölnir Sneak+Linksklick: Smashdown-Combo (Up-Launch → Air-Hit → Slam)
        Bukkit.getPluginManager().registerEvents(new MjolnirComboListener(this), this);

        // Vampiric Knives: Rechtsklick wirft 5 IRON_SWORD-ItemDisplays (±15°-Spread),
        // 16 m/s, 24 Blöcke Reichweite, 5 HP PROJECTILE-Schaden, Homing-Heilung.
        Bukkit.getPluginManager().registerEvents(new VampireKnivesListener(this), this);

        // Terraria-Magie-Relikte (ehemalige Placeholder): fünf eigenständige
        // Listener für Wasserströme, Giftzähne, Shadowbeam, Harp-Noten und Inferno.
        Bukkit.getPluginManager().registerEvents(new AquaScepterListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PoisonStaffListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ShadowbeamStaffListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MagicalHarpListener(this), this);
        Bukkit.getPluginManager().registerEvents(new InfernoForkListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ChronoStopwatchListener(this), this);
        Bukkit.getPluginManager().registerEvents(new VoidScytheListener(this), this);
        Bukkit.getPluginManager().registerEvents(new NightfallDaggerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DeathScepterListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ShadowCatalystListener(this), this);
        Bukkit.getPluginManager().registerEvents(new com.notschie.relics.listener.SoulAnchorListener(this), this);

        // 10 Neue Kampf-Relikte (Skill-Combos & Absorption)
        Bukkit.getPluginManager().registerEvents(new EchoReaverListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GaleSpearListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ParryBladeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new WitherFangListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ChorusEdgeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BlazeCombustorListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ShulkerBulwarkListener(this), this);
        Bukkit.getPluginManager().registerEvents(new FrostCleaverListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ChainCrossbowListener(this), this);
        Bukkit.getPluginManager().registerEvents(new KineticMaceListener(this), this);

        // Resource-Pack-System: ZIP einbetten + lokaler HTTP-Host + Auto-Push-on-Join + Status-Listener
        resourcePackManager = new ResourcePackManager(this);
        resourcePackManager.enable();
        Bukkit.getPluginManager().registerEvents(new ResourcePackListener(this), this);

        // /relic pack <...> an denselben RelicCommand-Executor hängen (Subcommand "pack")
        PluginCommand existingRelicCmd = getCommand("relic");
        if (existingRelicCmd != null && existingRelicCmd.getExecutor() instanceof RelicCommand) {
            // RelicCommand.dispatchPack() wird genutzt — siehe RelicCommand.handleSub().
        }

        // Periodischer Tracker-Save (alle 30 Sekunden)
        new BukkitRunnable() {
            @Override
            public void run() {
                relicTracker.save();
            }
        }.runTaskTimerAsynchronously(this, 600L, 600L);

        getLogger().info("Relics v" + getDescription().getVersion() + " enabled. " + definitions.size() + " Relikte definiert.");
    }

    @Override
    public void onDisable() {
        relicTracker.save();
        if (resourcePackManager != null) resourcePackManager.disable();
        getLogger().info("Relics disabled.");
    }

    public static RelicsPlugin getInstance() {
        return instance;
    }

    public void loadDefinitions() {
        definitions.clear();
        definitions.putAll(com.notschie.relics.model.RelicRegistry.all());
    }

    public RelicDefinition getDefinition(String id) {
        return definitions.get(id);
    }

    public Map<String, RelicDefinition> allDefinitions() {
        return definitions;
    }

    public RelicFactory getRelicFactory() {
        return relicFactory;
    }

    public RelicTracker getRelicTracker() {
        return relicTracker;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID uuid) {
        this.ownerUuid = uuid;
    }

    public TestsCommand getTestsCommand() {
        return new TestsCommand(this);
    }

    public ResourcePackManager getResourcePackManager() {
        return resourcePackManager;
    }

    // ---------- ResourcePackManager-Schnittstelle (für PackCommand) ----------

    public void setPackRequired(boolean required) {
        if (resourcePackManager == null) return;
        try {
            java.lang.reflect.Field f = ResourcePackManager.class.getDeclaredField("required");
            f.setAccessible(true);
            f.setBoolean(resourcePackManager, required);
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Konnte required-Feld nicht setzen: " + e.getMessage());
        }
    }

    public void setPackPrompt(Component component) {
        if (resourcePackManager == null) return;
        try {
            java.lang.reflect.Field f = ResourcePackManager.class.getDeclaredField("prompt");
            f.setAccessible(true);
            f.set(resourcePackManager, component);
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Konnte prompt-Feld nicht setzen: " + e.getMessage());
        }
    }

    public void setPackHostEnabled(boolean enabled) {
        if (resourcePackManager == null) return;
        try {
            java.lang.reflect.Field f = ResourcePackManager.class.getDeclaredField("hostEnabled");
            f.setAccessible(true);
            f.setBoolean(resourcePackManager, enabled);
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Konnte hostEnabled-Feld nicht setzen: " + e.getMessage());
        }
    }

    public void setPackHostPort(int port) {
        if (resourcePackManager == null) return;
        try {
            java.lang.reflect.Field f = ResourcePackManager.class.getDeclaredField("hostPort");
            f.setAccessible(true);
            f.setInt(resourcePackManager, port);
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Konnte hostPort-Feld nicht setzen: " + e.getMessage());
        }
    }

    public void setPackAutoPushOnJoin(boolean enabled) {
        if (resourcePackManager == null) return;
        try {
            java.lang.reflect.Field f = ResourcePackManager.class.getDeclaredField("autoPushOnJoin");
            f.setAccessible(true);
            f.setBoolean(resourcePackManager, enabled);
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Konnte autoPushOnJoin-Feld nicht setzen: " + e.getMessage());
        }
    }

    /**
     * Liest das ZIP auf Disk neu ein, hasht neu und setzt die Pack-ID neu.
     * Wird von /relic pack reload aufgerufen.
     */
    public void reloadResourcePackFromDisk() {
        if (resourcePackManager == null) return;
        try {
            java.lang.reflect.Method m = ResourcePackManager.class.getDeclaredMethod("extractEmbeddedPackIfNeeded");
            m.setAccessible(true);
            m.invoke(resourcePackManager);
            java.lang.reflect.Method m2 = ResourcePackManager.class.getDeclaredMethod("recomputeHash");
            m2.setAccessible(true);
            m2.invoke(resourcePackManager);
            getLogger().info("Resource-Pack neu von Disk eingelesen. Neuer SHA1: " + resourcePackManager.sha1Hex());
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Reload fehlgeschlagen: " + e.getMessage());
        }
    }
}
