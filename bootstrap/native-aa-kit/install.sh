#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Install the Bluetooth wrapper at /eso/bin/PhoneCustomer/btstack.
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
    # A QNX union mount keeps /eso as its physical directory name. Require
    # two original executables to be the same device/inode through both
    # views; equal file contents alone do not establish the backing mount.
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
[ ! -e "$TARGET" ] && [ ! -L "$TARGET" ] || fail 'customer executable exists; run remove.sh first'
[ ! -e "$MARKER" ] && [ ! -L "$MARKER" ] || fail 'ownership marker exists; run remove.sh first'
RW=0
OWNED=0
OWN_PARENT=0
DONE=0
finish() {
    RC=$?
    trap - 0 HUP INT TERM
    if [ "$RW" = 1 ]; then
        if [ "$DONE" != 1 ] && [ "$OWNED" = 1 ]; then
            echo 'Rollback: remove only the newly created wrapper'
            rm -f "$TARGET" || { echo 'ERROR: wrapper rollback failed' >&2; RC=1; }
        fi
        if [ "$DONE" != 1 ] && [ "$OWN_PARENT" = 1 ]; then
            cleanup_parent || { echo 'ERROR: owned directory cleanup failed' >&2; RC=1; }
        fi
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
[ ! -e "$TARGET" ] && [ ! -L "$TARGET" ] || fail 'customer executable appeared; refusing replacement'
echo 'Create customer directory if absent'
if [ ! -d "${TARGET%/*}" ]; then
    mkdir "${TARGET%/*}" || fail 'customer directory creation failed'
    OWN_PARENT=1
    (set -C; echo "$TOKEN" > "$MARKER") || fail 'ownership marker creation failed'
    marker_valid || fail 'ownership marker verification failed'
fi
parents_safe || fail 'customer parent changed; refusing copy'
OWNED=1
echo 'Copy kit wrapper to customer slot'
cp "$SOURCE" "$TARGET" || fail 'wrapper copy failed'
[ -f "$TARGET" ] && [ ! -L "$TARGET" ] || fail 'copied wrapper is not regular'
S=$(sed -n l "$SOURCE" && cat "$SOURCE" && echo .) || fail 'cannot read kit wrapper'
T=$(sed -n l "$TARGET" && cat "$TARGET" && echo .) || fail 'cannot read copied wrapper'
[ "$S" = "$T" ] || fail 'copied wrapper differs'
echo 'Set wrapper mode 0755'
chmod 0755 "$TARGET" || fail 'wrapper chmod failed'
echo 'Remount /mnt/app read-only'
if ! mount -ur "$APP"; then
    fail 'read-only remount failed; rolling back owned wrapper'
fi
RW=0
DONE=1
echo 'Installed wrapper; read-only remount succeeded. No service was started.'
