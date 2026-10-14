@echo off
rem Starts the AIStudy backend on port 8081 for Desktop integration work.
rem 8080 is left alone because this machine runs another project there, and the
rem generated OpenAPI client silently picks up the wrong spec if the two swap.
rem Credentials come from .env.local next to this file; nothing is stored here.
setlocal
set "ENV_FILE=%~dp0.env.local"

for /f "usebackq tokens=1,* delims==" %%I in (`findstr /r /v "^# ^$" "%ENV_FILE%"`) do set "%%I=%%~J"
set "SERVER_PORT=8081"

cd /d "%~dp0..\..\..\server"
echo [run-8081] profile=%SPRING_PROFILES_ACTIVE% port=%SERVER_PORT%
call mvnw.cmd -o spring-boot:run
