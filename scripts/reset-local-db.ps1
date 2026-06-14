$ErrorActionPreference = "Stop"

Write-Host "Stopping and deleting local Docker volumes..."
docker compose down -v

Write-Host "Starting fresh Postgres and Redis..."
docker compose up -d postgres redis

Write-Host "Waiting for Postgres and Redis..."
Start-Sleep -Seconds 10

docker compose ps

Write-Host "Testing Docker Postgres through host port 5433..."
$env:PGPASSWORD = "banking_local_only"

if (Get-Command psql -ErrorAction SilentlyContinue) {
    psql -h 127.0.0.1 -p 5433 -U banking -d banking_risk -c "select current_user, current_database();"
} else {
    Write-Host "INFO: psql not found on PATH. To verify manually, install PostgreSQL client tools or run:"
    Write-Host "  docker exec banking-risk-postgres psql -U banking -d banking_risk -c 'select current_user, current_database();'"
}

Write-Host "Local DB reset complete."
