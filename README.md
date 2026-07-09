# IPTV Tłumacz — Android (Samsung Galaxy S25 Ultra)

Odtwarzacz IPTV z polskimi napisami tłumaczonymi na żywo.
Bez serwera — cała ciężka praca w chmurze:

    strumień IPTV → ExoPlayer → przechwycone audio (6 s porcje)
                 → OpenAI Whisper API (mowa → tekst)
                 → Claude API, model Haiku (tekst → polski)
                 → napisy na ekranie

## Wymagane klucze API (wpisujesz w Ustawieniach aplikacji)

1. **Rozpoznawanie mowy** — jedna z dwóch opcji (aplikacja wykrywa po kluczu):
   - **Groq — DARMOWE** (zalecane): https://console.groq.com/keys
     Klucz zaczyna się od gsk_. Darmowy tier: 2000 zapytań/dzień i 7200 s
     audio/godz. — wystarcza na ok. 3 h oglądania dziennie, bez karty.
     Model: whisper-large-v3-turbo (lepszy niż whisper-1 z OpenAI).
   - OpenAI (płatne): https://platform.openai.com/api-keys
     Klucz sk-..., whisper-1, 0,006 USD/min ≈ 1,5 zł/godz.
2. **Claude / Anthropic** (tłumaczenie): https://console.anthropic.com
   Model Haiku — przy napisach grosze za godzinę.

## Budowa w Android Studio

1. Otwórz folder projektu (File → Open).
2. Poczekaj na synchronizację Gradle (AGP 8.5.2, Kotlin 2.0.20, compileSdk 35).
   Jeśli Studio zapyta o Gradle wrapper — pozwól mu pobrać Gradle 8.7.
3. Włącz debugowanie USB na S25 Ultra (Opcje programisty), podłącz kabel,
   Run ▶ na urządzeniu.

## Użycie

1. Ustawienia → wklej oba klucze API.
2. Wklej adres playlisty M3U → Wczytaj.
3. Wybierz kanał. Język wykrywany z nazwy/grupy (ARD→DE, Rai→IT, TF1→FR...),
   można też ustawić ręcznie w Ustawieniach.
4. Napisy: polska linia na żółto, oryginał mniejszy nad nią (do wyłączenia).

## Uwagi techniczne

- Audio przechwytywane przez TeeAudioProcessor w torze DefaultAudioSink —
  działa dla programowej ścieżki dźwięku (domyślnej). Strumienie DRM odpadną.
- Porcje 6 s → opóźnienie napisów względem mowy ok. 7–10 s.
- Cisza jest pomijana lokalnie (bez wywołań API).
- Gdy sieć nie nadąża, najstarsze porcje są porzucane (napisy nie "uciekają").
