#!/bin/bash - 
#===============================================================================
#
#          FILE: deploy.sh
# 
#         USAGE: ./deploy.sh 
# 
#   DESCRIPTION: 
# 
#       OPTIONS: ---
#  REQUIREMENTS: ---
#          BUGS: ---
#         NOTES: ---
#        AUTHOR: Pierre RABY (), 
#  ORGANIZATION: 
#       CREATED: 07/13/17 11:18:11
#      REVISION:  ---
#===============================================================================

set -o nounset                              # Treat unset variables as an error

mvn clean package
cp ./target/redis-sessions-1.0.jar ../ext/

