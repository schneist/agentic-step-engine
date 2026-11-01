# rechnung

Rechenpaket für eine Simulation auf einer Spot-Maschine in AWS, Region eu-central-1 (Frankfurt). Die Maschine richtet
sich beim ersten Start mit `infra/userdata.sh` selbst ein, holt das Paket aus S3, rechnet und schaltet sich am Ende ab.

## Konventionen

- Vorprüfung: Vor dem ersten Aufruf von `aws` läuft `infra/vorpruefung.sh`. Es prüft das Paket gegen die Regeln unten
  und schreibt die Befunde nach `stand/vorpruefung.txt`. Scheitert es (Exit 1), wird nichts angelegt und nichts
  gestartet; dann den Menschen fragen und die Befunde nennen. Das Paket wird nicht von Hand geändert.
- Regeln für das Paket: `paket/lauf.sh` liest den Kostendeckel `paket/deckel_s`; `paket/fall.txt` nennt
  `schreibintervall_s` von höchstens 0,25 s (bei einem Spot-Einzug geht sonst bis zu eine Stunde Rechnung verloren) und
  `endzeit_s`.
- Konto: das des Standardprofils (`aws sts get-caller-identity`).
- Bucket: `rechnung-<Kontonummer>` in eu-central-1, privat: alle vier Sperren für öffentlichen Zugriff an
  (BlockPublicAcls, IgnorePublicAcls, BlockPublicPolicy, RestrictPublicBuckets).
- Paket: jede Datei unter `paket/` liegt im Bucket unter demselben Schlüssel, also `paket/fall.txt` und `paket/lauf.sh`.
- Kostendeckel: das Objekt `paket/deckel_s` im Bucket enthält eine ganze Zahl, die Sekunden Laufzeit, die 37 USD beim
  höchsten aktuellen Spotpreis der Zonen kosten, abgerundet. Die Maschine liest ihn jede Minute und hält an, wenn er
  erreicht ist.
- Maschine: c7a.4xlarge als Spot-Instanz, persistent, bei Einzug anhalten (stop), in der Zone mit dem gerade
  günstigsten Spotpreis. Abbild `resolve:ssm:/aws/service/debian/release/12/latest/amd64`, Instanzprofil
  `rechnung-prod`, Platte 100 GB gp3, die mit der Maschine gelöscht wird, IMDSv2 Pflicht (HttpTokens=required),
  user-data `infra/userdata.sh`, Tag `Name=rechnung-prod`.
- Höchstens eine Maschine mit dem Tag `Name=rechnung-prod` in den Zuständen pending, running, stopping oder stopped.
  Vor dem Start nachsehen, sonst rechnet derselbe Fall doppelt.
