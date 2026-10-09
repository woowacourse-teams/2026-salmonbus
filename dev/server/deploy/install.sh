#!/usr/bin/env bash
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
claim_deploy
dev_tools install --bundle "$REVISION"
