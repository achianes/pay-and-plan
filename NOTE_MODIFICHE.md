# Note sulle modifiche — Pay & Plan "locale" (29/09/2026)

Obiettivo: app **generica e pubblicabile sul Play Store**, che funziona **senza server e senza account**.
Il server (`server/`, `webapp/`) resta com'è: è facoltativo, per chi vuole condividere il calendario
con la famiglia ospitando un proprio server.

## Cosa è cambiato nell'app Android

### 1. Modalità locale (nuova, è la scelta principale)
- **Schermata iniziale** (`ui/screens/LoginScreen.kt`): nuova card "📱 ON THIS PHONE" con nome + START.
  Nessuna email, nessuna password, nessun server. Sotto resta il login a un server proprio (facoltativo).
- **Nessun server predefinito** (`util/Prefs.kt`): `DEFAULT_SERVER` ora è vuoto
  (prima era `https://pay.achianes.net`). Il login è possibile solo scrivendo l'indirizzo di un server.
  Chi aveva già fatto login conserva il suo indirizzo (era già salvato nelle preferenze).
- **`Prefs.localMode`**: indica che si lavora solo sul telefono.
- **`Repository`** (`data/Repository.kt`):
  - `isLocal`, `startLocal(name)`: crea una persona locale (`local-<uuid>`) e un calendario "Home" sul telefono.
  - Calendari locali: crea / rinomina + valuta / elimina (cancella righe, file e allarmi) / cambia nome, senza server.
  - `refreshAccount()` non fa nulla senza login; `sync()` già non faceva nulla senza login.
- **Funzioni che prima passavano dal server, ora fatte dal telefono** (`net/LocalServices.kt`, nuovo):
  - Codice a barre → prodotto: interroga direttamente Open Food/Products/Beauty/Pet Food Facts
    (stessa logica del server: varianti del codice, ITF-14, GS1), scarica la foto come allegato.
  - Foto "ereditata" da un prodotto già comprato: copia del file sul telefono.
  - Ricerca indirizzi: OpenStreetMap Nominatim direttamente.
  - "Leggi il link" nelle note: titolo + descrizione della pagina (senza immagini).
  - Link di Google Calendar: senza server diventa una nota (come prima quando il server non lo leggeva).
  - Lettura scontrini con AI (Ollama): **solo con server**; in locale la card resta nascosta.
- **Setup** (`ui/screens/SettingsScreen.kt`) in locale: niente email, LOG OUT, SYNC, persone, codice invito
  e "join"; restano nome, calendari, valuta, eliminazione, promemoria, permessi.

### 2. Backup
- **Token di login separato** in `payplan_auth.xml` (migrazione automatica dal vecchio file):
  non va più nel backup Google né nella copia.
- **Backup automatico del telefono** (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`):
  - cloud Google: database + `payplan_prefs.xml` (piccoli, sempre sotto il limite di 25 MB);
  - allegati **esclusi dal cloud** (foto e ricevute supererebbero i 25 MB e Android salterebbe tutto il backup);
  - passaggio diretto telefono→telefono: database + allegati + preferenze.
- **Copia a mano** (`data/Backup.kt`, nuovo; card "💾 BACKUP" in Setup):
  - "SAVE A COPY": zip con database + allegati + impostazioni, salvato con la finestra di sistema
    → l'utente può scegliere **Google Drive** senza alcun login/chiave/progetto Google nell'app;
  - "RESTORE": controlla che sia una copia di Pay & Plan (e non di una versione più nuova),
    sostituisce i dati e riavvia l'app;
  - "Phone backup settings": apre le impostazioni di backup Google del telefono.
- `AppDatabase.close()` aggiunto per il ripristino.

### 3. Firma release
- Nuovo keystore `keystore/payandplan-release.jks` + `signing.properties` (password), **entrambi fuori da git**
  (`.gitignore`). Prima la release era firmata con la chiave di debug (lo Store la rifiuta).
- Senza `signing.properties` la release si firma ancora con la chiave debug (solo per prove).
- ⚠️ **Conservare keystore e password** (es. password manager): senza, non si possono pubblicare aggiornamenti.
- ⚠️ Le app già installate (firmate debug) non si aggiornano con la release nuova: serve disinstallare.
  La build **debug** invece si installa sopra e conserva i dati.

## Provato (BlueStacks)
- START senza account → calendario vuoto; creata una spesa "Affitto 650 €" → salvata; notifica di scadenza arrivata.
- SAVE A COPY → zip in Download (json + db). RESTORE con zip di un'altra app → rifiutato
  ("This is not a Pay & Plan copy"). RESTORE con la copia giusta → app riavviata, dati presenti.

## Da fare / attenzione
- ⚠️ **`fallbackToDestructiveMigration()`** in `AppDatabase`: con il server era una cache, **in locale
  cancellerebbe tutti i dati** al prossimo cambio di schema. Prima di cambiare lo schema (versione 8)
  scrivere le `Migration` Room (gli schemi sono in `app/schemas/`).
- Dopo un ripristino dal backup Google su un telefono nuovo, gli allegati non ci sono (esclusi dal cloud):
  per quelli serve la copia zip.
- Passare da locale a server (portare i dati locali su un server) non è previsto: sono due modalità separate.
- Play Store: serve una privacy policy generica (quella in `webapp/privacy.html` cita la Gmail personale
  e il server di casa) e il README cita `pay.achianes.net` / progetto Google `leggimi-509009` (solo per il server).
- Il gradle wrapper non funziona con `&` nel percorso: usare `subst P: "C:\tmp\Pay&Plan"` e compilare da `P:\`.
