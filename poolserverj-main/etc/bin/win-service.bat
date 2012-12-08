@echo off

set J_HOME=C:\Java\jdk1.6.0_25
set MIN_HEAP=16
set MAX_HEAP=256

rem Set to 'Info' for normal use, 'Debug' if you have problems
set PR_LOG_LEVEL=Debug


if "%OS%" == "Windows_NT" setlocal
set CURRENT_DIR=%cd%
cd ..
set SERVICE_HOME=%cd%
:gotHome
set EXECUTABLE=%SERVICE_HOME%\bin\PoolServerJService.exe

if exist "%EXECUTABLE%" goto okHome
echo The %EXECUTABLE% was not found...
echo The SERVICE_HOME environment variable is not defined correctly.
echo This environment variable is needed to run this program
goto end


:okHome
set SERVICE_NAME=PoolServerJService
set PR_DISPLAYNAME=PoolServerJ

if "%1" == "" goto displayUsage

if %1 == install goto doInstall
if %1 == remove goto doRemove
if %1 == uninstall goto doRemove
echo Unknown parameter "%1"
:displayUsage
echo.
echo Usage: win-service.bat install/remove [poolserverj.properties]
goto end

:doRemove
rem Remove the service
"%EXECUTABLE%" //DS//%SERVICE_NAME%
echo The service '%SERVICE_NAME%' has been removed
goto end



:doInstall
rem Install the service
echo Installing the service '%SERVICE_NAME%' ...
echo Using SERVICE_HOME:    %SERVICE_HOME%
echo Using JAVA_HOME:        %J_HOME%

rem Use the environment variables as an example
rem Each command line option is prefixed with PR_

set PR_DESCRIPTION=Bitcoin Mining Pool Server
set PR_INSTALL=%EXECUTABLE%
set PR_LOGPATH=%SERVICE_HOME%\log
set PR_CLASSPATH=%SERVICE_HOME%\bin\poolserverj.jar;%SERVICE_HOME%\lib\*;%SERVICE_HOME%\lib\lib_non-maven\*;%SERVICE_HOME%\lib\plugins\*
set PR_LOGPREFIX=PoolServerJ_service.log

REM Set the server jvm from J_HOME
set PR_JVM=%J_HOME%\jre\bin\server\jvm.dll
if exist "%PR_JVM%" goto foundJvm
set PR_JVM=%J_HOME%jre\bin\server\jvm.dll
if exist "%PR_JVM%" goto foundJvm
rem Set the client jvm from J_HOME
set PR_JVM=%J_HOME%jre\bin\client\jvm.dll
if exist "%PR_JVM%" goto foundJvm
set PR_JVM=%J_HOME%\jre\bin\client\jvm.dll
if exist "%PR_JVM%" goto foundJvm
set PR_JVM=auto
:foundJvm
echo Using JVM:              %PR_JVM%

rem --logLevel=%PR_LOG_LEVEL% --LogJniMessages=1 --JvmMs=%MIN_HEAP% --JvmMx=%MAX_HEAP% ++JvmOptions=-server

set MAIN_CLASS=com.shadworld.poolserver.servicelauncher.PoolServerService

"%EXECUTABLE%" //IS//%SERVICE_NAME% --Install="%EXECUTABLE%" --LogJniMessages=1 --JavaHome="%J_HOME%" --Jvm="%PR_JVM%" --StartMode=jvm --StartClass=%MAIN_CLASS% --StartMethod=windowsService --StartParams=start;%2 --StopMode=jvm --StopClass=%MAIN_CLASS% --StopMethod=windowsService --StopParams=stop --StopTimeout=60 --StdOutput=auto --StdError=auto --StartPath="%SERVICE_HOME%/bin" --StopTimeout=60
if not errorlevel 1 goto installed
echo Failed installing '%SERVICE_NAME%' service
goto end

:installed
rem Clear the environment variables. They are not needed any more.
set PR_DISPLAYNAME=
set PR_DESCRIPTION=
set PR_INSTALL=
set PR_LOGPATH=
set PR_CLASSPATH=
set PR_JVM=
set PR_LOG_LEVEL=
echo The service '%SERVICE_NAME%' has been installed.


:end
cd %CURRENT_DIR%
