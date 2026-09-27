#!/bin/bash
# Run script for .NET OLTP Load Generator with .env support
# Usage: ./run.sh           - Run in foreground
#        ./run.sh --bg       - Run in background
#        ./run.sh --stop     - Stop background process
#        ./run.sh --build    - Build first, then run

set -e

BACKGROUND=false
STOP=false
BUILD=false

for arg in "$@"; do
    case $arg in
        --bg|-b)   BACKGROUND=true ;;
        --stop)    STOP=true ;;
        --build)   BUILD=true ;;
    esac
done

if [ "$STOP" = true ]; then
    echo "Stopping .NET OLTP Load Generator..."
    pkill -f 'dotnet.*app2-oltp-load-generator' && echo "Process stopped" || echo "No running process found"
    exit 0
fi

# Load .env
if [ -f .env ]; then
    echo "Loading configuration from .env file..."
    set -a
    source <(cat .env | sed 's/#.*//g' | grep -v '^$' | grep '=')
    set +a
else
    echo "WARNING: .env file not found. Copy .env.example to .env"
fi

# Build if requested or if no output exists
RELEASE_DIR="bin/Release/net10.0"
if [ "$BUILD" = true ] || [ ! -f "$RELEASE_DIR/app2-oltp-load-generator.dll" ]; then
    echo "Building..."
    dotnet build -c Release
fi

echo "=========================================="
echo "Starting .NET OLTP Load Generator (APP2)"
echo "=========================================="
echo "Threads: ${THREADS:-3}"
echo "Port: 8081"
echo "NR App Name: ${NEW_RELIC_APP_NAME:-APP2}"
echo "=========================================="

# Set New Relic profiler env vars
NR_AGENT_DIR="$RELEASE_DIR/newrelic"
if [ -d "$NR_AGENT_DIR" ]; then
    if [[ "$OSTYPE" == "darwin"* ]]; then
        NR_PROFILER_LIB="$NR_AGENT_DIR/libNewRelicProfiler.dylib"
        # macOS arm64 path
        if [ ! -f "$NR_PROFILER_LIB" ]; then
            NR_PROFILER_LIB="$(find $NR_AGENT_DIR -name 'libNewRelicProfiler.dylib' 2>/dev/null | head -1)"
        fi
    else
        NR_PROFILER_LIB="$NR_AGENT_DIR/libNewRelicProfiler.so"
    fi
    if [ -n "$NR_PROFILER_LIB" ] && [ -f "$NR_PROFILER_LIB" ]; then
        export CORECLR_ENABLE_PROFILING=1
        export CORECLR_PROFILER="{36032161-FFC0-4B61-B559-F6C5D41BAE5A}"
        export CORECLR_PROFILER_PATH="$NR_PROFILER_LIB"
        export NEW_RELIC_HOME="$NR_AGENT_DIR"
        export NEW_RELIC_CONFIG_FILE="$(pwd)/newrelic.config"
        export NEW_RELIC_LICENSE_KEY="${NEW_RELIC_LICENSE_KEY:-}"
        export NEW_RELIC_APP_NAME="${NEW_RELIC_APP_NAME:-APP2}"
        echo "New Relic Agent: ENABLED"
        echo "Config: $(pwd)/newrelic.config"
        echo "Profiler: $NR_PROFILER_LIB"
    else
        echo "New Relic Agent: DISABLED (profiler not found for this OS; install system agent or use Linux)"
        echo "  Searched: $NR_PROFILER_LIB"
    fi
else
    echo "New Relic Agent: DISABLED (run with --build first)"
fi
echo "=========================================="

if [ "$BACKGROUND" = true ]; then
    nohup dotnet "$RELEASE_DIR/app2-oltp-load-generator.dll" > /dev/null 2>&1 &
    echo "Started in background with PID: $!"
else
    dotnet "$RELEASE_DIR/app2-oltp-load-generator.dll"
fi
