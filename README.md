# Wizualizator ramy montażowej

Repozytorium natywnej aplikacji Android do przygotowywania zdjęć kroków montażowych w wymiarach podanych w milimetrach oraz arkusza PDF do wycięcia. Szczegółowy zakres: [PRD.md](PRD.md). Instrukcja obsługi ze zrzutami ekranu: [docs/instrukcja.html](docs/instrukcja.html) (po angielsku: [docs/instrukcja-en.html](docs/instrukcja-en.html)). Gradle kopiuje oba pliki do APK przy budowaniu, więc w aplikacji (logo → O aplikacji → Instrukcja obsługi) jest zawsze ta sama wersja co w `docs/`.

## Stan prototypu

Wersja 0.8.0 obsługuje projekty zapisane lokalnie jako JSON i zdjęcia, tworzenie i usuwanie projektów, zmianę ich nazw, aparat z kadrem o proporcji wynikającej z wymiarów w mm, opcjonalne PN i kroki, korektę jasności/kontrastu, wybór narożników oznaczeń oraz wielostronicowy PDF A4 w formie wycinanki. Projekty mają okładki z własnych zdjęć, galeria równą siatkę kafelków, a formularz wymiarów podgląd proporcji i szybki wybór rozmiaru. Edytor ma ograniczony do ekranu obszar podglądu oraz osobne narzędzia: Dane, Układ, Światło i Plik. W orientacji poziomej panel edycji przenosi się obok zdjęcia. Oznaczenia w podglądzie korzystają z fizycznych proporcji tekstu użytych w PDF. Jasny i ciemny motyw można przełączyć z górnego paska. Wybrany motyw zapisuje się na telefonie. Dodawanie zdjęcia prowadzi przez trzy kroki (Wymiar → Zdjęcie → Opis) z ekranem „Użyj / Powtórz” po zrobieniu zdjęcia i opcjonalną siatką 3 × 3 w wizjerze. Galeria pokazuje prawdziwe proporcje zdjęć, a przejścia między ekranami, zmiana proporcji kadru i przesuwanie oznaczeń są animowane. W aparacie dotknięcie kadru ustawia i blokuje ostrość oraz ekspozycję, a przybliżenie działa gestem lub przyciskami 1× / 2×. Edytor ma zakładkę Kadr (przybliżenie i przesunięcie w stałym wymiarze wydruku, z przeliczeniem DPI) oraz automatyczną korektę tonów. Ustawienia drukarki (rozjaśnienie tonów średnich, wyostrzanie w rozdzielczości druku, druk czarno-biały) dotyczą podglądu i PDF; strona testowa PDF pomaga dobrać rozjaśnienie do drukarki. Cała aplikacja działa po polsku i angielsku (przełącznik PL/EN obok motywu; przy pierwszym uruchomieniu według języka telefonu), łącznie z PDF i instrukcją. Lista projektów wita użytkownika zależnie od pory dnia. Przy pierwszym uruchomieniu pojawia się ekran powitalny, a każdy ekran ma objaśnienie z podświetlaniem elementów (przycisk ? powtarza je) oraz podpowiedzi kontekstowe, np. przy małym kadrze, niskim DPI czy nachodzących oznaczeniach. Okno O aplikacji (dotknięcie logo) pokazuje wersję, instrukcję i ustawienia wskazówek. Ikona aplikacji (żółte tło, karty kroków) mieści się w strefie bezpiecznej każdej maski launchera, także okrągłej.

Wycinanka ma nagłówek z nazwą projektu, numerem arkusza i linijką kontrolną 50 mm z podziałką milimetrową, pasek instrukcji, przerywaną linię cięcia na krawędzi każdego zdjęcia ze znacznikami w narożnikach oraz podpis z numerem (jak w galerii) i wymiarem. PN na zdjęciu zawiera tylko cyfry, bez dopisku „PN”. Rozmiar istniejącego zdjęcia jest stały; aby użyć innych wymiarów, trzeba usunąć zdjęcie i wykonać nowe.

Interfejs uwzględnia obszary paska statusu i nawigacji Androida. Do sprawdzenia na telefonie i drukarce: układ na Pixelu 10, zgodność zapisanego kadru z wizjerem, czytelność oznaczeń na wydruku 20 × 30 mm oraz fizyczne wymiary zdjęć po wydrukowaniu PDF w skali 100%. Prototyp nie ma jeszcze swobodnego przesuwania oznaczeń ani kopii zapasowej projektów.

W wersji debug plik `files/demo_preview.jpg` w pamięci aplikacji zastępuje podgląd aparatu (do zrzutów ekranu instrukcji); wersja release go ignoruje.

## Narzędzia potrzebne do prototypu

### Na komputerze

1. **Git** — kontrola wersji.
2. **JDK 17** — uruchamia Gradle i kompilator używany przy budowaniu aplikacji.
3. **Android SDK Command-line Tools** — zarządzanie pakietami SDK bez Android Studio.
4. **Android SDK Platform + Build-Tools** — kompilowanie APK.
5. **Android SDK Platform-Tools (`adb`)** — przesyłanie APK na telefon i odczyt logów.
6. **Gradle 8.13** — lokalnie zainstalowany do uruchamiania budowania i utworzenia Gradle Wrappera. Po dodaniu projektu aplikacji to Wrapper będzie przypinał wersję Gradle.

Android Studio jest opcjonalne jako edytor. Do budowania APK i testowania na telefonie nie jest potrzebny emulator.

### W projekcie Android

- **Kotlin i Jetpack Compose / Material 3** — natywny, skalowalny interfejs.
- **CameraX** — aparat, wizjer o zadanej proporcji i zapis zdjęcia.
- **Pliki JSON w pamięci prywatnej aplikacji** — projekty bez bazy danych.
- **Android `PdfDocument` i systemowy druk** — arkusz w rzeczywistych wymiarach.
- **Storage Access Framework** — zapis gotowego PDF w miejscu wybranym przez użytkownika.

### Do sprawdzenia wyniku

- Telefon z Androidem i aparatem oraz kabel USB lub debugowanie bezprzewodowe.
- Rzeczywiste zdjęcia ramy, miarka, papier A4 i docelowa drukarka.
- Wydruk próbny z wzorcem 50 mm oraz zdjęciem 20 × 30 mm. Trzeba sprawdzić rozmiar i czytelność oznaczeń przy druku w skali 100%.

## Praca z terminala

W katalogu projektu uruchom `source scripts/android-env.sh`. Ustawia to `JAVA_HOME`, `ANDROID_HOME` i `PATH` dla bieżącego terminala. Narzędzia są zainstalowane w `.local-tools/`, którego Git nie śledzi. Dostępne są JDK 17, Android SDK Command-line Tools, Android SDK Platform 36, Build-Tools 35.0.0 i 36.0.0, `adb` 37.0.1 oraz Gradle 8.13.

```bash
source scripts/android-env.sh
./gradlew assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gotowy APK: `app/build/outputs/apk/debug/app-debug.apk` (plik jest ignorowany przez Git). Minimalna wersja systemu to Android 8.0 (API 26). Pakiet jest podpisany kluczem debugowania i przeznaczony do testów na własnym urządzeniu.

Na telefonie trzeba włączyć **Opcje programisty → Debugowanie USB**, podłączyć go i zaakceptować komunikat o autoryzacji komputera. `adb devices` powinno wtedy pokazać urządzenie jako `device`.

GitHub CLI (`gh`) jest obecne, ale jego zapisane uwierzytelnienie wymaga odnowienia przed utworzeniem repozytorium zdalnego.

## Dokumentacja

- [Instalacja Android Studio](https://developer.android.com/studio/install)
- [Konfiguracja Jetpack Compose](https://developer.android.com/develop/ui/compose/setup)
- [CameraX](https://developer.android.com/jetpack/androidx/releases/camera)
