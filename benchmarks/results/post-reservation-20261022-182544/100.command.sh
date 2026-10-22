#!/usr/bin/env bash
# HISTORICAL COMMAND: this slot has already been used. Prepare a fresh test slot before replay.
cd /home/colaminos/resq
export RESQ_VUS=100
export RESQ_POST_SLOT_ID=118
export RESQ_RUN_ID=d24_20261022_182544_3163fc_n100
export RESQ_BASE_URL=http://127.0.0.1:8080
export RESQ_TOKENS_FILE=/tmp/d24_20261022_182544_3163fc_credentials_bt07wvbp/tokens.json
k6 run --summary-mode=full '--summary-trend-stats=avg,min,med,max,p(90),p(95),p(99)' --summary-export=/home/colaminos/resq/benchmarks/results/d24_20261022_182544_3163fc/100.summary.json --out json=/home/colaminos/resq/benchmarks/results/d24_20261022_182544_3163fc/100.metrics.ndjson benchmarks/post-reservation-race.js
