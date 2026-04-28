@setlocal enableextensions
@cd /d "%~dp0"
SET _JAVA_OPTIONS=

SET PZ_CLASSPATH=commons-compress-1.27.1.jar;commons-io-2.18.0.jar;imgui-binding-1.86.11-8-g3e33dde.jar;sqlite-jdbc-3.48.0.0.jar;./
".\jre64\bin\java.exe" -Djava.awt.headless=true -Dzomboid.steam=1 -Dzomboid.znetlog=1 -XX:+UseZGC -Xmx3072m -Djava.library.path=./win64/;./ -cp %PZ_CLASSPATH% zombie.gameStates.MainScreenState %1 %2

IF %ERRORLEVEL% NEQ 0 (
".\jre64\bin\java.exe" -Djava.awt.headless=true -Dzomboid.steam=1 -Dzomboid.znetlog=1 -Xmx3072m -Djava.library.path=./win64/;./ -cp %PZ_CLASSPATH% zombie.gameStates.MainScreenState %1 %2

)

PAUSE
