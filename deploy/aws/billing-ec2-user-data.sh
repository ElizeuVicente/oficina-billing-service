#!/bin/bash
set -euo pipefail

REGION="us-east-1"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text --region "$REGION")"
IMAGE="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/oficina-billing-service:phase4"

dnf install -y docker
systemctl enable --now docker

mkdir -p /opt/oficina-billing
chmod 700 /opt/oficina-billing

DB_PASSWORD="$(openssl rand -hex 24)"
SERVICE_TOKEN="$(openssl rand -hex 32)"

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "${IMAGE%%/*}"
docker network create oficina-billing || true

docker run -d --restart unless-stopped --name billing-postgres --network oficina-billing \
  -e POSTGRES_DB=billing -e POSTGRES_USER=billing -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -v billing-postgres-data:/var/lib/postgresql/data postgres:15-alpine

until docker exec billing-postgres pg_isready -U billing -d billing; do sleep 2; done

docker run -d --restart unless-stopped --name billing-rabbit --network oficina-billing \
  -e RABBITMQ_DEFAULT_USER=billing -e RABBITMQ_DEFAULT_PASS="$DB_PASSWORD" \
  rabbitmq:3.13-alpine

docker run -d --restart unless-stopped --name billing --network oficina-billing -p 8081:8081 \
  -e BILLING_DB_URL=jdbc:postgresql://billing-postgres:5432/billing \
  -e BILLING_DB_USER=billing -e BILLING_DB_PASSWORD="$DB_PASSWORD" \
  -e BILLING_SERVICE_TOKEN="$SERVICE_TOKEN" \
  -e BILLING_OUTBOX_ENABLED=true \
  -e RABBITMQ_HOST=billing-rabbit -e SPRING_RABBITMQ_USERNAME=billing -e SPRING_RABBITMQ_PASSWORD="$DB_PASSWORD" \
  -e BILLING_MERCADOPAGO_CHECKOUT_ENABLED=false \
  -e BILLING_MERCADOPAGO_CHECKOUT_AUTO_ENABLED=false \
  -e BILLING_MERCADOPAGO_WEBHOOK_ENABLED=false \
  -e BILLING_MERCADOPAGO_RECONCILIATION_ENABLED=false \
  -e BILLING_MERCADOPAGO_REFUNDS_ENABLED=false \
  "$IMAGE"
