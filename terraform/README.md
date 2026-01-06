# Marquez Migration Test with Terraform

Complete infrastructure-as-code setup for testing Marquez 0.52.26 → 0.52.33 migration with 4 million runs.

## Quick Start

### 1. Setup Terraform

```powershell
# Get your public IP
$myIp = (Invoke-WebRequest -Uri "https://api.ipify.org" -UseBasicParsing).Content
Write-Host "Your IP: $myIp"

# Create tfvars file
cd terraform
Copy-Item terraform.tfvars.example terraform.tfvars

# Edit terraform.tfvars and set:
# my_ip_address = "YOUR_IP_FROM_ABOVE"

# Initialize Terraform
terraform init

# Preview changes
terraform plan

# Create infrastructure (~15 minutes)
terraform apply

# Save outputs
terraform output -json > ../terraform-outputs.json
```

### 2. Generate 4M Sample Runs

**Option A: Using k6 Load Testing (Recommended - More Realistic)**
```powershell
# Go back to root
cd ..

# Get connection details from Terraform output
$outputs = Get-Content terraform-outputs.json | ConvertFrom-Json
$serverFqdn = $outputs.server_fqdn.value
$adminUser = $outputs.admin_username.value
$adminPass = "MarquezTest2024!Secure"  # From terraform.tfvars

# Install k6 if not already installed
# Windows: choco install k6
# Or download from https://k6.io/docs/getting-started/installation/

# Generate 4 million runs using existing k6 infrastructure (~60-90 minutes)
# This creates realistic OpenLineage events with jobs, datasets, and lineage
.\generate-data-with-k6.ps1 `
    -ServerFqdn $serverFqdn `
    -AdminUser $adminUser `
    -AdminPassword $adminPass `
    -TotalRuns 4000000 `
    -JobsCount 500 `
    -BatchSize 10000
```

**Option B: Direct SQL Generation (Faster but Less Realistic)**
```powershell
# Generate 4 million runs via SQL (~30-40 minutes)
.\generate-sample-data.ps1 `
    -ServerFqdn $serverFqdn `
    -AdminUser $adminUser `
    -AdminPassword $adminPass `
    -TotalRuns 4000000 `
    -ChunkSize 10000
```

**Comparison:**
- **k6 method**: Realistic OpenLineage events, includes datasets/lineage, slower (~90 min)
- **SQL method**: Fast run generation, minimal related data, faster (~40 min)

### 3. Run Migration Test

```powershell
# Create marquez config for Azure
$jdbcUrl = $outputs.jdbc_url.value

@"
server:
  applicationConnectors:
    - type: http
      port: 5001
  adminConnectors:
    - type: http
      port: 5002

database:
  driverClass: org.postgresql.Driver
  url: $jdbcUrl
  user: $adminUser
  password: $adminPass
  properties:
    maximumPoolSize: 20
    minimumIdle: 5

logging:
  level: INFO
"@ | Out-File marquez.azure.yml -Encoding UTF8

# Build latest version
.\gradlew.bat build -x test

# Get JAR path
$jar = (Get-ChildItem "api\build\libs\marquez-api-*.jar" | Select-Object -First 1).FullName

# Run V79-V85 migrations
java -jar $jar db migrate marquez.azure.yml

# Run V80 backfill (this is the critical test!)
# For 4M runs with chunk size 50K: ~2-3 hours
$startTime = Get-Date
java -jar $jar db migrate marquez.azure.yml --target=80 --chunkSize=50000
$endTime = Get-Date

Write-Host "V80 backfill completed in $($($endTime - $startTime).TotalMinutes) minutes" -ForegroundColor Green
```

### 4. Verify Results

```powershell
$env:PGPASSWORD = $adminPass
psql -h $serverFqdn -U $adminUser -d marquez_test -c "
SELECT 
    'run_lineage_denormalized' as table_name,
    COUNT(*) as row_count,
    pg_size_pretty(pg_total_relation_size('run_lineage_denormalized')) as size
FROM run_lineage_denormalized
UNION ALL
SELECT 
    'run_parent_lineage_denormalized' as table_name,
    COUNT(*) as row_count,
    pg_size_pretty(pg_total_relation_size('run_parent_lineage_denormalized')) as size
FROM run_parent_lineage_denormalized;
"
```

### 5. Cleanup

```powershell
# Destroy all Azure resources
cd terraform
terraform destroy

# This will delete:
# - PostgreSQL server
# - Database
# - Resource group
# Total cleanup: ~5 minutes
```

## Cost Estimates

### Infrastructure (Standard_D4s_v3 + 256GB storage)
- **Compute**: ~$6.70/day ($200/month)
- **Storage**: ~$29/month ($0.97/day)
- **Total**: ~$7.67/day

### Test Duration
- Setup: 15 min
- Data generation: 30-40 min
- Migration: 10 min
- V80 backfill: 2-3 hours
- **Total**: ~3-4 hours

### Total Cost for Full Test
- **4 hours**: ~$1.28
- **24 hours** (if left running): ~$7.67

## Sizing Guide

| Runs | Recommended SKU | Storage | Estimated V80 Time | Cost/Day |
|------|----------------|---------|-------------------|----------|
| 100K | Standard_B2s | 64GB | 5-10 min | $1.50 |
| 500K | Standard_D2s_v3 | 128GB | 20-30 min | $4.00 |
| 1M | Standard_D2s_v3 | 128GB | 40-60 min | $4.00 |
| 4M | Standard_D4s_v3 | 256GB | 2-3 hours | $7.67 |
| 8M | Standard_D8s_v3 | 512GB | 4-6 hours | $14.00 |

## Files Created

- `terraform/main.tf` - Infrastructure definition
- `terraform/variables.tf` - Configuration variables
- `terraform/outputs.tf` - Connection details
- `terraform/terraform.tfvars` - Your settings (not committed)
- `generate-sample-data.ps1` - Optimized data generation
- `marquez.azure.yml` - Marquez config for Azure (generated)
- `terraform-outputs.json` - Terraform outputs (generated)

## Troubleshooting

### Connection Issues
```powershell
# Check firewall rules
az postgres flexible-server firewall-rule list `
    --resource-group rg-marquez-migration-test `
    --name <server-name>

# Add your IP if changed
az postgres flexible-server firewall-rule create `
    --resource-group rg-marquez-migration-test `
    --name <server-name> `
    --rule-name AllowNewIP `
    --start-ip-address <new-ip> `
    --end-ip-address <new-ip>
```

### Slow Data Generation
```powershell
# Check database metrics in Azure Portal
# If CPU > 80%, reduce chunk size:
.\generate-sample-data.ps1 ... -ChunkSize 5000

# If CPU < 50%, increase chunk size:
.\generate-sample-data.ps1 ... -ChunkSize 20000
```

### V80 Backfill Too Slow
```powershell
# Monitor progress
tail -f marquez.log | Select-String "V80"

# If stuck, check database connections
psql ... -c "SELECT count(*) FROM pg_stat_activity;"

# Adjust chunk size and retry
java -jar $jar db migrate marquez.azure.yml --target=80 --chunkSize=25000
```

## Best Practices

1. **Always run terraform plan** before apply
2. **Save terraform outputs** immediately after apply
3. **Test with smaller dataset first** (100K runs)
4. **Monitor Azure costs** in portal
5. **Delete resources promptly** after testing
6. **Use tags** for cost tracking (already configured)

## Next Steps

After successful 4M run test:
1. Document migration time for your SLA
2. Adjust chunk size for optimal performance
3. Test with production-like query patterns
4. Plan maintenance window based on results
5. Prepare rollback procedures
