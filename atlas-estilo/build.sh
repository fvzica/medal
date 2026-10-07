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

# artefatos do SSO do BB (binários do BB, copiados de uma ferramenta já implantada,
# ex. boaspraticas; nunca entram no git). Com os quatro presentes o WAR sai
# completo; sem eles sai como atlasestilo-sem-sso.war, para completar no
# servidor com terceiros/sso/injetar-sso.bat.
SSO_OK=1
for a in terceiros/sso/br/com/bb/sso/filter/FilterOauth2.class \
         terceiros/sso/br/com/bb/sso/bean/Usuario.class \
         terceiros/sso/oauth.properties; do
  [ -f "$a" ] || SSO_OK=0
done
JSON_JAR="$(ls terceiros/sso/json-*.jar 2>/dev/null | head -1 || true)"
[ -n "$JSON_JAR" ] || SSO_OK=0
if [ "$SSO_OK" = "1" ]; then
  mkdir -p build/war/WEB-INF/classes/br/com/bb/sso
  cp -r terceiros/sso/br/com/bb/sso/. build/war/WEB-INF/classes/br/com/bb/sso/
  cp "$JSON_JAR" build/war/WEB-INF/lib/
  cp terceiros/sso/oauth.properties build/war/WEB-INF/classes/oauth.properties
  grep -q "^redirect_uri=https://super-pf1.intranet.bb.com.br/atlasestilo$" terceiros/sso/oauth.properties \
    || echo "AVISO: redirect_uri do oauth.properties não é https://super-pf1.intranet.bb.com.br/atlasestilo"
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
  # cópia de referência do schema
  cp WebContent/WEB-INF/sql/schema.sql sql/schema.sql
  if [ "$SSO_OK" = "1" ]; then
    rm -f dist/atlasestilo-sem-sso.war
    (cd build/war && jar cf ../../dist/atlasestilo.war .)
    for e in WEB-INF/classes/br/com/bb/sso/filter/FilterOauth2.class WEB-INF/classes/oauth.properties; do
      jar tf dist/atlasestilo.war | grep -q "^$e$" || { echo "ERRO: WAR sem $e"; exit 1; }
    done
    jar tf dist/atlasestilo.war | grep -q "^WEB-INF/lib/json-.*\.jar$" || { echo "ERRO: WAR sem o json-*.jar do SSO"; exit 1; }
    echo "OK: dist/atlasestilo.war (produção, com SSO do BB)"
  else
    rm -f dist/atlasestilo.war
    (cd build/war && jar cf ../../dist/atlasestilo-sem-sso.war .)
    echo "OK: dist/atlasestilo-sem-sso.war"
    echo "    Este WAR ainda NÃO loga: faltam os binários do SSO do BB (FilterOauth2.class,"
    echo "    Usuario.class, json-*.jar) e o oauth.properties com o client_secret."
    echo "    No servidor: terceiros\\sso\\injetar-sso.bat  -> copia tudo do boaspraticas já implantado"
    echo "    e gera atlasestilo.war pronto. Ou copie os 4 arquivos para terceiros/sso/ e rode ./build.sh."
  fi
fi
