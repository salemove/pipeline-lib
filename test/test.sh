#!/bin/sh
set -eux

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No color

response=$(curl "$DEPLOY_PIPELINE_TEST_SERVICE_HOST:$DEPLOY_PIPELINE_TEST_SERVICE_PORT")

# shellcheck disable=SC2059
if [ "$response" = "BUILD_VALUE=$BUILD_VALUE, TEMPLATE_VALUE=$TEMPLATE_VALUE" ]
then
  printf "${GREEN}Success!${NC}\\n"
else
  printf "${RED}Failure!${NC}\\n"
  exit 1
fi
