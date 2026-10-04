# Historia zmian

Lista zmian widocznych dla użytkownika. Ta sama treść, w obu językach, jest w aplikacji
(`app/src/main/java/pl/visualizer/montaz/Changelog.kt`) i pokazuje się raz po każdej aktualizacji,
a w całości w oknie **O aplikacji → Historia zmian**.

Dodając wersję: wpisz zmiany tutaj i w `Changelog.kt`, podnieś `versionCode` oraz `versionName`
w `app/build.gradle.kts` (pole `code` w `Changelog.kt` musi być równe nowemu `versionCode`),
a na koniec oznacz wydanie tagiem `v<versionName>`, który zbuduje podpisany APK na GitHubie.

## 0.12.0 — 2026-10-04

- **Kopia automatyczna.** Projekty, zdjęcia i wszystkie ustawienia zapisują się same w jednym pliku
  `Documents/wizualizator_ram_backup_auto/backup.zip` — kilka sekund po każdej zmianie i od razu przy wyjściu z aplikacji.
  Włącza się ją z paska pod powitaniem na liście projektów albo w **O aplikacji → Kopia zapasowa**; telefon raz pyta
  o folder (Documents). Plik jest aktualizowany w miejscu: dopisywane są tylko nowe zdjęcia, więc nawet duża kopia
  zapisuje się szybko. Pasek pokazuje, kiedy kopia zapisała się ostatnio i ile waży, a po przekroczeniu 1 GB (potem co
  500 MB) aplikacja proponuje archiwum na komputer.
- **Przywracanie po ponownej instalacji.** Po instalacji wskaż ten sam folder: aplikacja znajdzie kopię, pokaże jej
  zawartość i przywróci projekty oraz ustawienia, a dalej będzie aktualizować ten sam plik. Kopii z innej instalacji
  (innego telefonu, wersji debug) kopia automatyczna nigdy nie nadpisze — można ją przejrzeć albo zachować jako
  `backup_<data>.zip` i zacząć nową.
- **Archiwum.** „Zrób archiwum” zapisuje kopię z dzisiejszą datą (`wizualizator_RRRR-MM-DD.zip`) w wybranym miejscu.
- **Odczyt na komputerze.** Kopia i archiwum to zwykłe pliki ZIP: oryginalne zdjęcia, `project.json` każdego projektu,
  `backup.json` z ustawieniami oraz `index.html`, który po rozpakowaniu pokazuje w przeglądarce wszystkie projekty
  i zdjęcia w kadrze, z korektą światła i oznaczeniami PN / kroków.
- **Przegląd i wybiórcze wgrywanie.** „Otwórz plik kopii” pokazuje projekty i zdjęcia z kopii z oznaczeniem, czy
  projekt jest nowy, taki sam jak w aplikacji, czy się różni. Zaznaczone projekty wgrywają się obok obecnych; projekt,
  który w aplikacji ma inną wersję, wchodzi jako nowy projekt z dopiskiem „z kopii” albo dokłada tylko brakujące
  zdjęcia. Nic, co jest w aplikacji, nie zostaje nadpisane.
- **Wersja debug obok wydania.** Wersja debug ma własny identyfikator (`pl.visualizer.montaz.debug`) i nazwę
  „Wizualizator ramy (debug)”, więc instaluje się obok wersji z Releases i przejście między nimi nie wymaga już
  odinstalowania (ani utraty projektów).

## 0.11.0 — 2026-10-04

- **Eksport do Excela.** Przycisk PDF w galerii projektu zmienił się w **Eksportuj** z listą: „PDF – wycinanka”
  (jak dotąd), „Excel – wizualizacje” albo „ZIP – same zdjęcia”. Plik .xlsx ma dwa arkusze. **wizualizacje** to pusty szablon kroków
  w układzie arkusza wizualizacji (Poprzedni / Aktualny komponent, opis, żółte paski PN, Nr komponentu,
  Nr czujnika, Nr kroku) z wpisanymi numerami kroków z projektu i trzema blokami zapasowymi, a na końcu pięć
  pustych bloków „Montaż przewodu” (szerokie zdjęcie, instrukcja, jeden żółty pasek). **zdjęcia** zawiera
  wszystkie zdjęcia projektu ułożone po kroku, każde na identycznym białym kwadracie (kadr z edytora, bez przycinania),
  z PN i krokiem pod spodem, gotowe do skopiowania do szablonu.
- **Zdjęcia w ZIP.** „ZIP – same zdjęcia” zapisuje każde zdjęcie jako osobny JPEG w kadrze z edytora (bez
  dopełniania), z nazwą `PN_nrKroku.jpg`. Przed zapisem aplikacja pyta, czy na zdjęciach mają być widoczne
  oznaczenia PN i kroków (takie jak na wycinance).

## 0.10.1 — 2026-10-02

- **Kolor PN: biały lub żółty.** Wybór koloru okienka PN ograniczony do białego i żółtego (tego samego co
  okienko kroków). Zdjęcia, którym w 0.10.0 ustawiono inny kolor, wczytują się z białym.

## 0.10.0 — 2026-10-02

- **Kolor okienka PN.** W zakładce Układ, pod wielkością, można wybrać kolor okienka z numerem PN: biały,
  pomarańczowy, czerwony, zielony, niebieski lub czarny. Na ciemnym tle numer drukuje się na biało, żeby był
  czytelny. Kafelki narożników, podgląd wydruku i PDF pokazują wybrany kolor. Kolor zapisuje się w projekcie;
  starsze projekty wczytują się z białym.

## 0.9.0 — 2026-09-26

- **Wielkość oznaczeń.** W zakładce Układ suwak ustawia wielkość każdego oznaczenia osobno — od 60 do 200%
  rozmiaru wzorcowego (10 pt). Panel pokazuje wysokość okienka w milimetrach, kafelki narożników i podgląd
  wydruku zmieniają się razem z suwakiem, a PDF drukuje dokładnie ten rozmiar. Wielkości zapisują się
  w projekcie; starsze projekty wczytują się ze 100%.
- **Co nowego po aktualizacji.** Po każdej aktualizacji aplikacja pokazuje raz listę zmian. Pełna historia
  jest dostępna w oknie O aplikacji.

## 0.8.0 — 2026-09-22

- Pierwsza wersja: projekty, aparat z kadrem w milimetrach, oznaczenia PN i kroków, kadrowanie, korekta
  światła, ustawienia drukarki oraz wycinanka PDF A4 do druku w skali 100%.
