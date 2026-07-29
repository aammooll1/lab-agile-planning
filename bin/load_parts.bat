@echo off
rem
rem Wrapper for the Windchill Part Loader (Windows method server hosts).
rem
rem Run on the Windchill server as the Windchill service account. The windchill command
rem sets up the codebase classpath the utility expects.
rem
rem Usage:
rem   load_parts.bat --dry-run
rem   load_parts.bat
rem

setlocal

if "%WT_HOME%"=="" (
    echo WT_HOME is not set - open a Windchill shell first.
    exit /b 3
)

if "%LOAD_DIR%"=="" set LOAD_DIR=%WT_HOME%\loadFiles
if "%CONFIG%"==""   set CONFIG=%LOAD_DIR%\partloader.properties
if "%PARTS%"==""    set PARTS=%LOAD_DIR%\parts.csv
if "%BOM%"==""      set BOM=%LOAD_DIR%\bom.csv
if "%WC_USER%"==""  set WC_USER=wcadmin

echo Windchill home : %WT_HOME%
echo Config         : %CONFIG%
echo Parts file     : %PARTS%
echo BOM file       : %BOM%
echo User           : %WC_USER%
echo.

call "%WT_HOME%\bin\windchill.bat" com.plm.tools.partloader.PartLoaderMain ^
    --config "%CONFIG%" ^
    --parts  "%PARTS%" ^
    --bom    "%BOM%" ^
    --user   "%WC_USER%" ^
    %*

exit /b %ERRORLEVEL%
