@echo off
REM Pay & Plan sync server launcher. It deliberately lives outside the project folder:
REM the project path contains an "&", which Task Scheduler splits the command on.
REM Keep this file CRLF terminated or cmd.exe silently mis-parses the set lines.
set "PAYPLAN_CONFIG=C:\ProgramData\PayAndPlan\config.json"
set "TEMP=C:\tmp\jt"
set "TMP=C:\tmp\jt"
if not exist "C:\tmp\jt" mkdir "C:\tmp\jt"
cd /d "C:\tmp\Pay&Plan\server"
echo [%date% %time%] starting >> "C:\ProgramData\PayAndPlan\server.log"
"C:\Program Files\nodejs\node.exe" --experimental-sqlite "C:\tmp\Pay&Plan\server\src\index.js" >> "C:\ProgramData\PayAndPlan\server.log" 2>&1
echo [%date% %time%] exited with %ERRORLEVEL% >> "C:\ProgramData\PayAndPlan\server.log"
