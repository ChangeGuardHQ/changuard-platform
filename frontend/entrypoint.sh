#!/bin/sh
set -eu

exec npm run dev -- --host 0.0.0.0 "$@"
