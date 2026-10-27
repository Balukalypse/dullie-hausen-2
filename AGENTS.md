# Arbeitsregeln für Dullie Hausen 2.0

Gemeinsamer Quellstand: https://github.com/Balukalypse/dullie-hausen-2.
Vor der Entwicklung `START-HIER-MORGEN.md` und `docs/ENTWICKLUNGSSTATUS.md` lesen.

## Git und Zusammenarbeit

- Vor Abgleich oder Veröffentlichung Arbeitsverzeichnis und Remote-Stand prüfen.
  Vorhandene Änderungen anderer Personen erhalten und Unterschiede nachvollziehen.
- Zusammenhängende, geprüfte Fortschritte auf einem eigenen Branch sichern und
  als Pull Request einreichen. Kein direkter Push auf `main`, kein Force-Push
  und keine Umgehung von Branch-Schutzregeln. Zusammenführen nur nach Auftrag.
- Nicht jede Kleinigkeit braucht einen Upload. Bei wesentlichen Fortschritten
  README und Entwicklungsstatus mit aktualisieren.
- Nur freigegebene Projektdateien veröffentlichen. Keine Zugangsdaten, echten
  Spielerkennungen, privaten Serverdaten, Welten, Laufzeitdateien oder
  vertraulichen Protokolle. Lokale Testnachweise nur bereinigt zusammenfassen.
- Zusätzlich lokale Sicherungen außerhalb des Repositorys erstellen und prüfen.
  Vor Veröffentlichung den Remote-Stand erneut abrufen; neuere Änderungen
  abgleichen, bevor der Branch eingereicht wird.

## Prüfung und Freigabe

- Zum Umfang passende Prüfungen ausführen. Bei Geld- und Inventaränderungen
  insbesondere Wiederholung, Unterbrechung und Speicherung berücksichtigen.
- Quellcode, vollständigen Build, getestetes JAR und echten Spieltest getrennt
  benennen. Ein Compilerlauf mit Fehlerdiagnose ist kein sauberer Buildnachweis.
- Für den ersten kleinen Spielerkreis haben spielbare Kernfunktionen Vorrang.
  Kleinere, schwer auslösbare Fehler können dokumentiert später behoben werden.
  Datenverlust und falsche Tokenbuchungen bleiben Freigabekriterien.
- Experimentelle Funktionen kennzeichnen und bestehende Testfreigaben beachten.
  Keine automatische Wiederholung oder unbelegte Kompensation offener Buchungen.

## Anmeldung und wiederkehrende Arbeit

- Eine bestehende sichere Sitzung oder offizielle GitHub-Anbindung verwenden.
  Passwörter oder Tokens niemals in Dateien, Quellcode, Chatnotizen oder Git
  speichern. Eine erforderliche Anmeldung führt die nutzende Person selbst aus.
- Diese Sicherungspraxis gilt während aktiver Arbeit. Automatisierungen außerhalb
  aktiver Chats erst nach ausdrücklicher Vereinbarung einrichten.
- Ein Nutzerkürzel `w` bedeutet, selbstständig am nächsten sinnvollen Schritt
  weiterzuarbeiten und den geprüften Fortschritt zu dokumentieren.
