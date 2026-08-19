@echo off
rem Compila o apigol para bytecode Java 8 e gera dist\apigol.war.
rem Requer um JDK no PATH (com JDK 9+ use --release 8; com JDK 8 os flags
rem -source/-target abaixo ja bastam).
setlocal enabledelayedexpansion
cd /d "%~dp0"

set CP=lib\javax.servlet-api-3.1.0.jar;lib\javax.websocket-api-1.1.jar

if exist build rmdir /s /q build
if exist dist\apigol.war del dist\apigol.war
mkdir build\classes build\test-classes build\war\WEB-INF dist 2>nul

dir /s /b src\main\java\*.java > build\fontes.txt
javac -encoding UTF-8 -source 8 -target 8 -cp "%CP%" -d build\classes @build\fontes.txt
if errorlevel 1 exit /b 1
xcopy /e /q /y src\main\resources build\classes >nul

dir /s /b src\test\java\*.java > build\fontes-teste.txt
javac -encoding UTF-8 -source 8 -target 8 -cp "%CP%;build\classes" -d build\test-classes @build\fontes-teste.txt
if errorlevel 1 exit /b 1
java -cp "build\classes;build\test-classes" bb.apigol.core.EngineSelfTest
if errorlevel 1 exit /b 1

xcopy /e /q /y build\classes build\war\WEB-INF\classes\ >nul
copy /y src\main\webapp\WEB-INF\web.xml build\war\WEB-INF\web.xml >nul
cd build\war
jar cf ..\..\dist\apigol.war .
cd ..\..

echo OK: dist\apigol.war
