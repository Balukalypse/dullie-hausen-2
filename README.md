# Dullie Hausen 2.0

Minecraft-Java-Servermod auf Basis von Fabric mit Tokenwirtschaft, Shops und einem Schwerpunkt auf nachvollziehbaren Transaktionen und Datenerhalt.

## Entwicklungsstand

Aktive Entwicklung: **2.0.0-phase2-wip.21**, Quellstand 27. Oktober 2026. Keine stabile Veröffentlichung. Implementierung bedeutet hier keine vollständig getestete oder garantierte Funktion. Der technische Mod-Bezeichner lautet weiterhin `erzmarken`, das Java-Paket `de.baluserver.erzmarken`.

Der aktuelle [Entwicklungsstatus](docs/ENTWICKLUNGSSTATUS.md) unterscheidet Implementierung, lokale Prüfungen und noch fehlende Spieltests. Für die nächste Arbeit mit [START-HIER-MORGEN.md](START-HIER-MORGEN.md) beginnen. Zusammenhängende Fortschritte werden auf einem eigenen Branch geprüft und per Pull Request eingereicht; lokale Sicherungen ergänzen GitHub. Die [Arbeitsregeln](AGENTS.md) halten diesen Ablauf fest.

Der vorhandene Code umfasst Aktivitätsbelohnungen, Spielerkonten, Shopverwaltung mit Lager und separater Kasse, Inventarwechsel und Schutzprüfungen, Transaktionsjournale, Belegprüfungen und Werkzeuge zur Wiederherstellungsprüfung. Bugreports, Verwaltungsfunktionen und optionale externe KI-Hilfsprogramme sind ebenfalls enthalten. KI-Vorschläge erfordern menschliche Prüfung.

Der neueste Warenkauf ist im Code angebunden, im regulären Betrieb jedoch gesperrt. Experimentelle Phase-2-Funktionen erfordern einen gesonderten Testbuild und ausdrücklich freigegebenen isolierten lokalen Testbetrieb. Für Warenkauf .21 fehlen laut mitgeliefertem Entwicklungsnachweis noch vollständiger Gradle-Build, echter Spieltest, Neustart- und Prozessabbruchtest. Ältere Test-JARs belegen nicht den neuesten Quellstand. In diesem Veröffentlichungsauftrag wurden keine Builds oder Funktionstests ausgeführt.

Offene Transaktionen können manuellen Abgleich erfordern. Journale nicht löschen, um Fehler scheinbar zu beheben. Nur in isolierten Testwelten mit Sicherungen erproben.

## Voraussetzungen und Build

Die Projektdateien nennen Minecraft **26.3**, Fabric Loader **0.19.5**, Fabric API **0.165.0+26.3**, Loom **1.18.2**, JDK **25** und im Windows-Buildhelfer Gradle **9.7.1**. Dies sind Angaben des Quellstands; ihre externe Verfügbarkeit und Kompatibilität wurden für diese Veröffentlichung nicht neu geprüft. Ein Gradle-Wrapper liegt nicht bei.

Mit passend installiertem JDK und Gradle im Projektverzeichnis: `gradle build`. Unter Windows steht alternativ `powershell -File .\build-windows.ps1` bereit; der Helfer kann Gradle herunterladen. Ergebnisse entstehen unter `build/`. Es wird kein fertiges .21-JAR mitgeliefert. Tests und Prüfwerkzeuge liegen in `tests/` und `tools/`; einige benötigen zusätzliche Minecraft/Fabric-Abhängigkeiten. Vor Ausführung die jeweiligen Argumente und Voraussetzungen lesen.

Optionale KI-Werkzeuge beziehen Zugangsdaten aus `OPENAI_API_KEY`. Keine Schlüssel ins Repository schreiben. Ihre manuelle Ausführung kann API-Kosten verursachen und Berichtstexte an OpenAI übertragen. Sie wurden für diese Veröffentlichung nicht ausgeführt.

## Roadmap und Community

- Warenkauf, Inventarwechsel und Shoptransaktionen vollständig integrieren und im Spiel testen.
- Neustart-, Abbruch-, Duplikations- und Datenverlustszenarien systematisch prüfen.
- Dokumentation, Administration, Bugreport- und optionale KI-Abläufe weiter verbessern.
- Quests und weitere Erweiterungen ausbauen; Integration und Testumfang sind noch offen.
- Anschließend eine öffentliche Testphase vorbereiten.

Das Projekt ist langfristig angelegt und hat keinen abschließend festgelegten Funktionsumfang. Neue Ideen können laufend hinzukommen. **Mit Beginn der öffentlichen Testphase** sollen Spieler und Interessierte Fehler melden, Verbesserungen und neue Funktionen vorschlagen sowie gegebenenfalls über GitHub beitragen können. Geeignete Vorschläge sollen nach Prüfung in die Entwicklung einfließen. Eine bereits aktive öffentliche Community wird damit nicht behauptet.

## Fehler und Vorschläge

Die Issue-Vorlagen für Bugs und Feature Requests verwenden. Fehler mit Mod-, Minecraft-, Fabric- und Java-Version, reproduzierbaren Schritten, erwartetem und tatsächlichem Ergebnis sowie bereinigten Protokollauszügen beschreiben. Keine Weltstände, Spielerdaten, Zugangsdaten oder privaten Serverinformationen öffentlich hochladen. Für Sicherheitslücken gilt [SECURITY.md](SECURITY.md). Hinweise zur Mitarbeit: [CONTRIBUTING.md](CONTRIBUTING.md).

## Lizenzstatus

Der Projektinhaber hat die Veröffentlichung unter der bereits im Ursprungsarchiv enthaltenen [MIT-Lizenz](LICENSE) ausdrücklich freigegeben. Die ursprüngliche LICENSE-Datei und die MIT-Metadaten wurden unverändert übernommen.

## English overview

Dullie Hausen 2.0 is a Minecraft Java Fabric server mod under active development, featuring a token economy, shop accounts and stock management, inventory safeguards, transaction journals and recovery review tools. Current source: **2.0.0-phase2-wip.21**. This is unfinished software, not a stable release. The latest retail purchase flow remains disabled in ordinary operation and lacks a full .21 Gradle build and live gameplay, restart and process interruption tests according to the supplied development record.

The roadmap includes transaction validation, reliable persistence, administration, optional AI-assisted bug triage, quests and ongoing extensions. Community bug reports, suggestions and possible GitHub contributions are **planned for the public testing phase**, not presented as existing activity. There is no fixed final feature scope. Use the issue templates and submit only sanitized evidence. See SECURITY.md for private vulnerability reporting.

Build requirements are recorded above from the supplied source, not independently verified. No Gradle wrapper or latest compiled release is included. Published under the original [MIT license](LICENSE), explicitly approved by the project owner. Original source and license metadata are preserved.
