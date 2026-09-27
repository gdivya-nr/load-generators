# Run script for .NET OLTP Load Generator
# Usage: .\run.ps1              - Run with New Relic agent
#        .\run.ps1 -NoAgent     - Run without agent
#        .\run.ps1 -Build       - Build first, then run
#        .\run.ps1 -Stop        - Stop background process

param(
    [switch]$NoAgent,
    [switch]$Build,
    [switch]$Stop
)

if ($Stop) {
    Write-Host "Stopping .NET OLTP Load Generator..."
    Get-Process dotnet -ErrorAction SilentlyContinue |
        Where-Object { $_.MainModule.FileName -like '*app2*' -or $_.CommandLine -like '*app2-oltp*' } |
        Stop-Process -Force
    exit 0
}

# Load .env
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
Write-Host "Threads: $($env:THREADS ?? '3')"
Write-Host "Port: 8081"
Write-Host "NR App: $($env:NEW_RELIC_APP_NAME ?? 'APP2')"
Write-Host ("=" * 42)

$nrAgentDir = "$releaseDir\newrelic"
if (-not $NoAgent -and (Test-Path $nrAgentDir)) {
    $nrProfilerDll = "$nrAgentDir\NewRelic.Profiler.dll"
    if (-not (Test-Path $nrProfilerDll)) {
        $nrProfilerDll = Get-ChildItem $nrAgentDir -Recurse -Filter "NewRelic.Profiler.dll" |
            Select-Object -First 1 -ExpandProperty FullName
    }
    $env:CORECLR_ENABLE_PROFILING = "1"
    $env:CORECLR_PROFILER = "{36032161-FFC0-4B61-B559-F6C5D41BAE5A}"
    $env:CORECLR_PROFILER_PATH = $nrProfilerDll
    $env:NEW_RELIC_HOME = (Resolve-Path $nrAgentDir).Path
    $env:NEW_RELIC_CONFIG_FILE = (Resolve-Path "newrelic.config").Path
    $env:NEW_RELIC_APP_NAME = $env:NEW_RELIC_APP_NAME ?? "APP2"
    Write-Host "New Relic Agent: ENABLED"
    Write-Host "Profiler: $nrProfilerDll"
} else {
    Write-Host "New Relic Agent: DISABLED"
}

dotnet "$releaseDir\app2-oltp-load-generator.dll"
