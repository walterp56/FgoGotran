@echo off
setlocal
cd /d "%~dp0.."
set "PUBLISH_ARGS="
if defined FGOGOTRAN_CATALOG_S3_URI set PUBLISH_ARGS=%PUBLISH_ARGS% -S3Uri "%FGOGOTRAN_CATALOG_S3_URI%"
if defined FGOGOTRAN_AWS_CLI set PUBLISH_ARGS=%PUBLISH_ARGS% -AwsCli "%FGOGOTRAN_AWS_CLI%"
if defined FGOGOTRAN_NODE set PUBLISH_ARGS=%PUBLISH_ARGS% -Node "%FGOGOTRAN_NODE%"

echo Generating local native-assets release...
echo   1. validates story_assets\cloud\host\announcements.json
echo   2. writes release\native-assets\catalog\catalog.json
echo   3. prints the S3 upload command (no automatic upload)
echo.
echo   Add -Upload to upload automatically.
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-announcements-existing.ps1" %PUBLISH_ARGS% %*
set "EXIT_CODE=%ERRORLEVEL%"
echo.
if not "%EXIT_CODE%"=="0" (
  echo publish failed with exit code %EXIT_CODE%.
) else (
  echo publish finished successfully.
)
echo.
pause
exit /b %EXIT_CODE%
