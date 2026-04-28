@setlocal enableextensions
@cd /d "%~dp0"
SET _JAVA_OPTIONS=-agentlib:zbNative

SET PZ_CLASSPATH=commons-compress-1.18.jar;sqlite-jdbc-3.27.2.1.jar;./
".\jre64\bin\java.exe" -Djava.awt.headless=true -Dzomboid.steam=1 -Xmx3072m -Djava.library.path=./win64/;./ -cp %PZ_CLASSPATH% zombie.gameStates.MainScreenState %1 %2

IF %ERRORLEVEL% NEQ 0 (
".\jre64\bin\java.exe" -Djava.awt.headless=true -Dzomboid.steam=1 -Xmx3072m -Djava.library.path=./win64/;./ -cp %PZ_CLASSPATH% zombie.gameStates.MainScreenState %1 %2

)

PAUSE
