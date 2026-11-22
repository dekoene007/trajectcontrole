# Release notes

Nieuwste versie bovenaan. Elke versie van de app (`versionName` in `app/build.gradle.kts`) heeft hier een sectie
`## <versie> – <datum>`; de build in GitHub Actions weigert een versie zonder sectie en zet de tekst ervan bij
de release **app-laatste**. Wijzigingen aan data, pipeline of workflows zonder nieuwe app-versie staan onder
*Data en pipeline*.

## 0.9.3 – 21 november 2026

- Nieuw traject in **beide richtingen**: na de limiet vraagt de app "Enkel deze richting / Beide richtingen".
  Achteraf wijzigen kan in *Markeringen* (tik op het traject); daarna opnieuw indienen.
- Ingediende melding bevat `beide=1` voor een traject in beide richtingen.

## 0.9.2 – 21 november 2026

- Positiebol en kaart bewegen **vloeiend** tussen de GPS-punten (gsm en Android Auto); zoom glijdt mee.
  De bol loopt daardoor ±1 s achter; gemiddelde en meldingen niet.
- App **stopt** na de Bluetooth-wachttijd (standaard 30 s) wanneer Android Auto afsluit of de auto-Bluetooth
  verbreekt, ook als het volgen door Android Auto gestart werd.

## 0.9.1 – 21 november 2026

- Paneel in Android Auto springt niet meer wanneer de knoppen verschijnen/verdwijnen (vaste bovenrand).
- Trajectnaam op maximaal 2 regels; meer ruimte boven een iets kleinere meter.
- **Tweede scherm (instrumentenpaneel)**: meter met limiet, boetedrempel en resterende afstand.
- Volgen start automatisch zodra Android Auto de app opent.
- "Afsluiten na 5 minuten stilstand" standaard **uit**.

## 0.9.0 – 21 november 2026

- App registreert zich als **navigatie-app** in Android Auto: gesplitst scherm naast Waze werkt.
- Eén eigen paneel links (ontwerp "A + meter van C").

## 0.8.1 – 21 november 2026

- Android Auto-paneel opgekuist en in dezelfde stijl als de gsm; "max. tot einde" niet meer getoond
  (enkel nog uit te spreken via instelling).

## 0.8.0 – 21 november 2026

- **Levende kaart** (MapLibre, OpenFreeMap) in Android Auto met eigen paneel en volgcamera.

## 0.7.0 – 0.7.3 – 21 november 2026

- Kaart op de gsm met actieve en andere trajecten, legende.
- **Markeren** (begin, einde, correctie) met reactietijd-correctie, *Markeringen* en **Indienen** als GitHub-issue.
- **Passages**: logboek van elke afgewerkte passage met eindgemiddelde.
- Tijdelijke limiet (variabele limiet), aankondiging van aansluitende trajecten, ouderdom van de data.
- Meter zonder streepjes, voortgang onderaan, gecentreerde tegels; optie volledig scherm.
- APK enkel voor arm64; gebouwd in GitHub Actions met vaste sleutel (release **app-laatste**).

## 0.6.0 – 20 november 2026

- Snelheid om onder de boetedrempel te blijven; meer spraakmeldingen.

## 0.5.0 – 0.5.2 – 6–10 november 2026

- Overlappende trajecten tegelijk volgen; het strengste wordt getoond.
- Car App Library 1.7.0, targetSdk 36; vereenvoudigde Android Auto-declaratie.

## 0.4 – 0.4.1 – 4 november 2026

- Eerste versie met automatische wekelijkse update van de trajectdata.
- Automatisch starten via Bluetooth, afsluiten na stilstand, overlay boven Waze, melding vóór het begin,
  spraak per soort aan/uit.

## Data en pipeline

- **21 november 2026** – Wekelijkse update maakt enkel nog een issue bij een **mislukte** update.
- **21 november 2026** – Meldingen-workflow hersteld (faalde als `correcties.tsv` nog niet bestond);
  `beide=1` / `richting=beide` in een melding. Eerste eigen traject via de app: Bierbeek, Koning Albertlaan (#11).
- **21 november 2026** – Workflow *Meldingen verwerken*: issues uit de app → `eigen_trajecten.tsv` / `correcties.tsv`.
- **5 november 2026** – AWV open data als tweede bron; AWV-portaalstukken krijgen voorrang; koppeling over bronnen heen.
  Robuustere Overpass-opvraging (meerdere servers, nooit een leeg antwoord publiceren, terugvalmodus).
- **4 november 2026** – Eigen trajecten (`eigen_trajecten.tsv`, Dutselstraat Holsbeek) en eigen meldingen (`eigen_meldingen.csv`).
