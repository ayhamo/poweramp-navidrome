# Builds the APK inside Docker (nothing installed on Windows).
#   powershell -File build.ps1            debug:   app\build\outputs\apk\debug\app-debug.apk
#   powershell -File build.ps1 -Release   release: app\build\outputs\apk\release\app-release.apk,
#                                         signed with keystore\poweramp-navidrome.jks (gitignored; backup in ayhamo/keys-backup)
# Volumes keep the Gradle cache and the debug signing key between builds, so updates install over the old APK.
param([string]$Task = "assembleDebug", [switch]$Release)
# Not "Stop": Windows PowerShell 5.1 turns any stderr line from docker/gradle (even warnings) into an error.
$root = $PSScriptRoot
docker image inspect poweramp-navidrome-build *> $null
if ($LASTEXITCODE -ne 0) { docker build -t poweramp-navidrome-build $root }
$signing = @()
if ($Release) {
    $Task = "assembleRelease"
    $password = (Get-Content -Raw "$root\keystore\keystore-password.txt").Trim()
    $signing = @("-e", "SIGNING_KEYSTORE=/work/keystore/poweramp-navidrome.jks", "-e", "SIGNING_PASSWORD=$password")
}
docker run --rm `
    -v "${root}:/work" `
    -v pn-gradle:/root/.gradle `
    -v pn-android:/root/.android `
    @signing `
    poweramp-navidrome-build gradle --no-daemon --console=plain $Task
exit $LASTEXITCODE
