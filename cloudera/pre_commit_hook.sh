#!/bin/bash
set -ex

#Setup environment vars for build
export JAVA8_BUILD=true
. /opt/toolchain/toolchain.sh
export PATH="$MAVEN_3_5_0_HOME/bin:$PATH"

# activate mvn-gbn wrapper
mv "$(which mvn-gbn-wrapper)" "$(dirname "$(which mvn-gbn-wrapper)")/mvn"

mvn -Drat.numUnapprovedLicenses=10000 clean test package
