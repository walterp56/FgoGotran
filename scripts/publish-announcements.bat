@echo off
setlocal
cd /d "%~dp0.."
set "PUBLISH_ARGS="
if defined FGOGOTRAN_CATALOG_S3_URI set PUBLISH_ARGS=%PUBLISH_ARGS% -S3Uri "%FGOGOTRAN_CATALOG_S3_URI%"
if defined FGOGOTRAN_AWS_CLI set PUBLISH_ARGS=%PUBLISH_ARGS% -AwsCli "%FGOGOTRAN_AWS_CLI%"
if defined FGOGOTRAN_NODE set PUBLISH_ARGS=%PUBLISH_ARGS% -Node "%FGOGOTRAN_NODE%"

echo publish-announcements options:
echo   No arguments: interactive title/body entry.
echo   -List        show current announcements.
echo   -Title ... -Body ... -Level success -Date 2026-11-02
echo   -Remove ^<id^>  remove one announcement.
echo   -NoUpload    generate local JSON/catalogue only.
echo   -Verify      query the public API after upload.
echo.
echo   Optional env: FGOGOTRAN_CATALOG_S3_URI, FGOGOTRAN_AWS_CLI, FGOGOTRAN_NODE
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-announcements.ps1" %PUBLISH_ARGS% %*
set "EXIT_CODE=%ERRORLEVEL%"
echo.
if not "%EXIT_CODE%"=="0" (
  echo publish-announcements failed with exit code %EXIT_CODE%.
) else (
  echo publish-announcements finished successfully.
)
echo.
pause
exit /b %EXIT_CODE%
