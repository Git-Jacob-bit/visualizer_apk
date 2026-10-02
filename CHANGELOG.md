# Historia zmian

Lista zmian widocznych dla użytkownika. Ta sama treść, w obu językach, jest w aplikacji
(`app/src/main/java/pl/visualizer/montaz/Changelog.kt`) i pokazuje się raz po każdej aktualizacji,
a w całości w oknie **O aplikacji → Historia zmian**.

Dodając wersję: wpisz zmiany tutaj i w `Changelog.kt`, podnieś `versionCode` oraz `versionName`
w `app/build.gradle.kts` (pole `code` w `Changelog.kt` musi być równe nowemu `versionCode`),
a na koniec oznacz wydanie tagiem `v<versionName>`, który zbuduje podpisany APK na GitHubie.

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
