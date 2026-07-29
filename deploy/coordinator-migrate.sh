#!/usr/bin/env sh
set -eu

exec java \
    -cp '/opt/marketlab/migrator:/opt/marketlab/lib/*' \
    dev.marketlab.deploy.MarketlabMigrator
