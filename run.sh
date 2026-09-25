#!/bin/bash

# Run script for OLTP Load Generator with .env support
# Usage: ./run.sh           - Run in foreground
#        ./run.sh --bg       - Run in background with logs in app.log
#        ./run.sh --stop     - Stop background process

set -e  # Exit on error

# Check for background flag
BACKGROUND=false
STOP=false
if [[ "$1" == "--bg" || "$1" == "-b" ]]; then
    BACKGROUND=true
elif [[ "$1" == "--stop" ]]; then
    STOP=true
fi

# Stop mode: kill the process
if [ "$STOP" = true ]; then
    echo "Stopping OLTP Load Generator..."
    pkill -f 'java.*app1-oltp-load-generator' && echo "Process stopped" || echo "No running process found"
    exit 0
fi

# Load environment variables from .env file
if [ -f .env ]; then
    echo "Loading configuration from .env file..."
    # Export variables, ignoring comments and empty lines
    set -a
    source <(cat .env | sed 's/#.*//g' | grep -v '^$' | grep '=')
    set +a
else
    echo "WARNING: .env file not found. Using default environment variables or application.properties"
    echo "Copy .env.example to .env and configure your database credentials"
fi

# Check if JAR file exists
JAR_FILE="target/app1-oltp-load-generator-1.0.0.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: $JAR_FILE not found"
    echo "Please build the application first: mvn clean package"
    exit 1
fi

# Set thread count from environment or default
THREADS=${THREADS:-50}

echo "=========================================="
echo "Starting OLTP Load Generator"
echo "=========================================="
echo "Database: ${DB_URL}"
echo "Username: ${DB_USERNAME}"
echo "Threads: ${THREADS}"
echo "Pool Max: ${DB_POOL_MAX:-100}"
echo "=========================================="
echo ""

# Check if New Relic agent is available (unpacked by Maven or manually placed)
NEWRELIC_JAR=""
NEWRELIC_YML=""

# Check for unpacked agent from Maven (newrelic/newrelic.jar)
if [ -f "newrelic/newrelic.jar" ] && [ -f "newrelic/newrelic.yml" ]; then
    NEWRELIC_JAR="newrelic/newrelic.jar"
    NEWRELIC_YML="newrelic/newrelic.yml"
# Fallback to current directory (for manual placement)
elif [ -f "newrelic.jar" ] && [ -f "newrelic.yml" ]; then
    NEWRELIC_JAR="newrelic.jar"
    NEWRELIC_YML="newrelic.yml"
fi

if [ -n "$NEWRELIC_JAR" ]; then
    echo "=========================================="
    echo "New Relic Agent: ENABLED"
    echo "=========================================="
    echo "Config File: $NEWRELIC_YML"
    echo "Agent JAR: $NEWRELIC_JAR"
    echo "Note: Configuration is read from newrelic.yml"
    echo "Note: Logs are not stored to disk (redirected to /dev/null)"
    echo "=========================================="
    echo ""

    if [ "$BACKGROUND" = true ]; then
        nohup java -javaagent:$NEWRELIC_JAR -Dnewrelic.config.file=$(pwd)/newrelic.yml -Dthreads=${THREADS} -jar "$JAR_FILE" > /dev/null 2>&1 &
        PID=$!
        echo "Started in background with PID: $PID"
        echo "Logs: Not stored (redirected to /dev/null)"
    else
        java -javaagent:$NEWRELIC_JAR -Dnewrelic.config.file=$(pwd)/newrelic.yml -Dthreads=${THREADS} -jar "$JAR_FILE"
    fi
else
    echo "=========================================="
    echo "New Relic Agent: DISABLED"
    echo "=========================================="
    echo "Reason: newrelic.jar or newrelic.yml not found"
    echo "Hint: Run './build-all.sh' to download and unpack the agent"
    echo "Note: Logs are not stored to disk (redirected to /dev/null)"
    echo "=========================================="
    echo ""

    if [ "$BACKGROUND" = true ]; then
        nohup java -Dthreads=${THREADS} -jar "$JAR_FILE" > /dev/null 2>&1 &
        PID=$!
        echo "Started in background with PID: $PID"
        echo "Logs: Not stored (redirected to /dev/null)"
    else
        java -Dthreads=${THREADS} -jar "$JAR_FILE"
    fi
fi
