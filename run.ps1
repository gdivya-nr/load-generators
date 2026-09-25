# Run script for APP1 OLTP Load Generator with New Relic APM-to-QPM linking
# Usage:
#   .\run.ps1              - Run with New Relic agent (APM + QPM linking)
#   .\run.ps1 -NoAgent     - Run without New Relic agent
#   .\run.ps1 -Build       - Build first, then run

param(
    [switch]$NoAgent,
    [switch]$Build
)

$ErrorActionPreference = "Stop"

# Load .env file if present
if (Test-Path ".env") {
    Write-Host "Loading configuration from .env file..." -ForegroundColor Green
    Get-Content ".env" | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
            $name = $matches[1].Trim()
            $value = $matches[2].Trim()
            [System.Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
} else {
    Write-Host "WARNING: .env file not found. Copy .env.example to .env and configure it." -ForegroundColor Yellow
    Write-Host "APM-to-QPM linking requires NEW_RELIC_LICENSE_KEY to be set." -ForegroundColor Yellow
}

# Build if requested
if ($Build) {
    Write-Host "Building application..." -ForegroundColor Cyan
    mvn clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Build failed!" -ForegroundColor Red
        exit 1
    }
}

# Check JAR exists
$JarFile = "target\app1-oltp-load-generator-1.0.0.jar"
if (-not (Test-Path $JarFile)) {
    Write-Host "ERROR: $JarFile not found. Run: mvn clean package" -ForegroundColor Red
    exit 1
}

# Get configuration
$Threads = if ($env:THREADS) { $env:THREADS } else { "3" }
$DbUrl = if ($env:DB_URL) { $env:DB_URL } else { "jdbc:sqlserver://localhost:1433;databaseName=loadtest;encrypt=false;trustServerCertificate=true" }

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  APP1 - OLTP Load Generator"
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Database: $DbUrl"
Write-Host "  Threads:  $Threads"
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

if (-not $NoAgent) {
    # Find New Relic agent
    $NewRelicJar = $null
    $NewRelicYml = $null

    if ((Test-Path "newrelic\newrelic.jar") -and (Test-Path "newrelic\newrelic.yml")) {
        $NewRelicJar = "newrelic\newrelic.jar"
        $NewRelicYml = "newrelic\newrelic.yml"
    } elseif ((Test-Path "newrelic.jar") -and (Test-Path "newrelic.yml")) {
        $NewRelicJar = "newrelic.jar"
        $NewRelicYml = "newrelic.yml"
    }

    if ($NewRelicJar) {
        # Validate license key
        if (-not $env:NEW_RELIC_LICENSE_KEY -or $env:NEW_RELIC_LICENSE_KEY -eq "your_license_key_here") {
            Write-Host "WARNING: NEW_RELIC_LICENSE_KEY not set or is placeholder!" -ForegroundColor Yellow
            Write-Host "APM-to-QPM linking will NOT work without a valid license key." -ForegroundColor Yellow
            Write-Host "Set it in .env file or as environment variable." -ForegroundColor Yellow
            Write-Host ""
        }

        Write-Host "  New Relic Agent: ENABLED" -ForegroundColor Green
        Write-Host "  Config: $NewRelicYml"
        Write-Host "  QPM Linking: ENABLED (sql_metadata_comments + instance_reporting)"
        Write-Host "==========================================" -ForegroundColor Cyan
        Write-Host ""

        $ConfigPath = (Resolve-Path $NewRelicYml).Path

        java "-javaagent:$NewRelicJar" "-Dnewrelic.config.file=$ConfigPath" "-Dthreads=$Threads" -jar $JarFile
    } else {
        Write-Host "  New Relic Agent: NOT FOUND" -ForegroundColor Yellow
        Write-Host "  Run 'mvn clean package' to download and unpack the agent" -ForegroundColor Yellow
        Write-Host "==========================================" -ForegroundColor Cyan
        Write-Host ""

        java "-Dthreads=$Threads" -jar $JarFile
    }
} else {
    Write-Host "  New Relic Agent: DISABLED (--NoAgent flag)" -ForegroundColor Yellow
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host ""

    java "-Dthreads=$Threads" -jar $JarFile
}
