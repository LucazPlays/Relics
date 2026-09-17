package com.notschie.relics.model;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alle Relikt-Definitionen sind hier HARDCODED (keine config.yml).
 * Änderungen erfordern ein Plugin-Rebuild.
 */
public final class RelicRegistry {

    private static final Map<String, RelicDefinition> DEFINITIONS = new LinkedHashMap<>();

    static {
        // ============ SCHWERT DES FEUERS ============
        {
            Map<Enchantment, Integer> enchs = new HashMap<>();
            enchs.put(Enchantment.SHARPNESS, 5);
            enchs.put(Enchantment.FIRE_ASPECT, 2);
            enchs.put(Enchantment.UNBREAKING, 3);
            enchs.put(Enchantment.LOOTING, 3);

            List<String> lore = List.of(
                    "<gray>Ein legendäres Relikt",
                    "<gradient:#ff5555:#ffaa00>Feuer widersteht ihm nicht</gradient>"
            );

            Map<String, Object> effects = new HashMap<>();
            effects.put("on-hit-ignite", 5);           // Sekunden Brand
            effects.put("on-hit-extra-damage", 4.0);   // Extra-Schaden
            // Rechtsklick-AOE: ringförmige Feuer-Explosion um den Spieler.
            // Alle Entities in der Kugel werden angezündet + Direktschaden am Herzen.
            // Persistent-Burn: nach der initialen Explosion läuft ein 5s-Tick (10 Iterationen),
            // der den doppelten Radius abdeckt und alle 0.5s erneut anzündet + Damage dealt.
            // Die Partikel-Optik (Pentagramm, Helix) bleibt am fire-aoe-radius (kompakt),
            // der Schaden-Bereich ist größer (fire-aoe-burn-radius).
            effects.put("fire-aoe-radius", 5);          // Partikel-Visual
            effects.put("fire-aoe-burn-radius", 10);    // doppelt so groß für Damage
            effects.put("fire-aoe-ignite-seconds", 6);
            effects.put("fire-aoe-cooldown-ms", 12000L);
            effects.put("fire-aoe-flat-damage", 4.0);
            effects.put("fire-aoe-damage-per-tick", 1.0); // ½ Herz pro Welle: 5 × 1.0 = 5 HP (2.5 Herzen) über 5s
            effects.put("fire-aoe-iterations", 5);     // 5 Wellen × 1s Abstand
            effects.put("fire-aoe-tick-interval", 20L);   // 1s pro Welle → 5s gesamt
            effects.put("fire-aoe-particle-count", 60);
            effects.put("fire-aoe-sound", true);
            // Feuerschwert bricht Fire-Resistance: Per-Hit und AOE entfernen den
            // Fire-Resistance-Effekt vom getroffenen Target (no-op wenn nicht vorhanden).
            effects.put("on-hit-remove-fire-resistance", true);
            effects.put("fire-aoe-remove-fire-resistance", true);

            // Schwert-Strahl (Shift+Linksklick): wandernde Cone-Hitbox in Blickrichtung.
            // Reichweite 16 Blöcke, voller Cone-Winkel 30° (±15°), 8 Blöcke/s = 2s Flug.
            // Pro Entity genau 1 Treffer (Scoreboard-Tag); 5 HP Direktschaden am Herzen.
            effects.put("fire-swing-enabled", true);
            effects.put("fire-swing-range", 16);
            effects.put("fire-swing-cone-angle", 30.0);
            effects.put("fire-swing-speed", 16.0);
            effects.put("fire-swing-damage", 9.0);
            effects.put("fire-swing-cooldown-ms", 8000L);
            effects.put("fire-swing-particle", true);
            effects.put("fire-swing-sound", true);
            effects.put("fire-swing-tick-step", 1L);
            effects.put("fire-swing-anti-multi-hit", true);

            DEFINITIONS.put("sword_of_fire", new RelicDefinition(
                    "sword_of_fire",
                    Material.NETHERITE_SWORD,
                    "<gradient:#ff2200:#ffaa00><bold>Schwert des Feuers</bold></gradient>",
                    lore,
                    enchs,
                    true,   // glow
                    true,   // unbreakable
                    1001,   // custom-model-data
                    effects,
                    "<gradient:#ff5500:#ffaa00><bold>Feuerklinge</bold> <dark_gray>» <gray>Dein Schwert glüht!"
            ));
        }

        // ============ MJÖLNIR - THORS HAMMER (Axt) ============
        {
            Map<Enchantment, Integer> enchs = new HashMap<>();
            enchs.put(Enchantment.SHARPNESS, 5);
            enchs.put(Enchantment.UNBREAKING, 3);
            // Wind Burst 1 als „unsafe" Enchant — Bukkit würde das normalerweise
            // ablehnen (Wind Burst ist nur für Maces), aber die Relic-Factory nutzt
            // addEnchant(..., ignoreLevelRestriction=true), was erlaubt ist.
            // ACHTUNG: Wenn Wind Burst auf einer Axt vom Server als „illegal" gewertet
            // wird (PvP-Anti-Cheat), ggf. weglassen — semantisch ist es hier als
            // „Schlag wirft Gegner zurück" Effekt gedacht.
            enchs.put(Enchantment.WIND_BURST, 1);

            List<String> lore = List.of(
                    "<gray>Ein legendäres Relikt",
                    "<gradient:#00ddff:#ffff00>Thors Hammer lässt grüßen</gradient>"
            );

            Map<String, Object> effects = new HashMap<>();
            effects.put("held-speed", 1);
            effects.put("on-hit-lightning", true);  // Trigger für RelicEffectListener.onDamage
            // Mjölnir ist eine Netherite-Axt (Basis 10 Schaden, 1.0 Speed).
            // Wir wollen „legendär, aber fair": leicht stärker als Netherite-Axt
            // (10 DPS → 15 DPS = +50 %), aber unter Schwert-des-Feuers-Niveau.
            //   +5 additiv → 15 Schaden (7.5 Herzen) auf Axt mit 1.0 Speed
            //   = 15 DPS, klar über Netherite-Axt, unter Schwert-des-Feuers.
            //
            // Attack-Speed ZIEL 1.0 (absolut); Vanilla-Basis ist 4.0,
            // Netherite-Axt bringt -3.0 → wir setzen -3.0 → 4.0 - 3.0 = 1.0.
            effects.put("held-attack-damage", 8.5);
            effects.put("held-attack-speed", 1.0);
            // Blitz: 5s-Cooldown pro Spieler, 4 HP (2 Herzen) als MAGIC-DamageSource
            // → umgeht Rüstung + Protection komplett (siehe RelicUtils.applyMagicDamage).
            effects.put("lightning-cooldown-ms", 5000L);
            effects.put("lightning-damage", 4.0);

            // === Donner-Aura (Shift+Rechtsklick) ===
            // 5 Sekunden lang alle 2s einen Blitz auf jedes offene Ziel im
            // 90°-Cone (Blickrichtung) / 8-Block-Radius. Spieler und Mobs die
            // KEINEN Block über sich haben (im Freien stehen) bekommen einen
            // MAGIC-bypass Blitz ab. Spawne 5 zufällige Thunderbolts beim
            // Aktivieren + sichtbarer Particle-Ring für die 5s Dauer.
            effects.put("thunder-aura-enabled", true);
            effects.put("thunder-aura-cooldown-ms", 14000L);
            effects.put("thunder-aura-duration-ms", 5000L);
            effects.put("thunder-aura-tick-interval-ms", 2000L);  // 3 Ticks bei 5s
            effects.put("thunder-aura-radius", 8.0);
            effects.put("thunder-aura-cone-deg", 90.0);
            effects.put("thunder-aura-damage", 4.0);  // MAGIC-Bypass
            effects.put("thunder-aura-bolt-count", 5);  // zufällige Bolts beim Aktivieren

            // === Smashdown-Combo (Sneak+Linksklick → Up-Launch → Air-Hit → Slam) ===
            // 1. Sneak+Hit wirft den Gegner in die Luft (Boost +Y + Vorwärts-Velocity).
            // 2. Während der Gegner in der Luft ist, feuert jeder weitere Treffer einen
            //    Blitz am Gegner + Velocity nach unten (Slamdown).
            // 3. Beim Landen: Blitz-Explosion am Boden + Fallschaden wird multipliziert.
            // Cooldown 12s, gilt pro Spieler.
            effects.put("combo-enabled", true);
            effects.put("combo-cooldown-ms", 12000L);
            effects.put("combo-launch-velocity-y", 4.0);     // Hoch-Boost (sichtbar!)
            effects.put("combo-launch-velocity-forward", 0.8); // Vorwärts-Boost
            effects.put("combo-launch-player-velocity-y", 3.0);     // Spieler-Bounce (etwas weniger als Mob)
            effects.put("combo-launch-player-velocity-forward", 1.0); // Spieler nach vorne in Blickrichtung
            effects.put("combo-launch-jump-boost-amplifier", 3);     // Jump IV (Verstärker 3 = Stufe 4)
            effects.put("combo-launch-jump-boost-ticks", 60);         // 3 Sekunden Air-Control
            effects.put("combo-air-downward-velocity", -2.0); // Velocity nach unten beim Air-Hit
            effects.put("combo-air-bolt-damage", 4.0);       // MAGIC-Bypass beim Air-Hit
            effects.put("combo-ground-fall-multiplier", 3.0); // Fallschaden-Multiplier beim Landen
            effects.put("combo-ground-bolt-damage", 6.0);     // MAGIC-Bypass Blitz am Boden

            DEFINITIONS.put("mjolnir", new RelicDefinition(
                    "mjolnir",
                    Material.NETHERITE_AXE,
                    "<gradient:#00d4ff:#ffff00><bold>Mjölnir</bold></gradient>",
                    lore,
                    enchs,
                    true,
                    true,
                    1002,
                    effects,
                    "<gradient:#00ddff:#ffffff><bold>Mjölnir</bold> <dark_gray>» <gray>Donner knistert in deiner Axt."
            ));
        }

        // ============ SCHILD DES ATLAS ============
        {
            Map<Enchantment, Integer> enchs = new HashMap<>();
            enchs.put(Enchantment.UNBREAKING, 3);
            enchs.put(Enchantment.MENDING, 1);

            List<String> lore = List.of(
                    "<gray>Ein legendäres Relikt",
                    "<gradient:#ffaa00:#ffdd99>Trägt das Gewicht der Welt</gradient>"
            );

            Map<String, Object> effects = new HashMap<>();
            effects.put("held-damage-reduction", 25);
            effects.put("held-resistance", 1);
            // Dash-on-Block: Sneak + Block → Dash 4 Blöcke nach vorne, AOE-Schaden.
            effects.put("shield-dash-on-block", true);
            effects.put("shield-dash-cooldown-ms", 12000L);
            effects.put("shield-dash-distance", 4);
            effects.put("shield-dash-speed", 0.7);
            effects.put("shield-dash-damage", 10.0);
            effects.put("shield-dash-particle", true);
            effects.put("shield-dash-sound", true);
            // Final-Damage-Cap (4 Herzen = 8 HP) — schützt vor One-Shots. Greift
            // in RelicEffectListener.onPlayerDamageFinalCap (Priority MONITOR).
            effects.put("shield-damage-cap", 8.0);
            // NoFallDamage-Fenster nach Dash (10s).
            effects.put("shield-nofall-duration-ms", 10000L);

            DEFINITIONS.put("shield_of_atlas", new RelicDefinition(
                    "shield_of_atlas",
                    Material.SHIELD,
                    "<gradient:#ffd700:#aa5500><bold>Schild des Atlas</bold></gradient>",
                    lore,
                    enchs,
                    true,
                    true,
                    1003,
                    effects,
                    "<gradient:#ffd700:#aa5500><bold>Atlas</bold> <dark_gray>» <gray>Du spürst die Macht der Erde."
            ));
        }

        // ============ BOGEN DER ARTEMIS ============
        {
            Map<Enchantment, Integer> enchs = new HashMap<>();
            enchs.put(Enchantment.POWER, 5);
            enchs.put(Enchantment.INFINITY, 1);

            List<String> lore = List.of(
                    "<gray>Ein legendäres Relikt",
                    "<gradient:#55ffaa:#ddffdd>Trifft jedes Ziel</gradient>"
            );

            Map<String, Object> effects = new HashMap<>();
            effects.put("on-hit-extra-damage", 6.0);
            effects.put("on-shoot-pierce", true);
            effects.put("held-speed", 1);
            // === ARTEMIS REWORK v2 ===
            // Linksklick-Boost: 1.4↑ + 1.2→ in Blickrichtung (doppelt so stark), 7s Cooldown.
            effects.put("artemis-jump-cooldown-ms", 7000L);
            effects.put("artemis-jump-velocity-y", 1.4);
            effects.put("artemis-jump-velocity-forward", 1.2);
            // Sneak-Schnellfeuer: 3 Pfeile, 7 Ticks Abstand, 19s Cooldown.
            effects.put("artemis-burst-cooldown-ms", 19000L);
            effects.put("artemis-burst-tick-step", 7L);
            effects.put("artemis-burst-count", 3L);
            // Regen-Pfeile-AOE: 12 Pfeile von oben, 5-Block-Radius, 3 HP/Pfeil.
            effects.put("artemis-rain-count", 12L);
            effects.put("artemis-rain-radius", 5.0);
            effects.put("artemis-rain-damage", 3.0);
            effects.put("artemis-rain-altitude", 15.0);
            // 10s NoFallDamage-Fenster nach Jump-Boost.
            effects.put("artemis-nofall-duration-ms", 10000L);

            DEFINITIONS.put("bow_of_artemis", new RelicDefinition(
                    "bow_of_artemis",
                    Material.BOW,
                    "<gradient:#00ff88:#aaffaa><bold>Bogen der Artemis</bold></gradient>",
                    lore,
                    enchs,
                    true,
                    true,
                    1004,
                    effects,
                    "<gradient:#00ff88:#aaffaa><bold>Artemis</bold> <dark_gray>» <gray>Dein Blick trifft jedes Ziel."
            ));
        }

        // ============ RUNAN'S HURRICANE (LoL-Bogen) ============
        {
            Map<Enchantment, Integer> enchs = new HashMap<>();
            enchs.put(Enchantment.INFINITY, 1);
            enchs.put(Enchantment.POWER, 4);
            // KEIN FLAME mehr — der Hurricane zündet nicht, dafür Auto-Refire-Kette.
            enchs.put(Enchantment.UNBREAKING, 3);

            List<String> lore = List.of(
                    "<gray>Ein legendäres Relikt",
                    "<gradient:#00ffff:#ff00ff>Winde folgen deinem Schuss</gradient>"
            );

            Map<String, Object> effects = new HashMap<>();
            effects.put("multishot-count", 3);
            effects.put("multishot-spread-degrees", 15.0);
            effects.put("auto-refire", true);
            effects.put("auto-refire-angle-degrees", 120.0);
            effects.put("auto-refire-delay-ticks", 10);
            // Max. Reichweite der Auto-Refire-Salve (Blöcke)
            effects.put("auto-refire-max-range", 64.0);
            effects.put("on-hit-extra-damage", 3.0);
            // noDamageTicks nach jedem Pfeiltreffer zurücksetzen (Salven-Kette)
            effects.put("reset-no-damage-ticks", true);
            // === HOMING-ABILITY (Linksklick → alle Hurricane-Pfeile aufs Ziel) ===
            effects.put("homing-ability", true);
            effects.put("homing-cooldown-ms", 8000.0);    // 8s Cooldown
            effects.put("homing-max-range", 32.0);         // 32 Blöcke Sichtlinie (Original-Goal)
            effects.put("homing-cone-angle-deg", 45.0);   // 45° Sichtkegel — näheste LE drin
            effects.put("homing-arrow-max-range", 64.0);  // 64 Blöcke Pfeil-zu-Ziel (Original-Goal)
            // === PARTICLE-TRAIL + LIFETIME ===
            effects.put("particle-lifetime-ticks", 300);  // 15s — Homing-Trigger-Fenster
            effects.put("particle-trail-radius", 0.15);
            effects.put("particle-trail-count", 2);

            DEFINITIONS.put("runans_hurricane", new RelicDefinition(
                    "runans_hurricane",
                    Material.BOW,
                    "<gradient:#00ffff:#ff00ff><bold>Runan's Hurricane</bold></gradient>",
                    lore,
                    enchs,
                    true,
                    true,
                    1005,
                    effects,
                    "<gradient:#00ffff:#ff00ff><bold>Runan's Hurricane</bold> <dark_gray>» <gray>Die Winde gehorchen dir."
            ));
        }

        // ============ DRACHENEI (Double-Jump) ============
        {
            // Keine typischen Bogen-Verzauberungen — Double-Jump ist der Effekt.
            Map<Enchantment, Integer> enchs = new HashMap<>();

            List<String> lore = List.of(
                    "<gray>Ein legendäres Relikt",
                    "<gradient:#b04dff:#ff77ff>Der Hauch des Drachen</gradient>"
            );

            Map<String, Object> effects = new HashMap<>();
            effects.put("double-jump-enabled", true);
            effects.put("double-jump-cooldown-ms", 5000L);
            effects.put("double-jump-vertical-boost", 0.9);
            effects.put("double-jump-horizontal-boost", 1.2);
            effects.put("double-jump-particles", true);
            effects.put("double-jump-sound", true);

            DEFINITIONS.put("dragon_egg", new RelicDefinition(
                    "dragon_egg",
                    Material.DRAGON_EGG,
                    "<gradient:#b04dff:#ff77ff><bold>Drachenei</bold></gradient>",
                    lore,
                    enchs,
                    true,
                    true,
                    1006,
                    effects,
                    "<gradient:#b04dff:#ff77ff><bold>Drachenei</bold> <dark_gray>» <gray>Du spürst die Schwingen des Drachen."
            ));
        }

        // ============ EISGRAB (Eis-Sphäre) ============
        {
            Map<Enchantment, Integer> enchs = new HashMap<>();
            enchs.put(Enchantment.SHARPNESS, 4);
            enchs.put(Enchantment.UNBREAKING, 3);
            enchs.put(Enchantment.MENDING, 1);

            List<String> lore = List.of(
                    "<gray>Ein legendäres Relikt",
                    "<gradient:#aaddff:#ffffff>Der Frost umschließt jeden</gradient>"
            );

            Map<String, Object> effects = new HashMap<>();
            effects.put("ice-sphere-radius", 7);              // Blöcke, inkl. Spieler
            effects.put("ice-sphere-duration-ms", 8000L);     // Eis bleibt 8s
            effects.put("ice-sphere-cooldown-ms", 12000L);    // 12s Cooldown pro Spieler
            effects.put("ice-sphere-particle", true);
            effects.put("ice-sphere-sound", true);
            // Beim Aktivieren der Eissphäre wird der Spieler selbst entflammt (falls er brennt).
            effects.put("ice-sphere-clear-self-fire", true);
            // === ICE-SPHERE AUTO-EFFEKTE: alle Entities in der Sphere bekommen ===
            // Slowness + Mining Fatigue (außer der Anwender selbst).
            effects.put("ice-sphere-apply-effects", true);
            effects.put("ice-sphere-slow-amplifier", 2);     // Slowness II (in der Sphere stärker als Swing)
            effects.put("ice-sphere-slow-duration-ms", 5000L); // 5s
            effects.put("ice-sphere-fatigue-amplifier", 2);  // Mining Fatigue II
            effects.put("ice-sphere-fatigue-duration-ms", 5000L); // 5s

            // Per-Hit-Stacken: Slowness und Mining Fatigue Level steigt pro Hit additiv.
            // Dauer wird bei jedem Hit neu gesetzt (40 Ticks = 2s), Cap ist Amplifier 4
            // (= Level V, Bukkit-Hard-Cap). Das aktuelle Level auf dem Target ist die Quelle.
            effects.put("on-hit-slowness-amplifier-per-hit", 1);
            effects.put("on-hit-slowness-duration-ticks", 40);
            effects.put("on-hit-fatigue-amplifier-per-hit", 1);
            effects.put("on-hit-fatigue-duration-ticks", 40);
            effects.put("on-hit-clear-fire", true);
            effects.put("on-hit-snow-particles", true);

            // === ICE-SWING-ABILITY (Shift+Linksklick → Cone mit Slowness + Mining Fatigue) ===
            effects.put("ice-swing-enabled", true);
            effects.put("ice-swing-range", 16.0);
            effects.put("ice-swing-cone-angle", 30.0);
            effects.put("ice-swing-speed", 8.0);
            // Zusätzlich zur Cone trifft der Swing auch Schaden am Herzen (Direktschaden).
            effects.put("ice-swing-damage", 9.0);
            effects.put("ice-swing-slow-amplifier", 1);    // Slowness I
            effects.put("ice-swing-slow-duration-ms", 5000L); // 5s
            effects.put("ice-swing-fatigue-amplifier", 1); // Mining Fatigue I
            effects.put("ice-swing-fatigue-duration-ms", 5000L); // 5s
            effects.put("ice-swing-cooldown-ms", 8000.0);
            effects.put("ice-swing-particle", true);
            effects.put("ice-swing-sound", true);

            DEFINITIONS.put("ice_grab", new RelicDefinition(
                    "ice_grab",
                    Material.NETHERITE_SWORD,
                    "<gradient:#aaddff:#ffffff><bold>Eisgrab</bold></gradient>",
                    lore,
                    enchs,
                    true,   // glow
                    true,   // unbreakable
                    1007,   // custom-model-data
                    effects,
                    "<gradient:#aaddff:#ffffff><bold>Eisgrab</bold> <dark_gray>» <gray>Rechtsklick für die Kälte."
            ));
        }

        // ============ DONNERKATANA (Teleport-Dash) ============
        {
            Map<Enchantment, Integer> enchs = new HashMap<>();
            enchs.put(Enchantment.SHARPNESS, 5);
            enchs.put(Enchantment.UNBREAKING, 3);
            enchs.put(Enchantment.MENDING, 1);

            List<String> lore = List.of(
                    "<gray>Ein legendäres Relikt",
                    "<gradient:#6666ff:#ffff00>Ein Blitz trägt dich durch die Schlacht</gradient>"
            );

            Map<String, Object> effects = new HashMap<>();
            // === TELEPORT-DASH (Shift+Rechtsklick) ===
            effects.put("lightning-dash-enabled", true);
            effects.put("lightning-dash-max-distance", 12.0);      // 12 Blöcke (stoppt an Wänden)
            effects.put("lightning-dash-cooldown-ms", 10000.0);    // 10s Cooldown
            effects.put("lightning-dash-damage", 9.0);             // 4.5 Herzen Vanilla-Schaden
            effects.put("lightning-dash-hit-radius", 3.0);         // Quer-Radius um Pfad-Linie (großzügig für Mobs)
            effects.put("lightning-dash-step", 0.25);              // Raycast-Schrittweite
            effects.put("lightning-dash-self-speed-amplifier", 2); // Speed II
            effects.put("lightning-dash-self-speed-duration-ms", 3000L); // 3s
            effects.put("lightning-dash-self-jump-amplifier", 2);  // Jump-Boost II
            effects.put("lightning-dash-self-jump-duration-ms", 3000L);  // 3s
            effects.put("lightning-dash-particle", true);
            effects.put("lightning-dash-sound", true);
            effects.put("lightning-dash-trail-count", 8);          // Partikel pro Schritt
            // Nahkampf-Hit: zusätzlich kosmetischer Lightning-Effekt am Trefferpunkt.
            effects.put("on-hit-lightning", true);
            // === RÜCKKEHR-DASH (zweiter Shift+Rechtsklick innerhalb returnWindowMs) ===
            effects.put("lightning-dash-return-window-ms", 700.0);  // 700ms Rückkehr-Fenster
            effects.put("lightning-dash-return-damage", 2.0);      // 1 Herz pro getroffene Entity

            DEFINITIONS.put("thunder_katana", new RelicDefinition(
                    "thunder_katana",
                    Material.NETHERITE_SWORD,
                    "<gradient:#6666ff:#ffff00><bold>Donnerkatana</bold></gradient>",
                    lore,
                    enchs,
                    true,   // glow
                    true,   // unbreakable
                    1009,   // custom-model-data
                    effects,
                    "<gradient:#6666ff:#ffff00><bold>Donnerkatana</bold> <dark_gray>» <gray>Shift+Rechtsklick zum Blitzen."
            ));
        }

        // ============ VAMPIRIC KNIVES ============
        {
            // Absichtlich KEIN SHARPNESS: Schaden soll flach bleiben (~8 HP Nahkampf).
            Map<Enchantment, Integer> enchs = new HashMap<>();
            enchs.put(Enchantment.UNBREAKING, 3);
            enchs.put(Enchantment.MENDING, 1);

            List<String> lore = List.of(
                    "<gray>Ein legendäres Relikt",
                    "<gradient:#660000:#ff5555>Das Blut deiner Feinde nährt dich</gradient>"
            );

            Map<String, Object> effects = new HashMap<>();
            // Rechtsklick-Wurf: 5 ItemDisplays (IRON_SWORD) fliegen in einem ±15°-Spread
            // in echte 3D-Blickrichtung, 32 m/s, Reichweite 24 Blöcke.
            // Treffer-Schaden 5 HP über die normale PROJECTILE-Pipeline
            // (Rüstung + Protection-Potions reduzieren).
            effects.put("vampire-throw-cooldown-ms", 1500L);
            effects.put("vampire-throw-speed", 32.0);
            effects.put("vampire-throw-range", 24);
            effects.put("vampire-throw-hit-radius", 2.0);
            effects.put("vampire-throw-damage", 5.0);
            effects.put("vampire-throw-count", 5);            // Multishot: 5 Schwerter
            effects.put("vampire-throw-spread-degrees", 15.0); // ±15° Spread
            // Heilung: 50% des ausgeteilten Schadens, min 1 HP, max 4 HP pro Treffer.
            effects.put("vampire-heal-fraction", 0.5);
            effects.put("vampire-throw-heal-floor", 1.0);
            effects.put("vampire-throw-heal-ceiling", 4.0);
            effects.put("vampire-throw-particle", true);
            effects.put("vampire-throw-sound", true);
            effects.put("vampire-throw-trail-count", 14);
            effects.put("vampire-homing-particles", 8);
            effects.put("vampire-homing-duration", 10);
            // Nahkampf-Lifesteal: bei jedem normalen Hit regeneriert der Spieler etwas HP.
            effects.put("vampire-lifesteal-melee", 0.5);

            DEFINITIONS.put("vampire_knives", new RelicDefinition(
                    "vampire_knives",
                    Material.NETHERITE_SWORD,
                    "<gradient:#aa0000:#ff3333><bold>Vampiric Knives</bold></gradient>",
                    lore,
                    enchs,
                    true,   // glow
                    true,   // unbreakable
                    1008,   // custom-model-data
                    effects,
                    "<gradient:#aa0000:#ff3333><bold>Vampiric Knives</bold> <dark_gray>» <gray>Du spürst Durst."
            ));
        }

        // ============ STILLER STERN (Locator-Jammer) ============
        {
            List<String> lore = List.of(
                    "<gray>Ein mechanisches Zusatz-Modul",
                    "<dark_gray>— <gray>Keine Waypoints. Kein Tracking.",
                    "<dark_gray>— <gray>Inventar genügt.",
                    "",
                    "<gray>Passiv: Solange in deinem Inventar, wird deine",
                    "<gray>Locator-Übertragungsreichweite auf <white>0</white> gesetzt."
            );

            Map<String, Object> effects = new HashMap<>();
            // Solange das Item im PlayerInventory liegt, setzt der periodische
            // Sync-Task in RelicEffectListener den Spieler-Attribut
            // WAYPOINT_TRANSMIT_RANGE auf diesen Wert (Default 0).
            effects.put("waypoint-transmit-range", 0.0);

            DEFINITIONS.put("waypoint_jammer", new RelicDefinition(
                    "waypoint_jammer",
                    // Mechanisches Device: Redstone-Repeater (klassisches „Gerät"-
                    // Material, kein magisches Item). BlockPlaceEvent wird in
                    // RelicEffectListener gecancelt, damit der Repeater nicht
                    // platziert werden kann.
                    Material.REPEATER,
                    "<gray><bold>Locator-Störsender</bold></gray>",
                    lore,
                    new HashMap<>(),
                    false,  // kein Glow — keine Magie, sondern Technik
                    false,  // nicht unbreakable — mechanisches Bauteil, geht kaputt
                    1010,   // custom-model-data (1010 = Locator-Störsender)
                    effects,
                    "<gray><bold>Locator-Störsender</bold> <dark_gray>» <gray>Störimpuls aktiv."
            ));
        }


        // ============ INVENTAR-SICHERUNG (SEELEN-ANKER) ============
        {
            List<String> lore = List.of(
                    "<gray>Ein mechanisches Zusatz-Modul",
                    "<dark_gray>— <gray>Notfall-Sicherung für 1x Tod.",
                    "<dark_gray>— <gray>Inventar genügt.",
                    "",
                    "<gray>Passiv: Bewahrt bei deinem nächsten Tod dein",
                    "<gray>gesamtes Inventar (KeepInventory).",
                    "<dark_gray>» <gray>Zerbricht und verschwindet nach dem Tod."
            );

            Map<String, Object> effects = new HashMap<>();
            effects.put("keep-inventory", true);
            effects.put("keep-exp", true);
            effects.put("consume-on-death", true);

            DEFINITIONS.put("soul_anchor", new RelicDefinition(
                    "soul_anchor",
                    Material.RECOVERY_COMPASS,
                    "<gray><bold>Inventar-Sicherung</bold></gray>",
                    lore,
                    new HashMap<>(),
                    false,  // kein Glow — Technik-Modul wie Störsender
                    false,  // nicht unbreakable
                    1022,   // custom-model-data (1022 = Seelen-Anker / Inventar-Sicherung)
                    effects,
                    "<gray><bold>Inventar-Sicherung</bold> <dark_gray>» <gray>Notfall-Absicherung aktiv."
            ));
        }

        // ============ TERRARIA-MAGIE-RELIKTE (ehemalige Platzhalter) ============
        // Die CMD-Slots 1011-1015 bleiben absichtlich stabil. So können vorhandene
        // Admin-Shortcuts und spätere Resource-Pack-Modelle dieselben Slots weiter
        // verwenden. Die eigentlichen Fähigkeiten liegen in den fünf separaten
        // Listenern und lesen ihre Balance-Werte aus diesen Effects-Maps.
        {
            // ============ AQUA SCEPTER ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);
                List<String> lore = List.of(
                        "<gray>Ein legendäres Relikt",
                        "<gradient:#00bfff:#eaffff>Gebogene Wasserströme</gradient>"
                );
                Map<String, Object> effects = new HashMap<>();
                effects.put("aqua-cooldown-ms", 650L);
                effects.put("aqua-stream-count", 2);
                effects.put("aqua-stream-spread-degrees", 8.0);
                effects.put("aqua-stream-speed", 30.0); // deutlich schneller als der normale Wasserstrom
                effects.put("aqua-stream-gravity", 0.035);
                effects.put("aqua-stream-range", 32.0);
                effects.put("aqua-stream-damage", 6.0);
                effects.put("aqua-stream-hit-radius", 0.7);
                effects.put("aqua-stream-pierce", 5);
                effects.put("aqua-stream-bounce-lava", true);
                effects.put("aqua-air-drain", 300); // volle Luftleiste sofort leeren
                effects.put("aqua-air-lock-ms", 8000L);
                effects.put("hydro-prison-cooldown-ms", 14000L);
                effects.put("hydro-prison-range", 24.0);
                effects.put("hydro-prison-hit-radius", 1.2);
                effects.put("hydro-prison-radius", 2.0);
                effects.put("hydro-prison-duration-ticks", 70);
                effects.put("hydro-prison-damage", 5.0);
                effects.put("hydro-prison-pull-radius", 6.0);
                effects.put("hydro-prison-pull-strength", 0.35);
                effects.put("maelstrom-cooldown-ms", 12000L);
                effects.put("maelstrom-range", 26.0);
                effects.put("maelstrom-radius", 5.5);
                effects.put("maelstrom-duration-ticks", 100);
                effects.put("maelstrom-pulse-interval", 8);
                effects.put("maelstrom-damage", 3.0);
                effects.put("maelstrom-pull", 0.22);
                DEFINITIONS.put("aqua_scepter", new RelicDefinition(
                        "aqua_scepter", Material.BLAZE_ROD,
                        "<gradient:#00bfff:#eaffff><bold>Aqua Scepter</bold></gradient>",
                        lore, enchs, true, true, 1011, effects,
                        "<gradient:#00bfff:#eaffff><bold>Aqua Scepter</bold> <dark_gray>» <gray>Wasser gehorcht deinem Willen."
                ));
            }

            // ============ POISON STAFF ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);
                List<String> lore = List.of(
                        "<gray>Ein legendäres Relikt",
                        "<gradient:#66ff22:#ccff66>Giftzähne durchbohren deine Feinde</gradient>"
                );
                Map<String, Object> effects = new HashMap<>();
                effects.put("poison-cooldown-ms", 1000L);
                effects.put("poison-fang-count", 3);
                effects.put("poison-spread-degrees", 30.0);
                effects.put("poison-fang-speed", 24.0);
                effects.put("poison-fang-range", 31.0);
                effects.put("poison-fang-damage", 7.0);
                effects.put("poison-fang-hit-radius", 0.65);
                effects.put("poison-fang-max-targets", 3);
                effects.put("poison-duration-ticks", 600); // 30 Sekunden
                effects.put("poison-amplifier", 0);
                effects.put("toxic-rain-cooldown-ms", 14000L);
                effects.put("toxic-rain-range", 26.0);
                effects.put("toxic-rain-radius", 6.0);
                effects.put("toxic-rain-duration-ticks", 100);
                effects.put("toxic-rain-pulse-interval", 10);
                effects.put("toxic-rain-damage", 2.5);
                effects.put("toxic-rain-poison-ticks", 100);
                effects.put("venom-bloom-cooldown-ms", 11000L);
                effects.put("venom-bloom-range", 22.0);
                effects.put("venom-bloom-radius", 5.0);
                effects.put("venom-bloom-duration-ticks", 90);
                effects.put("venom-bloom-pulse-interval", 8);
                effects.put("venom-bloom-damage", 3.0);
                effects.put("venom-bloom-poison-ticks", 140);
                DEFINITIONS.put("poison_staff", new RelicDefinition(
                        "poison_staff", Material.BLAZE_ROD,
                        "<gradient:#66ff22:#ccff66><bold>Poison Staff</bold></gradient>",
                        lore, enchs, true, true, 1012, effects,
                        "<gradient:#66ff22:#ccff66><bold>Poison Staff</bold> <dark_gray>» <gray>Gift liegt in der Luft."
                ));
            }

            // ============ SHADOWBEAM STAFF ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);
                List<String> lore = List.of(
                        "<gray>Ein legendäres Relikt",
                        "<gradient:#762cff:#d7aaff>Ein Strahl aus der Tiefe</gradient>"
                );
                Map<String, Object> effects = new HashMap<>();
                effects.put("shadowbeam-cooldown-ms", 900L);
                effects.put("shadowbeam-range", 84.0); // verbesserte normale Reichweite
                effects.put("shadowbeam-step", 0.1);
                effects.put("shadowbeam-hit-radius", 0.32);
                effects.put("shadowbeam-damage", 9.0);
                effects.put("shadowbeam-damage-decay", 0.10);
                effects.put("shadowbeam-max-bounces", 12); // deutlich mehr Wandreflexionen
                effects.put("void-rift-cooldown-ms", 16000L);
                effects.put("void-rift-range", 28.0);
                effects.put("void-rift-radius", 4.5);
                effects.put("void-rift-duration-ticks", 100);
                effects.put("void-rift-pulse-interval", 8);
                effects.put("void-rift-damage", 4.0);
                effects.put("void-rift-pull", 0.12);
                effects.put("shadow-barrage-cooldown-ms", 12000L);
                effects.put("shadow-barrage-count", 7);
                effects.put("shadow-barrage-spread-degrees", 26.0);
                effects.put("shadow-barrage-range", 32.0);
                effects.put("shadow-barrage-damage", 6.0);
                effects.put("shadow-barrage-max-bounces", 2);
                DEFINITIONS.put("shadowbeam_staff", new RelicDefinition(
                        "shadowbeam_staff", Material.BLAZE_ROD,
                        "<gradient:#762cff:#d7aaff><bold>Shadowbeam Staff</bold></gradient>",
                        lore, enchs, true, true, 1013, effects,
                        "<gradient:#762cff:#d7aaff><bold>Shadowbeam Staff</bold> <dark_gray>» <gray>Schatten werden zum Strahl."
                ));
            }

            // ============ MAGICAL HARP ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);
                List<String> lore = List.of(
                        "<gray>Ein legendäres Relikt",
                        "<gradient:#be41ff:#f0c2ff>Noten, die durch Wände tanzen</gradient>"
                );
                Map<String, Object> effects = new HashMap<>();
                effects.put("harp-cooldown-ms", 160L);
                effects.put("harp-note-speed", 16.0);
                effects.put("harp-note-range", 48.0);
                effects.put("harp-note-damage", 6.0);
                effects.put("harp-note-hit-radius", 0.8);
                effects.put("harp-note-max-hits", 5);
                effects.put("harp-note-max-bounces", 24);
                effects.put("harp-note-lifetime-ticks", 60); // 3 Sekunden
                effects.put("note-storm-cooldown-ms", 10000L);
                effects.put("note-storm-count", 14);
                effects.put("note-storm-spread-degrees", 34.0);
                effects.put("note-storm-speed", 19.0);
                effects.put("note-storm-range", 30.0);
                effects.put("note-storm-damage", 4.0);
                effects.put("note-storm-max-hits", 2);
                effects.put("note-storm-lifetime-ticks", 40);
                effects.put("grand-finale-cooldown-ms", 18000L);
                effects.put("grand-finale-duration-ticks", 50);
                effects.put("grand-finale-pulse-interval", 8);
                effects.put("grand-finale-radius", 8.0);
                effects.put("grand-finale-damage", 5.0);
                effects.put("grand-finale-knockback", 0.7);
                DEFINITIONS.put("magical_harp", new RelicDefinition(
                        "magical_harp", Material.BOW,
                        "<gradient:#be41ff:#f0c2ff><bold>Magical Harp</bold></gradient>",
                        lore, enchs, true, true, 1014, effects,
                        "<gradient:#be41ff:#f0c2ff><bold>Magical Harp</bold> <dark_gray>» <gray>Jede Note findet ihr Ziel."
                ));
            }

            // ============ INFERNO FORK ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);
                List<String> lore = List.of(
                        "<gray>Ein legendäres Relikt",
                        "<gradient:#ff4b00:#ffd000>Ein Feuer, das nicht vergeht</gradient>"
                );
                Map<String, Object> effects = new HashMap<>();
                effects.put("inferno-cooldown-ms", 3200L); // Legacy-Key = normaler Rechtsklick
                effects.put("inferno-normal-cooldown-ms", 3200L);
                effects.put("inferno-projectile-speed", 10.0);
                effects.put("inferno-projectile-gravity", 0.018);
                effects.put("inferno-projectile-range", 32.0);
                effects.put("inferno-projectile-hit-radius", 0.8);
                effects.put("inferno-direct-damage", 5.0);
                effects.put("inferno-zone-radius", 4.7);
                effects.put("inferno-zone-duration-ticks", 45); // 2,25 Sekunden
                effects.put("inferno-zone-pulse-interval", 5);
                effects.put("inferno-zone-damage", 3.0);
                effects.put("inferno-hellfire-duration-ticks", 240); // 12 Sekunden
                effects.put("inferno-hellfire-amplifier", 0);
                effects.put("phoenix-dive-cooldown-ms", 14000L);
                effects.put("phoenix-dive-step", 1.35);
                effects.put("phoenix-dive-duration-ticks", 9);
                effects.put("phoenix-dive-impact-radius", 4.5);
                effects.put("phoenix-dive-impact-damage", 8.0);
                effects.put("infernal-rift-cooldown-ms", 12000L);
                effects.put("infernal-rift-range", 22.0);
                effects.put("infernal-rift-radius", 2.5);
                effects.put("infernal-rift-duration-ticks", 75);
                effects.put("infernal-rift-pulse-interval", 6);
                effects.put("infernal-rift-damage", 3.5);
                DEFINITIONS.put("inferno_fork", new RelicDefinition(
                        "inferno_fork", Material.BLAZE_ROD,
                        "<gradient:#ff4b00:#ffd000><bold>Inferno Fork</bold></gradient>",
                        lore, enchs, true, true, 1015, effects,
                        "<gradient:#ff4b00:#ffd000><bold>Inferno Fork</bold> <dark_gray>» <gray>Die Hölle folgt deinem Wurf."
                ));
            }

            // ============ CHRONOS TASCHENUHR ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Ein legendäres Relikt außerhalb der Zeit",
                        "<gradient:#ffd700:#00ffff>Beuge die Sekunden nach deinem Willen</gradient>",
                        "",
                        "<gray>Rechtsklick:</gray> <white>Temporale Rückspulung</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Stasis-Feld (Slowmotion-Zone)</white>",
                        "<gray>Passiv:</gray> <white>Notfall-Zeitsprung + Regeneration</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("recall-cooldown-ms", 28000L);
                effects.put("recall-duration-ms", 5000L);
                effects.put("bubble-cooldown-ms", 20000L);
                effects.put("bubble-duration-ticks", 80);
                effects.put("bubble-radius", 6.0);
                effects.put("bubble-tickrate", 6.0f);
                effects.put("cheat-death-cooldown-ms", 90000L);
                effects.put("cheat-death-threshold-hp", 4.0);
                effects.put("cheat-death-regen-duration-ticks", 100);

                DEFINITIONS.put("chrono_stopwatch", new RelicDefinition(
                        "chrono_stopwatch", Material.CLOCK,
                        "<gradient:#ffd700:#00ffff><bold>Chronos Taschenuhr</bold></gradient>",
                        lore, enchs, true, true, 1016, effects,
                        "<gradient:#ffd700:#00ffff><bold>Chronos Taschenuhr</bold> <dark_gray>» <gray>Die Zeit beugt sich deinem Willen."
                ));
            }

            // ============ SENSE DER LEERE (Void Scythe) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.SHARPNESS, 5);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Ein legendäres Relikt aus dem Abgrund",
                        "<gradient:#aa00ff:#ff00ff>Ernte die Seelen deiner Feinde</gradient>",
                        "",
                        "<gray>Rechtsklick:</gray> <white>Ereignishorizont (Vortex Pull)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Dimensionsriss (Shield-Break & Pull)</white>",
                        "<gray>Passiv / On-Hit:</gray> <white>Seelenernte (Magic Detonation bei 5 Hits)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("held-attack-damage", 8.0);
                effects.put("held-attack-speed", 1.2);
                effects.put("vortex-cooldown-ms", 13000L);
                effects.put("vortex-damage", 6.0);
                effects.put("vortex-range", 16.0);
                effects.put("cleave-cooldown-ms", 8000L);
                effects.put("cleave-damage", 7.0);
                effects.put("soul-harvest-detonation-damage", 4.0);

                DEFINITIONS.put("void_scythe", new RelicDefinition(
                        "void_scythe", Material.NETHERITE_HOE,
                        "<gradient:#aa00ff:#440088><bold>Sense der Leere</bold></gradient>",
                        lore, enchs, true, true, 1017, effects,
                        "<gradient:#aa00ff:#ff00ff><bold>Sense der Leere</bold> <dark_gray>» <gray>Der Abgrund hungert nach Seelen."
                ));
            }

            // ============ SCHATTENKLINGE DER NACHT (Nightfall Dagger) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.SHARPNESS, 5);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Ein legendäres Relikt der Schatten",
                        "<gradient:#220033:#aa00ff>Der lautlose Schnitt im Dunkeln</gradient>",
                        "",
                        "<gray>Shift + Rechtsklick:</gray> <white>Schattenschleier (True Stealth)</white>",
                        "<gray>Rechtsklick:</gray> <white>Schattenbombe (Hybrid-Dmg, Blind & Silence)</white>",
                        "<gray>Passiv / On-Hit:</gray> <white>Schatten-Hybrid-Schaden (+True Damage)</white>",
                        "<gray>Meuchelmord:</gray> <white>Massiver True-Damage-Backstab + Wither II</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("stealth-cooldown-ms", 16000L);
                effects.put("smoke-cooldown-ms", 12000L);
                effects.put("backstab-multiplier", 1.5);
                effects.put("backstab-wither-duration-ticks", 80);
                effects.put("stealth-slowness-duration-ticks", 30);

                DEFINITIONS.put("nightfall_dagger", new RelicDefinition(
                        "nightfall_dagger", Material.NETHERITE_SWORD,
                        "<gradient:#220033:#7700aa><bold>Schattenklinge der Nacht</bold></gradient>",
                        lore, enchs, true, true, 1018, effects,
                        "<gradient:#220033:#aa00ff><bold>Schattenklinge der Nacht</bold> <dark_gray>» <gray>Schatten umhüllen deine Klinge."
                ));
            }

            // ============ PHANTOMSCHLINGE (Ghost Harpoon) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Ein legendäres Relikt aus Ektoplasma & Geisterketten",
                        "<gradient:#00f5d4:#00b4d8>Greife nach den Seelen der Lebenden</gradient>",
                        "",
                        "<gray>Rechtsklick:</gray> <white>Seelenkette (Grapple & Reel)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Spektral-Leash (Anti-Escape Anker)</white>",
                        "<gray>Linksklick (Nahkampf):</gray> <white>Spektraler Überwurf (Slam)</white>",
                        "<gray>Passiv:</gray> <white>Ätherisches Momentum & Seelenbeute</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("grapple-cooldown-ms", 4500L);
                effects.put("grapple-range", 24.0);
                effects.put("grapple-damage", 5.0);
                effects.put("leash-cooldown-ms", 18000L);
                effects.put("leash-duration-ticks", 80);
                effects.put("leash-radius", 6.0);
                effects.put("slam-cooldown-ms", 12000L);
                effects.put("slam-damage", 6.0);

                DEFINITIONS.put("phantom_hook", new RelicDefinition(
                        "phantom_hook", Material.FISHING_ROD,
                        "<gradient:#00f5d4:#0077b6><bold>Phantomschlinge</bold></gradient>",
                        lore, enchs, true, true, 1019, effects,
                        "<gradient:#00f5d4:#00b4d8><bold>Phantomschlinge</bold> <dark_gray>» <gray>Geisterketten durchdringen den Raum."
                ));
            }

            // ============ SZEPTER DES TODESREICHS (Death Scepter) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.SHARPNESS, 5);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Ein legendäres Relikt aus dem Totenreich",
                        "<gradient:#00ff88:#004422>Beherrsche die Seelen der Unterwelt</gradient>",
                        "",
                        "<gray>Shift + Rechtsklick:</gray> <white>Reich der Toten (1v1 Domain)</white>",
                        "<gray>Rechtsklick:</gray> <white>Auslöschung (+100% Isolationsschaden)</white>",
                        "<gray>Shift + Linksklick:</gray> <white>Todesgriff (Klaue zieht Feinde heran)</white>",
                        "<gray>Passiv:</gray> <white>Aufsteigende Dunkelheit (Seelen-Mahlstrom)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("realm-cooldown-ms", 40000L);
                effects.put("realm-duration-ticks", 120);
                effects.put("realm-radius", 10.0);
                effects.put("obliterate-cooldown-ms", 6000L);
                effects.put("obliterate-base-damage", 4.5);
                effects.put("obliterate-isolated-damage", 8.0);
                effects.put("grasp-cooldown-ms", 12000L);
                effects.put("grasp-damage", 2.5);
                effects.put("held-attack-damage", 8.5);
                effects.put("held-attack-speed", 1.6);

                DEFINITIONS.put("death_scepter", new RelicDefinition(
                        "death_scepter", Material.NETHERITE_SWORD,
                        "<gradient:#00ff88:#004422><bold>Szepter des Todesreichs</bold></gradient>",
                        lore, enchs, true, true, 1020, effects,
                        "<gradient:#00ff88:#004422><bold>Szepter des Todesreichs</bold> <dark_gray>» <gray>Das Reich der Toten fordert deinen Tribut."
                ));
            }

            // ============ SCHATTENKATALYSATOR (Shadow Catalyst) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.SHARPNESS, 5);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Ein legendäres Ninja-Artefakt aus Schattenkristall",
                        "<gradient:#aa00ff:#ff00aa>Meistere die Kunst der Schatten-Klone</gradient>",
                        "",
                        "<gray>Rechtsklick:</gray> <white>Lebender Schatten (Phantom & Tausch)</white>",
                        "<gray>Linksklick:</gray> <white>Schatten-Doppelwurf (Hybrid-Schaden & Synergie)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Todesurteil (Hybrid-Detonation bis 1 Herz)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("shadow-cooldown-ms", 14000L);
                effects.put("shadow-duration-ms", 5000L);
                effects.put("shuriken-cooldown-ms", 5000L);
                effects.put("shuriken-base-damage", 3.0);
                effects.put("death-mark-cooldown-ms", 30000L);
                effects.put("death-mark-pop-percent", 0.35);
                effects.put("held-attack-damage", 8.5);
                effects.put("held-attack-speed", 1.6);

                DEFINITIONS.put("shadow_catalyst", new RelicDefinition(
                        "shadow_catalyst", Material.ECHO_SHARD,
                        "<gradient:#aa00ff:#5500aa><bold>Schattenkatalysator</bold></gradient>",
                        lore, enchs, true, true, 1021, effects,
                        "<gradient:#aa00ff:#ff00ff><bold>Schattenkatalysator</bold> <dark_gray>» <gray>Die Schatten flüstern dein Todesurteil."
                ));
            }

            // ============ 1. SCULK-WELLENBRECHER (Echo Reaver) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.SHARPNESS, 5);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Eine flüsternde Sense aus den dunkelsten Tiefen",
                        "<gradient:#00ffff:#003344>Entfessle die Schwingungen des Deep Darks</gradient>",
                        "",
                        "<gray>Passiv:</gray> <white>Schall-Resonanz (Jeder 3. Hit = 4 HP Magic-DMG + 2 Absorptions-Herzen)</white>",
                        "<gray>Rechtsklick:</gray> <white>Schall-Stoß (8m Strahl stoppt Sprint & bricht Schild)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Echo-Parry (0.8s Abwehr reflektiert Treffer + 3 Absorptions-Herzen)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("held-attack-damage", 9.0);
                effects.put("held-attack-speed", 1.2);
                effects.put("sonic-thrust-cooldown-ms", 6000L);
                effects.put("echo-parry-cooldown-ms", 12000L);

                DEFINITIONS.put("echo_reaver", new RelicDefinition(
                        "echo_reaver", Material.NETHERITE_HOE,
                        "<gradient:#00ffff:#003344><bold>Sculk-Wellenbrecher</bold></gradient>",
                        lore, enchs, true, true, 1023, effects,
                        "<gradient:#00ffff:#003344><bold>Sculk-Wellenbrecher</bold> <dark_gray>» <gray>Der Schrei der Tiefe hallt durch deine Klinge."
                ));
            }

            // ============ 2. WINDTÄNZER-GLEVE (Gale Spear) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.SHARPNESS, 5);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);
                enchs.put(Enchantment.LOYALTY, 3);

                List<String> lore = List.of(
                        "<gray>Geschmiedet aus den Wirbelstürmen der Trial Chambers",
                        "<gradient:#d0e0ff:#7799ee>Beherrsche die Lüfte und zerschmettere Feinde im Flug</gradient>",
                        "",
                        "<gray>Passiv:</gray> <white>Aufwind-Juggle (+40% DMG auf Gegner in der Luft + Cooldown-Reset)</white>",
                        "<gray>Rechtsklick:</gray> <white>Breeze-Sprungstoß (6m Stoß schleudert beide 4m hoch)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Windwirbel (360° Knockback, lenkt Pfeile ab + 2 Absorptions-Herzen)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("held-attack-damage", 8.5);
                effects.put("held-attack-speed", 1.4);
                effects.put("thrust-cooldown-ms", 6000L);
                effects.put("sweep-cooldown-ms", 10000L);

                DEFINITIONS.put("gale_spear", new RelicDefinition(
                        "gale_spear", Material.TRIDENT,
                        "<gradient:#d0e0ff:#7799ee><bold>Windtänzer-Gleve</bold></gradient>",
                        lore, enchs, true, true, 1024, effects,
                        "<gradient:#d0e0ff:#7799ee><bold>Windtänzer-Gleve</bold> <dark_gray>» <gray>Der Wind trägt deine Schläge empor."
                ));
            }

            // ============ 3. DUELLANTEN-RAPIER (Parry Blade) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.SHARPNESS, 5);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Ein meisterhaft ausbalanciertes Fechtschwert alter Schwertmeister",
                        "<gradient:#ffffff:#aaccff>Perfektioniere dein Timing und bestrafe jeden Fehler</gradient>",
                        "",
                        "<gray>Rechtsklick:</gray> <white>Perfekter Parry (0.5s Fenster: Stunnt Feind, bricht Schild + 2 Absorpt. + Crit)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Fecht-Ausfallschritt (Ausweichen nach hinten + 7m Stoß mit Slowness)</white>",
                        "<gray>Passiv:</gray> <white>Riposte-Crit (+6 HP Direktschaden nach erfolgreichem Parry)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("held-attack-damage", 8.0);
                effects.put("held-attack-speed", 1.8);
                effects.put("parry-cooldown-ms", 9000L);
                effects.put("lunge-cooldown-ms", 7000L);

                DEFINITIONS.put("parry_blade", new RelicDefinition(
                        "parry_blade", Material.NETHERITE_SWORD,
                        "<gradient:#ffffff:#aaccff><bold>Duellanten-Rapier</bold></gradient>",
                        lore, enchs, true, true, 1025, effects,
                        "<gradient:#ffffff:#aaccff><bold>Duellanten-Rapier</bold> <dark_gray>» <gray>Ein Klingenstreich im perfekten Moment."
                ));
            }

            // ============ 4. SEELENDORN DES WITHERS (Wither Fang) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.SHARPNESS, 5);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Geschmiedet in den Seelenfeuern der Nether-Festungen",
                        "<gradient:#222222:#888888>Zersetze Fleisch und verbrenne jede fremde Heilung</gradient>",
                        "",
                        "<gray>Passiv:</gray> <white>Seelenbrand (3 Treffer: 5 HP Magieschaden, 3 HP Heal & 50% Anti-Heal)</white>",
                        "<gray>Rechtsklick:</gray> <white>Seelenstrahl (10m Strahl zieht Feind 4m heran + Wither II)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Asche-Entladung (4m Wolke: Blindness & -30% gegnerischer Schaden)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("held-attack-damage", 8.5);
                effects.put("held-attack-speed", 1.6);
                effects.put("grasp-cooldown-ms", 8000L);
                effects.put("ash-cooldown-ms", 14000L);

                DEFINITIONS.put("wither_fang", new RelicDefinition(
                        "wither_fang", Material.NETHERITE_SWORD,
                        "<gradient:#222222:#888888><bold>Seelendorn des Withers</bold></gradient>",
                        lore, enchs, true, true, 1026, effects,
                        "<gradient:#222222:#888888><bold>Seelendorn des Withers</bold> <dark_gray>» <gray>Verwelkende Schatten verzehren das Leben."
                ));
            }

            // ============ 5. CHORUSKLINGE DER RISSE (Chorus Edge) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.SHARPNESS, 5);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Gefüllt mit instabiler Chorus-Energie aus dem Ende der Welt",
                        "<gradient:#cc44ff:#ff88ff>Teleportiere hinter Feinde und überrasche sie aus dem toten Winkel</gradient>",
                        "",
                        "<gray>Passiv:</gray> <white>Flanken-Hinterhalt (Schlag von hinten nach Blink = 40% Rüstungsdurchschlag + 2 Absorpt.)</white>",
                        "<gray>Rechtsklick:</gray> <white>Phasen-Schritt (6m Vorwärts-Teleport durch Feinde mit Schnittschaden)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Riss-Tausch (12m Projektil tauscht Positionen & dreht Feind um 180°)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("held-attack-damage", 8.5);
                effects.put("held-attack-speed", 1.6);
                effects.put("blink-cooldown-ms", 6000L);
                effects.put("swap-cooldown-ms", 16000L);

                DEFINITIONS.put("chorus_edge", new RelicDefinition(
                        "chorus_edge", Material.NETHERITE_SWORD,
                        "<gradient:#cc44ff:#ff88ff><bold>Chorusklinge der Risse</bold></gradient>",
                        lore, enchs, true, true, 1027, effects,
                        "<gradient:#cc44ff:#ff88ff><bold>Chorusklinge der Risse</bold> <dark_gray>» <gray>Der Raum beugt sich deiner Klinge."
                ));
            }

            // ============ 6. PYROMANTEN-FLEGEL (Blaze Combustor) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.SHARPNESS, 5);
                enchs.put(Enchantment.FIRE_ASPECT, 2);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Ein kochender Flegel aus dem Herzen einer Lohen-Festung",
                        "<gradient:#ff5500:#ffff00>Lade pure Hitze auf und schmelze feindliche Abwehr</gradient>",
                        "",
                        "<gray>Passiv:</gray> <white>Überhitzung (Schläge laden Hitze auf; bei 100% bricht Schild + 2 Absorptions-Herzen)</white>",
                        "<gray>Rechtsklick:</gray> <white>Flammenpeitsche (7m Kegel zieht Ziel heran + Feuerschaden)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Magma-Barriere (2.5s Barriere verbrennt Pfeile & reflektiert Schaden)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("held-attack-damage", 8.5);
                effects.put("held-attack-speed", 1.5);
                effects.put("lash-cooldown-ms", 6000L);
                effects.put("barrier-cooldown-ms", 12000L);

                DEFINITIONS.put("blaze_combustor", new RelicDefinition(
                        "blaze_combustor", Material.NETHERITE_SWORD,
                        "<gradient:#ff5500:#ffff00><bold>Pyromanten-Flegel</bold></gradient>",
                        lore, enchs, true, true, 1028, effects,
                        "<gradient:#ff5500:#ffff00><bold>Pyromanten-Flegel</bold> <dark_gray>» <gray>Lodernde Lohenglut brennt in deinen Adern."
                ));
            }

            // ============ 7. SHULKER-RESONATOR (Shulker Bulwark) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Ein Kinetik-Schild aus verdichteten Shulker-Schalen",
                        "<gradient:#ff88ff:#aa00aa>Lade Angriffe ab und hebe Gegner in die Schwerelosigkeit</gradient>",
                        "",
                        "<gray>Passiv:</gray> <white>Kinetik-Absorb (Perfekter Block lädt Resonanz für +2 Absorptions-Herzen)</white>",
                        "<gray>Rechtsklick:</gray> <white>Schwebegeschoss (12m Projektil verleiht 1.5s Levitation II)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Kinetische Schale (2s Resistenz III; platzt auf und stößt Feinde weg)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("orb-cooldown-ms", 10000L);
                effects.put("fortify-cooldown-ms", 16000L);

                DEFINITIONS.put("shulker_bulwark", new RelicDefinition(
                        "shulker_bulwark", Material.SHIELD,
                        "<gradient:#ff88ff:#aa00aa><bold>Shulker-Resonator</bold></gradient>",
                        lore, enchs, true, true, 1029, effects,
                        "<gradient:#ff88ff:#aa00aa><bold>Shulker-Resonator</bold> <dark_gray>» <gray>Kinetische Resonanz fängt jede Erschütterung ab."
                ));
            }

            // ============ 8. FROSTBEIL DER TUNDRA (Frost Cleaver) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.SHARPNESS, 5);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Eine uralte Nordmann-Kriegsaxt, bedeckt von ewigem Permafrost",
                        "<gradient:#00ffff:#0088cc>Friere deine Beute ein und zerschmettere die Eisschicht</gradient>",
                        "",
                        "<gray>Passiv:</gray> <white>Eisbrecher (3 Kältestacks = +5 HP Bonusschaden, 1.2s Root & 2 Absorptions-Herzen)</white>",
                        "<gray>Rechtsklick:</gray> <white>Frostbeil-Wurf & Fang (12m Wurf bricht Schilde; Fangen halbiert Cooldown)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Frosthauch (Löscht Feuer sofort & senkt gegnerischen Attack-Speed um 30%)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("held-attack-damage", 9.5);
                effects.put("held-attack-speed", 1.0);
                effects.put("toss-cooldown-ms", 8000L);
                effects.put("gust-cooldown-ms", 12000L);

                DEFINITIONS.put("frost_cleaver", new RelicDefinition(
                        "frost_cleaver", Material.NETHERITE_AXE,
                        "<gradient:#00ffff:#0088cc><bold>Frostbeil der Tundra</bold></gradient>",
                        lore, enchs, true, true, 1030, effects,
                        "<gradient:#00ffff:#0088cc><bold>Frostbeil der Tundra</bold> <dark_gray>» <gray>Der Atem des ewigen Eises gefriert dein Blut."
                ));
            }

            // ============ 9. ARKANER REPETIER-WERFER (Chain Crossbow) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.QUICK_CHARGE, 3);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Eine präzise modifizierte Schnellfeuer-Armbrust mit arkaner Spannfeder",
                        "<gradient:#00ddff:#0044aa>Reiße Salven ab und halte aggressive Nahkämpfer auf Distanz</gradient>",
                        "",
                        "<gray>Passiv:</gray> <white>Scharfschützen-Kette (2 Treffer in Folge = 3. Schuss piercend + 2 Absorpt. + Speed)</white>",
                        "<gray>Rechtsklick:</gray> <white>Rückstoß-Salvenschuss (Feuert 3er-Fächer & wirft dich 4m nach hinten / Disengage)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Fangnetz-Bolzen (1.5s Root / Fesselung)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("recoil-cooldown-ms", 7000L);
                effects.put("snare-cooldown-ms", 14000L);

                DEFINITIONS.put("chain_crossbow", new RelicDefinition(
                        "chain_crossbow", Material.CROSSBOW,
                        "<gradient:#00ddff:#0044aa><bold>Arkaner Repetier-Werfer</bold></gradient>",
                        lore, enchs, true, true, 1031, effects,
                        "<gradient:#00ddff:#0044aa><bold>Arkaner Repetier-Werfer</bold> <dark_gray>» <gray>Pfeilschnelle Salven durchdringen jeden Schild."
                ));
            }

            // ============ 10. SEISMISCHER SCHLAGHAMMER (Kinetic Mace) ============
            {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                enchs.put(Enchantment.SHARPNESS, 5);
                enchs.put(Enchantment.UNBREAKING, 3);
                enchs.put(Enchantment.MENDING, 1);

                List<String> lore = List.of(
                        "<gray>Ein titanischer Belagerungshammer, dessen Wucht tektonische Wellen erzeugt",
                        "<gradient:#ff6600:#662200>Zerschmettere den Boden und beherrsche das Schlachtfeld</gradient>",
                        "",
                        "<gray>Passiv:</gray> <white>Boden-Resonanz (Sprung-Crits erzeugen Schockwellen + 2 Absorptions-Herzen)</white>",
                        "<gray>Rechtsklick:</gray> <white>Schockwellen-Spalte (7m Erdstoß wirft Feinde 2.5m hoch)</white>",
                        "<gray>Shift + Rechtsklick:</gray> <white>Titan-Schlag (3m Sprung-Einschlag zieht Feinde heran + Mining Fatigue)</white>"
                );

                Map<String, Object> effects = new HashMap<>();
                effects.put("held-attack-damage", 9.5);
                effects.put("held-attack-speed", 0.9);
                effects.put("shockwave-cooldown-ms", 6000L);
                effects.put("titan-cooldown-ms", 14000L);

                DEFINITIONS.put("kinetic_mace", new RelicDefinition(
                        "kinetic_mace", Material.NETHERITE_AXE,
                        "<gradient:#ff6600:#662200><bold>Seismischer Schlaghammer</bold></gradient>",
                        lore, enchs, true, true, 1032, effects,
                        "<gradient:#ff6600:#662200><bold>Seismischer Schlaghammer</bold> <dark_gray>» <gray>Die Erde bebt unter deinen Schritten."
                ));
            }
        }
    }

    public static Map<String, RelicDefinition> all() {
        return DEFINITIONS;
    }

    public static RelicDefinition get(String id) {
        return DEFINITIONS.get(id);
    }

    public static boolean exists(String id) {
        return DEFINITIONS.containsKey(id);
    }

    private RelicRegistry() {}
}