# Build and Deploy Local Marquez to AKS
# This script builds your local code into Docker images and deploys to AKS

param(
    [string]$Version = "0.52.33-local",
    [string]$Namespace = "marquez",
    [switch]$SkipBuild = $false,
    [switch]$SkipTests = $false,
    [switch]$DeployOnly = $false  # Skip build, docker, push - go straight to Helm deploy
)

$ErrorActionPreference = "Stop"

# Add Azure CLI to PATH
$azCliPath = "C:\Program Files\Microsoft SDKs\Azure\CLI2\wbin"
if (Test-Path $azCliPath) {
    $env:Path += ";$azCliPath"
}

Write-Host @"
╔═══════════════════════════════════════════════════════════╗
║  Build & Deploy Local Marquez to AKS                      ║
╚═══════════════════════════════════════════════════════════╝
"@ -ForegroundColor Cyan

# ============================================================================
# Step 1: Get Terraform outputs
# ============================================================================
Write-Host "`n[1/6] Reading infrastructure details..." -ForegroundColor Yellow

$outputFile = "terraform-outputs.json"
if (-not (Test-Path $outputFile)) {
    Write-Host "  Exporting Terraform outputs..." -ForegroundColor Gray
    terraform output -json | Out-File -FilePath $outputFile -Encoding utf8
}

$outputs = Get-Content $outputFile | ConvertFrom-Json

if (-not $outputs.acr_login_server.value) {
    Write-Host "  Error: ACR not found. Make sure create_acr=true in terraform.tfvars" -ForegroundColor Red
    exit 1
}

$acrServer = $outputs.acr_login_server.value
$acrName = $acrServer -replace '\.azurecr\.io$', ''
$dbHost = $outputs.postgres_server_fqdn.value
$dbName = $outputs.database_name.value
$dbUser = $outputs.postgres_admin_username.value

Write-Host "  ✅ ACR: $acrServer" -ForegroundColor Green
Write-Host "  ✅ PostgreSQL: $dbHost" -ForegroundColor Green

# ============================================================================
# Step 2: Build Marquez locally
# ============================================================================
if (-not $SkipBuild -and -not $DeployOnly) {
    Write-Host "`n[2/6] Building Marquez locally..." -ForegroundColor Yellow
    
    # Go to repo root
    Push-Location ..
    
    if (-not $SkipTests) {
        Write-Host "  Running Gradle build with tests..." -ForegroundColor Gray
        .\gradlew.bat clean build
    } else {
        Write-Host "  Running Gradle build (skipping tests)..." -ForegroundColor Gray
        .\gradlew.bat clean build -x test
    }
    
    if ($LASTEXITCODE -ne 0) {
        Pop-Location
        Write-Host "  ❌ Build failed" -ForegroundColor Red
        exit 1
    }
    
    Pop-Location
    Write-Host "  ✅ Build successful" -ForegroundColor Green
} else {
    Write-Host "`n[2/6] Building - SKIPPED" -ForegroundColor Gray
}

# ============================================================================
# Step 3: Build Docker images
# ============================================================================
if (-not $DeployOnly) {
    Write-Host "`n[3/6] Building Docker images..." -ForegroundColor Yellow

    Push-Location ..

    # Build API image
    Write-Host "  Building marquez-api:$Version..." -ForegroundColor Gray
    docker build -t marquez-api:$Version -f Dockerfile .
    if ($LASTEXITCODE -ne 0) {
        Pop-Location
        Write-Host "  ❌ Docker build failed for API" -ForegroundColor Red
        exit 1
    }
    Write-Host "  ✅ marquez-api:$Version built" -ForegroundColor Green

    # Build Web image
    Write-Host "  Building marquez-web:$Version..." -ForegroundColor Gray
    docker build -t marquez-web:$Version -f web/Dockerfile web/
    if ($LASTEXITCODE -ne 0) {
        Pop-Location
        Write-Host "  ❌ Docker build failed for Web" -ForegroundColor Red
        exit 1
    }
    Write-Host "  ✅ marquez-web:$Version built" -ForegroundColor Green

    Pop-Location
} else {
    Write-Host "`n[3/6] Building Docker images - SKIPPED" -ForegroundColor Gray
}

# ============================================================================
# Step 4: Push images to ACR
# ============================================================================
if (-not $DeployOnly) {
    Write-Host "`n[4/6] Pushing images to Azure Container Registry..." -ForegroundColor Yellow

    # Login to ACR
    Write-Host "  Logging into ACR..." -ForegroundColor Gray
    az acr login --name $acrName
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ❌ ACR login failed" -ForegroundColor Red
        exit 1
    }

    # Tag and push API
    Write-Host "  Pushing marquez-api..." -ForegroundColor Gray
    docker tag marquez-api:$Version $acrServer/marquez-api:$Version
    docker push $acrServer/marquez-api:$Version
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ❌ Failed to push API image" -ForegroundColor Red
        exit 1
    }
    Write-Host "  ✅ marquez-api pushed to ACR" -ForegroundColor Green

    # Tag and push Web
    Write-Host "  Pushing marquez-web..." -ForegroundColor Gray
    docker tag marquez-web:$Version $acrServer/marquez-web:$Version
    docker push $acrServer/marquez-web:$Version
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ❌ Failed to push Web image" -ForegroundColor Red
        exit 1
    }
    Write-Host "  ✅ marquez-web pushed to ACR" -ForegroundColor Green
} else {
    Write-Host "`n[4/6] Pushing images to ACR - SKIPPED" -ForegroundColor Gray
}

# ============================================================================
# Step 5: Create Helm values with ACR images
# ============================================================================
Write-Host "`n[5/6] Generating Helm values..." -ForegroundColor Yellow

# Get DB password from outputs (it's already in the $outputs variable from Step 1)
$dbPassword = $outputs.db_admin_password.value
if ([string]::IsNullOrEmpty($dbPassword)) {
    Write-Host "  ÔÜá´©Å  Warning: DB password not found in outputs, prompting for password" -ForegroundColor Yellow
    $dbPassword = Read-Host "Enter PostgreSQL admin password" -AsSecureString | ConvertFrom-SecureString -AsPlainText
}
Write-Host "  DB Password length: $($dbPassword.Length) characters" -ForegroundColor Gray

$helmValues = @"
# Custom Helm values for Marquez deployment with Azure PostgreSQL and ACR images
# Generated by build-and-deploy.ps1

# Root-level service configuration (applies to all services)
service:
  type: LoadBalancer
  port: 80

# Marquez API configuration
marquez:
  # Use custom ACR images
  image:
    registry: $acrServer
    repository: marquez-api
    tag: "$Version"
    pullPolicy: Always
  
  # Increase replicas for HA
  replicaCount: 2
  
  # External Azure PostgreSQL configuration
  db:
    host: $dbHost
    port: 5432
    name: $dbName
    user: $dbUser
    password: $dbPassword
    
  # HikariCP Connection Pool Configuration
  # These settings help prevent long-running connections and connection leaks
  dbConnectionPool:
    maximumPoolSize: 20
    minimumIdle: 5
    connectionTimeout: 30000      # 30 seconds
    idleTimeout: 300000           # 5 minutes (reduced from default 10 min)
    maxLifetime: 600000           # 10 minutes (reduced from default 30 min)
    leakDetectionThreshold: 60000 # 1 minute - detect connection leaks
    validationTimeout: 5000       # 5 seconds
  
  # Resource limits
  resources:
    requests:
      memory: "1Gi"
      cpu: "500m"
    limits:
      memory: "2Gi"
      cpu: "1000m"

# Web UI configuration
web:
  enabled: true
  replicaCount: 1
  
  # Use custom ACR images
  image:
    registry: $acrServer
    repository: marquez-web
    tag: "$Version"
    pullPolicy: Always
  
  # Resource limits
  resources:
    requests:
      memory: "256Mi"
      cpu: "250m"
    limits:
      memory: "512Mi"
      cpu: "500m"

# Disable bundled PostgreSQL chart (we're using Azure PostgreSQL)
postgresql:
  enabled: false
"@

$helmValues | Out-File -FilePath "marquez-local-build.yaml" -Encoding utf8
Write-Host "  ✅ Helm values saved to marquez-local-build.yaml" -ForegroundColor Green

# ============================================================================
# Step 6: Deploy to AKS
# ============================================================================
Write-Host "`n[6/6] Deploying to AKS..." -ForegroundColor Yellow

# Get kubectl credentials
$resourceGroup = "rg-marquez-test"
$clusterName = $outputs.aks_cluster_name.value

Write-Host "  Getting AKS credentials..." -ForegroundColor Gray
az aks get-credentials --resource-group $resourceGroup --name $clusterName --overwrite-existing

# Create namespace if not exists
kubectl create namespace $Namespace --dry-run=client -o yaml | kubectl apply -f -

# Create database secret
kubectl create secret generic marquez-db-credentials `
    --from-literal=POSTGRES_HOST=$dbHost `
    --from-literal=POSTGRES_PORT=5432 `
    --from-literal=POSTGRES_DB=$dbName `
    --from-literal=POSTGRES_USER=$dbUser `
    --from-literal=POSTGRES_PASSWORD=$dbPassword `
    --namespace=$Namespace `
    --dry-run=client -o yaml | kubectl apply -f -

Write-Host "  Deploying with Helm..." -ForegroundColor Gray

# Check if chart exists (go up one level from terraform directory)
if (-not (Test-Path "../chart/Chart.yaml")) {
    Write-Host "  ❌ Marquez chart not found at ../chart/" -ForegroundColor Red
    exit 1
}

# Build chart dependencies (downloads common and postgresql sub-charts)
Write-Host "  Building chart dependencies..." -ForegroundColor Gray
Push-Location ../chart
helm dependency build
if ($LASTEXITCODE -ne 0) {
    Pop-Location
    Write-Host "  ❌ Failed to build chart dependencies" -ForegroundColor Red
    exit 1
}
Pop-Location

# Deploy with Helm using custom values file
Write-Host "  Installing/Upgrading Marquez..." -ForegroundColor Gray
helm upgrade marquez ../chart `
    --install `
    --namespace $Namespace `
    --values marquez-local-build.yaml `
    --wait `
    --timeout 10m

if ($LASTEXITCODE -ne 0) {
    Write-Host "  ❌ Helm deployment failed" -ForegroundColor Red
    Write-Host "  Check logs: kubectl logs -n $Namespace -l app.kubernetes.io/name=marquez" -ForegroundColor Yellow
    exit 1
}

Write-Host "  ✅ Deployed to AKS" -ForegroundColor Green

# Wait for pods
Write-Host "  Waiting for pods to be ready..." -ForegroundColor Gray
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=marquez -n $Namespace --timeout=300s

# Get LoadBalancer IPs
Start-Sleep -Seconds 10
$apiService = kubectl get svc marquez -n $Namespace -o json 2>$null | ConvertFrom-Json
$webService = kubectl get svc marquez-web -n $Namespace -o json 2>$null | ConvertFrom-Json

Write-Host "`n╔═══════════════════════════════════════════════════════════╗" -ForegroundColor Green
Write-Host "║  ✅ Deployment Complete!                                  ║" -ForegroundColor Green
Write-Host "╚═══════════════════════════════════════════════════════════╝" -ForegroundColor Green

Write-Host "`n📦 Deployed Images:" -ForegroundColor Cyan
Write-Host "  API: $acrServer/marquez-api:$Version" -ForegroundColor Gray
Write-Host "  Web: $acrServer/marquez-web:$Version" -ForegroundColor Gray

if ($apiService.status.loadBalancer.ingress) {
    $apiIp = $apiService.status.loadBalancer.ingress[0].ip
    Write-Host "`n🌐 Access URLs:" -ForegroundColor Cyan
    Write-Host "  API:    http://$apiIp:5000" -ForegroundColor Green
    Write-Host "  Web UI: http://$apiIp:3000" -ForegroundColor Green
    
    Write-Host "`n🧪 Test API:" -ForegroundColor Cyan
    Write-Host "  curl http://$apiIp:5000/api/v1/namespaces" -ForegroundColor Gray
} else {
    Write-Host "`n⏳ LoadBalancer IP pending (takes 2-3 min)..." -ForegroundColor Yellow
    Write-Host "  Check status: kubectl get svc -n $Namespace" -ForegroundColor Gray
}

Write-Host "`nUseful Commands:" -ForegroundColor Cyan
Write-Host "  View pods:     kubectl get pods -n $Namespace" -ForegroundColor Gray
Write-Host "  View logs:     kubectl logs -n $Namespace -l app.kubernetes.io/name=marquez -f" -ForegroundColor Gray
Write-Host "  Rebuild and redeploy: .\terraform\build-and-deploy.ps1" -ForegroundColor Gray

Write-Host "`nYour local code is now running on AKS!" -ForegroundColor Green
