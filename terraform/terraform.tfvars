subscription_id         = ""
location                = "westeurope"
deploy_aks              = true
create_acr              = true
aks_node_count          = 2
aks_vm_size             = "Standard_D2s_v3"
expose_via_loadbalancer = true
db_admin_username       = "marquezadmin"
# Note: Do NOT store real DB admin passwords in this file. Use TF_VAR_db_admin_password or -var on the CLI.
db_admin_password       = ""
db_sku_name             = "GP_Standard_D4s_v3"
my_ip_address           = "10.0.0.1"
my_ip_address           = "change_me"
marquez_version         = "0.52.33"
