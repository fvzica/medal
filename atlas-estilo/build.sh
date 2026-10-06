#!/usr/bin/env bash
# Compila o Atlas Estilo para bytecode Java 8 (Tomcat 8.5 / JRE 1.8 x86 do
# servidor super-pf1), roda o SelfTest e gera dist/atlasestilo.war.
#
# Modo dev:  ./build.sh dev  -> gera dist/atlasestilo-dev.war SEM o filtro de
# SSO do BB e com atlas.dev.simular=true (login simulado como Master). Use só
# para testar fora do servidor — NUNCA em produção.
set -euo pipefail
cd "$(dirname "$0")"

MODO="${1:-prod}"
CP="../lib/javax.servlet-api-3.1.0.jar:lib/sqlite-jdbc-3.36.0.3.jar"

rm -rf build
mkdir -p build/classes build/test-classes build/war dist

javac -encoding UTF-8 --release 8 -Xlint:-options -cp "$CP" -d build/classes \
    $(find src/br -name '*.java')

javac -encoding UTF-8 --release 8 -Xlint:-options -cp "$CP:build/classes" \
    -d build/test-classes src/test/SelfTest.java
java -cp "build/classes:build/test-classes:lib/sqlite-jdbc-3.36.0.3.jar:../lib/javax.servlet-api-3.1.0.jar" SelfTest

# monta a árvore do WAR
cp -r WebContent/. build/war/
mkdir -p build/war/WEB-INF/classes build/war/WEB-INF/lib
cp -r build/classes/. build/war/WEB-INF/classes/
cp lib/sqlite-jdbc-3.36.0.3.jar build/war/WEB-INF/lib/

# artefatos do SSO do BB (binários copiados de uma ferramenta existente)
SSO_OK=1
for a in terceiros/sso/br/com/bb/sso/filter/FilterOauth2.class \
         terceiros/sso/br/com/bb/sso/bean/Usuario.class; do
  [ -f "$a" ] || SSO_OK=0
done
if [ "$SSO_OK" = "1" ]; then
  mkdir -p build/war/WEB-INF/classes/br/com/bb/sso
  cp -r terceiros/sso/br/com/bb/sso/. build/war/WEB-INF/classes/br/com/bb/sso/
  [ -f terceiros/sso/json-20230618.jar ] && cp terceiros/sso/json-20230618.jar build/war/WEB-INF/lib/
else
  echo "AVISO: classes do SSO do BB ausentes em terceiros/sso/ — o WAR de"
  echo "       producao NAO vai logar. Copie FilterOauth2.class, Usuario.class"
  echo "       e json-20230618.jar de uma ferramenta existente (boaspraticas)."
fi

if [ "$MODO" = "dev" ]; then
  # remove o filtro de SSO e liga o usuário simulado
  sed -i.bak '/<!-- SSO-INICIO/,/SSO-FIM -->/d' build/war/WEB-INF/web.xml
  sed -i.bak 's|<param-name>atlas.dev.simular</param-name>\n*|&|' build/war/WEB-INF/web.xml
  # troca o valor de atlas.dev.simular para true (linha seguinte ao param-name)
  awk '{
    print;
  }' build/war/WEB-INF/web.xml > /dev/null
  python3 - "$PWD/build/war/WEB-INF/web.xml" <<'EOF'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s = re.sub(r'(<param-name>atlas\.dev\.simular</param-name>\s*<param-value>)false(</param-value>)',
           r'\g<1>true\g<2>', s)
open(p, 'w', encoding='utf-8').write(s)
EOF
  rm -f build/war/WEB-INF/web.xml.bak
  (cd build/war && jar cf ../../dist/atlasestilo-dev.war .)
  echo "OK: dist/atlasestilo-dev.war (DEV — sem SSO, usuário simulado)"
else
  (cd build/war && jar cf ../../dist/atlasestilo.war .)
  # cópia de referência do schema
  cp WebContent/WEB-INF/sql/schema.sql sql/schema.sql
  echo "OK: dist/atlasestilo.war"
fi
