$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "Starting Docker services..."
docker compose up -d postgres redis

Write-Host "Waiting for services..."
Start-Sleep -Seconds 8

Write-Host "Docker status:"
docker compose ps

Write-Host "Starting Spring Boot local profile..."
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
