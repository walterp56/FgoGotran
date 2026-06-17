@echo off
setlocal
cd /d "%~dp0.."
set "PUBLISH_ARGS="
if defined FGOGOTRAN_S3_URI set PUBLISH_ARGS=%PUBLISH_ARGS% -S3Uri "%FGOGOTRAN_S3_URI%"
if defined FGOGOTRAN_CLOUDFRONT_DISTRIBUTION_ID set PUBLISH_ARGS=%PUBLISH_ARGS% -CloudFrontDistributionId "%FGOGOTRAN_CLOUDFRONT_DISTRIBUTION_ID%"
if defined FGOGOTRAN_AWS_CLI set PUBLISH_ARGS=%PUBLISH_ARGS% -AwsCli "%FGOGOTRAN_AWS_CLI%"

echo release-app options:
echo   Set FGOGOTRAN_S3_URI and FGOGOTRAN_CLOUDFRONT_DISTRIBUTION_ID to publish and invalidate automatically.
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0release-app.ps1" %PUBLISH_ARGS% %*
set "EXIT_CODE=%ERRORLEVEL%"
echo.
if not "%EXIT_CODE%"=="0" (
  echo release-app failed with exit code %EXIT_CODE%.
) else (
  echo release-app finished successfully.
)
echo.
pause
exit /b %EXIT_CODE%
