# Marquez on AKS with Azure PostgreSQL - Quick Setup

Complete infrastructure-as-code setup for deploying Marquez on Azure Kubernetes Service (AKS) with Azure PostgreSQL Flexible Server. Perfect for migration testing, development, and production-like environments you can spin up/down as needed.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  Azure Subscription                                 │
│                                                     │
│  ┌────────────────────────────────────────────┐   │
│  │  AKS Cluster (Standard_D2s_v3 x 2)         │   │
│  │                                            │   │
│  │  ┌──────────────────────────────────┐     │   │
│  │  │  marquez namespace               │     │   │
│  │  │                                  │     │   │
│  │  │  ┌────────┐  ┌────────┐         │     │   │
│  │  │  │ API    │  │ API    │         │     │   │
│  │  │  │ Pod 1  │  │ Pod 2  │         │     │   │
│  │  │  └────┬───┘  └────┬───┘         │     │   │
│  │  │       │           │              │     │   │
│  │  │       └─────┬─────┘              │     │   │
│  │  │             │                    │     │   │
│  │  │       ┌─────▼─────┐              │     │   │
│  │  │       │ Web UI    │              │     │   │
│  │  │       │ Pod       │              │     │   │
│  │  │       └───────────┘              │     │   │
│  │  └──────────────────────────────────┘     │   │
│  │             │                              │   │
│  │             │ JDBC Connection              │   │
│  └─────────────┼──────────────────────────────┘   │
│                │                                   │
│  ┌─────────────▼──────────────────────────────┐   │
│  │  PostgreSQL Flexible Server                │   │
│  │  (Standard_D4s_v3 - 4vCPU, 16GB, 256GB)   │   │
│  │                                            │   │
│  │  - Database: marquez_test                  │   │
│  │  - High Availability: Zone Redundant       │   │
│  │  - Backup: 7 days retention                │   │
│  └────────────────────────────────────────────┘   │
│                                                     │
└─────────────────────────────────────────────────────┘
```

## Features

- **Complete AKS Deployment**: 2-replica Marquez API with horizontal pod autoscaling
- **Azure PostgreSQL Flexible Server**: Optimized for 4M+ runs with performance tuning
- **LoadBalancer Access**: Public IP for easy testing (optional)
- **Helm-based**: Uses official Marquez Helm chart from the repo
- **Production-Ready**: Health checks, pod disruption budgets, anti-affinity
- **Cost-Effective**: ~$484/month (~$16/day) - destroy when not in use
- **One Command Setup**: `terraform apply` creates everything

## Quick Start

### Prerequisites

```powershell
# Install required tools
choco install terraform kubernetes-cli azure-cli

# Login to Azure
az login
az account set --subscription "YOUR_SUBSCRIPTION_ID"

# Get your public IP
curl https://api.ipify.org
```

### 1. Configure Variables

```powershell
cd terraform

# Copy example and edit
cp terraform-aks.tfvars.example terraform.tfvars

# Edit terraform.tfvars:
# - Set my_ip_address (from curl https://api.ipify.org)
# - Set db_admin_password (strong password)
# - Adjust aks_node_count, aks_vm_size, db_sku_name as needed
```

**terraform.tfvars example:**
```hcl
subscription_id = "YOUR_SUBSCRIPTION_ID"
location        = "eastus"

deploy_aks              = true
aks_node_count          = 2
aks_vm_size             = "Standard_D2s_v3"
expose_via_loadbalancer = true

db_admin_username = "marquezadmin"
db_admin_password = "YourStr0ngP@ssw0rd!"
db_sku_name       = "Standard_D4s_v3"
storage_size_gb   = 256

my_ip_address = "203.0.113.42"
marquez_version = "0.52.33"
```

### 2. Deploy Infrastructure

```powershell
# Use the AKS-specific Terraform files
terraform init
terraform plan -var-file="terraform.tfvars" -out=tfplan
terraform apply tfplan

# Wait 10-15 minutes for:
# - Resource group creation
# - PostgreSQL provisioning (~8 min)
# - AKS cluster creation (~10 min)
```

### 3. Deploy Marquez to AKS

After Terraform completes, deploy Marquez using the provided script:

```powershell
# Deploy Marquez to the AKS cluster
.\terraform\deploy-marquez.ps1

# This script will:
# 1. Get AKS credentials (kubectl config)
# 2. Create namespace
# 3. Create database secret
# 4. Deploy Marquez via Helm (from local chart/)
# 5. Wait for pods to be ready
# 6. Show LoadBalancer IP (if enabled)
```

**Why 2 phases?**
- Phase 1 (Terraform): Creates AKS cluster + PostgreSQL
- Phase 2 (Script): Deploys Marquez after cluster exists
- This avoids Terraform/Helm provider chicken-and-egg issues

### 4. Verify Deployment

```powershell
# Check pods are running
kubectl get pods -n marquez

# Expected output:
# NAME                         READY   STATUS    RESTARTS   AGE
# marquez-xxxx-yyyy            1/1     Running   0          2m
# marquez-xxxx-zzzz            1/1     Running   0          2m
# marquez-web-xxxx-yyyy        1/1     Running   0          2m

# Check logs
kubectl logs -n marquez -l app.kubernetes.io/name=marquez -f
```

### 5. Access Marquez

**Option A: Via LoadBalancer (Public IP)**

The deploy-marquez.ps1 script shows the LoadBalancer IP automatically. Or get it manually:

```powershell
# Get LoadBalancer IP
kubectl get svc marquez -n marquez

# Get URLs
$API_IP = (kubectl get svc marquez -n marquez -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
Write-Host "Marquez API: http://${API_IP}:5000"
Write-Host "Marquez Web: http://${API_IP}:3000"

# Test API
curl "http://${API_IP}:5000/api/v1/namespaces"

# Open Web UI in browser
Start-Process "http://${API_IP}:3000"
```

**Option B: Via Port Forwarding (Local Access)**
```powershell
# Forward API to localhost:8080
kubectl port-forward -n marquez svc/marquez 8080:5000

# In another terminal, forward Web to localhost:3000
kubectl port-forward -n marquez svc/marquez-web 3000:3000

# Access locally
curl http://localhost:8080/api/v1/namespaces
Start-Process http://localhost:3000
```

### 5. Run Database Migrations

```powershell
# Get a pod name
$POD = (kubectl get pods -n marquez -l app.kubernetes.io/name=marquez -o jsonpath='{.items[0].metadata.name}')

# Exec into pod and run migrations
kubectl exec -n marquez $POD -- java -jar marquez.jar db migrate /usr/src/app/marquez.yml

# For V80 backfill (if testing large migrations):
kubectl exec -n marquez $POD -- java -jar marquez.jar db migrate /usr/src/app/marquez.yml --target=80 --chunkSize=50000
```

### 6. Load Testing with k6

```powershell
# Install k6
choco install k6

# Get database connection info
terraform output -json > terraform-outputs.json

# Generate 4M runs using the existing script
# Update generate-data-with-k6.ps1 to use AKS API endpoint instead of localhost
$API_ENDPOINT = terraform output -raw marquez_api_url

# Or use port-forward and keep script as-is
kubectl port-forward -n marquez svc/marquez 8080:5000

# Run data generation
.\generate-data-with-k6.ps1 `
  -MarquezApiUrl "http://localhost:8080" `
  -TotalRuns 4000000 `
  -JobsCount 500 `
  -BatchSize 10000
```

### 7. Cleanup (IMPORTANT!)

```powershell
# Destroy all resources to avoid charges
terraform destroy -var-file="terraform.tfvars"

# Note: This will also delete any Kubernetes resources (Helm deployments)
# Confirm deletion (takes ~5 minutes)
# - Removes AKS cluster (and all pods/services)
# - Removes PostgreSQL server
# - Removes resource group
```

## Configuration Options

### PostgreSQL Only (No AKS)

If you already have Rancher/Kubernetes locally and only need Azure PostgreSQL:

```hcl
# terraform.tfvars
deploy_aks = false  # Only creates PostgreSQL

db_sku_name     = "Standard_D4s_v3"
storage_size_gb = 256
```

Then connect from your local Marquez:
```yaml
# marquez.yml
db:
  url: jdbc:postgresql://<from-terraform-output>.postgres.database.azure.com:5432/marquez_test
  user: marquezadmin
  password: YourPassword
  properties:
    ssl: true
    sslmode: require
```

### AKS Node Sizing

| VM Size | vCPU | RAM | Cost/node | Use Case |
|---------|------|-----|-----------|----------|
| Standard_D2s_v3 | 2 | 8GB | ~$96/mo | Light testing |
| Standard_D4s_v3 | 4 | 16GB | ~$192/mo | Medium load |
| Standard_D8s_v3 | 8 | 32GB | ~$384/mo | Heavy load |

### PostgreSQL Sizing

| SKU | vCPU | RAM | Cost/mo | Recommended For |
|-----|------|-----|---------|----------------|
| B_Standard_B2s | 2 | 4GB | ~$37 | <100K runs |
| Standard_D2s_v3 | 2 | 8GB | ~$146 | 100K-1M runs |
| Standard_D4s_v3 | 4 | 16GB | ~$292 | 1M-4M runs |
| Standard_D8s_v3 | 8 | 32GB | ~$584 | 4M-8M runs |

### Autoscaling Configuration

Default autoscaling settings in `marquez-values.yaml`:
- **Min Replicas**: 2 (high availability)
- **Max Replicas**: 10 (handles load spikes)
- **CPU Trigger**: 70% utilization
- **Memory Trigger**: 80% utilization

Adjust in Helm values:
```yaml
autoscaling:
  minReplicas: 2
  maxReplicas: 20  # Increase for higher load
  targetCPUUtilizationPercentage: 60  # Scale earlier
```

## Cost Estimates

### Testing Scenario (4-8 hours)

**Configuration:**
- AKS: 2 x Standard_D2s_v3 nodes
- PostgreSQL: Standard_D4s_v3, 256GB storage

**Costs:**
- AKS: 2 nodes × $96/month = $192/month → ~$6.40/day → **~$2.67 for 10 hours**
- PostgreSQL: $292/month → ~$9.67/day → **~$4.03 for 10 hours**
- **Total**: ~$6.70 for 10-hour test session

### Monthly Cost (if left running)

| Component | Configuration | Monthly Cost |
|-----------|---------------|--------------|
| AKS | 2 x Standard_D2s_v3 | $192 |
| PostgreSQL | Standard_D4s_v3 | $292 |
| Storage | 256GB | Included |
| **Total** | | **~$484/month** |

**💰 Cost Saving Tip**: Always run `terraform destroy` after testing!

## Deployment Scenarios

### Scenario 1: Quick Migration Test
**Goal**: Test V79-V85 migrations with 4M runs

```powershell
# 1. Deploy infrastructure (15 min)
terraform apply -var-file="terraform.tfvars"

# 2. Deploy Marquez (3 min)
.\terraform\deploy-marquez.ps1

# 3. Load test data (90 min)
# Get LoadBalancer IP or use port-forward
kubectl port-forward -n marquez svc/marquez 8080:5000
.\generate-data-with-k6.ps1 -MarquezApiUrl "http://localhost:8080" -TotalRuns 4000000

# 4. Run migrations (2-3 hours)
$POD = (kubectl get pods -n marquez -l app.kubernetes.io/name=marquez -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n marquez $POD -- java -jar marquez.jar db migrate /usr/src/app/marquez.yml --target=80 --chunkSize=50000

# 5. Verify
kubectl exec -n marquez $POD -- psql $DATABASE_URL -c "SELECT COUNT(*) FROM runs_denormalized;"

# 6. Cleanup (5 min)
terraform destroy -var-file="terraform.tfvars"

# Total time: ~7 hours, Cost: ~$5
```

### Scenario 2: Development Environment
**Goal**: Persistent dev environment for team

```hcl
# terraform.tfvars - Optimized for cost
aks_node_count = 1  # Single node for dev
aks_vm_size = "Standard_D2s_v3"
db_sku_name = "Standard_D2s_v3"
storage_size_gb = 128

# Cost: ~$242/month (~$8/day)
```

Keep running during work hours, destroy nights/weekends:
```powershell
# Monday morning
terraform apply

# Friday evening
terraform destroy
```

### Scenario 3: Load Testing
**Goal**: Stress test API with high concurrency

```hcl
# terraform.tfvars - High performance
aks_node_count = 5
aks_vm_size = "Standard_D4s_v3"
autoscaling_max_replicas = 20

# In marquez-values.yaml
autoscaling:
  maxReplicas: 20
  targetCPUUtilizationPercentage: 60
```

Run k6 load test:
```powershell
k6 run --vus 100 --duration 30m api/load-testing/http.js
```

## Troubleshooting

### Pods Not Starting

```powershell
# Check pod status
kubectl get pods -n marquez

# Describe pod for events
kubectl describe pod -n marquez <pod-name>

# Check logs
kubectl logs -n marquez <pod-name>

# Common issues:
# - Database connection: Check firewall rules allow AKS
# - Image pull: Verify marquezproject/marquez:0.52.33 exists
# - Resources: Check node has enough CPU/memory
```

### LoadBalancer IP Not Assigned

```powershell
# Check service
kubectl get svc -n marquez marquez-api-external

# If stuck in <pending>:
# - Azure may be provisioning (wait 2-3 min)
# - Check AKS network permissions
# - Verify subscription has public IP quota

# Alternative: Use port-forward
kubectl port-forward -n marquez svc/marquez 8080:5000
```

### Database Connection Issues

```powershell
# Test PostgreSQL connectivity from local machine
$DB_HOST = terraform output -raw postgres_server_fqdn
psql "host=$DB_HOST port=5432 dbname=marquez_test user=marquezadmin sslmode=require"

# Check firewall rules
az postgres flexible-server firewall-rule list `
  --resource-group rg-marquez-test `
  --name $(terraform output -raw postgres_server_fqdn | cut -d. -f1)

# Verify secret in Kubernetes
kubectl get secret -n marquez marquez-db-credentials -o yaml
```

### Migration Failures

```powershell
# Check migration status
kubectl exec -n marquez $(kubectl get pods -n marquez -l app=marquez -o jsonpath='{.items[0].metadata.name}') -- \
  psql $DATABASE_URL -c "SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"

# Rollback if needed
kubectl exec -n marquez $(kubectl get pods -n marquez -l app=marquez -o jsonpath='{.items[0].metadata.name}') -- \
  java -jar marquez.jar db rollback /usr/src/app/marquez.yml --count=1
```

### High Costs

```powershell
# Check current spend
az consumption usage list --subscription "YOUR_SUBSCRIPTION_ID" -o table

# Verify all resources destroyed
az resource list --resource-group rg-marquez-test

# If resources still exist
az group delete --name rg-marquez-test --yes --no-wait
```

## Monitoring

### Kubernetes Dashboard

```powershell
# Enable dashboard
kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/v2.7.0/aio/deploy/recommended.yaml

# Create admin user
kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: admin-user
  namespace: kubernetes-dashboard
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: admin-user
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-admin
subjects:
- kind: ServiceAccount
  name: admin-user
  namespace: kubernetes-dashboard
EOF

# Get token
kubectl -n kubernetes-dashboard create token admin-user

# Access dashboard
kubectl proxy
# Open: http://localhost:8001/api/v1/namespaces/kubernetes-dashboard/services/https:kubernetes-dashboard:/proxy/
```

### Application Insights (Optional)

Add to `marquez-values.yaml`:
```yaml
marquez:
  config:
    APPLICATIONINSIGHTS_CONNECTION_STRING: "InstrumentationKey=your-key"
```

### Prometheus/Grafana (Optional)

```powershell
# Install Prometheus
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install prometheus prometheus-community/kube-prometheus-stack -n monitoring --create-namespace

# Port-forward Grafana
kubectl port-forward -n monitoring svc/prometheus-grafana 3001:80

# Access: http://localhost:3001 (admin/prom-operator)
```

## Advanced: CI/CD Integration

### GitHub Actions Example

```yaml
name: Deploy to AKS

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Terraform
        uses: hashicorp/setup-terraform@v2
      
      - name: Azure Login
        uses: azure/login@v1
        with:
          creds: ${{ secrets.AZURE_CREDENTIALS }}
      
      - name: Terraform Apply
        working-directory: terraform
        env:
          TF_VAR_db_admin_password: ${{ secrets.DB_PASSWORD }}
          TF_VAR_my_ip_address: ${{ secrets.RUNNER_IP }}
        run: |
          terraform init
          terraform apply -auto-approve
      
      - name: Get Kubeconfig
        run: |
          az aks get-credentials \
            --resource-group rg-marquez-test \
            --name $(terraform output -raw aks_cluster_name)
      
      - name: Wait for Deployment
        run: |
          kubectl wait --for=condition=ready pod \
            -l app=marquez -n marquez --timeout=300s
```

## Comparison: AKS vs Local Rancher

| Aspect | AKS (This Setup) | Local Rancher |
|--------|------------------|---------------|
| **Cost** | ~$16/day (destroy when done) | $0 (local hardware) |
| **Setup Time** | 15 minutes (automated) | 30-60 min (manual) |
| **Scalability** | Auto-scale 2-20 pods | Limited by local resources |
| **Production-Like** | ✅ Azure services | ❌ Local only |
| **Team Access** | ✅ Public IP/LoadBalancer | ❌ VPN/port-forward needed |
| **Persistence** | ✅ Destroy/recreate anytime | ✅ Always available |
| **Migration Testing** | ✅ Realistic Azure PostgreSQL | ⚠️ Local PostgreSQL differences |

**Recommendation**: 
- Use **AKS** for migration testing and realistic performance validation
- Use **Local Rancher** for day-to-day development
- Hybrid: Local Rancher + Azure PostgreSQL (`deploy_aks = false`)

## Next Steps

1. **Deploy Infrastructure**: `terraform apply`
2. **Verify Deployment**: `kubectl get pods -n marquez`
3. **Access Marquez**: Use LoadBalancer IP or port-forward
4. **Run Migrations**: Execute V79-V85 migrations
5. **Load Test**: Generate 4M runs with k6
6. **Monitor**: Check logs, metrics, resource usage
7. **Cleanup**: `terraform destroy` when done

## Files Reference

- `main-aks.tf`: Complete infrastructure (AKS + PostgreSQL)
- `variables-aks.tf`: Configuration parameters
- `outputs-aks.tf`: Connection info and kubectl commands
- `marquez-values.yaml`: Helm chart configuration
- `terraform-aks.tfvars.example`: Example configuration

## Support

For issues with:
- **Terraform**: Check `terraform plan` output, validate variables
- **AKS**: Review Azure Portal → AKS cluster → Monitoring
- **PostgreSQL**: Check firewall rules, connection strings
- **Marquez**: Review pod logs with `kubectl logs`

Always remember to **destroy resources** when not in use to avoid unexpected charges!
