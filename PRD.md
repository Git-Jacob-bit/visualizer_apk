# Aplikacja do wizualizacji kroków na ramie montażowej — PRD

**Status:** koncepcja pierwszej wersji (MVP)  
**Platforma:** natywna aplikacja na Androida  
**Język interfejsu:** polski  
**Przechowywanie:** lokalnie na telefonie, bez konta, serwera i bazy danych

## 1. Cel i problem

Inżynier przygotowuje zestaw zdjęć pokazujących kroki montażu. Każde zdjęcie ma trafić w konkretne miejsce na ramie montażowej. Dostępna przestrzeń bywa różna, więc dziś trzeba osobno fotografować, kadrować, zmieniać rozmiar, nakładać oznaczenia i przygotowywać arkusz do wycięcia. To zajmuje czas i sprzyja pomyłkom przy drukowaniu.

Aplikacja prowadzi inżyniera od pomiaru miejsca na ramie do gotowego PDF z wycinanką. **Podane wymiary w milimetrach określają fizyczny rozmiar zdjęcia na wydruku**, a proporcje kadru podczas fotografowania odpowiadają tym wymiarom.

## 2. Użytkownik i główny scenariusz

Użytkownikiem jest inżynier pracujący z ramą montażową. Ma telefon z Androidem i miarkę. Mierzy wolne miejsce, zakłada projekt, dodaje zdjęcia kolejnych kroków i eksportuje arkusz do wydrukowania oraz wycięcia.

Przykład: dla miejsca **20 × 30 mm** wpisuje 20 mm szerokości i 30 mm wysokości. Wizjer pokazuje pionowy kadr 2:3. Po zrobieniu zdjęcia inżynier wpisuje `PN 1234567890` i `S1, S2`, a aplikacja umieszcza oznaczenia na obrazie. W PDF obszar przeznaczony do wycięcia ma dokładnie 20 × 30 mm.

## 3. Zakres MVP

### Projekty

- Tworzenie projektu z nazwą i datą utworzenia.
- Lista projektów z możliwością otwarcia, zmiany nazwy i usunięcia.
- Automatyczny zapis zmian lokalnie po każdej operacji; przycisk **„Przygotuj PDF”** generuje aktualną wycinankę.
- Powrót do istniejącego projektu i dodawanie, edycja lub usuwanie zdjęć.
- Potwierdzenie usunięcia projektu albo zdjęcia, ponieważ usunięcie kasuje lokalny oryginał.

### Dodawanie zdjęcia

1. Inżynier wybiera **„Dodaj zdjęcie”** i wpisuje szerokość oraz wysokość w mm (liczby dodatnie).
2. Aplikacja otwiera aparat z obszarem kadru o proporcji `szerokość : wysokość`. Kadr zajmuje możliwie dużą część ekranu; elementy sterujące nie zasłaniają jego istotnej części. Wymiary w mm są widoczne obok wizjera.
3. Aplikacja zapisuje zdjęcie w najwyższej praktycznie dostępnej rozdzielczości. Zapisany obraz jest przycinany do kadru widocznego w wizjerze, z poprawnym uwzględnieniem obrotu telefonu.
4. Użytkownik widzi podgląd i może zaakceptować lub powtórzyć zdjęcie.
5. Użytkownik może wpisać dwa niezależne, **opcjonalne** pola:
   - **PN:** dokładnie 10 cyfr, jeśli pole jest wypełnione; brak wartości jest dozwolony.
   - **Kroki:** swobodna lista oznaczeń, np. `S1` albo `S1, S2`; brak wartości jest dozwolony.
6. Podgląd pokazuje zdjęcie z oznaczeniami. Użytkownik zapisuje je do projektu.

Jeśli PN ma inną liczbę cyfr lub zawiera inne znaki, aplikacja informuje o błędzie i pozwala poprawić wartość albo wyczyścić pole. Pole kroków przyjmuje wiele oznaczeń; aplikacja zachowuje wpisaną kolejność.

### Oznaczenia

- PN: lewy górny róg zdjęcia, same cyfry kodu bez prefiksu „PN”, czytelny ciemny tekst na białym tle.
- Kroki: prawy dolny róg zdjęcia, czytelny ciemny tekst na żółtym tle.
- Puste pole nie tworzy ramki na zdjęciu.
- Tekst nigdy nie wychodzi poza granice zdjęcia. Długi napis zawija się albo aplikacja sygnalizuje, że nie zmieści się czytelnie.
- Oznaczenia są przechowywane jako dane do edycji. Aplikacja nakłada je dopiero w podglądzie i przy eksporcie, nie niszcząc oryginalnego zdjęcia.

### Edycja zdjęcia

- Podgląd każdego zdjęcia, jego wymiarów i oznaczeń.
- Korekta jasności i kontrastu z podglądem oraz przyciskiem przywrócenia wartości początkowych. Korekty są niedestrukcyjne.
- Zmiana PN, listy kroków oraz położenia oznaczeń w granicach zdjęcia; domyślne położenia pozostają opisane wyżej.
- Korekta kadru przez przesunięcie obrazu w ramach **tej samej proporcji**; oryginał pozostaje dostępny.
- Zmiana rozmiaru wydruku istniejącego zdjęcia nie jest dostępna. Użytkownik może powtórzyć zdjęcie, podając nowe wymiary.

### Wycinanka i druk

- Eksport projektu do wielostronicowego PDF w formacie A4. Liczba arkuszy wynika z liczby i wymiarów zdjęć; projekt nie ma limitu jednej strony. Każde zdjęcie ma na stronie fizyczny rozmiar zgodny z zapisanymi wymiarami w mm.
- Aplikacja układa zdjęcia w kolejności projektu na kolejnych stronach, zachowuje ustawione odstępy do cięcia i nie skaluje ich po to, by zmieścić więcej na arkuszu.
- Arkusze mają nagłówek z nazwą projektu i numerem strony, ramki oraz znaczniki cięcia poza zdjęciami, numer zdjęcia i jego wymiar przy obszarze do wycięcia oraz stopkę ze wzorcem kontrolnym 50 mm. W podglądzie PDF są widoczne liczba stron, rozmiar arkusza i lista zdjęć.
- Użytkownik wybiera miejsce zapisu PDF przez systemowy wybór pliku i może otworzyć systemowy ekran drukowania/udostępniania.
- Ekran eksportu przypomina o druku w **skali 100% / rozmiarze rzeczywistym**, bez opcji „dopasuj do strony”. Zawiera wzorzec kontrolny 50 mm do sprawdzenia linijką po wydruku.
- Jeśli zdjęcie albo projekt nie mieści się na A4 przy wymaganych marginesach, aplikacja jasno wskazuje problem przed eksportem. Przypadek większych formatów papieru wymaga osobnej decyzji.

## 4. Kryteria akceptacji

1. Dla wpisu 20 × 30 mm wizjer ma proporcję 2:3 niezależnie od proporcji ekranu telefonu; zdjęcie w PDF ma 20 × 30 mm przy druku w skali 100%.
2. PDF zawierający kilka zdjęć zachowuje **osobny** rozmiar każdego z nich. Żadne zdjęcie nie jest rozciągane ani ściskane.
3. Zapisany kadr odpowiada temu, co użytkownik widział w wizjerze, z uwzględnieniem tolerancji wynikającej z podglądu aparatu.
4. PN można pominąć; jeśli jest podany, składa się z 10 cyfr. Kroki można pominąć albo wpisać kilka, np. `S1, S2`. Puste pola nie zostawiają pustych ramek.
5. Jasność, kontrast, kadr i oznaczenia można poprawić po zapisaniu zdjęcia. Po ponownym uruchomieniu aplikacji zmiany pozostają w projekcie.
6. Rozmiar wydruku istniejącego zdjęcia jest tylko do odczytu; ponowne wykonanie zdjęcia pozwala podać nowy rozmiar.
7. Przed eksportem aplikacja ostrzega, gdy po kadrowaniu liczba pikseli jest zbyt mała dla wybranego progu jakości druku.
8. Wydruk próbny na docelowej drukarce potwierdza miarką rozmiar kilku zdjęć oraz wzorca 50 mm. Odbiór dotyczy **wydruku**, nie tylko geometrii PDF.
9. Projekt działa bez internetu i pozostaje dostępny po zamknięciu aplikacji.
10. Ekrany działają w pionie na typowych rozmiarach telefonów, z poprawnym przewijaniem, obsługą większych czcionek i bez uciętych przycisków.

## 5. Jakość obrazu i dokładność wymiarów

To są dwa osobne zagadnienia:

- **Rozmiar fizyczny:** PDF zapisuje geometrię w punktach drukarskich. Przeliczenie to `punkty = milimetry × 72 / 25,4`. Dla 20 × 30 mm daje to około 56,69 × 85,04 punktu. Ostateczny wynik zależy też od ustawień drukarki, dlatego potrzebny jest wydruk kontrolny.
- **Szczegółowość:** dla 300 DPI minimalna liczba pikseli to `zaokrąglij_w_górę(mm / 25,4 × 300)` na każdej osi. Dla 20 × 30 mm jest to około **237 × 355 px**. To próg techniczny, a nie gwarancja czytelności małego tekstu.
- Aplikacja zachowuje oryginalne zdjęcie i wykonuje kadrowanie oraz korekty dopiero podczas renderowania. Nie zwiększa sztucznie rozdzielczości. „Bezstratna konwersja” i algorytmy AI nie odzyskają szczegółów, których aparat nie zarejestrował.
- Eksport nie powinien zmniejszać obrazu poniżej liczby pikseli potrzebnej dla wybranego progu. Oznaczenia warto rysować na etapie generowania PDF, żeby ich krawędzie pozostały ostre.
- Dla bardzo małych zdjęć ograniczeniem może być czytelność PN i kroków. Prototyp musi sprawdzić realne próbki, szczególnie format 20 × 30 mm oraz zdjęcia z kilkoma krokami.

## 6. Interfejs i układ

Styl: nowoczesny, spokojny, czytelny w środowisku produkcyjnym. Duże pola dotykowe, wyraźny przycisk aparatu, jasny podgląd końcowego wydruku, czytelne komunikaty błędów. Interfejs powinien wspierać jasny i ciemny motyw oraz skalowanie czcionek Androida.

Główne ekrany: **Projekty → Projekt → Wymiary → Aparat → Oznaczenia i podgląd → Edycja zdjęcia → Podgląd PDF / eksport**. Na małych ekranach formularze się przewijają, a aparat zachowuje proporcję kadru bez wymuszania proporcji całego ekranu.

## 7. Przechowywanie i prywatność

Każdy projekt jest katalogiem w prywatnej przestrzeni aplikacji: plik metadanych JSON oraz oryginalne zdjęcia. Zapis metadanych powinien być atomowy, aby przerwanie działania aplikacji nie uszkodziło projektu. Miniatury i wygenerowane PDF mogą być odtwarzane i trzymane w pamięci podręcznej. Brak bazy danych oznacza tu **brak SQLite/Room**; pliki JSON są wystarczające dla pierwszej wersji.

Usunięcie aplikacji usuwa jej prywatne projekty. Dlatego po MVP warto rozważyć eksport i import całego projektu jako kopii zapasowej; sam PDF nie pozwala odzyskać edytowalnych zdjęć i ustawień.

## 8. Proponowane narzędzia i komponenty

| Potrzeba | Propozycja | Zastosowanie |
|---|---|---|
| Budowa aplikacji | Android Studio, Kotlin, Gradle | Natywny projekt Android i pakiet APK |
| Interfejs | Jetpack Compose + Material 3 | Ekrany, motywy, skalowanie układu i czcionek |
| Aparat | CameraX (`Preview`, `ImageCapture`, `PreviewView`, `ViewPort`) | Wizjer, zdjęcie w wysokiej rozdzielczości, zgodność kadru z podglądem |
| Zapis projektów | Pliki prywatne aplikacji + JSON | Projekty bez bazy danych i serwera |
| Obróbka zdjęć | Android `Bitmap`/`Canvas`, dekodowanie z uwzględnieniem EXIF | Kadr, jasność, kontrast, podgląd |
| PDF | Android `PdfDocument` albo `PrintedPdfDocument` | Strony A4, dokładne wymiary i oznaczenia |
| Zapis/udostępnienie PDF | Storage Access Framework + Android Print Framework | Zapis w wybranym miejscu i druk |
| Kontrola jakości | Telefon testowy, drukarka, papier A4, linijka/miarka | Sprawdzenie kadru, czytelności i wymiarów wydruku |
| Kod i testy | Git, testy jednostkowe geometrii PDF oraz test wydruku | Kontrola zmian i weryfikacja krytycznych wymiarów |

Na start potrzebne są też: telefon z aparatem obsługiwany przez CameraX, komputer z Android Studio, kabel albo debugowanie bezprzewodowe, drukarka używana w praktyce oraz kilka rzeczywistych zdjęć ramy. Dostęp do sieci może być potrzebny do jednorazowego pobrania narzędzi i zależności, ale sama aplikacja działa offline.

## 9. Plan prototypu

1. **Dowód techniczny:** aparat z kadrem wynikającym z mm, zapis pełnego zdjęcia, PDF z jednym i kilkoma rozmiarami, wydruk próbny 20 × 30 mm. To rozstrzyga najważniejsze ryzyko.
2. **Przepływ MVP:** projekty w plikach, dodawanie zdjęć, opcjonalne oznaczenia, lista, edycja, eksport wielostronicowy.
3. **Dopracowanie:** jakość wizualna, ergonomia aparatu, komunikaty o jakości, testy na różnych ekranach i drukarkach.

## 10. Decyzje do potwierdzenia podczas prototypowania

- Czy A4 jest jedynym wymaganym formatem papieru? Obecny zakres MVP zakłada A4.
- Jakie minimalne marginesy i odstępy są potrzebne dla używanej drukarki i sposobu wycinania? Wartości należy dobrać po wydruku próbnym.
- Czy PN zawsze ma dokładnie 10 cyfr, także wtedy, gdy zaczyna się od zera? Dokument zakłada, że tak, dlatego jest traktowany jako tekst.
- Czy oznaczenia muszą znajdować się **wewnątrz** zmierzonego obszaru zdjęcia? Dokument zakłada, że tak.
- Czy zdjęcia mogą być poziome i pionowe oraz czy obrót telefonu podczas fotografowania ma być dozwolony? Dokument zakłada oba warianty.
- Czy wymagane jest przywracanie projektu po odinstalowaniu lub zmianie telefonu? W MVP nie ma kopii zapasowej projektu.

## Źródła techniczne

- [CameraX: konfiguracja, rozdzielczość i `ViewPort`](https://developer.android.com/media/camera/camerax/configuration)
- [CameraX: podgląd i skalowanie `PreviewView`](https://developer.android.com/media/camera/camerax/preview)
- [Android: generowanie dokumentów do druku i jednostki PDF](https://developer.android.com/training/printing/custom-docs)
- [Android: prywatne pliki aplikacji](https://developer.android.com/training/data-storage/app-specific)
- [Android: zapis dokumentu przez Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
