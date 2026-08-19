#!/usr/bin/env bash
# Compila o apigol para bytecode Java 8 (Tomcat 8.5 / JRE 1.8 do servidor
# super-pf1), roda o self-test e gera dist/apigol.war.
set -euo pipefail
cd "$(dirname "$0")"

CP="lib/javax.servlet-api-3.1.0.jar:lib/javax.websocket-api-1.1.jar"

rm -rf build dist/apigol.war
mkdir -p build/classes build/test-classes build/war/WEB-INF dist

javac -encoding UTF-8 --release 8 -Xlint:-options -cp "$CP" -d build/classes \
    $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/

javac -encoding UTF-8 --release 8 -Xlint:-options -cp "$CP:build/classes" -d build/test-classes \
    $(find src/test/java -name '*.java')
java -cp "build/classes:build/test-classes" bb.apigol.core.EngineSelfTest

cp -r build/classes build/war/WEB-INF/classes
cp src/main/webapp/WEB-INF/web.xml build/war/WEB-INF/web.xml
(cd build/war && jar cf ../../dist/apigol.war .)

echo "OK: dist/apigol.war"
