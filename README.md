# TrackTimer

TrackTimer ist ein Paper-Plugin zur Verwaltung von Rennstrecken und
Zeitfahr-Events auf Minecraft-Servern. Es erfasst Start-, Zwischen-
(Checkpoint-) und Zielzeiten, speichert diese in einer Datenbank
(SQLite oder MySQL/MariaDB) und wird um Live-Daten sowie Bestenlisten
über dynamische Hologramme erweitert.

## Kernfunktionen

- **Multi-Event-Verwaltung**: Beliebig viele Events/Strecken können
  parallel existieren. Jedes Event hat einen eindeutigen Namen
  (`event_name`) und eine feste Rundenanzahl (`laps`).
- **Trigger-System**: Start-, Checkpoint- und Ziel-Trigger werden pro
  Event an Blockpositionen gebunden (Server, Welt, Koordinaten,
  Blocktyp). Checkpoints besitzen eine feste Reihenfolge
  (`checkpoint_order`).
- **Ergebniserfassung**: Start-, End- und Gesamtzeit sowie gefahrene
  Runden werden pro Spieler und Lauf gespeichert, inklusive
  Zwischenzeiten je Checkpoint und Runde.
- **Mehrsprachigkeit**: Chat- und GUI-Texte werden über
  [MiniMessage](https://docs.papermc.io/adventure/minimessage/api/)
  aus Sprachdateien geladen (aktuell Deutsch und Englisch), mit
  automatischem Fallback auf Englisch bei fehlenden Texten.

## Datenbank

Die Datenbankanbindung wird über `config.yml` konfiguriert
(`database.type: sqlite` oder `mysql`). Folgende Tabellen werden beim
Start automatisch angelegt:

- `events` – Event-Stammdaten (Name, Rundenanzahl, Erstellungsdatum)
- `event_triggers` – Start-/Checkpoint-/Ziel-Trigger je Event
- `players` – zuletzt bekannter Spielername und Zeitpunkt
- `race_results` – Lauf-Ergebnisse je Spieler und Event (inkl.
  gefahrener Runden)
- `race_checkpoint_times` – Zwischenzeiten je Lauf, Checkpoint und
  Runde

## Befehle

| Befehl                 | Beschreibung                                         | Berechtigung                 |
|-------------------------|-------------------------------------------------------|-------------------------------|
| `/tracktimer help`      | Zeigt die verfügbaren Befehle an.                     | `tracktimer.command.help`    |
| `/tracktimer reload`    | Lädt `config.yml` und die Sprachdateien neu.          | `tracktimer.command.reload`  |

Alias: `/tt` (konfigurierbar über `command.aliases` in `config.yml`).

## Konfiguration

Die wichtigsten Einstellungen in `config.yml`:

```yaml
language: de-DE

command:
  aliases:
    - tt

database:
  type: sqlite   # oder mysql

  sqlite:
    file: tracktimer.db

  mysql:
    host: localhost
    port: 3306
    database: tracktimer
    username: root
    password: ""
    useSSL: true
```

Sprachdateien liegen unter `plugins/TrackTimer/language/` und können
dort pro Server angepasst werden (`de-DE.yml`/`de-DE_gui.yml` sowie
`en-US.yml`/`en-US_gui.yml`).

## Build

Das Projekt ist ein Standard-Maven-Projekt für Paper 1.21+ (API 26.2,
Java 25):

```powershell
mvn clean package
```

Das fertige Plugin-JAR liegt danach unter `target/TrackTimer-1.0.jar`.

## Status

Das Projekt befindet sich in aktiver Entwicklung. Bisher umgesetzt:

- Datenbank-Setup (SQLite/MySQL) mit vollständigem Schema
- Sprachsystem (MiniMessage, DE/EN, Chat + GUI, Fallback)
- Hauptbefehl `/tracktimer` mit `help`- und `reload`-Subcommand

Geplant: Event- und Trigger-Verwaltung, Lauf-Tracking, Bestenlisten-
GUI und Hologramm-Integration.
