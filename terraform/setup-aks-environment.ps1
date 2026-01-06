# Complete setup script for Marquez on AKS
# Handles: Azure login, Terraform, Helm installation, and deployment

param(
    [Parameter(Mandatory = $true)]
    [string]$SubscriptionId,
    [switch]$SkipInfrastructure = $false,
    [switch]$DeployMarquezOnly = $false,
    [switch]$BuildLocalCode = $false,
    [switch]$SkipTests = $false
)

$ErrorActionPreference = "Stop"

Write-Host @"
╔═══════════════════════════════════════════════════════════╗
║  Marquez on AKS - Complete Setup Script                   ║
║  This script will:                                        ║
║    1. Verify/install prerequisites (az, terraform, helm)  ║
║    2. Login to Azure                                      ║
║    3. Create AKS + PostgreSQL + ACR infrastructure        ║
║    4. Build & deploy Marquez (local or official image)    ║
╚═══════════════════════════════════════════════════════════╝
"@ -ForegroundColor Cyan

# ============================================================================
# Step 1: Prerequisites
# ============================================================================
Write-Host "`n[1/4] Checking Prerequisites..." -ForegroundColor Yellow

$prerequisites = @(
    @{Name="Chocolatey"; Command="choco --version"; Install="Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072; iex ((New-Object System.Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))"}
    @{Name="Azure CLI"; Command="az --version"; Install="choco install azure-cli -y"}
    @{Name="Terraform"; Command="terraform version"; Install="choco install terraform -y"}
    @{Name="kubectl"; Command="kubectl version --client"; Install="choco install kubernetes-cli -y"}
    @{Name="Helm"; Command="helm version"; Install="choco install kubernetes-helm -y"}
)

foreach ($prereq in $prerequisites) {
    Write-Host "  Checking $($prereq.Name)..." -NoNewline
    try {
        $null = Invoke-Expression $prereq.Command 2>$null
        Write-Host " ✅" -ForegroundColor Green
    } catch {
        Write-Host " ❌ Not found" -ForegroundColor Yellow
        Write-Host "    Installing $($prereq.Name)..." -ForegroundColor Gray
        Invoke-Expression $prereq.Install
        if ($LASTEXITCODE -ne 0) {
            Write-Host "    ❌ Failed to install $($prereq.Name)" -ForegroundColor Red
            exit 1
        }
        Write-Host "    ✅ $($prereq.Name) installed" -ForegroundColor Green
    }
}

# ============================================================================
# Step 2: Azure Login
# ============================================================================
Write-Host "`n[2/4] Azure Authentication" -ForegroundColor Yellow

$account = az account show 2>$null | ConvertFrom-Json
if (-not $account) {
    Write-Host "  Not logged in. Opening Azure login..." -ForegroundColor Gray
    az login
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ❌ Azure login failed" -ForegroundColor Red
        exit 1
    }
    $account = az account show | ConvertFrom-Json
}

Write-Host "  ✅ Logged in as: $($account.user.name)" -ForegroundColor Green
Write-Host "  ✅ Current subscription: $($account.name)" -ForegroundColor Green

if ($account.id -ne $SubscriptionId) {
    Write-Host "  Switching to subscription: $SubscriptionId" -ForegroundColor Gray
    az account set --subscription $SubscriptionId
    Write-Host "  ✅ Subscription set" -ForegroundColor Green
}

# ============================================================================
# Step 3: Infrastructure (Terraform)
# ============================================================================
if (-not $DeployMarquezOnly) {
    Write-Host "`n[3/4] Infrastructure Provisioning (Terraform)" -ForegroundColor Yellow

    # Check if terraform.tfvars exists
    if (-not (Test-Path "terraform.tfvars")) {
        Write-Host "  ⚠️  terraform.tfvars not found" -ForegroundColor Yellow
        
        # Get user's public IP
        Write-Host "  Getting your public IP..." -ForegroundColor Gray
        $myIp = (Invoke-WebRequest -Uri "https://api.ipify.org" -UseBasicParsing).Content
        Write-Host "  Your IP: $myIp" -ForegroundColor Gray
        
        # Get password
        Write-Host "  Enter PostgreSQL admin password:" -ForegroundColor Gray
        $securePassword = Read-Host "  Password" -AsSecureString
        $password = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
            [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        )
        
        # Create terraform.tfvars
        $tfvars = @"
subscription_id = "$SubscriptionId"
location        = "eastus"

deploy_aks              = true
aks_node_count          = 2
aks_vm_size             = "Standard_D2s_v3"
expose_via_loadbalancer = true

db_admin_username = "marquezadmin"
db_admin_password = "$password"
db_sku_name       = "Standard_D4s_v3"
storage_size_gb   = 256

my_ip_address = "$myIp"
marquez_version = "0.52.33"
"@
        $tfvars | Out-File -FilePath "terraform.tfvars" -Encoding utf8
        Write-Host "  ✅ Created terraform.tfvars" -ForegroundColor Green
    }

    # Terraform init
    if (-not (Test-Path ".terraform")) {
        Write-Host "  Running terraform init..." -ForegroundColor Gray
        terraform init
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  ❌ Terraform init failed" -ForegroundColor Red
            exit 1
        }
    }

    # Terraform plan
    Write-Host "  Running terraform plan..." -ForegroundColor Gray
    terraform plan -var-file="terraform.tfvars" -out=tfplan
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ❌ Terraform plan failed" -ForegroundColor Red
        exit 1
    }

    # Terraform apply
    Write-Host "`n  Ready to create infrastructure:" -ForegroundColor Cyan
    Write-Host "    - AKS cluster (2 nodes, ~10 min)" -ForegroundColor Gray
    Write-Host "    - PostgreSQL Flexible Server (~8 min)" -ForegroundColor Gray
    Write-Host "    - Estimated cost: ~$16/day" -ForegroundColor Gray
    Write-Host ""
    $confirm = Read-Host "  Proceed with terraform apply? (yes/no)"
    
    if ($confirm -ne "yes") {
        Write-Host "  Aborted by user" -ForegroundColor Yellow
        exit 0
    }

    Write-Host "  Running terraform apply (this will take 10-15 minutes)..." -ForegroundColor Gray
    terraform apply tfplan
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ❌ Terraform apply failed" -ForegroundColor Red
        exit 1
    }

    Write-Host "  ✅ Infrastructure created" -ForegroundColor Green

    # Export outputs
    terraform output -json | Out-File -FilePath "../terraform-outputs.json" -Encoding utf8
    Write-Host "  ✅ Outputs saved to terraform-outputs.json" -ForegroundColor Green
} else {
    Write-Host "`n[3/4] Infrastructure Provisioning - SKIPPED" -ForegroundColor Gray
}

# ============================================================================
# Step 4: Deploy Marquez
# ============================================================================
Write-Host "`n[4/4] Deploying Marquez to AKS" -ForegroundColor Yellow

if ($BuildLocalCode) {
    Write-Host "  Using LOCAL build with Azure Container Registry" -ForegroundColor Cyan
    
    # Run the build and deploy script
    & "$PSScriptRoot\build-and-deploy.ps1" -Version "0.52.33-local" -SkipTests:$SkipTests
    
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ❌ Build and deployment failed" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "  Using OFFICIAL images from Docker Hub" -ForegroundColor Cyan
    
    # Run the deployment script with official images
    & "$PSScriptRoot\deploy-marquez.ps1" -UseLoadBalancer
    
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ❌ Marquez deployment failed" -ForegroundColor Red
        exit 1
    }
}

# ============================================================================
# Summary
# ============================================================================
Write-Host "`n╔═══════════════════════════════════════════════════════════╗" -ForegroundColor Green
Write-Host "║  ✅ Setup Complete!                                       ║" -ForegroundColor Green
Write-Host "╚═══════════════════════════════════════════════════════════╝" -ForegroundColor Green

Write-Host "`nNext steps:" -ForegroundColor Cyan
if ($BuildLocalCode) {
    Write-Host "  ✅ Your local 0.52.33 code is running on AKS!" -ForegroundColor Green
    Write-Host "  1. Access Marquez (see URLs above)" -ForegroundColor Gray
    Write-Host "  2. To rebuild & redeploy: .\terraform\build-and-deploy.ps1" -ForegroundColor Gray
} else {
    Write-Host "  1. Access Marquez (see LoadBalancer IP above)" -ForegroundColor Gray
    Write-Host "  2. To deploy local code: .\terraform\build-and-deploy.ps1" -ForegroundColor Gray
}
Write-Host "  3. Run migrations: kubectl exec -n marquez PODNAME -- java -jar marquez.jar db migrate /usr/src/app/marquez.yml" -ForegroundColor Gray
Write-Host "  4. Load test data: .\generate-data-with-k6.ps1" -ForegroundColor Gray
Write-Host "  5. When done: cd terraform; terraform destroy" -ForegroundColor Gray

Write-Host "`n💰 Remember: Run 'terraform destroy' to avoid charges!" -ForegroundColor Yellow
