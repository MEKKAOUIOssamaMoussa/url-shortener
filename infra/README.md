# Infrastructure Bootstrap (Stage 0b)

This directory documents the initial cloud infrastructure provisioning for the URL shortener on Azure.

## Overview

The [`bootstrap.sh`](file:///home/jinruy/projects/url-shortener/infra/bootstrap.sh) script documents the manual, one-time bootstrap process for setting up:
- **Resource Group**: `rg-shortener` in `francecentral`.
- **Container Apps Environment**: `cae-shortener` configured with `--logs-destination none` to avoid Log Analytics workspace ingestion/retention charges.
- **Container App**: `ca-shortener` with external ingress on port 8080, 0.5 vCPU, 1.0 GiB memory, `minReplicas = 0` (scale-to-zero), and `maxReplicas = 1` (strict cost ceiling).
- **Entra OIDC Identity**: Dedicated application and service principal (`gh-url-shortener-deployer`) with `Contributor` role scoped strictly to `rg-shortener` and a federated credential bound to `repo:MEKKAOUIOssamaMoussa/url-shortener:ref:refs/heads/main`.
- **GitHub Secrets**: Non-secret IDs (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`) configured in the repository.

## Prerequisites

Before executing the script:
1. [Azure CLI](https://learn.microsoft.com/en-us/cli/azure/) installed and authenticated:
   ```bash
   az login
   ```
2. [GitHub CLI](https://cli.github.com/) installed and authenticated:
   ```bash
   gh auth login
   ```
3. Appropriate permissions (Owner or User Access Administrator + Contributor) on the target subscription.

## Evolution

This manual script serves as an explicit, auditable record of initial provisioning. In later stages, this infrastructure will be codified and managed declaratively via Terraform.

## Stage 1: Database Connection Configuration

Stage 1 connects Azure Container Apps to Neon Serverless PostgreSQL and configures runtime environment variables and secret references.

### Prerequisites & Required Environment Variables

Before running the Stage 1 database configuration commands, export the following environment variables in your terminal session:

```bash
export NEON_HOST="ep-<id>.<region>.aws.neon.tech"  # Direct host (not pooler)
export NEON_DB="<database-name>"                   # Database name placeholder
export NEON_USER="<username>"                      # Database username placeholder
read -rs NEON_PW                                   # Read password without echoing to shell history
export NEON_PW
```

### Execution

With Azure CLI logged in (`az login`) and variables exported, run the Stage 1 section of [`bootstrap.sh`](bootstrap.sh):

```bash
# 1. Set DB password secret in Container Apps
az containerapp secret set \
  --name ca-shortener \
  --resource-group rg-shortener \
  --secrets "db-password=$NEON_PW"

# 2. Derive base URL from Container App ingress FQDN
APP_FQDN=$(az containerapp show \
  --name ca-shortener \
  --resource-group rg-shortener \
  --query "properties.configuration.ingress.fqdn" \
  -o tsv)

# 3. Update container app environment variables
az containerapp update \
  --name ca-shortener \
  --resource-group rg-shortener \
  --set-env-vars \
    DB_URL="jdbc:postgresql://${NEON_HOST}/${NEON_DB}?sslmode=require" \
    DB_USERNAME="${NEON_USER}" \
    DB_PASSWORD="secretref:db-password" \
    SHORTENER_BASE_URL="https://${APP_FQDN}" \
  -o none
```

### Password Rotation Runbook

When rotating the database password:
1. Reset the role password in the Neon console.
2. Read the new password securely in your terminal without saving to disk or command history:
   ```bash
   read -rs NEON_PW
   ```
3. Update the Container App secret:
   ```bash
   az containerapp secret set \
     --name ca-shortener \
     --resource-group rg-shortener \
     --secrets "db-password=$NEON_PW"
   ```
4. Active replicas do not pick up modified secrets automatically. Restart the active revision:
   ```bash
   # Retrieve the active revision name
   ACTIVE_REV=$(az containerapp show \
     --name ca-shortener \
     --resource-group rg-shortener \
     --query "properties.latestRevisionName" \
     -o tsv)

   # Restart the revision to load the updated secret
   az containerapp revision restart \
     --name ca-shortener \
     --resource-group rg-shortener \
     --revision "$ACTIVE_REV"
   ```
   *(Note: The CI deployment workflow does not need any secret updates because it only authenticates via OIDC and updates container images).*

## Stage 7: Static Web Apps

The Angular frontend is hosted on Azure Static Web Apps `swa-shortener` (Free, resource in `eastus2`; content is globally distributed) at `https://gentle-island-020fcf90f.3.azurestaticapps.net`. Setup commands are in the Stage 7 section of [`bootstrap.sh`](bootstrap.sh).

### Deployment token

`AZURE_STATIC_WEB_APPS_API_TOKEN` (GitHub repository secret) is the SWA deployment token. Microsoft describes it as "a token used to identify the application during deployment". It is a bearer credential: anyone holding it can deploy content to this static web app. It is used by the `deploy` job in `.github/workflows/frontend.yml`. It is never displayed: it goes straight from `az` into `gh` through a pipe.

### Token rotation runbook

Rotate when the token may have been exposed:
```bash
# 1. Invalidate the current token
az staticwebapp secrets reset-api-key \
  --name swa-shortener \
  --resource-group rg-shortener \
  -o none

# 2. Pipe the new token into the GitHub secret (gh reads stdin when --body is omitted)
az staticwebapp secrets list \
  --name swa-shortener \
  --resource-group rg-shortener \
  --query "properties.apiKey" \
  -o tsv \
  | gh secret set AZURE_STATIC_WEB_APPS_API_TOKEN -R MEKKAOUIOssamaMoussa/url-shortener
```
Deploys between steps 1 and 2 fail. Re-run the latest Frontend CI run on `main` afterwards to confirm the new token works.

### Changing `CORS_ALLOWED_ORIGINS`

Production allows only the SWA origin (`http://localhost:4200` is the local default from `application.yml`, not set in production). Multiple origins are comma-separated.
```bash
az containerapp update \
  --name ca-shortener \
  --resource-group rg-shortener \
  --set-env-vars CORS_ALLOWED_ORIGINS="https://gentle-island-020fcf90f.3.azurestaticapps.net" \
  -o none
```
Verify with two preflights (expected: SWA origin `200`, localhost `403`; measured on 2026-11-02):
```bash
API="https://$(az containerapp show --name ca-shortener --resource-group rg-shortener \
  --query properties.configuration.ingress.fqdn -o tsv)/api/urls"

for ORIGIN in https://gentle-island-020fcf90f.3.azurestaticapps.net http://localhost:4200; do
  curl -s -o /dev/null -w "${ORIGIN} -> %{http_code}\n" -X OPTIONS "${API}" \
    -H "Origin: ${ORIGIN}" \
    -H "Access-Control-Request-Method: POST" \
    -H "Access-Control-Request-Headers: Content-Type"
done
```

### Rule: list env vars after every change

Every deploy copies the environment forward to the next revision, so a forgotten temporary setting survives indefinitely (Stage 5 incident: a temporary access log printing client IPs stayed on for revisions up to `0000018`). After **every** env var change, list what is actually set:
```bash
az containerapp show \
  --name ca-shortener \
  --resource-group rg-shortener \
  --query "properties.template.containers[0].env[].name" \
  -o tsv
```
