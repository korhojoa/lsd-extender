#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
python3 -B "$ROOT/tools/build.py" "$@"
exec python3 -B -m unittest discover -s "$ROOT/tools" -p 'test_*.py'
