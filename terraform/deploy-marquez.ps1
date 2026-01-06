# Deploy Marquez to AKS using Helm
# Run this AFTER terraform apply has created the AKS cluster

param(
    [string]$ResourceGroup = "rg-marquez-test",
    [string]$Namespace = "marquez",
    [string]$MarquezVersion = "0.52.33",
    [switch]$UseLoadBalancer = $true
)

$ErrorActionPreference = "Stop"

Write-Host "🚀 Deploying Marquez to AKS..." -ForegroundColor Cyan

# Step 0: Check and install prerequisites
Write-Host "`n🔧 Step 0: Checking prerequisites..." -ForegroundColor Yellow

# Check Azure CLI
try {
    $azVersion = az version --query '\"azure-cli\"' -o tsv 2>$null
    Write-Host "✅ Azure CLI: $azVersion" -ForegroundColor Green
} catch {
    Write-Host "❌ Azure CLI not found. Install with: choco install azure-cli" -ForegroundColor Red
    exit 1
}

# Check kubectl
try {
    $kubectlVersion = kubectl version --client -o json 2>$null | ConvertFrom-Json
    Write-Host "✅ kubectl: $($kubectlVersion.clientVersion.gitVersion)" -ForegroundColor Green
} catch {
    Write-Host "⚠️  kubectl not found. Installing..." -ForegroundColor Yellow
    choco install kubernetes-cli -y
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ Failed to install kubectl" -ForegroundColor Red
        exit 1
    }
    Write-Host "✅ kubectl installed" -ForegroundColor Green
}

# Check Helm
try {
    $helmVersion = helm version --short 2>$null
    Write-Host "✅ Helm: $helmVersion" -ForegroundColor Green
} catch {
    Write-Host "⚠️  Helm not found. Installing..." -ForegroundColor Yellow
    choco install kubernetes-helm -y
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ Failed to install Helm" -ForegroundColor Red
        exit 1
    }
    Write-Host "✅ Helm installed" -ForegroundColor Green
}

# Verify Azure login
Write-Host "`nVerifying Azure login..." -ForegroundColor Gray
$account = az account show 2>$null | ConvertFrom-Json
if (-not $account) {
    Write-Host "❌ Not logged into Azure. Run: az login" -ForegroundColor Red
    exit 1
}
Write-Host "✅ Logged in as: $($account.user.name)" -ForegroundColor Green
Write-Host "✅ Subscription: $($account.name)" -ForegroundColor Green

# Step 1: Get Terraform outputs
Write-Host "`n📋 Step 1: Reading Terraform outputs..." -ForegroundColor Yellow
if (-not (Test-Path "terraform-outputs.json")) {
    Write-Host "Exporting Terraform outputs..." -ForegroundColor Gray
    Push-Location terraform
    terraform output -json | Out-File -FilePath "../terraform-outputs.json" -Encoding utf8
    Pop-Location
}

$outputs = Get-Content "terraform-outputs.json" | ConvertFrom-Json

if (-not $outputs.aks_cluster_name.value) {
    Write-Host "❌ Error: AKS cluster not found in Terraform outputs." -ForegroundColor Red
    Write-Host "Make sure you ran 'terraform apply' with deploy_aks=true" -ForegroundColor Red
    exit 1
}

$clusterName = $outputs.aks_cluster_name.value
$dbHost = $outputs.postgres_server_fqdn.value
$dbName = $outputs.database_name.value
$dbUser = $outputs.postgres_admin_username.value

Write-Host "✅ Found AKS cluster: $clusterName" -ForegroundColor Green
Write-Host "✅ Found PostgreSQL: $dbHost" -ForegroundColor Green

# Step 2: Get AKS credentials
Write-Host "`n🔐 Step 2: Getting AKS credentials..." -ForegroundColor Yellow
az aks get-credentials --resource-group $ResourceGroup --name $clusterName --overwrite-existing
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Failed to get AKS credentials" -ForegroundColor Red
    exit 1
}
Write-Host "✅ Kubectl configured" -ForegroundColor Green

# Step 3: Create namespace
Write-Host "`n📦 Step 3: Creating namespace '$Namespace'..." -ForegroundColor Yellow
kubectl create namespace $Namespace --dry-run=client -o yaml | kubectl apply -f -
Write-Host "✅ Namespace ready" -ForegroundColor Green

# Step 4: Create database secret
Write-Host "`n🔑 Step 4: Creating database credentials secret..." -ForegroundColor Yellow

# Get password from terraform
Push-Location terraform
$dbPassword = (terraform output -raw db_admin_password 2>$null)
Pop-Location

if (-not $dbPassword) {
    Write-Host "⚠️  Warning: Could not get password from Terraform" -ForegroundColor Yellow
    $dbPassword = Read-Host "Enter PostgreSQL admin password" -AsSecureString
    $dbPassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($dbPassword)
    )
}

kubectl create secret generic marquez-db-credentials `
    --from-literal=POSTGRES_HOST=$dbHost `
    --from-literal=POSTGRES_PORT=5432 `
    --from-literal=POSTGRES_DB=$dbName `
    --from-literal=POSTGRES_USER=$dbUser `
    --from-literal=POSTGRES_PASSWORD=$dbPassword `
    --namespace=$Namespace `
    --dry-run=client -o yaml | kubectl apply -f -

Write-Host "✅ Secret created" -ForegroundColor Green

# Step 5: Create Helm values file
Write-Host "`n📝 Step 5: Generating Helm values..." -ForegroundColor Yellow

$helmValues = @"
# Marquez Helm Values - Generated for Azure PostgreSQL

db:
  host: "$dbHost"
  port: 5432
  name: "$dbName"
  user: "$dbUser"
  password: "$dbPassword"

marquez:
  image:
    registry: marquezproject
    repository: marquez
    tag: "$MarquezVersion"
    pullPolicy: IfNotPresent

  replicaCount: 2

  resources:
    requests:
      memory: "1Gi"
      cpu: "500m"
    limits:
      memory: "2Gi"
      cpu: "1000m"

  service:
    type: $(if ($UseLoadBalancer) { "LoadBalancer" } else { "ClusterIP" })
    port: 5000
    annotations: {}

web:
  enabled: true
  replicaCount: 1
  
  image:
    registry: marquezproject
    repository: marquez-web
    tag: "$MarquezVersion"
    pullPolicy: IfNotPresent

  resources:
    requests:
      memory: "256Mi"
      cpu: "100m"
    limits:
      memory: "512Mi"
      cpu: "500m"

  service:
    type: $(if ($UseLoadBalancer) { "LoadBalancer" } else { "ClusterIP" })
    port: 3000

# Horizontal Pod Autoscaler
autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70
  targetMemoryUtilizationPercentage: 80

# Pod Disruption Budget
podDisruptionBudget:
  enabled: true
  minAvailable: 1

# Anti-affinity for HA
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchExpressions:
              - key: app.kubernetes.io/name
                operator: In
                values:
                  - marquez
          topologyKey: kubernetes.io/hostname
"@

$helmValues | Out-File -FilePath "marquez-helm-values.yaml" -Encoding utf8
Write-Host "✅ Helm values saved to marquez-helm-values.yaml" -ForegroundColor Green

# Step 6: Deploy with Helm
Write-Host "`n🎯 Step 6: Deploying Marquez with Helm..." -ForegroundColor Yellow

# Check if Marquez chart exists in repo
if (Test-Path "chart/Chart.yaml") {
    $chartPath = "./chart"
    Write-Host "Using local Marquez chart: $chartPath" -ForegroundColor Gray
} else {
    Write-Host "❌ Error: Marquez Helm chart not found at ./chart/" -ForegroundColor Red
    Write-Host "Make sure you're running this from the marquez repository root" -ForegroundColor Red
    exit 1
}

# Install or upgrade
helm upgrade marquez $chartPath `
    --install `
    --namespace $Namespace `
    --values marquez-helm-values.yaml `
    --wait `
    --timeout 10m

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Helm deployment failed" -ForegroundColor Red
    exit 1
}

Write-Host "✅ Helm deployment successful" -ForegroundColor Green

# Step 7: Wait for pods
Write-Host "`n⏳ Step 7: Waiting for pods to be ready..." -ForegroundColor Yellow
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=marquez -n $Namespace --timeout=300s

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ All pods are ready" -ForegroundColor Green
} else {
    Write-Host "⚠️  Timeout waiting for pods. Check status with: kubectl get pods -n $Namespace" -ForegroundColor Yellow
}

# Step 8: Get access information
Write-Host "`n🌐 Step 8: Getting access information..." -ForegroundColor Yellow

$pods = kubectl get pods -n $Namespace -l app.kubernetes.io/name=marquez -o json | ConvertFrom-Json
Write-Host "Pods running: $($pods.items.Count)" -ForegroundColor Gray

if ($UseLoadBalancer) {
    Write-Host "`nWaiting for LoadBalancer IP assignment (this may take 2-3 minutes)..." -ForegroundColor Gray
    
    $timeout = 180
    $elapsed = 0
    $apiIP = $null
    
    while ($elapsed -lt $timeout) {
        Start-Sleep -Seconds 5
        $elapsed += 5
        
        $svc = kubectl get svc marquez -n $Namespace -o json 2>$null | ConvertFrom-Json
        if ($svc.status.loadBalancer.ingress) {
            $apiIP = $svc.status.loadBalancer.ingress[0].ip
            break
        }
        
        Write-Host "." -NoNewline -ForegroundColor Gray
    }
    
    Write-Host ""
    
    if ($apiIP) {
        Write-Host "`n✅ Marquez is accessible at:" -ForegroundColor Green
        Write-Host "   API:    http://$apiIP:5000" -ForegroundColor Cyan
        Write-Host "   Web UI: http://$apiIP:3000" -ForegroundColor Cyan
        Write-Host "`nTest API:"
        Write-Host "   curl http://$apiIP:5000/api/v1/namespaces" -ForegroundColor Gray
    } else {
        Write-Host "`n⚠️  LoadBalancer IP not assigned yet" -ForegroundColor Yellow
        Write-Host "Check status: kubectl get svc marquez -n $Namespace" -ForegroundColor Gray
    }
} else {
    Write-Host "`n✅ Marquez is running (ClusterIP mode)" -ForegroundColor Green
    Write-Host "`nTo access locally, use port-forward:" -ForegroundColor Yellow
    Write-Host "   kubectl port-forward -n $Namespace svc/marquez 8080:5000" -ForegroundColor Gray
    Write-Host "   kubectl port-forward -n $Namespace svc/marquez-web 3000:3000" -ForegroundColor Gray
    Write-Host "`nThen access:" -ForegroundColor Yellow
    Write-Host "   API:    http://localhost:8080" -ForegroundColor Cyan
    Write-Host "   Web UI: http://localhost:3000" -ForegroundColor Cyan
}

# Step 9: Show useful commands
Write-Host "`n📚 Useful Commands:" -ForegroundColor Yellow
Write-Host "   View pods:      kubectl get pods -n $Namespace" -ForegroundColor Gray
Write-Host "   View logs:      kubectl logs -n $Namespace -l app.kubernetes.io/name=marquez -f" -ForegroundColor Gray
Write-Host "   Describe pod:   kubectl describe pod -n $Namespace <pod-name>" -ForegroundColor Gray
Write-Host "   Scale replicas: kubectl scale deployment marquez -n $Namespace --replicas=5" -ForegroundColor Gray
Write-Host "   Delete all:     helm uninstall marquez -n $Namespace" -ForegroundColor Gray

Write-Host "`n✨ Deployment complete!" -ForegroundColor Green
