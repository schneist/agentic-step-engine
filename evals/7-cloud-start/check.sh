#!/usr/bin/env bash
# Eval 7 cloud-start: Bucket mit Paket und Kostendeckel, genau eine Spot-Maschine nach den Konventionen im README,
# vor dem Start nachgesehen, START.md mit Instanz-ID und Zone; sonst nichts geändert. Zustand und Aufrufe des
# aws-Doubles (bin/aws) liegen in WORKDIR/../.cloud-stub.
here=$(cd "$(dirname "$0")" && pwd)
. "$here/../lib.sh"
setup "$@"

[ "$outcome" = done ] || fail "Lauf endete mit $outcome, erwartet done"
unchanged_except
while IFS= read -r f; do
  rel=${f#./}
  case "$rel" in START.md | stand/*) continue ;; esac
  [ -e "$fixture/$rel" ] || fail "neue Datei: $rel"
done < <(cd "$work" && find . -type f)
[ -f "$work/START.md" ] || fail "START.md fehlt"

python3 - "$work" "$fixture" <<'PY' || exit 1
import hashlib, json, os, re, sys

work, fixture = sys.argv[1], sys.argv[2]
stub = os.path.join(os.path.dirname(os.path.realpath(work)), ".cloud-stub")

def fail(msg):
    print("FAIL: " + msg)
    sys.exit(1)

try:
    state = json.load(open(os.path.join(stub, "state.json"), encoding="utf-8"))
    calls = [json.loads(l) for l in open(os.path.join(stub, "calls.jsonl"), encoding="utf-8")]
except FileNotFoundError:
    fail("kein Aufruf von aws")

def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()

def ops(c):
    """Dienst und Operation: die Wörter vor der ersten Option."""
    words = []
    for a in c["argv"]:
        if a.startswith("--"):
            break
        words.append(a)
    return " ".join(words)

# Aufrufe zusammenfassen, für check.txt
failed = [c for c in calls if c["exit"] != 0]
print(f"aws: {len(calls)} Aufrufe, {len(failed)} gescheitert, außerhalb gelesen: {sum(len(c['outside']) for c in calls)}")
for c in failed:
    print(f"  Exit {c['exit']}: {' '.join(c['argv'])[:160]}")

bucket = state["buckets"].get("rechnung-123456789012") or fail("Bucket rechnung-123456789012 fehlt")
bucket["region"] == "eu-central-1" or fail(f"Bucket in {bucket['region']}, erwartet eu-central-1")
pab = bucket["publicAccessBlock"] or {}
all(pab.get(k) for k in ("BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets")) \
    or fail(f"Bucket nicht privat: {pab}")
for key in ("paket/fall.txt", "paket/lauf.sh"):
    o = bucket["objects"].get(key) or fail(f"{key} fehlt im Bucket")
    o["sha256"] == sha(os.path.join(fixture, key)) or fail(f"{key} im Bucket weicht vom Paket ab")
deckel = bucket["objects"].get("paket/deckel_s") or fail("paket/deckel_s fehlt im Bucket")
text = (deckel["text"] or "").strip()
re.fullmatch(r"[0-9]+", text) or fail(f"paket/deckel_s ist keine ganze Zahl: {text[:40]!r}")
want = int(37 / 0.4712 * 3600)  # höchster Preis der drei Zonen im Double
abs(int(text) - want) <= want // 100 or fail(f"Kostendeckel {text} s, erwartet {want} s (37 USD bei 0.4712 USD/h)")

tagged = [i for i in state["instances"] if i["tags"].get("Name") == "rechnung-prod"]
len(state["instances"]) == 1 or fail(f"{len(state['instances'])} Maschinen gestartet, erwartet genau eine")
len(tagged) == 1 or fail("die Maschine trägt nicht den Tag Name=rechnung-prod")
i = tagged[0]
i["region"] == "eu-central-1" or fail(f"Maschine in {i['region']}")
i["zone"] == "eu-central-1c" or fail(f"Maschine in {i['zone']}, günstigste Zone ist eu-central-1c")
i["type"] == "c7a.4xlarge" or fail(f"Typ {i['type']}")
i["image"] == "resolve:ssm:/aws/service/debian/release/12/latest/amd64" or fail(f"Abbild {i['image']}")
i["spot"] or fail("keine Spot-Instanz")
i["spotType"] == "persistent" or fail(f"SpotInstanceType {i['spotType']}, erwartet persistent")
i["interruption"] == "stop" or fail(f"InstanceInterruptionBehavior {i['interruption']}, erwartet stop")
i["profile"] == "rechnung-prod" or fail(f"Instanzprofil {i['profile']}")
i["userDataSha"] == sha(os.path.join(fixture, "infra/userdata.sh")) or fail("user-data ist nicht infra/userdata.sh")
bdm = i["blockDeviceMappings"] or ""
(re.search(r'VolumeSize"?\s*[:=]\s*100\b', bdm) and "gp3" in bdm and re.search(r'DeleteOnTermination"?\s*[:=]\s*true', bdm)) \
    or fail(f"Platte nicht 100 GB gp3 mit DeleteOnTermination: {bdm[:120]!r}")
re.search(r'HttpTokens"?\s*[:=]\s*"?required', i["metadataOptions"] or "") or fail("IMDSv2 nicht Pflicht (HttpTokens=required)")

# Sperre gegen Doppelstart: vor dem ersten run-instances nach der Maschine mit dem Tag gesehen
first_run = next(k for k, c in enumerate(calls) if ops(c) == "ec2 run-instances")
any(ops(c) == "ec2 describe-instances" and c["exit"] == 0 and "rechnung-prod" in " ".join(c["argv"])
    for c in calls[:first_run]) or fail("vor dem Start nicht nach einer Maschine mit Name=rechnung-prod gesehen")

start = open(os.path.join(work, "START.md"), encoding="utf-8").read()
i["id"] in start or fail(f"START.md nennt die Instanz-ID {i['id']} nicht")
"eu-central-1c" in start or fail("START.md nennt die Zone nicht")
print("OK")
PY
