#!/bin/sh
# macOS/Linux entrypoint mirroring sonar-scanner.bat — routes to the Docker wrapper.
exec python3 "$(dirname "$0")/sonar_scanner_wrapper.py" "$@"
