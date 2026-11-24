@echo off
setlocal
cd /d "%~dp0.."
set "PUBLISH_ARGS="
if defined FGOGOTRAN_S3_URI set PUBLISH_ARGS=%PUBLISH_ARGS% -S3Uri "%FGOGOTRAN_S3_URI%"
if defined FGOGOTRAN_CLOUDFRONT_DISTRIBUTION_ID set PUBLISH_ARGS=%PUBLISH_ARGS% -CloudFrontDistributionId "%FGOGOTRAN_CLOUDFRONT_DISTRIBUTION_ID%"
if defined FGOGOTRAN_AWS_CLI set PUBLISH_ARGS=%PUBLISH_ARGS% -AwsCli "%FGOGOTRAN_AWS_CLI%"

echo release-story options:
echo   Set FGOGOTRAN_S3_URI and FGOGOTRAN_CLOUDFRONT_DISTRIBUTION_ID to publish and invalidate automatically.
echo   Pass -ChapterId event-80639 -Version 2026.11.24.1 to name the release explicitly.
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0release-story.ps1" %PUBLISH_ARGS% %*
set "EXIT_CODE=%ERRORLEVEL%"
echo.
if not "%EXIT_CODE%"=="0" (
  echo release-story failed with exit code %EXIT_CODE%.
) else (
  echo release-story finished successfully.
)
echo.
pause
exit /b %EXIT_CODE%
