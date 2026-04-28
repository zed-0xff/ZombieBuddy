@setlocal enableextensions
@cd /d "%~dp0"
SET _JAVA_OPTIONS=-agentlib:zbNative

SET PZ_CLASSPATH=./;projectzomboid.jar
".\jre64\bin\java.exe" -Djava.awt.headless=true -Dzomboid.steam=1 -Dzomboid.znetlog=1 -XX:-CreateCoredumpOnCrash -XX:-OmitStackTraceInFastThrow -XX:+UseZGC -Xmx3072m -Djava.library.path=./win64/;./ -cp %PZ_CLASSPATH% zombie.gameStates.MainScreenState %1 %2

IF %ERRORLEVEL% NEQ 0 ( 
".\jre64\bin\java.exe" -Djava.awt.headless=true -Dzomboid.steam=1 -Dzomboid.znetlog=1 -XX:-CreateCoredumpOnCrash -XX:-OmitStackTraceInFastThrow -Xmx3072m -Djava.library.path=./win64/;./ -cp %PZ_CLASSPATH% zombie.gameStates.MainScreenState %1 %2

)

PAUSE
