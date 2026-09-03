# Shiko's Pokedex

Point the camera at a Pokémon card. Get its name, raw price, and (optionally) PSA 9/10 values, live.

## Fully offline identification, zero signup
Card identification needs **no network call at all** for the matching step itself:
- On-device OCR (Google ML Kit, free, runs locally) reads the card's name and collector number from the cropped image.
- That text is matched against a **bundled offline index of ~20,444 English cards** (`assets/cards_index.json`, built from the same open dataset pokemontcg.io itself is built from) — exact match first, then a small fuzzy (edit-distance) fallback so OCR noise, glare, or a finger partially covering the text doesn't sink the whole scan.
- Once matched, one network call to **pokemontcg.io** (free, no key needed for personal volumes) fetches the live raw (TCGPlayer) price and card image.

The only optional piece is **PSA 9/10 graded prices**, from pokemonpricetracker.com — a genuinely self-serve free key (no sales call, just a "Generate Key" button at pokemonpricetracker.com/api-keys).

## How it works
1. CameraX feeds every frame to `CardImageAnalyzer`.
2. `CardDetector` (OpenCV) tries to find the card-shaped quadrilateral and perspective-warp it; if no clean quad is found (messy background, odd lighting), it falls back to a plain center-crop instead of getting stuck.
3. `PHash` debounces: after 3 consecutive stable frames of a *different* card, identification fires.
4. `CardRepository.identify()`:
   - Runs on-device OCR (`TextExtractor`), gets several ranked name-line candidates (`CardTextParser`).
   - `LocalCardIndex` matches against the bundled 20k-card index — tries each candidate name, falls through to fuzzy matching if nothing matches exactly.
   - Fetches the matched card's live price/image from pokemontcg.io.
5. Results stream progressively: identification + raw price show immediately; if a PSA key is set, PSA 9/10 fill in a moment later without blocking the rest.
6. Everything is cached locally (Room, 24h TTL) so re-scanning the same card costs nothing.

## Sharing a built APK with someone else (no setup on their end)
Bake your pokemonpricetracker.com key into the build so whoever installs the APK gets PSA prices with zero configuration:

**Via GitHub Actions:** repo → Settings → Secrets and variables → Actions → "New repository secret" → name `PRICE_API_KEY`, value = your key. `.github/workflows/build.yml` passes it to Gradle automatically.

**Locally:** `./gradlew assembleDebug -PPRICE_API_KEY=your_key_here`. Never commit the key into `build.gradle.kts` directly.

Anyone can still override it later via the in-app Settings screen. Worth knowing: a key baked into an APK can be recovered by decompiling it — fine for a free-tier personal key, not for anything sensitive.

## Getting an installable APK
This project can't be compiled in the sandbox it was written in (no Android SDK, no network access to Google's Maven or Gradle's distribution servers).

**GitHub Actions:** push to a repo, `.github/workflows/build.yml` builds on every push to `main` (or trigger manually), download the `shikos-pokedex-debug` artifact.

**Android Studio, locally:** open the folder (Koala+), it uses the included Gradle wrapper (8.7) automatically.

## Known gaps
- **OCR name/number parsing** (`CardTextParser`) is a heuristic, not a robust parser — tune the regexes against real cards if matches are missing often.
- **English-only index.** The bundled index and pokemontcg.io cover English-language cards. Japanese/Korean cards need a different index + OCR language model — not wired up here.
- **Fuzzy match threshold** (edit distance ≤ 2) is a starting point — loosen or tighten in `LocalCardIndex.findBest` based on real-world accuracy.
- **No slab/graded-card detection** — this pipeline estimates raw + PSA medians for an ungraded card; it can't read a PSA slab's label directly from a photo.
- **Currency**: prices are USD. Convert to ILS client-side if needed.

## Structure
```
app/src/main/assets/cards_index.json   Bundled offline index: id, name, number, set (20,444 English cards)
app/src/main/java/com/shiko/pokedex/
  camera/     CardDetector.kt (OpenCV crop + fallback), CardImageAnalyzer.kt (debounce),
              TextExtractor.kt (on-device OCR), CardTextParser.kt (name/number heuristics)
  network/    PokemonTcgIoApi.kt (live price/image for a matched card, no key),
              PriceApi.kt (PSA, optional key), RetrofitClient.kt
  data/       LocalCardIndex.kt (offline matching), ApiKeys.kt (optional PSA key), Room cache
  repository/ CardRepository.kt — OCR -> local index -> live price -> optional PSA -> cache
  ui/         CardViewModel.kt, ScannerScreen.kt, SettingsScreen.kt, Theme.kt
  MainActivity.kt
.github/workflows/build.yml   CI: builds a debug APK on every push, reads PRICE_API_KEY from repo secrets
```
