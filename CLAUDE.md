# Werkafspraken voor deze repository

- **Taal:** Nederlands (Vlaams) in code-commentaar, UI, commits en documentatie.
- **Bij elke app-wijziging die gebruikers merken:** `versionCode` +1 en `versionName` verhogen in
  `app/build.gradle.kts`, een sectie `## <versie> – <datum>` bovenaan in `CHANGELOG.md`, en `README.md` bijwerken
  (versie bovenaan + de betrokken secties). De workflow *App bouwen* faalt als de sectie in `CHANGELOG.md` ontbreekt
  en zet de tekst ervan bij de release **app-laatste**.
- **Wijzigingen aan data, pipeline of workflows** zonder nieuwe app-versie: een regel onder *Data en pipeline* in
  `CHANGELOG.md`, en de README aanpassen als de werking verandert.
- De release notes staan ook in het claude.ai-project "Traject Controle app" (`claude/release-notes.md`); houd die
  gelijk met `CHANGELOG.md`.
- Buildtooling: Android Gradle Plugin 8.13.2 / Gradle 8.13. Installatie via UnknownAuto (release app-laatste).
- Geen gegevens van Flitsmeister, TollTracker of andere commerciële diensten; enkel eigen waarneming, AWV/overheid
  open data en OpenStreetMap.
