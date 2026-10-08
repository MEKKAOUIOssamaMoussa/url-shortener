#!/usr/bin/env bash
# ==============================================================================
# Azure Infrastructure Bootstrap Script
#
# NOTE: This script is intended to be run MANUALLY, ONCE, by an administrator
# from an authenticated local terminal. CI never executes this script.
#
# Prerequisites:
#   - Azure CLI (az) installed and logged in (az login)
#   - GitHub CLI (gh) installed and authenticated (gh auth login)
#   - Contributor/Owner access on the target Azure subscription
# ==============================================================================

set -euo pipefail

# ------------------------------------------------------------------------------
# Configuration Variables
# ------------------------------------------------------------------------------
RG="rg-shortener"
LOCATION="francecentral"
ENV_NAME="cae-shortener"
APP_NAME="ca-shortener"
GITHUB_REPO="MEKKAOUIOssamaMoussa/url-shortener"
REPO_OWNER_LOWER=$(echo "${GITHUB_REPO%%/*}" | tr '[:upper:]' '[:lower:]')
# Note: :latest is only the bootstrap placeholder image for initial creation;
# CI deploys SHA-tagged images for all subsequent revisions.
IMAGE="ghcr.io/${REPO_OWNER_LOWER}/url-shortener-backend:latest"
ENTRA_APP_NAME="gh-url-shortener-deployer"
FEDERATED_CRED_NAME="github-main"

echo "==> Resolving Azure subscription and tenant IDs..."
SUBSCRIPTION_ID=$(az account show --query id -o tsv)
TENANT_ID=$(az account show --query tenantId -o tsv)

echo "Subscription ID: ${SUBSCRIPTION_ID}"
echo "Tenant ID:       ${TENANT_ID}"

# ------------------------------------------------------------------------------
# 1. Register Resource Providers
# WHAT: Registers Microsoft.App and Microsoft.OperationalInsights resource providers.
# WHY: Required once per subscription before deploying Azure Container Apps resources.
# ------------------------------------------------------------------------------
echo "==> 1. Registering resource providers..."
az provider register --namespace Microsoft.App --wait
az provider register --namespace Microsoft.OperationalInsights --wait

# ------------------------------------------------------------------------------
# 2. Create Resource Group
# WHAT: Creates an Azure Resource Group in the target region.
# WHY: Defines the lifecycle, billing, and access boundary for all URL shortener resources.
# ------------------------------------------------------------------------------
echo "==> 2. Creating resource group '${RG}' in '${LOCATION}'..."
az group create \
  --name "${RG}" \
  --location "${LOCATION}"

# ------------------------------------------------------------------------------
# 3. Create Container Apps Managed Environment
# WHAT: Creates the Container Apps Environment with '--logs-destination none'.
# WHY: Provides the shared network and security boundary for container apps while
#      disabling Log Analytics workspace ingestion to eliminate log retention costs.
# ------------------------------------------------------------------------------
echo "==> 3. Creating Container Apps environment '${ENV_NAME}'..."
az containerapp env create \
  --name "${ENV_NAME}" \
  --resource-group "${RG}" \
  --location "${LOCATION}" \
  --logs-destination none

# ------------------------------------------------------------------------------
# 4. Create Container App
# WHAT: Creates the container app with external ingress, port 8080, 0.5 CPU / 1.0 GiB RAM,
#      min-replicas 0, and max-replicas 1.
# WHY: Allows HTTP traffic over the internet to the Spring Boot app, scales to zero when idle
#      to fit entirely within the ACA free monthly grant, and caps replicas at 1 to prevent runaway costs.
# ------------------------------------------------------------------------------
echo "==> 4. Creating Container App '${APP_NAME}'..."
az containerapp create \
  --name "${APP_NAME}" \
  --resource-group "${RG}" \
  --environment "${ENV_NAME}" \
  --image "${IMAGE}" \
  --target-port 8080 \
  --ingress external \
  --cpu 0.5 \
  --memory 1.0Gi \
  --min-replicas 0 \
  --max-replicas 1

# ------------------------------------------------------------------------------
# 5. Create Microsoft Entra Identity (App Registration & Service Principal)
# WHAT: Creates an Entra Application registration and corresponding Service Principal.
# WHY: Establishes a dedicated machine identity for GitHub Actions deployments without passwords.
# ------------------------------------------------------------------------------
echo "==> 5. Creating Microsoft Entra app registration and service principal..."
APP_ID=$(az ad app create --display-name "${ENTRA_APP_NAME}" --query appId -o tsv)
az ad sp create --id "${APP_ID}" >/dev/null

# ------------------------------------------------------------------------------
# 6. Assign Contributor Role Scoped to Resource Group
# WHAT: Assigns Contributor role to the Service Principal scoped strictly to 'rg-shortener'.
# WHY: Follows least-privilege principles by restricting deployer permissions to this resource group only.
# ------------------------------------------------------------------------------
echo "==> 6. Assigning Contributor role scoped to resource group '${RG}'..."
RG_ID="/subscriptions/${SUBSCRIPTION_ID}/resourceGroups/${RG}"
az role assignment create \
  --assignee "${APP_ID}" \
  --role "Contributor" \
  --scope "${RG_ID}"

# ------------------------------------------------------------------------------
# 7. Configure Federated Credential (OIDC)
# WHAT: Adds a federated identity credential to the Entra application for GitHub Actions.
# WHY: Enables passwordless authentication using OpenID Connect (OIDC) for workflows running
#      on the main branch of the repository.
# ------------------------------------------------------------------------------
echo "==> 7. Creating federated credential for GitHub Actions OIDC..."
# GitHub includes immutable numeric IDs (owner and repo IDs) in the token subject claim
# so the trust rule cannot be matched by a different account or repository that later reuses the same names.
# Azure matches the subject claim exactly, so the federated credential must match this format.
OWNER_ID=$(gh api "users/${GITHUB_REPO%%/*}" --jq .id)
REPO_ID=$(gh api "repos/${GITHUB_REPO}" --jq .id)
FEDERATED_SUBJECT="repo:${GITHUB_REPO%%/*}@${OWNER_ID}/${GITHUB_REPO##*/}@${REPO_ID}:ref:refs/heads/main"
az ad app federated-credential create \
  --id "${APP_ID}" \
  --parameters "{
    \"name\": \"${FEDERATED_CRED_NAME}\",
    \"issuer\": \"https://token.actions.githubusercontent.com\",
    \"subject\": \"${FEDERATED_SUBJECT}\",
    \"description\": \"GitHub Actions deployment from main branch\",
    \"audiences\": [\"api://AzureADTokenExchange\"]
  }"

# ------------------------------------------------------------------------------
# 8. Set GitHub Repository Secrets
# WHAT: Stores AZURE_CLIENT_ID, AZURE_TENANT_ID, and AZURE_SUBSCRIPTION_ID in GitHub secrets.
# WHY: Supplies the required non-secret identifiers for the 'azure/login' action in CI.
# ------------------------------------------------------------------------------
echo "==> 8. Setting GitHub repository secrets via GitHub CLI..."
gh secret set AZURE_CLIENT_ID -b "${APP_ID}" -R "${GITHUB_REPO}"
gh secret set AZURE_TENANT_ID -b "${TENANT_ID}" -R "${GITHUB_REPO}"
gh secret set AZURE_SUBSCRIPTION_ID -b "${SUBSCRIPTION_ID}" -R "${GITHUB_REPO}"

echo "==> Bootstrap completed successfully!"
