#!/bin/sh
set -eu

exec node /app/node_modules/vite/bin/vite.js --host 0.0.0.0 "$@"
