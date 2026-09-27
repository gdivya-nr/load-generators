# Run script for .NET OLTP Load Generator
# Usage: .\run.ps1              - Run with New Relic agent
#        .\run.ps1 -Build       - Build first, then run
#        .\run.ps1 -Stop        - Stop background process

param(
    [switch]$Build,
    [switch]$Stop
)

if ($Stop) {
    Write-Host "Stopping .NET OLTP Load Generator..."
    Get-Process -Name "app2-oltp-load-generator" -ErrorAction SilentlyContinue | Stop-Process -Force
    exit 0
}

# Load .env — sets ALL env vars including CORECLR_* and NEW_RELIC_*
if (Test-Path ".env") {
    Write-Host "Loading configuration from .env file..."
    Get-Content ".env" | ForEach-Object {
        if ($_ -notmatch '^\s*#' -and $_ -match '=') {
            $parts = $_ -split '=', 2
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
        }
    }
} else {
    Write-Warning ".env file not found. Copy .env.example to .env"
}

$releaseDir = "bin\Release\net8.0"
if ($Build -or -not (Test-Path "$releaseDir\app2-oltp-load-generator.dll")) {
    Write-Host "Building..."
    dotnet build -c Release
}

Write-Host ("=" * 42)
Write-Host ".NET OLTP Load Generator (APP2)"
Write-Host ("=" * 42)
Write-Host "Threads:  $(if ($env:THREADS) { $env:THREADS } else { '3' })"
Write-Host "Port:     8081"
Write-Host "NR App:   $(if ($env:NEW_RELIC_APP_NAME) { $env:NEW_RELIC_APP_NAME } else { 'APP2' })"
Write-Host "Profiler: $(if ($env:CORECLR_ENABLE_PROFILING -eq '1') { 'ENABLED' } else { 'DISABLED' })"
Write-Host ("=" * 42)

dotnet run -c Release --no-build
