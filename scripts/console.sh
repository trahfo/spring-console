#!/usr/bin/env sh
#
# Generic launcher for spring-console embedded in any Spring Boot application.
# Runs the application in a fresh JVM attached to *this* terminal with an
# exploded classpath so JLine3 and the Kotlin scripting compiler work out-of-the-box.
#
# Opinionated behavior:
#   - When launched without arguments, it expects the Spring Boot app to be in
#     the folder it was launched from ($PWD).
#   - You can also pass a project directory explicitly:
#       console.sh /path/to/my-app [spring-boot arguments...]
#
set -eu

# Resolve target application directory
if [ $# -gt 0 ] && [ -d "$1" ]; then
    APP_DIR=$(CDPATH= cd -- "$1" && pwd)
    shift
else
    APP_DIR=$(pwd)
fi

# Verify build file exists in APP_DIR
if [ ! -f "$APP_DIR/build.gradle" ] && [ ! -f "$APP_DIR/build.gradle.kts" ] && [ ! -f "$APP_DIR/pom.xml" ]; then
    echo "Error: No Spring Boot project found in: $APP_DIR" >&2
    echo "Usage: $(basename "$0") [project-directory] [spring-boot-arguments...]" >&2
    echo "When launched without arguments, console.sh expects the Spring Boot application in the current directory." >&2
    exit 1
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
INIT_SCRIPT="$SCRIPT_DIR/init-console.gradle"

# Locate gradlew in APP_DIR or ancestor directories
GRADLEW=""
curr="$APP_DIR"
while [ "$curr" != "/" ]; do
    if [ -x "$curr/gradlew" ]; then
        GRADLEW="$curr/gradlew"
        break
    fi
    curr=$(dirname "$curr")
done

if [ -z "$GRADLEW" ]; then
    if command -v gradle >/dev/null 2>&1; then
        GRADLEW="gradle"
    else
        echo "Error: Could not find gradlew in $APP_DIR (or ancestor directories) and gradle is not on PATH." >&2
        exit 1
    fi
fi

# Extract classpath and main class dynamically without modifying target project
LAUNCH_INFO=$("$GRADLEW" -q --console=plain -p "$APP_DIR" -Dconsole.target.dir="$APP_DIR" -I "$INIT_SCRIPT" consoleLaunchInfo 2>&1) || {
    echo "Error: Project in $APP_DIR is not a runnable Spring Boot application (no Spring Boot plugin or bootRun task found)." >&2
    echo "Usage: $(basename "$0") [project-directory] [spring-boot-arguments...]" >&2
    echo "When launched without arguments, console.sh expects the Spring Boot application in the current directory." >&2
    exit 1
}

CP=$(echo "$LAUNCH_INFO" | grep '^CONSOLE_CP=' | head -n 1 | sed 's/^CONSOLE_CP=//')
MAIN=$(echo "$LAUNCH_INFO" | grep '^CONSOLE_MAIN=' | head -n 1 | sed 's/^CONSOLE_MAIN=//')

if [ -z "$CP" ] || [ -z "$MAIN" ]; then
    echo "Error: Failed to resolve Spring Boot classpath or main class for project in $APP_DIR" >&2
    echo "$LAUNCH_INFO" >&2
    exit 1
fi

cd "$APP_DIR"

# Check if a Spring Console instance is already running (e.g. backend on port 8080)
MCP_URL="${SPRING_CONSOLE_URL:-http://127.0.0.1:8085/mcp}"
IS_RUNNING=false
if command -v curl >/dev/null 2>&1; then
    if curl -s -f -m 1 -X POST "$MCP_URL" \
        -H "Content-Type: application/json" \
        -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' >/dev/null 2>&1; then
        IS_RUNNING=true
    fi
fi

if [ "$IS_RUNNING" = "true" ]; then
    echo "Spring Console — Attaching to live application at $MCP_URL..."
    exec java -cp "$CP" io.github.springconsole.client.ConsoleClient "$MCP_URL"
fi

# Not running: launch standalone Spring Boot application
# If port 8080 is already in use by another process, prevent collision by assigning a random port
PORT_8080_TAKEN=false
if command -v lsof >/dev/null 2>&1; then
    if lsof -i :8080 -sTCP:LISTEN >/dev/null 2>&1; then
        PORT_8080_TAKEN=true
    fi
elif command -v nc >/dev/null 2>&1; then
    if nc -z 127.0.0.1 8080 >/dev/null 2>&1; then
        PORT_8080_TAKEN=true
    fi
fi

EXTRA_ARGS=""
if [ "$PORT_8080_TAKEN" = "true" ]; then
    case " $* " in
        *" --server.port="*) ;;
        *)
            echo "Port 8080 is in use. Launching on random port (--server.port=0)..."
            EXTRA_ARGS="--server.port=0"
            ;;
    esac
fi

exec java -cp "$CP" "$MAIN" $EXTRA_ARGS "$@"
