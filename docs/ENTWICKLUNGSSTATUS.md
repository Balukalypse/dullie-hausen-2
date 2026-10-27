# Entwicklungsstatus

Stand: 27. Oktober 2026. Quellversion: **2.0.0-phase2-wip.21**.

## Implementiert

- Öffnen und Schließen des Shops sind mit dem Wechsel zwischen privatem und
  Arbeitsinventar verbunden. Wiederholte Befehle übertragen keine Items erneut.
- Spielerankauf verwendet Journal, unveränderlichen Buchungsplan, Token- und
  Shopbelege sowie einen Nachweis des gespeicherten Inventars.
- `/shop kaufen ITEM MENGE` verbindet Tokenabbuchung, Shopkasse, Lager und
  Lieferung von 1 bis 64 gewöhnlichen Items. Die Lieferung wird vollständig
  geplant; bei Platzmangel erfolgt keine Kaufabbuchung und kein Bodenabwurf.
- Ein bestätigter Verkauf wird erst nach unabhängiger Datei- und Inventarprüfung
  abgeschlossen. Unterbrochene Buchungen bleiben gesperrt und werden nicht
  automatisch wiederholt. `shop kaufjournal` ist eine reine Konsolenabfrage.

Die experimentellen Phase-2-Funktionen bleiben im regulären Betrieb gesperrt.
Der Warenkauf verlangt einen Testbuild und eine ausdrücklich für RETAIL
freigegebene isolierte lokale Testwelt. Dies ist keine stabile Veröffentlichung.

## Bisherige lokale Nachweise

Diese Ergebnisse stammen aus der lokalen Entwicklung und wurden für diese
Dokumentationsänderung nicht erneut ausgeführt. Der Abgleich hat die
inhaltliche Übereinstimmung des getesteten lokalen Codes mit dem Repository
bestätigt. Private Rohprotokolle und Spielerkennungen werden nicht veröffentlicht.

| Prüfung | Ergebnis | Grenze |
| --- | --- | --- |
| Warenkauf mit echten Shop-/Token-/Journaldateien | 86 Checks bestanden | Inventarspeicher als Testadapter; erfolgreicher Folgekauf und neun Fehlerfälle |
| Minecraft-Codecs und komprimierte Spielerdaten | 30 Checks bestanden | Explizite Fixture-Komponenten, kein laufender Server mit Datenpaketen |
| Einzelne Verkaufsbuchungen | Tokenabzug 15, Shopbuchung 18 Checks bestanden | Kein eigenständiger vollständiger Spieltest |
| Ankauf-Integration | 21 Checks bestanden | Echter Shop-/Tokenspeicher, künstlicher Inventarspeicher |
| Fünf ausgewählte unabhängige Java-Programme für .21 | Alle bestanden | Testfreigabe 24, Ankaufablauf 236, Verkaufsjournal 21, Verkaufsplan 15, Kontosperren 54 Checks |
| Gesamte unabhängige Java-Sammlung für .20 | 44 Programme bestanden | Historischer Lauf; kein erneuter vollständiger .21-Gesamtlauf |
| Typprüfung des .21-Codes gegen Cache-Abhängigkeiten | Klassen erzeugt, Exit 0 | Windows-Zugriffsfehler beim JAR-Schließen; kein sauberer Fabric-Buildnachweis |

Das letzte tatsächlich vollständig gebaute und im Spiel geprüfte lokale Test-JAR
war **.14-test**. Seine Inventar- und Shopstatusprüfungen sind historische
Nachweise und bestätigen den neuen Warenkauf nicht.

## Noch offen

1. Sauberer vollständiger .21-Fabric-Build mit Quellen-/JAR-Nachweis.
2. Echter Kundenkauf und Waren-/Tokenvergleich nach Serverneustart.
3. Mehrspieler- und echte Prozessabbruchtests; ein zweites Testkonto ist derzeit
   für die lokale Entwicklung nicht verfügbar.
4. Geprüfte Wiederherstellung unterbrochener Verkäufe und vollständiger
   Item-Exportschutz. Bis dahin bleibt manueller Abgleich erforderlich.
5. Weitere geplante Spielinhalte, Belohnungsabläufe und Quest-/Fortschrittssysteme.

Für die erste Runde mit einem kleinen Spielerkreis haben Kernfunktionen Vorrang.
Kleinere, schwer auslösbare Fehler können dokumentiert nachgezogen werden.
Inventarverlust und falsche Buchungen sind weiterhin vor Freigabe zu prüfen.
Es gibt keine pauschale Absturz- oder Produktionsgarantie.

## Arbeits- und Sicherungsablauf

Während aktiver Arbeit Remote-Stand abrufen, Unterschiede prüfen, einen eigenen
Branch verwenden, passend testen, die Dokumentation aktualisieren und einen
Pull Request einreichen. `main` und seine Schutzregeln bleiben maßgeblich.
Lokale Sicherungen ergänzen GitHub; sie liegen außerhalb des Repositorys.
Anmeldungen erfolgen über eine offizielle Verbindung oder sicheren
Anmeldespeicher. Außerhalb aktiver Chats ist keine Automatisierung eingerichtet.
