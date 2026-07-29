#!/bin/sh
#
# Wrapper for the Windchill Part Loader.
#
# Must be run on the Windchill server as the Windchill OS user. The `windchill` command
# sets up the codebase classpath and the JVM the utility expects; running plain `java`
# with a hand-built classpath will appear to work and then fail in ways that waste a day.
#
# Usage:
#   ./load_parts.sh --dry-run
#   ./load_parts.sh
#
# The password is read from WC_PASSWORD when set, otherwise the utility prompts.
#

set -e

: "${WT_HOME:?WT_HOME is not set - source the Windchill environment first}"

LOAD_DIR="${LOAD_DIR:-$WT_HOME/loadFiles}"
CONFIG="${CONFIG:-$LOAD_DIR/partloader.properties}"
PARTS="${PARTS:-$LOAD_DIR/parts.csv}"
BOM="${BOM:-$LOAD_DIR/bom.csv}"
WC_USER="${WC_USER:-wcadmin}"

echo "Windchill home : $WT_HOME"
echo "Config         : $CONFIG"
echo "Parts file     : $PARTS"
echo "BOM file       : $BOM"
echo "User           : $WC_USER"
echo

"$WT_HOME/bin/windchill" com.plm.tools.partloader.PartLoaderMain \
    --config "$CONFIG" \
    --parts  "$PARTS" \
    --bom    "$BOM" \
    --user   "$WC_USER" \
    "$@"

# Exit code is propagated: 0 clean, 1 row failures, 2 aborted, 3 usage error.
