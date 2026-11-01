#!/bin/bash
# Erster Start der Maschine: Pakete, Paket aus dem Bucket, Dienst für lauf.sh.
set -eu
apt-get update -q && apt-get install -y -q awscli
konto=$(aws sts get-caller-identity --query Account --output text --region eu-central-1)
mkdir -p /root/rechnung && cd /root/rechnung
aws s3 cp "s3://rechnung-$konto/paket/" . --recursive --region eu-central-1
bash lauf.sh
