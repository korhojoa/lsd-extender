#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# mount-vaa.sh -- mount/unmount the devu-vaa virtual USB HCD in the running boot
# io-usb. The release archive supplies it beside devu-vaa.so.
#
#   mount-vaa.sh            mount   (default; removes a prior instance first)
#   mount-vaa.sh umount     remove the driver
#   mount-vaa.sh remount    remove, then mount
#
# The driver is mounted from a RAM copy (/tmp), so the card copy is never held
# open and a card copy can replace it. An unmount first reports the device
# gone and waits for io-usb to finish removing it. An unmount with a live
# device can stop the USB stack. If removal fails, update the card and restart.
#
# Env overrides:
#   VAA_SO=/path/devu-vaa.so   driver to load (else the card copy, else /tmp)
#   VAA_PORT=N                 bridge TCP port (must match the wireless bridge; default 5288)
#
# The unit's shell is ksh: this uses only ksh builtins and /bin tools present on
# the unit (no printf, no bash-isms).
set -u

# The node the mounted driver registers (io_usb_dll_entry name "VAA"; cname
# "vaa"). We ONLY ever umount one of these specific nodes -- NEVER
# /dev/io-usb/io-usb, which is the real USB stack -- so an unmount is harmless
# even if the node name here is wrong (it just finds nothing).
VAA_NODES="/dev/io-usb/VAA /dev/io-usb/vaa"

# The driver is mounted from a RAM copy, never from the card. A mounted .so is
# held open, so a card copy makes /fs/sdb0/lsd-extender/devu-vaa.so busy and
# a card copy gets "Resource busy (16)" on that file. Mounting from
# /tmp (which is /dev/shmem) leaves the card file free at all times; that path
# is how the driver was first brought up on the unit, so it is known to work.
VAA_LIVE=/tmp/devu-vaa-live.so

# Is the synthetic device enumerated right now? The product id is distinctive
# enough on this unit, and matching one field keeps this independent of how
# `usb` lays its output out.
vaa_device_present() {
  usb -v -N /dev/io-usb/io-usb 2>/dev/null | grep -qi "2d00"
}

# Report the device gone and wait for io-usb to finish removing it, BEFORE the
# driver is unmounted. Unmounting with the device still enumerated takes the
# USB stack down with it because io-usb keeps
# calling into a controller whose code is being unloaded. The sentinel is the
# proven hotplug path the driver already uses for SI-disconnect recovery.
vaa_unplug_and_settle() {
  # Always ask, even when the check above cannot see the device: the sentinel
  # is harmless when nothing is enumerated, and a missed unplug is what costs
  # the USB stack.
  echo "mount-vaa: reporting the device gone before the unmount"
  mkdir -p /ramdisk/pps/aawg || return 1
  echo 'request::1' > /ramdisk/pps/aawg/vaa_unplug || return 1
  sleep 1
  waited=10
  while [ "$waited" -lt 50 ]; do
    if ! vaa_device_present; then
      echo "mount-vaa: device gone after ${waited} tenths of a second"
      return 0
    fi
    sleep 1
    waited=$((waited + 10))
  done
  echo "mount-vaa: device STILL enumerated after 5 s -- refusing to unmount" >&2
  echo "mount-vaa: unmounting now would take io-usb (and the debug ethernet) down." >&2
  echo "mount-vaa: deploy the new driver to the card and reboot instead." >&2
  return 1
}

vaa_node_exists() {
  for n in $VAA_NODES; do
    if [ -e "$n" ]; then return 0; fi
  done
  return 1
}

vaa_umount() {
  vaa_unplug_and_settle || return 1
  removed=""
  for n in $VAA_NODES; do
    if umount "$n" 2>/dev/null; then
      echo "mount-vaa: unmounted $n"
      removed="$n"
    fi
  done
  if [ -z "$removed" ]; then
    echo "mount-vaa: no devu-vaa node to unmount (tried: $VAA_NODES)"
    echo "mount-vaa: entries under /dev/io-usb:"
    ls /dev/io-usb 2>/dev/null | sed 's/^/  /'
    return 1
  fi
  return 0
}

vaa_mount() {
  SO="${VAA_SO:-}"
  if [ -z "$SO" ]; then
    for c in /fs/sdb0/lsd-extender/devu-vaa.so /tmp/devu-vaa.so; do
      if [ -f "$c" ]; then SO="$c"; break; fi
    done
  fi
  if [ -z "$SO" ] || [ ! -f "$SO" ]; then
    echo "mount-vaa: devu-vaa.so not found (looked in /fs/sdb0/lsd-extender and /tmp); set VAA_SO=/path" >&2
    return 1
  fi
  OPTS="ioport=0,irq=0,bind_all=1"
  if [ -n "${VAA_PORT:-}" ]; then OPTS="${OPTS},port=${VAA_PORT}"; fi
  # Report the selected driver's byte count with the mount request.
  # The count describes the file selected for this command.
  # It is not confirmation that the device mounted successfully.
  # No tr on the unit: strip wc's padding with the shell's own word splitting.
  set -- $(wc -c < "$SO" 2>/dev/null)
  SIZE=${1:-unknown}
  # Mount the RAM copy, so the card file stays free for the next sync.
  if [ "$SO" != "$VAA_LIVE" ]; then
    if cp "$SO" "$VAA_LIVE"; then
      LIVE="$VAA_LIVE"
    else
      echo "mount-vaa: could not copy $SO to $VAA_LIVE; mounting the original (the card file will be busy)" >&2
      LIVE="$SO"
    fi
  else
    LIVE="$SO"
  fi
  if mount -T io-usb -o "$OPTS" "$LIVE" /dev/io-usb/io-usb; then
    echo "mount-vaa: mounted $SO ($SIZE bytes, via $LIVE) into /dev/io-usb/io-usb ($OPTS)"
  else
    echo "mount-vaa: mount FAILED for $SO" >&2
    return 1
  fi
}

case "${1:-mount}" in
  umount|unmount|remove|down|stop)
    vaa_umount ;;
  remount|reload)
    vaa_umount && vaa_mount ;;
  mount|up|"")
    # Nothing mounted (the boot case) means nothing to take down first, and no
    # reason to pay for the unplug wait.
    if vaa_node_exists; then vaa_umount >/dev/null 2>&1 || exit 1; fi
    vaa_mount ;;
  *)
    echo "usage: mount-vaa.sh [mount|umount|remount]" >&2; exit 2 ;;
esac
