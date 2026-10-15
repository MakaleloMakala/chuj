# Miniony

Plugin minionów dla serwera Skyblock: NPC stawiany na wyspie, który sam pracuje w zasięgu wokół siebie
i zbiera drop do własnego magazynu. Gracz otwiera GUI prawym kliknięciem miniona.

- Paper **26.2**, Java **25**
- Integracje (wszystkie opcjonalne): **SuperiorSkyblock2**, **LuckPerms**, **Vault**, **Nexo**
- Dane w SQLite (`plugins/Miniony/miniony.db`), zapis asynchroniczny

---

## 1. Instalacja

1. Zbuduj plugin: `mvn clean package` (wynik: `target/Miniony-1.0.0.jar`) albo użyj gotowego jara.
2. Wrzuć jar do `plugins/` i uruchom serwer (Java 25).
3. Plugin utworzy `plugins/Miniony/config.yml` i bazę `miniony.db`.
4. Skonfiguruj `config.yml` i wpisz `/minion reload`.

Zależności opcjonalne (`softdepend`) - plugin działa bez nich, wtedy dana funkcja jest wyłączona:

| Plugin | Do czego | Bez niego |
|---|---|---|
| SuperiorSkyblock2 | stawianie tylko na swojej wyspie, dostęp członków wyspy, zwrot minionów po usunięciu wyspy / wyrzuceniu, praca tylko w granicach wyspy, liczniki bloków wyspy | brak sprawdzania wysp (miniona można postawić wszędzie, dostęp ma tylko właściciel) |
| LuckPerms | odczyt permisji limitów + automatyczne odświeżanie limitów po zmianie rangi | limity z uprawnień Bukkit, odświeżane przy wejściu na serwer |
| Vault (+ plugin ekonomii) | koszty w pieniądzach, moduł auto-sprzedaży | koszty pieniężne niedostępne (ulepszenie z takim kosztem jest zablokowane z komunikatem) |
| Nexo | przedmioty Nexo w kosztach (`nexo:id:ilość`) | koszty z przedmiotami Nexo niedostępne |

Pluginy można włączać/wyłączać w trakcie działania (np. PlugMan) - Miniony podłączą lub odłączą integrację.

### Budowanie ze źródeł

```
mvn clean package
```

Repozytoria w `pom.xml`: PaperMC, BG-Software (SuperiorSkyblockAPI), JitPack (VaultAPI), Maven Central (LuckPerms API).
Wersję Paper API zmienisz we właściwości `paper.version`.

---

## 2. Jak to działa

- **Przedmiot miniona** (`/minion give`) kliknij prawym na blok wyspy - minion stanie na bloku nad nim.
  Kliknięcie skrzyni/drzwi itp. bez skradania otwiera je normalnie (przytrzymaj Shift, aby postawić miniona).
- **Praca w cyklach** - co interwał (zależny od poziomu, rzadkości i paliwa) minion wykonuje jedną akcję.
  Działa tylko, gdy jego chunk jest załadowany; plugin nigdy nie ładuje chunków.
- **GUI** (prawy klik na miniona): magazyn (klik = odbierz stos), odbierz wszystko, ulepsz, moduły,
  informacje, zbierz miniona, ulepsz rzadkość (jeśli włączone), strony magazynu.
- **Zbieranie** - przycisk "Zbierz miniona": minion wraca jako przedmiot z zachowanymi danymi
  (poziom, rzadkość, moduły, paliwo, magazyn). Zawartość magazynu trafia do ekwipunku
  (`zbieranie.oddaj-magazyn`), to co się nie zmieści - zostaje w minionie.
- **Praca offline** - gdy chunk był wyładowany, przy ponownym załadowaniu minion dostaje produkcję za ten czas
  (maks. `praca-offline.maks-godzin`).

### Typy minionów

| Id | Nazwa | Działanie |
|---|---|---|
| `gornik` | Górnik | na warstwie pod sobą stawia blok (`blok`) w pustych miejscach, a gdy ich brak - wykopuje go |
| `drwal` | Drwal | na swoim poziomie stawia pnie na stałym gruncie i ścina je |
| `farmer` | Farmer | zbiera dojrzałe uprawy (i sadzi ponownie), sadzi na ziemi uprawnej, przyspiesza wzrost |
| `rybak` | Rybak | potrzebuje źródeł wody pod sobą; łup losowany z tabeli `lup` |
| `zabojca` | Zabójca | zabija moby z listy `moby` w zasięgu; drop trafia do magazynu |
| `zbieracz` | Zbieracz | podnosi leżące przedmioty w zasięgu |

Minion dotyka tylko powietrza i "własnych" bloków (np. górnik tylko bruku), nigdy bloku pod innym minionem,
a przy SSB2 - tylko w granicach swojej wyspy.

### Ochrona przed duplikacją

- Przedmiot miniona zawiera tylko **id + token**; dane są w bazie. Token zmienia się przy każdym postawieniu,
  zebraniu i zwrocie - skopiowany przedmiot (np. w kreatywie) jest nieważny i znika przy próbie użycia.
- GUI: każde kliknięcie i przeciąganie jest anulowane, przedmioty są przenoszone programowo z magazynu.
- Zapis do bazy następuje przed zapisem ekwipunku gracza - przy crashu możliwa jest co najwyżej strata, nie duplikat.
- Encje minionów są nietrwałe (nie zapisują się w świecie) - po crashu nie zostają osierocone kopie.

---

## 3. Konfiguracja (`config.yml`)

Wszystkie teksty to [MiniMessage](https://docs.advntr.dev/minimessage/format.html). Błędne lub brakujące wartości
dają ostrzeżenie w konsoli i bezpieczną wartość domyślną. Błąd składni YAML przy `/minion reload`
zostawia poprzednią konfigurację.

| Sekcja | Opis |
|---|---|
| `baza-danych` | plik bazy, co ile sekund autozapis (akcje graczy zapisywane są natychmiast) |
| `praca.takt-tickow` | co ile ticków scheduler sprawdza miniony (domyślnie 20 = 1 s) |
| `praca.maks-ms-na-takt` | budżet czasu pracy na takt - reszta minionów pracuje w kolejnym takcie |
| `wyspy` | wymaganie wyspy SSB2, dostęp członków wyspy, zgłaszanie zmian bloków do SSB2 |
| `limity` | limit domyślny, twardy sufit, czy bypass ignoruje sufit |
| `ulepszanie.tylko-wlasciciel` | kto może ulepszać i instalować moduły |
| `zbieranie.oddaj-magazyn` | oddawanie zawartości magazynu przy zbieraniu |
| `hologram` | włączenie, wysokość, zasięg widoczności, linie, teksty statusów |
| `rzadkosci` | rzadkości (kolejność = kolejność ulepszania) - patrz niżej |
| `ulepszanie-rzadkosci` | włączenie ulepszania rzadkości, wymóg maksymalnego poziomu |
| `praca-offline` | włączenie, limit godzin, liczenie czasu wyłączenia serwera |
| `moduly` | moduły - patrz niżej |
| `typy` | typy minionów: nazwa, ustawienia typu, przedmiot, poziomy, praca offline |
| `gui`, `gui-moduly` | wygląd GUI i sloty przycisków |
| `wiadomosci` | wszystkie wiadomości (pusta wartość = wiadomość wyłączona) |

### Poziomy (`typy.<id>.poziomy`)

```yaml
poziomy:
  1:
    interwal-sekundy: 15     # co ile sekund cykl pracy
    pojemnosc: 9             # sloty magazynu (1-270)
    zasieg: 2                # zasięg w blokach (1 = 3x3)
    mnoznik-dropu: 1.0
    wyglad:
      narzedzie: WOODEN_PICKAXE
      kolor-zbroi: '#7F7F7F'
      glowa-tekstura: ''     # base64 tekstury głowy
  2:
    interwal-sekundy: 13     # brakujące wartości są dziedziczone z poziomu 1
    koszt:                   # koszt ulepszenia NA ten poziom
      pieniadze: 5000
      przedmioty:
        - 'COBBLESTONE:256'
        - 'nexo:mithril_ingot:4'
```

Przedmioty w koszcie muszą być "czyste" (bez nazwy/lore/enchantów) i w głównym ekwipunku gracza.
Błędny wpis w koszcie blokuje ulepszenie na ten poziom (komunikat w konsoli) - koszt nigdy nie jest po cichu obniżany.

### Rzadkości (`rzadkosci`)

| Klucz | Opis |
|---|---|
| `nazwa` | nazwa (MiniMessage) |
| `mnoznik-szybkosci` | dzieli interwał pracy |
| `mnoznik-dropu` | mnoży drop (razem z poziomem) |
| `mnoznik-pojemnosci` | mnoży pojemność magazynu |
| `max-poziom` | najwyższy poziom dla tej rzadkości (0 = bez ograniczenia) |
| `sloty-modulow` | liczba modułów (0-9) |
| `szansa-bonusowego-dropu` | szansa (0.0-1.0) na podwójny drop w cyklu |
| `limit` | limit postawionych minionów tej rzadkości na gracza (-1 = brak) |
| `koszt-ulepszenia` | koszt ulepszenia NA tę rzadkość |

`ZWYKLY` musi istnieć. Można dodawać własne rzadkości.

### Moduły (`moduly`)

Wspólne: `wlaczony`, `nazwa`, `ikona`, `opis`, `koszt`, `dozwolone-typy` (puste = wszystkie).
Odinstalowanie (Shift + klik w GUI modułów) nie zwraca kosztu.

| Id | Działanie | Opcje |
|---|---|---|
| `auto-przetapianie` | przetapia drop wg przepisów pieca | `pomijaj` |
| `auto-sprzedaz` | sprzedaje drop z cennika (Vault), pieniądze dla właściciela | `co-ile-sekund`, `powiadomienia`, `ceny` |
| `kompaktor` | zamienia przedmioty w bloki | `przepisy` (`WEJŚCIE:ilość:WYJŚCIE`) |
| `paliwo` | dolewane paliwo przyspiesza pracę | `maks-godzin`, `paliwa` (czas, mnożnik) |
| `rozszerzenie-zasiegu` | zasięg + N | `bonus` |
| `lacze-skrzyni` | przenosi magazyn do wskazanej skrzyni | `maks-odleglosc` |

### Praca offline (`typy.<id>.offline`)

```yaml
offline:
  wlaczona: true
  cykle-na-drop: 2        # ile cykli pracy daje jedną jednostkę dropu
  drop:
    - 'COBBLESTONE:1'
```

Rybak losuje łup z tabeli `lup`. Zabójca i Zbieracz nie pracują offline.
Minion, który w chwili wyładowania chunka nie miał miejsca/celu pracy, nie dostaje produkcji offline.

---

## 4. Komendy

| Komenda | Opis | Permisja |
|---|---|---|
| `/minion` lub `/minion pomoc` | pomoc | - |
| `/minion list` | lista Twoich postawionych minionów | `miniony.use` |
| `/minion list <gracz>` | lista minionów gracza (także offline) | `miniony.admin.list` |
| `/minion limit` | Twój limit minionów | `miniony.use` |
| `/minion limit <gracz>` | limit gracza (online) | `miniony.admin.limit` |
| `/minion odbierz` | odbierz miniony zwrócone z usuniętej wyspy | `miniony.use` |
| `/minion give <gracz> <typ> [ilość] [poziom] [rzadkość]` | daj miniona | `miniony.admin.give` |
| `/minion reload` | przeładuj konfigurację | `miniony.admin.reload` |

Alias: `/miniony`.

## 5. Permisje

| Permisja | Domyślnie | Opis |
|---|---|---|
| `miniony.use` | wszyscy | stawianie, GUI, `/minion list`, `limit`, `odbierz` |
| `miniony.admin` | OP | wszystkie permisje administracyjne poniżej |
| `miniony.admin.give` | OP | `/minion give` |
| `miniony.admin.reload` | OP | `/minion reload` |
| `miniony.admin.list` | OP | `/minion list <gracz>` |
| `miniony.admin.limit` | OP | `/minion limit <gracz>` |
| `miniony.admin.access` | OP | otwieranie, zbieranie i ulepszanie cudzych minionów, stawianie poza wyspą |
| `miniony.limit.<liczba>` | - | limit postawionych minionów (bierzemy NAJWIĘKSZĄ, maks. `limity.maksimum`) |
| `miniony.limit.<rzadkość>.<liczba>` | - | limit minionów danej rzadkości, np. `miniony.limit.legendarny.5` |
| `miniony.limit.bypass` | OP | brak limitów (sufit wg `limity.bypass-ignoruje-maksimum`) |

Przykład (LuckPerms): `/lp group vip permission set miniony.limit.10`

Limity liczone są po właścicielu (osobie, która postawiła miniona). Przekazanie przedmiotu innemu graczowi
nie omija limitu - postawiony minion liczy się do limitu stawiającego.

---

## 6. Zmienne (placeholdery) w tekstach

`<typ>`, `<poziom>`, `<max-poziom>`, `<rzadkosc>`, `<wlasciciel>`, `<zapelnienie>`, `<pojemnosc>`, `<status>`,
`<interwal>`, `<zasieg>`, `<mnoznik>`, `<bonus>`, `<moduly>`, `<sloty-modulow>`, `<paliwo>`.
Przyciski ulepszeń: `<nastepny-poziom>`, `<nastepna-rzadkosc>`, `<nowy-interwal>`, `<nowa-pojemnosc>`,
`<nowy-zasieg>`, `<nowy-mnoznik>`, `<nowy-max-poziom>`, `<nowe-sloty-modulow>`, linia `<koszt>`.

## 7. Dane i kopie zapasowe

- Baza: `plugins/Miniony/miniony.db` (SQLite, tryb WAL - pliki `-wal` i `-shm` są normalne).
- Kopia zapasowa: zatrzymaj serwer i skopiuj `miniony.db` (razem z `-wal`/`-shm`, jeśli istnieją).
- Aktualizacja pluginu automatycznie dodaje brakujące kolumny w bazie.
- Po aktualizacji config.yml ma nowszą wersję (`wersja-configu`) - brakujące wartości są brane z domyślnych
  z ostrzeżeniem; najprościej porównać plik z nowym domyślnym configiem.

## 8. Znane ograniczenia

- Bez SuperiorSkyblock2 nie ma ochrony terenu: miniona da się postawić wszędzie (dotyka tylko powietrza i swojego bloku).
- Symulacja offline zakłada, że minion ma miejsce do pracy.
- Paliwo zużywa się wg czasu rzeczywistego, gdy minion jest aktywny.
- Zniszczony przedmiot miniona (lawa, ogień, wybuch, pustka, zniknięcie po 5 min) usuwa miniona z bazy.
