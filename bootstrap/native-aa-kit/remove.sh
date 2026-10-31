#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Remove only the exact supplied Bluetooth wrapper.
set -u
LC_ALL=C
export LC_ALL
APP=/mnt/app
ESO=/eso
TARGET=/eso/bin/PhoneCustomer/btstack
DIR=$(CDPATH='' cd "${0%/*}" && pwd -P) || exit 1
SOURCE=$DIR/btstack
MARKER=$ESO/bin/.native-aa-phonecustomer-created
TOKEN='native-aa-customer-slot created /eso/bin/PhoneCustomer'
marker_valid() {
    [ -f "$MARKER" ] && [ ! -L "$MARKER" ] || return 1
    EXPECTED="$TOKEN"'$'
    EXPECTED="$EXPECTED
$TOKEN
."
    ACTUAL=$(sed -n l "$MARKER" && cat "$MARKER" && echo .) || return 1
    [ "$ACTUAL" = "$EXPECTED" ]
}
cleanup_parent() {
    marker_valid || { echo 'ERROR: ownership marker invalid; parent cleanup unresolved' >&2; return 1; }
    [ ! -L "${TARGET%/*}" ] && [ -d "${TARGET%/*}" ] || {
        echo 'ERROR: owned customer parent is linked or missing; retaining marker' >&2
        return 1
    }
    CONTENTS=$(ls -a "${TARGET%/*}") || return 1
    if [ "$CONTENTS" != '.
..' ]; then
        echo 'Customer directory retained: foreign contents remain; ownership marker retained.'
        return 0
    fi
    echo 'Remove empty customer directory created by this kit'
    rm -r "${TARGET%/*}" || return 1
    [ ! -e "${TARGET%/*}" ] && [ ! -L "${TARGET%/*}" ] || return 1
    rm -f "$MARKER" || return 1
}
fail() { echo "ERROR: $*" >&2; exit 1; }
[ -f "$SOURCE" ] && [ -r "$SOURCE" ] && [ ! -L "$SOURCE" ] || fail 'kit btstack must be a readable regular file, not a link'
app_mapping_valid() {
    [ "$(CDPATH='' cd "$ESO" && pwd -P)" = "$APP/eso" ] && return 0
    # Use the same union-view identity check as installation.
    for NAME in btstack connectivity_launcher; do
        [ -f "$ESO/bin/apps/$NAME" ] && [ ! -L "$ESO/bin/apps/$NAME" ] &&
        [ -f "$APP/eso/bin/apps/$NAME" ] && [ ! -L "$APP/eso/bin/apps/$NAME" ] &&
        [ "$ESO/bin/apps/$NAME" -ef "$APP/eso/bin/apps/$NAME" ] || return 1
    done
}
app_mapping_valid || fail '/eso is not a verified view of /mnt/app/eso'
parents_safe() {
    [ -d "$ESO/bin" ] && [ ! -L "$ESO/bin" ] || return 1
    [ ! -L "${TARGET%/*}" ] || return 1
    [ ! -e "${TARGET%/*}" ] || [ -d "${TARGET%/*}" ]
}
parents_safe || fail 'customer parent is linked or not a directory'
OWN_PARENT=0
if [ -e "$MARKER" ] || [ -L "$MARKER" ]; then
    marker_valid || fail 'ownership marker invalid; refusing removal'
    OWN_PARENT=1
fi
[ -f "$TARGET" ] && [ ! -L "$TARGET" ] || fail 'customer executable missing or not regular'
S=$(sed -n l "$SOURCE" && cat "$SOURCE" && echo .) || fail 'cannot read kit wrapper'
T=$(sed -n l "$TARGET" && cat "$TARGET" && echo .) || fail 'cannot read customer executable'
[ "$S" = "$T" ] || fail 'customer executable differs; refusing removal'
RW=0
finish() {
    RC=$?
    trap - 0 HUP INT TERM
    if [ "$RW" = 1 ]; then
        echo 'Remount /mnt/app read-only'
        mount -ur "$APP" || { echo 'ERROR: read-only remount failed; mount state unresolved' >&2; RC=1; }
    fi
    exit "$RC"
}
trap finish 0
trap 'exit 1' HUP INT TERM
echo 'Remount /mnt/app writable'
RW=1
mount -uw "$APP" || fail 'writable remount failed; attempting read-only recovery'
parents_safe || fail 'customer parent changed; refusing removal'
T=$(sed -n l "$TARGET" && cat "$TARGET" && echo .) || fail 'cannot recheck customer executable'
[ ! -L "$TARGET" ] && [ "$S" = "$T" ] || fail 'customer executable changed; refusing removal'
echo 'Remove exact owned wrapper only'
rm "$TARGET" || fail 'wrapper removal failed'
[ ! -e "$TARGET" ] && [ ! -L "$TARGET" ] || fail 'wrapper remains'
if [ "$OWN_PARENT" = 1 ]; then
    marker_valid || fail 'ownership marker changed; refusing parent cleanup'
    cleanup_parent || fail 'owned directory cleanup failed'
else
    echo 'Pre-existing customer directory retained.'
fi
echo 'Remount /mnt/app read-only'
mount -ur "$APP" || fail 'read-only remount failed after removal'
RW=0
echo 'Removed wrapper; read-only remount succeeded.'
