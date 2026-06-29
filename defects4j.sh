#!/bin/sh
# macOS/Linux entrypoint mirroring defects4j.bat — routes to the Docker wrapper.
exec python3 "$(dirname "$0")/defects4j_wrapper.py" "$@"
