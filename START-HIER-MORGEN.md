# Hier weiterarbeiten

Gemeinsamer Quellstand ist dieses GitHub-Repository. Aktueller Mod-Quellstand:
**2.0.0-phase2-wip.21**, Stand 27. Oktober 2026. Der Repository-Abgleich hat
für Quellcode, Tests und Builddateien denselben Inhalt wie die lokale .21-
Entwicklung ergeben; Unterschiede betrafen ausschließlich Zeilenumbrüche.
Die bereits vorhandenen GitHub-Dokumente und Einstellungen bleiben erhalten.

Zuerst [Entwicklungsstatus](docs/ENTWICKLUNGSSTATUS.md), [Arbeitsregeln](AGENTS.md)
und [Hinweise zur Mitarbeit](CONTRIBUTING.md) lesen. Danach den aktuellen
Remote-Stand und lokale Änderungen prüfen. Auf einem eigenen Branch arbeiten
und sinnvolle Fortschritte per Pull Request einreichen.

Der Warenkauf ist implementiert und lokal geprüft, im regulären Betrieb aber
gesperrt. Der vollständige .21-Testbuild ist am 27. Oktober 2026 bestanden und
gegen die Quellen geprüft: 77 Modklassen, sechs Mixins, gültige JAR-Prüfsumme.
Der [bereinigte Buildnachweis](docs/pruefungen/phase2-wip21-build.json) enthält
Versionen und Hashes. Echter Kunden-Spieltest und Neustartprüfung fehlen weiter.

Nächster fachlicher Schritt: den geprüften Build bei vollständig gestopptem
isoliertem Testserver installieren und einen isolierten echten Kundenkauf
prüfen, sobald ein zweites Testkonto verfügbar ist. Das letzte installierte
und im Spiel geprüfte JAR bleibt bislang .14-test. Offene Transaktionen
benötigen weiterhin manuellen Abgleich. Build und Spieltest getrennt dokumentieren.

Lokale Welten, Zugänge und vertrauliche Protokolle gehören ausschließlich in
die jeweilige private Arbeitsumgebung. Sie werden nicht aus diesem Repository
geladen und nicht mit einem Pull Request veröffentlicht.
