@echo off
rem OPCIONAL: troca a implementacao propria do SSO que ja vem no atlasestilo.war
rem pelos binarios OFICIAIS do BB (FilterOauth2, Usuario, json-*.jar e
rem oauth.properties) copiados do boaspraticas ja implantado neste servidor,
rem gerando atlasestilo.war ao lado. Nao precisa de JDK. Nao e necessario para o
rem WAR funcionar: o build ja gera o WAR pronto.
rem Uso:  injetar-sso.bat [caminho\atlasestilo.war] [pasta\webapps\boaspraticas]
setlocal
set "AQUI=%~dp0"
set "WAR=%~1"
set "ORIGEM=%~2"
set "ARGS="
if not "%WAR%"=="" set "ARGS=%ARGS% -War "%WAR%""
if not "%ORIGEM%"=="" set "ARGS=%ARGS% -Origem "%ORIGEM%""
powershell -NoProfile -ExecutionPolicy Bypass -File "%AQUI%injetar-sso.ps1" %ARGS%
if errorlevel 1 (
  echo.
  echo FALHOU. Confira as mensagens acima. Se o PowerShell estiver bloqueado, faca a mao:
  echo   expanda o WAR, copie WEB-INF\classes\br\com\bb\sso, WEB-INF\lib\json-*.jar e
  echo   WEB-INF\classes\oauth.properties do boaspraticas (trocando o redirect_uri
  echo   para https://super-pf1.intranet.bb.com.br/atlasestilo) e recompacte como .war.
  exit /b 1
)
endlocal
