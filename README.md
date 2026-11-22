# Trajectcontrole – gemiddelde snelheid (Android + Android Auto)

App voor eigen gebruik die de gemiddelde snelheid binnen Belgische trajectcontroles volgt, naast Waze.
**Versie 0.9.3, 21 november 2026.** Wat er per versie veranderde: [CHANGELOG.md](CHANGELOG.md).

**Buildversies:** Android Gradle Plugin 8.13.2 · Gradle 8.13 (wrapper) · Kotlin 2.2.21 · JDK 17 · Car App Library 1.7.0 ·
targetSdk 36 · MapLibre 11.8.0.

## Wat de app doet

- **Volgt je positie elke seconde** via GPS, in een voorgrondservice met een vaste melding.
- **Herkent trajecten** door je positie voortdurend op de trajectlijn te leggen, niet via een startpunt dat je kan missen.
  - Wordt een traject laat herkend, dan zoekt de app in de laatste 15 minuten GPS-historiek het echte instapmoment terug.
  - Overlappende trajecten worden tegelijk gevolgd; het strengste wordt getoond.
  - Aaneensluitende trajecten worden gekoppeld en aangekondigd ("aansluitend …").
- **Toont** (gsm en Android Auto): een meter met het gemiddelde tot nu toe, de huidige snelheid, limiet en
  boetedrempel, de voortgang en resterende afstand. Kleur van meter en trajectlijn:
  - groen: tot de limiet
  - oranje: tussen de limiet en de boetedrempel
  - rood: boven de boetedrempel
- **Kaart** met het actieve traject (gereden/rest), de andere trajecten (begin, einde, beide richtingen) en eigen
  markeringen. De camera volgt je vloeiend, met het hele traject in beeld.
- **Spraak** (Waze en muziek gaan even zachter): ±400 m voor het begin, bij het binnenrijden, als de kleur
  verandert, elke 45 s in het rood, bij het verlaten met je eindgemiddelde, en bij een aansluitend traject.
  Elk soort apart aan of uit; "maximaal … tot het einde" enkel op aanvraag.
- **Tijdelijke limiet**: geldt er een andere limiet (werken, variabele borden), stel ze in via de knop *Limiet*.
  Ze vervalt aan het einde van het traject.
- **Passages**: logboek van elke afgewerkte passage met eindgemiddelde. Eigen controle, geen bewijs (GPS ≠ teller).
- **Ritlogboek** per rit (CSV) met elke GPS-positie en de gebeurtenissen (start, stop, Bluetooth, Android Auto).

Boetedrempel (technische correctie BE: −6 km/u onder 100, −6 % vanaf 100):

| Limiet | 30 | 50 | 70 | 90 | 120 |
|---|---|---|---|---|---|
| Rood boven (gemeten) | 36 | 56 | 76 | 96 | 127,7 |

## Android Auto

- De app is een **navigatie-app** in Android Auto. Daardoor:
  - werkt ze in het **gesplitste scherm** naast Waze en Spotify, of volledig scherm;
  - toont ze een levende kaart met links één paneel (naam op 2 regels, meter, *Nu*, *Limiet · boete*, voortgang);
  - toont ze op het **tweede scherm** (instrumentenpaneel), als de auto dat aan navigatie-apps aanbiedt, enkel de
    meter met "Limiet · boete · nog x km".
- Knoppen rechtsboven: **Markeer**, **Limiet**, **Start/Stop**. Android Auto verbergt ze zelf na een tijdje;
  tik op het scherm om ze terug te tonen. Dat kan een app niet uitschakelen.
- Het volgen start vanzelf zodra je de app in Android Auto opent, en stopt 30 s nadat Android Auto afsluit.

## Installeren

Elke wijziging aan de app wordt in GitHub Actions gebouwd (workflow *App bouwen*). De nieuwste APK staat altijd bij
de release **app-laatste** (Releases, rechts op de startpagina van de repository), met de release notes van die versie.

1. Installeer de APK op de gsm via **UnknownAuto** (met Shizuku). Zo krijgt de app de Play-herkomst die Android Auto
   vereist; een gewone installatie wordt in de auto geweigerd ("Package DENIED").
2. Open de app, tik **Starten** en sta locatie en meldingen toe.
3. Android Auto: *Launcher aanpassen* → **Trajectcontrole** aanvinken.

De APK is ondertekend met de vaste sleutel in `app/signing/` (enkel persoonlijk gebruik). Een build uit Android Studio
en een build uit Actions kunnen elkaar daardoor bijwerken zonder te verwijderen.

### Instellingen

- **Starten bij verbinden met de auto (Bluetooth)**: start zodra de gsm verbindt met het gekozen toestel en stopt
  30 s na het verbreken (opnieuw verbinden binnen die tijd stopt niet). Volg de stappen op het scherm: toestemming
  *Apparaten in de buurt*, auto aanvinken, locatie **Altijd toestaan**, uitzondering op batterijoptimalisatie.
- **Afsluiten na 5 minuten stilstand**: standaard uit; nooit midden in een traject of bij een rit vanuit de auto.
- **Reactietijd** bij markeren (standaard 0,8 s), **overlay** boven andere apps, **volledig scherm**, spraak per soort.
- **Trajectdata → Nu controleren**: nieuwe data meteen ophalen (ook via mobiele data).
- Onderaan: diagnose van de schermruimte die Android Auto doorgeeft.

## Markeren en indienen

Tik tijdens het rijden op **Markeer** (gsm of Android Auto). De positie wordt vastgelegd op het moment van de tik,
min de reactietijd. Daarna kies je:

- **Begin** en later **Einde**: een nieuw traject dat exact het gereden GPS-spoor volgt. Op de gsm kies je limiet en
  **richting** (enkel deze / beide); in Android Auto enkel de limiet, de richting pas je nadien aan op de gsm;
- **Correctie**: het begin of einde van een bestaand traject in dezelfde rijrichting verschuiven;
- **Later beslissen**: afwerken in *Markeringen*.

Alles werkt meteen op het eigen toestel. Met **Indienen** opent een vooraf ingevuld GitHub-issue met een blok:

````
```trajectcontrole
nieuw;id=977273904;limiet=30;beide=1;naam=Bierbeek, Koning Albertlaan;punten=lat,lon;lat,lon;…
correctie;id=815154;kant=begin;naam=…;punten=lat,lon;…
```
````

De workflow *Meldingen verwerken* (enkel issues van de eigenaar) zet dat om naar `data/eigen_trajecten.tsv` of
`data/correcties.tsv`, antwoordt met een samenvatting, sluit het issue en start de update. De app haalt de nieuwe data
op bij de volgende controle. Lukt het niet, dan blijft het issue open met de reden; **bewerk het issue** om opnieuw te
verwerken. Hetzelfde id opnieuw indienen vervangt de vorige versie. `beide=1` (of `richting=beide`) = beide richtingen;
een negatief id bij een correctie = enkel de omgekeerde richting. Correcties op een traject dat later door AWV-data
vervangen wordt, vervallen; het rapport meldt dat.

## Bronnen

1. **OpenStreetMap** (ODbL): alle Belgische `enforcement=average_speed`-relaties.
2. **AWV open data** (laag `awv:Trajectcontroles`): trajectcontroles op Vlaamse gewest- en snelwegen.
   - Opgenomen: status *Opgeleverd* (ook als ze niet actief zijn) en *UNKNOWN* (installaties van federale politie en
     politiezones die AWV mee registreert).
   - Limiet uit de AWV-laag `Snelheidsregimes_op_wegen_in_beheer_van_AWV`.
   - De coördinaten staan in Lambert 2008 terwijl de laag Lambert 72 opgeeft; de pipeline rekent ze zelf om.
   - AWV-portaalstukken krijgen voorrang op OSM-trajecten die ze voor ≥ 80 % bedekken.
3. **Eigen trajecten** (`data/eigen_trajecten.tsv`), **eigen meldingen** (`data/eigen_meldingen.csv`) en
   **correcties** (`data/correcties.tsv`), uit eigen waarneming.

Geen gegevens van Flitsmeister, TollTracker of andere commerciële diensten.

Kaarttegels: OpenFreeMap (© OpenMapTiles, © OpenStreetMap-bijdragers).

## Trajectdata bijwerken (automatisch)

```
elke maandag 03:17 UTC, na een melding, of manueel (GitHub Actions)
  └─ pipeline/run_pipeline.py
       1. Overpass (meerdere servers) + AWV: alle Belgische trajectcontroles en de wegen errond
       2. verwerken, op de weg leggen, richting en koppelingen bepalen, eigen trajecten en correcties toepassen
       3. CONTROLES: ≥ 100 trajecten · niet > 10 % minder dan vorige versie · E40 ≥ 15 · geen traject < 100 m
       4. data/segments.tsv + data/meta.json (versie, sha256) + data/RAPPORT.md
  └─ enkel bij een fout: issue "update NIET gepubliceerd" met het rapport (= e-mail van GitHub)
app (bij openen of starten, hoogstens 1x per dag, enkel op wifi)
  └─ meta.json → nieuwere versie? → download → sha256 + ≥ 100 trajecten → vervangen
     → actief vanaf de volgende start. Lukt iets niet: vorige versie blijft.
```

- **Faalt een controle, dan wordt er niets gepubliceerd.** Meestal een tijdelijke fout in OpenStreetMap of bij Overpass.
  Opnieuw draaien: *Actions → Trajectcontroles bijwerken → Run workflow*.
- Een geslaagde update zie je in de commitgeschiedenis ("Trajecten bijgewerkt/gecontroleerd") en in `data/RAPPORT.md`.
- **Zelf een traject toevoegen zonder app:** regel in `data/eigen_meldingen.csv`
  (`naam;limiet;richting;begin;einde`, bv. `Tiensesteenweg Kessel-Lo;50;beide;50.8812,4.7231;50.8790,4.7342`; begin en
  einde = plaats van de camera's). Het rapport toont per regel of het traject op de weg gelegd kon worden.
  Hulpmiddel om een lijn op de weg te leggen: workflow *OSM-opzoeking*.
- Eigen trajecten (`ids ≥ 900000000`) worden samengevoegd met de OSM-/AWV-data. Duikt zo'n traject later in
  OpenStreetMap op, dan meldt het rapport dat, zodat de eigen versie weg kan.
- **Zelf draaien (test):** `python3 pipeline/run_pipeline.py --data data`, of `--offline export1.json export2.json`.
- GitHub zet geplande workflows stil na 60 dagen zonder activiteit; de wekelijkse run bewaart daarom altijd
  `meta.json` met de datum van de laatste controle.

## Testen

- **Rekenmotor:** `app/src/test/.../EngineTest` (draait ook bij elke build in Actions). Gesimuleerde ritten, o.a. de E40
  richting kust met drie trajecten na elkaar, een tunnel van 30 s en GPS-ruis. Criterium: gemiddelde binnen 1 km/u,
  instapmoment binnen 1,5 s.
- **Echte ritten:** het ritlogboek staat in `Android/data/be.koen.trajectcontrole/files/logs/`. De `FIX`-regels kunnen
  opnieuw door de rekenmotor gestuurd worden, zodat elk verschil achteraf te onderzoeken is.

## Bekende beperkingen

1. **GPS ≠ teller ≠ meting van de politie.** De app rekent met GPS-afstand langs de trajectlijn en tijd; houd marge.
2. **De positiebol loopt ±1 s achter** door de vloeiende beweging; de berekening niet.
3. **Tweede scherm** verschijnt enkel als de auto het instrumentenpaneel aan navigatie-apps aanbiedt.
4. **Automatisch starten via Bluetooth** hangt af van de batterijbesparing van het toestel.
5. **Simulaties gebruiken willekeurige GPS-ruis**; echte GPS wijkt soms een tijd in dezelfde richting af
   (gebouwen, vangrails). Echte ritten blijven de eigenlijke validatie.
6. **De melding vóór het begin** werkt op rechte aanlopen; in een bocht vlak voor het begin kan ze wegvallen.

## Onderhoud van deze documentatie

Bij elke nieuwe app-versie: `versionName`/`versionCode` verhogen, een sectie toevoegen in `CHANGELOG.md`
en deze README bijwerken waar functies of werking veranderen. De build in Actions faalt als de sectie ontbreekt.
Zie ook `CLAUDE.md`.
