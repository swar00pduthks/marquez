# Azure PostgreSQL Migration Test Setup

## Prerequisites
- Azure CLI installed and logged in
- Azure subscription: <changeme>
- PowerShell 7+

## Quick Setup Commands

```powershell
# Login to Azure
az login

# Set subscription
az account set --subscription YOUR_SUBSCRIPTION_ID

# Create resource group for testing
az group create `
  --name rg-marquez-migration-test `
  --location eastus

# Create Azure PostgreSQL Flexible Server
az postgres flexible-server create `
  --resource-group rg-marquez-migration-test `
  --name marquez-test-db-$(Get-Random -Minimum 1000 -Maximum 9999) `
  --location eastus `
  --admin-user marquezadmin `
  # NOTE: Azure Database for PostgreSQL admin passwords must be 8–128 characters and contain characters from at least 3 of these 4 categories:
  #   - Lowercase letters
  #   - Uppercase letters
  #   - Numbers
  #   - Non-alphanumeric characters (for example: !, $, #, %)
  # The password cannot contain the username ("marquezadmin") or "azure".
  --admin-password "Str0ngAdminPwd!23" `
  --sku-name Standard_B2s `
  --tier Burstable `
  --version 15 `
  --storage-size 128 `
  --public-access 0.0.0.0 `
  --yes

# Create database
az postgres flexible-server db create `
  --resource-group rg-marquez-migration-test `
  --server-name <server-name-from-above> `
  --database-name marquez_test

# Configure firewall rule for your IP
az postgres flexible-server firewall-rule create `
  --resource-group rg-marquez-migration-test `
  --name <server-name> `
  --rule-name AllowMyIP `
  --start-ip-address <your-ip> `
  --end-ip-address <your-ip>

# Get connection string
az postgres flexible-server show-connection-string `
  --server-name <server-name> `
  --database-name marquez_test `
  --admin-user marquezadmin `
  --admin-password <changeMe>
```

## Cost Estimation
- **Standard_B2s**: ~$30/month (~$1/day)
- **128GB storage**: ~$15/month (~$0.50/day)
- **Total**: ~$1.50/day for testing

**Remember to delete after testing!**
