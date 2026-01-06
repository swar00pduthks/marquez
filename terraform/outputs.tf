# PostgreSQL Server
output "postgres_server_fqdn" {
  description = "Fully qualified domain name of the PostgreSQL server"
  value       = azurerm_postgresql_flexible_server.marquez.fqdn
}

output "postgres_admin_username" {
  description = "PostgreSQL administrator username"
  value       = var.db_admin_username
}

output "database_name" {
  description = "Name of the Marquez database"
  value       = azurerm_postgresql_flexible_server_database.marquez_test.name
}

output "postgres_jdbc_url" {
  description = "JDBC connection string"
  value       = "jdbc:postgresql://${azurerm_postgresql_flexible_server.marquez.fqdn}:5432/${azurerm_postgresql_flexible_server_database.marquez_test.name}"
}

output "postgres_connection_string" {
  description = "PostgreSQL connection string (contains password)"
  value       = "postgresql://${var.db_admin_username}:${var.db_admin_password}@${azurerm_postgresql_flexible_server.marquez.fqdn}:5432/${azurerm_postgresql_flexible_server_database.marquez_test.name}?sslmode=require"
  sensitive   = true
}

output "psql_command" {
  description = "Command to connect via psql"
  value       = "psql 'host=${azurerm_postgresql_flexible_server.marquez.fqdn} port=5432 dbname=${azurerm_postgresql_flexible_server_database.marquez_test.name} user=${var.db_admin_username} sslmode=require'"
}

# AKS Cluster
output "aks_cluster_name" {
  description = "Name of the AKS cluster"
  value       = var.deploy_aks ? azurerm_kubernetes_cluster.marquez[0].name : null
}

output "aks_cluster_fqdn" {
  description = "FQDN of the AKS cluster"
  value       = var.deploy_aks ? azurerm_kubernetes_cluster.marquez[0].fqdn : null
}

output "aks_kube_config_cmd" {
  description = "Command to get kubectl credentials"
  value       = var.deploy_aks ? "az aks get-credentials --resource-group ${azurerm_resource_group.marquez_test.name} --name ${azurerm_kubernetes_cluster.marquez[0].name}" : null
}

output "deploy_marquez_cmd" {
  description = "Command to deploy Marquez to AKS"
  value       = var.deploy_aks ? "powershell -File terraform/deploy-marquez.ps1" : null
}

# Azure Container Registry
output "acr_login_server" {
  description = "ACR login server URL"
  value       = var.deploy_aks && var.create_acr ? azurerm_container_registry.marquez[0].login_server : null
}

output "acr_admin_username" {
  description = "ACR admin username"
  value       = var.deploy_aks && var.create_acr ? azurerm_container_registry.marquez[0].admin_username : null
}

output "acr_admin_password" {
  description = "ACR admin password"
  value       = var.deploy_aks && var.create_acr ? azurerm_container_registry.marquez[0].admin_password : null
  sensitive   = true
}

output "docker_login_cmd" {
  description = "Command to login to ACR"
  value       = var.deploy_aks && var.create_acr ? "az acr login --name ${azurerm_container_registry.marquez[0].name}" : null
}

# Cost Estimates
output "estimated_monthly_cost_postgres" {
  description = "Estimated monthly cost for PostgreSQL Flexible Server"
  value = format("$%.2f/month",
    var.db_sku_name == "B_Standard_B2s" ? 37.0 :
    var.db_sku_name == "Standard_D2s_v3" ? 146.0 :
    var.db_sku_name == "Standard_D4s_v3" ? 292.0 :
    var.db_sku_name == "Standard_D8s_v3" ? 584.0 : 0.0
  )
}

output "estimated_monthly_cost_aks" {
  description = "Estimated monthly cost for AKS cluster"
  value = var.deploy_aks ? format("$%.2f/month (%d x %s nodes)",
    var.aks_vm_size == "Standard_D2s_v3" ? 96.0 * var.aks_node_count :
    var.aks_vm_size == "Standard_D4s_v3" ? 192.0 * var.aks_node_count : 0.0,
    var.aks_node_count,
    var.aks_vm_size
  ) : "Not deployed"
}

output "estimated_total_monthly_cost" {
  description = "Estimated total monthly cost"
  value = format("$%.2f/month (Destroy when not in use!)",
    (var.db_sku_name == "B_Standard_B2s" ? 37.0 :
     var.db_sku_name == "Standard_D2s_v3" ? 146.0 :
     var.db_sku_name == "Standard_D4s_v3" ? 292.0 :
     var.db_sku_name == "Standard_D8s_v3" ? 584.0 : 0.0) +
    (var.deploy_aks ? (var.aks_vm_size == "Standard_D2s_v3" ? 96.0 * var.aks_node_count :
                       var.aks_vm_size == "Standard_D4s_v3" ? 192.0 * var.aks_node_count : 0.0) : 0.0)
  )
}

output "resource_group_name" {
  description = "Name of the resource group (for cleanup)"
  value       = azurerm_resource_group.marquez_test.name
}
