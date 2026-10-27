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
gesperrt. Für .21 fehlen ein sauberer vollständiger Fabric-Build und der echte
Kunden-Spieltest samt Neustart. Kein älteres JAR als .21 ausgeben.

Nächster fachlicher Schritt: vollständigen Build und JAR-/Quellabgleich
abschließen, anschließend einen isolierten echten Kundenkauf prüfen, sobald
ein zweites Testkonto verfügbar ist. Offene Transaktionen benötigen weiterhin
manuellen Abgleich. Den Build-/Spielteststatus danach ausdrücklich aktualisieren.

Lokale Welten, Zugänge und vertrauliche Protokolle gehören ausschließlich in
die jeweilige private Arbeitsumgebung. Sie werden nicht aus diesem Repository
geladen und nicht mit einem Pull Request veröffentlicht.
