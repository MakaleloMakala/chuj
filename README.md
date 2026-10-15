# Miniony

Plugin minionów dla serwera Skyblock (Paper 26.2, Java 25).

## Budowanie

```
mvn clean package
```

Wynikowy plik: `target/Miniony-<wersja>.jar`.

Zależności (wszystkie `provided`, opcjonalne na serwerze jako `softdepend`):
- `io.papermc.paper:paper-api` (wersja w `paper.version` w `pom.xml`)
- `com.bgsoftware:SuperiorSkyblockAPI:2026.2`
- `net.luckperms:api:5.5`
- `com.github.MilkBowl:VaultAPI:1.7.1` (jitpack)
- Nexo - bez zależności kompilacji (integracja przez refleksję)

## Status

Etap 1 (rdzeń): Górnik, stawianie/zbieranie, magazyn + GUI, hologram, SQLite, limity, integracja SSB2/LuckPerms.
Etap 2: poziomy 1-N (szybkość, pojemność, zasięg, mnożnik dropu, wygląd), koszt ulepszeń (Vault + przedmioty + Nexo),
typy Drwal, Farmer, Rybak, Zabójca, Zbieracz.
Etap 3: rzadkości (mnożniki, max poziom, sloty modułów, bonusowy drop, limity), ulepszanie rzadkości,
moduły (auto-przetapianie, auto-sprzedaż, kompaktor, paliwo, rozszerzenie zasięgu, łącze ze skrzynią), praca offline.
Pełna instrukcja konfiguracji pojawi się po Etapie 4.
