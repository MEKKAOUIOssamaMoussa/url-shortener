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
