# TrackTimer

![Version](https://img.shields.io/badge/version-1.0-blue)
![Paper API](https://img.shields.io/badge/Paper_API-26.2-green)
![Java](https://img.shields.io/badge/Java-25-orange)
![Languages](https://img.shields.io/badge/languages-DE%20%7C%20EN-blue)
[![Wiki](https://img.shields.io/badge/docs-Wiki-blue)](https://github.com/Leahcimkrob/TrackTimer/wiki)

[Deutsch](#deutsch) · [English](#english)

## Deutsch

TrackTimer ist ein Paper-Plugin für Rennstrecken und Zeitrennen in Minecraft.
Spieler können alleine auf Bestzeit fahren oder gemeinsam nach einem
Redstone-Signal starten. Das Plugin erfasst Runden und Checkpoint-Zeiten und
zeigt die Ergebnisse in Bestenlisten und optionalen Hologrammen an.

### Funktionen

- Mehrere Strecken mit eigener Rundenanzahl verwalten.
- Strecken über eine GUI bearbeiten und Start-, Checkpoint-, Ziel- und Redstone-Trigger mit Werkzeugen direkt in der Welt setzen.
- Mehrere Blöcke demselben Checkpoint zuordnen, etwa über die gesamte Streckenbreite.
- Checkpoints in der richtigen Reihenfolge verlangen. Erst nach allen Checkpoints zählt die Ziellinie eine Runde.
- Laufende Zeit in der Bossbar und Checkpoint-Zeiten in der Actionbar anzeigen.
- Rennen zu Fuß, im Minecart oder mit unterstützten Custom-Fahrzeugen fahren.
- Bestenlisten, persönliche Rundenzeiten und Checkpoint-Zeiten in GUIs ansehen.
- Normale Rennen und Ampel-Rennen getrennt auswerten.
- Ergebnis-Hologramme platzieren, die sich nach abgeschlossenen Rennen aktualisieren.
- Livehologramme für normale Rennen und Ampel-Rennen über den Editor oder `/tt hologram addlive <event>` platzieren.
- Platzierung, Spielerkopf und Name, aktuelle Runde und Gesamtzeit der Teilnehmer live anzeigen. Spielerköpfe werden mit CMI unterstützt.
- Aktualisierungsintervall und Anzeigedauer der letzten Ergebnisse einstellen. Die letzte Anzeige bleibt standardmäßig 10 Minuten stehen oder bis zum nächsten Start.
- Einstellungen über eine Config-GUI ändern. Beim Schließen werden Änderungen gespeichert; ohne Änderungen erfolgt kein Reload.
- Deutsche und englische Texte sowie Speicherung mit SQLite oder MySQL/MariaDB.

### Dokumentation

[Installation und erste Schritte](https://github.com/Leahcimkrob/TrackTimer/wiki/Install-Deutsch) · [Befehle](https://github.com/Leahcimkrob/TrackTimer/wiki/Befehle) · [Berechtigungen](https://github.com/Leahcimkrob/TrackTimer/wiki/Berechtigungen)

### Hologramme und optionale Plugins

Für Hologramme wird **CMI** oder **DecentHolograms** benötigt. Ohne beide Plugins
funktioniert TrackTimer weiterhin; die Hologramm-Buttons und der Hologramm-Befehl
sind dann nicht verfügbar. **HeadDatabase** ist optional und stellt die
besonderen Köpfe in den GUIs bereit.

[Hologramme erstellen und entfernen](https://github.com/Leahcimkrob/TrackTimer/wiki/Hologramme)

## English

TrackTimer is a Paper plugin for race tracks and timed races in Minecraft.
Players can race individually for their best time or start together after a
redstone signal. The plugin records laps and checkpoint times and displays
results in leaderboards and optional holograms.

### Features

- Manage multiple tracks with their own lap counts.
- Edit tracks through a GUI and place start, checkpoint, finish and redstone triggers directly in the world using selection tools.
- Assign several blocks to the same checkpoint to cover the width of a track.
- Require checkpoints to be passed in order. The finish line only counts a lap after all checkpoints have been reached.
- Show the running time in a boss bar and checkpoint times in the action bar.
- Race on foot, in minecarts or with supported custom vehicles.
- View leaderboards, personal lap times and checkpoint times through GUIs.
- Keep normal race results and redstone race results separate.
- Place results holograms that update after completed races.
- Place live holograms for normal and redstone races through the editor or `/tt hologram addlive <event>`.
- Show participants' position, player head and name, current lap and total elapsed time live. Player heads are supported with CMI.
- Configure the refresh interval and how long final standings remain visible. By default, they stay for 10 minutes or until the next start.
- Edit settings through a configuration GUI. Changes are saved when it closes; closing without changes does not trigger a reload.
- German and English text, with SQLite or MySQL/MariaDB storage.

### Documentation

[Installation and first steps](https://github.com/Leahcimkrob/TrackTimer/wiki/Install-English) · [Commands](https://github.com/Leahcimkrob/TrackTimer/wiki/Commands) · [Permissions](https://github.com/Leahcimkrob/TrackTimer/wiki/Permissions)

### Holograms and optional plugins

Holograms require **CMI** or **DecentHolograms**. TrackTimer works without either
plugin; hologram buttons and the hologram command are then unavailable.
**HeadDatabase** is optional and supplies the custom heads used in the GUIs.

[Creating and removing holograms](https://github.com/Leahcimkrob/TrackTimer/wiki/Holograms)
