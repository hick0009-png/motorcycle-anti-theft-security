# setup_env.ps1 - Environment Configuration Script for Motorcycle Anti-Theft Project

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\ASUS\AppData\Local\Android\Sdk"

# Append to PATH for current session
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\tools;C:\Users\ASUS\AppData\AndroidCLI;$env:PATH"

Write-Host "==========================================================" -ForegroundColor Green
Write-Host " Motorcycle Anti-Theft Project Environment Configured" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "JAVA_HOME    : $env:JAVA_HOME"
Write-Host "ANDROID_HOME : $env:ANDROID_HOME"

Write-Host "`nChecking Java Version:" -ForegroundColor Yellow
& "$env:JAVA_HOME\bin\java.exe" -version

Write-Host "`nChecking Android CLI Version:" -ForegroundColor Yellow
& "C:\Users\ASUS\AppData\AndroidCLI\android.exe" info
