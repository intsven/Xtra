# PowerShell script to build and install the app
$env:GRADLE_USER_HOME = "D:\Programming\Android\Xtra\.gradle_home"
.\gradlew.bat assembleRelease
if ($LASTEXITCODE -eq 0) {
    adb install -r app/build/outputs/apk/release/app-release.apk
} else {
    Write-Host "Build failed, installation skipped." -ForegroundColor Red
}