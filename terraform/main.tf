terraform {
  required_version = ">= 1.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.0"
    }
  }
}

provider "azurerm" {
  features {}
  subscription_id = var.subscription_id
}

# Random suffix for unique naming
resource "random_string" "suffix" {
  length  = 4
  special = false
  upper   = false
}

# Resource Group
resource "azurerm_resource_group" "marquez_test" {
  name     = var.resource_group_name
  location = var.location

  tags = {
    Environment = "Test"
    Purpose     = "Marquez Migration Testing"
    ManagedBy   = "Terraform"
  }
}

# AKS Cluster
resource "azurerm_kubernetes_cluster" "marquez" {
  count               = var.deploy_aks ? 1 : 0
  name                = "aks-marquez-test-${random_string.suffix.result}"
  location            = azurerm_resource_group.marquez_test.location
  resource_group_name = azurerm_resource_group.marquez_test.name
  dns_prefix          = "marquez-test"

  default_node_pool {
    name       = "default"
    node_count = var.aks_node_count
    vm_size    = var.aks_vm_size

    enable_auto_scaling = true
    min_count          = 1
    max_count          = var.aks_node_count + 2
  }

  identity {
    type = "SystemAssigned"
  }

  network_profile {
    network_plugin = "azure"
    network_policy = "azure"
  }

  tags = {
    Environment = "Test"
    Purpose     = "Marquez Testing"
  }
}

# Note: Kubernetes and Helm providers are configured via kubeconfig
# You must run 'az aks get-credentials' before deploying Kubernetes resources

# Removed Kubernetes/Helm resources - deploy manually after AKS is ready
# See deploy-marquez.ps1 script for Helm deployment

resource "azurerm_postgresql_flexible_server" "marquez" {
  name                   = "marquez-test-${random_string.suffix.result}"
  resource_group_name    = azurerm_resource_group.marquez_test.name
  location              = azurerm_resource_group.marquez_test.location
  version               = "15"
  administrator_login    = var.db_admin_username
  administrator_password = var.db_admin_password

  storage_mb            = var.storage_size_gb * 1024
  sku_name             = var.db_sku_name

  backup_retention_days = 7
  geo_redundant_backup_enabled = false

  tags = {
    Environment = "Test"
    Purpose     = "Migration Testing"
  }
}

# PostgreSQL Configuration for better performance
resource "azurerm_postgresql_flexible_server_configuration" "max_connections" {
  name      = "max_connections"
  server_id = azurerm_postgresql_flexible_server.marquez.id
  value     = "200"
}

resource "azurerm_postgresql_flexible_server_configuration" "shared_buffers" {
  name      = "shared_buffers"
  server_id = azurerm_postgresql_flexible_server.marquez.id
  value     = "524288" # 4GB in 8KB pages
}

resource "azurerm_postgresql_flexible_server_configuration" "work_mem" {
  name      = "work_mem"
  server_id = azurerm_postgresql_flexible_server.marquez.id
  value     = "16384" # 128MB in KB
}

resource "azurerm_postgresql_flexible_server_configuration" "maintenance_work_mem" {
  name      = "maintenance_work_mem"
  server_id = azurerm_postgresql_flexible_server.marquez.id
  value     = "524288" # 512MB in KB
}

resource "azurerm_postgresql_flexible_server_configuration" "effective_cache_size" {
  name      = "effective_cache_size"
  server_id = azurerm_postgresql_flexible_server.marquez.id
  value     = "1572864" # 12GB in 8KB pages
}

# Timeout configurations to prevent long-running connections
resource "azurerm_postgresql_flexible_server_configuration" "idle_in_transaction_session_timeout" {
  name      = "idle_in_transaction_session_timeout"
  server_id = azurerm_postgresql_flexible_server.marquez.id
  value     = "300000" # 5 minutes - kill idle transactions
}

resource "azurerm_postgresql_flexible_server_configuration" "statement_timeout" {
  name      = "statement_timeout"
  server_id = azurerm_postgresql_flexible_server.marquez.id
  value     = "3600000" # 1 hour - kill long-running queries
}

resource "azurerm_postgresql_flexible_server_configuration" "lock_timeout" {
  name      = "lock_timeout"
  server_id = azurerm_postgresql_flexible_server.marquez.id
  value     = "60000" # 1 minute - prevent lock waits
}

# Firewall Rule - Allow your IP
resource "azurerm_postgresql_flexible_server_firewall_rule" "allow_my_ip" {
  name             = "AllowMyIP"
  server_id        = azurerm_postgresql_flexible_server.marquez.id
  start_ip_address = var.my_ip_address
  end_ip_address   = var.my_ip_address
}

# Firewall Rule - Allow Azure Services (for AKS)
resource "azurerm_postgresql_flexible_server_firewall_rule" "allow_azure" {
  name             = "AllowAzureServices"
  server_id        = azurerm_postgresql_flexible_server.marquez.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "0.0.0.0"
}

# Database
resource "azurerm_postgresql_flexible_server_database" "marquez_test" {
  name      = var.database_name
  server_id = azurerm_postgresql_flexible_server.marquez.id
  collation = "en_US.utf8"
  charset   = "utf8"
}

# Azure Container Registry (for custom images)
resource "azurerm_container_registry" "marquez" {
  count               = var.deploy_aks && var.create_acr ? 1 : 0
  name                = "marquezacr${random_string.suffix.result}"
  resource_group_name = azurerm_resource_group.marquez_test.name
  location            = azurerm_resource_group.marquez_test.location
  sku                 = "Basic"
  admin_enabled       = true

  tags = {
    Environment = "Test"
    Purpose     = "Marquez Custom Images"
  }
}

# Grant AKS access to ACR
resource "azurerm_role_assignment" "aks_acr_pull" {
  count                = var.deploy_aks && var.create_acr ? 1 : 0
  principal_id         = azurerm_kubernetes_cluster.marquez[0].kubelet_identity[0].object_id
  role_definition_name = "AcrPull"
  scope                = azurerm_container_registry.marquez[0].id
}

# Network Watcher (automatically created by Azure, now managed by Terraform)
resource "azurerm_network_watcher" "marquez" {
  name                = "NetworkWatcher_${var.location}"
  location            = var.location
  resource_group_name = "NetworkWatcherRG"

  tags = {
    Environment = "Test"
    Purpose     = "Marquez Migration Testing"
    ManagedBy   = "Terraform"
  }
}

# Output kubeconfig to file for later use
resource "local_file" "kubeconfig" {
  count    = var.deploy_aks ? 1 : 0
  content  = azurerm_kubernetes_cluster.marquez[0].kube_config_raw
  filename = "${path.module}/kubeconfig"

  file_permission = "0600"
}
