#!/bin/bash
set -ex

#Setup environment vars for build
export JAVA8_BUILD=true
. /opt/toolchain/toolchain.sh
export PATH="$MAVEN_3_5_0_HOME/bin:$PATH"

mvn -Drat.numUnapprovedLicenses=10000 clean test package
