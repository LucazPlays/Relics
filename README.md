# Relics

Legendäre Relikt-Items für Minecraft Paper 1.26.x — einmalig pro Server, UUID-getrackt,
anti-dupe geschützt. Jedes Relikt hat eigene Sounds, Cooldowns, Partikel und
Fähigkeiten.

## Features

- **30 Relikte**, jedes mit eigener Hör-Animation, Cooldown und Combat-Logik
  (Aqua Scepter, Inferno Fork, Magical Harp, Shadowbeam Staff, Poison Staff,
  Bow of Artemis, Mjolnir, Void Scythe, Vampire Knives, Frost Cleaver, …)
- **Ultimate-Fähigkeiten** auf Sneak + Rechtsklick (z. B. Inferno Fork:
  Phoenix Dive, Magical Harp: Grand Finale, Aqua Scepter: Hydro Prison,
  Shadowbeam: Void Rift, Poison: Toxic Rain)
- **Linker Mausklick** als zweite Ability (Note Storm, Infernal Rift, Shadow
  Barrage, Venom Bloom, Maelstrom)
- **Anti-Dupe** über `RelicState` (UUID-Tracking), `/relics give|reset|track`
- **Auto-Resourcepack** beim ersten Join, HTTP-Server im Plugin verteilt die
  zip mit Custom-Item-Modellen + -Texturen
- **Combat-Log-Schutz** für Waypoint Jammer und ähnliche Gameplay-Items

## Build

Voraussetzungen: JDK 21, Maven 3.9+.

```bash
mvn clean package
# → target/Relics-1.0.0.jar
```

Resourcepack wird im laufenden Plugin automatisch gebaut und an Spieler
ausgeliefert; die Build-Skripte (`make_resourcepack.py`, `process_texture.py`,
`gen_runans.py`, `generate_pack.py`) liegen im Repo, falls du manuell
Texturen / Modelle anpassen willst.

## Plugin-Struktur

```
src/main/java/com/notschie/relics/
├── RelicsPlugin.java          # onEnable/onDisable, Listener-Registrierung
├── command/                   # /relics, /pack, /tests
├── listener/                  # 39 Listener, einer pro Item-Typ
├── model/                     # RelicDefinition, RelicState, RelicRegistry
├── util/                      # Tracker, Factory, Utils, ResourcePackManager
└── gui/                       # RelicTrackerGUI
```

## Lizenz

MIT — siehe `LICENSE`.
