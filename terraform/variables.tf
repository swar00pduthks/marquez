# Azure Subscription Configuration
variable "subscription_id" {
  description = "Azure Subscription ID"
  type        = string
  default     = "<change_me>"
}

variable "location" {
  description = "Azure region for resources"
  type        = string
  default     = "eastus"
}

variable "resource_group_name" {
  description = "Name of the resource group"
  type        = string
  default     = "rg-marquez-test"
}

# AKS Configuration
variable "deploy_aks" {
  description = "Whether to deploy AKS cluster (set to false for PostgreSQL only)"
  type        = bool
  default     = true
}

variable "aks_node_count" {
  description = "Initial number of nodes in AKS cluster"
  type        = number
  default     = 2
}

variable "aks_vm_size" {
  description = "VM size for AKS nodes. Standard_D2s_v3 = 2vCPU 8GB RAM"
  type        = string
  default     = "Standard_D2s_v3"
  # Options:
  # - Standard_D2s_v3: 2vCPU, 8GB RAM (~$96/month)
  # - Standard_D4s_v3: 4vCPU, 16GB RAM (~$192/month)
}

variable "k8s_namespace" {
  description = "Kubernetes namespace for Marquez"
  type        = string
  default     = "marquez"
}

variable "expose_via_loadbalancer" {
  description = "Create public LoadBalancer for Marquez API"
  type        = bool
  default     = true
}

variable "create_acr" {
  description = "Create Azure Container Registry for custom images"
  type        = bool
  default     = true
}

# PostgreSQL Configuration
variable "db_admin_username" {
  description = "PostgreSQL admin username"
  type        = string
  default     = "marquezadmin"
}

variable "db_admin_password" {
  description = "PostgreSQL admin password"
  type        = string
  sensitive   = true
}

variable "database_name" {
  description = "Name of the PostgreSQL database"
  type        = string
  default     = "marquez_test"
}

variable "db_sku_name" {
  description = "PostgreSQL Flexible Server SKU"
  type        = string
  default     = "Standard_D4s_v3"
  # Options for different scales:
  # - B_Standard_B2s: 2vCPU, 4GB RAM (~$37/month) - Small tests (<100K runs)
  # - Standard_D2s_v3: 2vCPU, 8GB RAM (~$146/month) - Medium tests (100K-1M runs)
  # - Standard_D4s_v3: 4vCPU, 16GB RAM (~$292/month) - Large tests (1M-4M runs)
  # - Standard_D8s_v3: 8vCPU, 32GB RAM (~$584/month) - Very Large tests (4M-8M runs)
}

variable "storage_size_gb" {
  description = "Storage size in GB (32-512 for Standard tier)"
  type        = number
  default     = 256
}

# Network Configuration
variable "my_ip_address" {
  description = "Your IP address for PostgreSQL firewall rule. Get it from: curl https://api.ipify.org"
  type        = string
}

# Marquez Configuration
variable "marquez_version" {
  description = "Marquez Docker image tag"
  type        = string
  default     = "0.52.33"
}
