# Trajectcontrole – gemiddelde snelheid (Android + Android Auto)

App voor eigen gebruik die de gemiddelde snelheid binnen Belgische trajectcontroles volgt, naast Waze.
Versie 0.4, 4 november 2026.

**Buildversies:** Android Gradle Plugin 8.13.2 · Gradle 8.13 (wrapper) · Kotlin 2.2.21 · JDK 17 · compileSdk 36 / targetSdk 35. Android Studio zou geen upgrade meer mogen voorstellen.

## Wat de app doet

- **Volgt je positie elke seconde** via GPS, in een voorgrondservice met een vaste melding.
- **Herkent trajecten** door voortdurend je positie op de trajectlijn te leggen, niet via een startpunt dat je kan missen.
  - Wordt een traject laat herkend, dan zoekt de app in de laatste 15 minuten GPS-historiek het echte instapmoment terug.
  - Aaneensluitende trajecten worden expliciet gekoppeld (bv. E40 Zwijnaarde → Drongen → Aalter → Beernem). Het volgende traject start op exact hetzelfde moment als het vorige eindigt.
- **Toont op de telefoon en in Android Auto:**
  - het gemiddelde tot nu toe, met kleur:
    - groen: tot de limiet
    - oranje: tussen de limiet en de boetedrempel
    - rood: boven de boetedrempel
  - het maximum voor de rest van het traject
  - de resterende afstand
- **Spraak** (Waze en muziek gaan even zachter):
  - bij het binnenrijden
  - als de kleur verandert
  - elke 45 s zolang je in het rood zit
  - bij het verlaten, met je eindgemiddelde
- **Logboek** per rit (CSV) voor de vergelijkingsritten met Flitsmeister.
- **Nieuw in 0.3** (*Instellingen* op het hoofdscherm):
  - **Automatisch starten via Bluetooth**: de app start zodra je telefoon verbindt met de gekozen auto. Hij stopt 30 s na het verbreken; even opnieuw verbinden binnen die tijd stopt de app niet.
  - **Automatisch afsluiten na 5 min stilstand**: geldt enkel bij manueel starten, zodat de app bij een Bluetooth-start niet in de file afsluit. Nooit midden in een traject.
  - **Overlay** boven Waze op het telefoonscherm: het gemiddelde in een gekleurde cirkel, de limiet in een rode ring. Verschijnt enkel binnen een traject en is versleepbaar.
  - **Melding ±400 m voor het begin** van een traject.
  - **Spraak per soort aan of uit**: nadering, begin, einde, te snel.

Boetedrempel (technische correctie BE: −6 km/u onder 100, −6 % vanaf 100):

| Limiet | 30 | 50 | 70 | 90 | 120 |
|---|---|---|---|---|---|
| Rood boven (gemeten) | 36 | 56 | 76 | 96 | 127,7 |

- **Nieuw in 0.4: trajectdata wordt automatisch bijgewerkt.** Zie *Trajectdata bijwerken* hieronder.

## Bouwen en installeren

1. Open de map in **Android Studio** (Ladybug of nieuwer) via *File → Open*. Laat Gradle synchroniseren; dat duurt de eerste keer een paar minuten.
2. Zet op je telefoon *Ontwikkelaarsopties → USB-foutopsporing* aan en sluit de telefoon aan.
3. Druk op **Run**. De app wordt geïnstalleerd.
   - Een losse APK maken kan ook: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
4. Open de app, tik **Starten** en sta locatie en meldingen toe.

### Automatisch starten via Bluetooth (eenmalig instellen)

*Instellingen → Starten bij verbinden met de auto*, en volg dan de stappen op het scherm:

1. Geef toestemming voor *Apparaten in de buurt* en vink je auto aan in de lijst van gekoppelde toestellen.
2. Zet *Locatie* op **Altijd toestaan**. Zonder dit weigert Android om de app vanuit de achtergrond te starten.
3. Sta een **uitzondering op batterijoptimalisatie** toe. Sommige fabrikanten blokkeren anders de Bluetooth-trigger.

Weigert Android het starten toch, dan krijg je een melding *Tik om te starten*.

### Android Auto

1. Android Auto-instellingen → tik ±10× op *Versie* → bevestig de ontwikkelaarsmodus.
2. Menu (⋮) → *Instellingen voor ontwikkelaars* → vink **Onbekende bronnen** aan.
3. Android Auto-instellingen → *Launcher aanpassen* → vink **Trajectcontrole** aan.
4. In de auto: Waze blijft navigeren; open Trajectcontrole om de cijfers te zien. De spraak werkt ook zonder dat het scherm open staat.

## Testen

- **Rekenmotor (op je pc):** in Android Studio rechtsklik op `app/src/test/.../EngineTest` → *Run*.
  - Zes gesimuleerde ritten, waaronder E40 richting kust met drie trajecten na elkaar, een tunnel van 30 s en GPS-ruis.
  - Criterium: gemiddelde binnen 1 km/u en instapmoment binnen 1,5 s.
- **Eerste echte rit:** laat Flitsmeister en deze app naast elkaar draaien. Het logboek staat in `Android/data/be.koen.trajectcontrole/files/logs/`.
  - De `FIX`-regels kunnen opnieuw door de rekenmotor gestuurd worden. Zo kan elk verschil achteraf onderzocht worden.

## Bronnen

1. **OpenStreetMap** (ODbL): alle Belgische `enforcement=average_speed`-relaties.
2. **AWV open data** (laag `awv:Trajectcontroles` op de open-dataserver van het Agentschap Wegen en Verkeer): trajectcontroles op Vlaamse gewest- en snelwegen.
   - Opgenomen: status *Opgeleverd* (ook als ze niet actief zijn) en *UNKNOWN*. Die laatste zijn installaties van de federale politie en de politiezones die AWV mee registreert.
   - De limiet komt uit de AWV-laag `Snelheidsregimes_op_wegen_in_beheer_van_AWV`.
   - Opgelet: de coördinaten staan in Lambert 2008 terwijl de laag Lambert 72 opgeeft. De pipeline rekent ze zelf om.
   - Overlapt een AWV-traject met een bestaand traject maar verschilt de lengte, dan blijft de bestaande versie staan en meldt het rapport het verschil.
3. **Eigen meldingen** (`data/eigen_meldingen.csv`) en **eigen trajecten** (`data/eigen_trajecten.tsv`).

## Trajectdata bijwerken (automatisch)

```
elke maandag 03:17 UTC (GitHub Actions)
  └─ pipeline/run_pipeline.py
       1. Overpass: alle Belgische trajectcontroles + wegen errond
       2. verwerken, aanvullende query voor trajecten zonder wegdata, opnieuw verwerken
       3. CONTROLES: ≥ 100 trajecten · niet > 10 % minder dan vorige versie · E40 ≥ 15 · geen traject < 100 m
       4. data/segments.tsv + data/meta.json (versie, sha256) + data/RAPPORT.md
  └─ bij wijziging of fout: issue met het rapport (= e-mail van GitHub)
app (bij openen of starten, hoogstens 1x per dag, enkel op wifi)
  └─ meta.json → nieuwere versie? → download → sha256 + ≥ 100 trajecten → vervangen
     → actief vanaf de volgende start. Lukt iets niet: vorige versie blijft.
```

- **Faalt een controle, dan wordt er niets gepubliceerd.** Je krijgt een issue "NIET gepubliceerd" met de reden. Het gaat meestal om een tijdelijke fout in OpenStreetMap of bij Overpass. Je kunt de workflow later opnieuw draaien via *Actions → Trajectcontroles bijwerken → Run workflow*.
- **Manueel controleren in de app:** *Instellingen → Trajectdata → Nu controleren*. Dat werkt ook via mobiele data.
- **Zelf trajectcontroles toevoegen:** voeg een regel toe aan `data/eigen_meldingen.csv` (op github.com: bestand openen → potloodje → *Commit changes*):
  `naam;limiet;richting;begin;einde`, bijvoorbeeld `Tiensesteenweg Kessel-Lo;50;beide;50.8812,4.7231;50.8790,4.7342`.
  Begin en einde zijn de plaatsen van de camera's (Google Maps: lang drukken → coördinaten kopiëren). Start daarna *Actions → Trajectcontroles bijwerken → Run workflow*. Het rapport (issue) toont per regel of het traject op de weg gelegd kon worden, en zo niet, waarom.
- **Eigen trajecten** die niet in OpenStreetMap staan (vaak gemeentelijke trajectcontroles), komen in `data/eigen_trajecten.tsv` in de repository. De wekelijkse update voegt ze samen met de OSM-data. Het rapport meldt het als zo'n traject later ook in OpenStreetMap opduikt, zodat de eigen versie weg kan. Om ze juist op de weg te leggen is er de workflow *OSM-opzoeking (hulpmiddel)*.
- **Je eigen `correcties.tsv` gaat altijd voor** op de gedownloade data (zie `app/src/main/assets/LEESMIJ_correcties.txt`).
- **Zelf draaien (test):** `python3 pipeline/run_pipeline.py --data data`, of `--offline export1.json export2.json` met bestaande exports.

## Eenmalige installatie op GitHub

1. Maak op github.com een **publieke** repository `trajectcontrole` aan onder `dekoene007`, zonder README.
   - Publiek is nodig zodat de app de data kan downloaden zonder wachtwoord of token.
   - De repository bevat enkel de app-code en OpenStreetMap-data, niets persoonlijks.
2. Zet de inhoud van deze map erin. Met git:
   ```
   cd trajectcontrole
   git init -b main
   git add .
   git commit -m "Trajectcontrole v0.4"
   git remote add origin https://github.com/dekoene007/trajectcontrole.git
   git push -u origin main
   ```
   Uploaden via de website kan ook. Let er dan op dat de verborgen map `.github` mee gaat, want sommige verkenners verbergen die.
3. Op GitHub, tabblad **Actions**: start *Trajectcontroles bijwerken* een eerste keer manueel met **Run workflow**. Na 5–15 minuten:
   - Is de run groen, dan is alles in orde.
   - Volgt er een issue, dan veranderde OpenStreetMap sinds 4 november. Het rapport toont wat.
4. Lukt het terugschrijven niet (fout bij *git push*): ga naar *Settings → Actions → General → Workflow permissions* en kies **Read and write permissions**.
5. Wil je de issues per e-mail ontvangen, kijk dan na of meldingen per e-mail aan staan in je GitHub-profiel (*Settings → Notifications*).

**Goed om te weten:** GitHub zet geplande workflows stil na 60 dagen zonder activiteit. De workflow bewaart daarom elke week `meta.json` met de datum van de laatste controle. Dat telt als activiteit, zodat de planning blijft lopen.

## Stand van de data (export 3–4 november 2026)

- **139 van 177** Belgische trajecten zijn opgenomen, waarvan 19 op wegen in beide richtingen. Bij die laatste bepaalt de app de richting tijdens het rijden.
- **De E40 is volledig**, behalve twee kapotte stukjes bij Wetteren (4 en 9 m lang in OpenStreetMap).
- **Niet opgenomen (37):**
  - 10 met een rijrichting die tegen de weg in lijkt te lopen
  - 8 met meerdere begin- of eindpunten
  - 10 waarvoor geen route over de weg gevonden werd (3 daarvan hebben ook meerdere begin- of eindpunten)
  - 6 met te weinig punten
  - 3 verdacht korte

  Eén rit langs zo'n traject en een regel in `correcties.tsv` lost het op. Nog beter is het traject in OpenStreetMap te verbeteren: dan neemt de volgende wekelijkse update het mee.

Een traject (13590319) werd in 0.4 uitgesloten: de controles van de pipeline vonden een lijn van 0 m.

Bron trajecten: © OpenStreetMap-bijdragers (ODbL).

## Bekende beperkingen en risico's (eerst op de eigen auto testen)

1. **Wat wel en niet gecompileerd werd.**
   - Versie 0.2 bouwde bij jou zonder fouten.
   - De rekenmotor, met de nieuwe melding bij nadering, is hier gecompileerd en getest.
   - De Android-code van 0.3 en 0.4 (Bluetooth, overlay, instellingen, downloaden van data) is enkel nagelezen tegen de documentatie. Een kleine compileerfout bij de eerste build is mogelijk.
   - De pipeline en de controles zijn hier getest met de exports van 3–4 november. Het ophalen bij Overpass zelf kon hier niet getest worden, omdat die server vanuit deze omgeving geblokkeerd is. De eerste manuele run op GitHub is dus de echte test.
2. **Vernieuwen van het scherm in Android Auto:** de app ververst hoogstens om de 2 s. Of jouw Range Rover-host dat toelaat, moet een rit tonen. Lukt het niet, dan blijft de spraak het hoofdkanaal.
3. **Starten vanuit de auto:** Android kan het starten van een locatieservice vanuit Android Auto weigeren als de telefoonapp nog nooit geopend werd. Start de eerste keer op de telefoon.
4. **Automatisch starten**: de Bluetooth-trigger hangt af van hoe streng je toestel batterijbesparing toepast. Test het eerst een paar keer met de auto.
   - *Starten op basis van beweging* (zoals Flitsmeister) zit er niet in. Daarvoor zijn Google Play-services nodig, en met Bluetooth heb je het niet nodig.
5. **De simulatie gebruikt willekeurige GPS-ruis.** Echte GPS wijkt vaak een tijd in dezelfde richting af en kaatst terug tegen gebouwen en vangrails. De echte rit is de eigenlijke validatie.
6. **De melding bij nadering** werkt op rechte aanlopen, zoals snelwegen en de meeste gewestwegen. In een bocht vlak voor het begin kan ze wegvallen; het traject zelf wordt wel herkend.
