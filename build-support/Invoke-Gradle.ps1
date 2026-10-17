[CmdletBinding()]
param(
    [string]$ProjectPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'android'),
    [string]$JavaHomePath = 'C:\Users\dr\AppData\Local\Android\jdk17\jdk-17.0.20.1+1',
    [string]$AndroidSdkPath = 'C:\Users\dr\AppData\Local\Android\Sdk',
    [string]$GradleExecutable = 'C:\Users\dr\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat',
    [string]$BuildOutputRoot = 'D:\app\ink-ledger-build',
    [string[]]$GradleArgs = @('--version')
)

$ErrorActionPreference = 'Stop'
foreach ($requiredPath in @($ProjectPath, (Join-Path $JavaHomePath 'bin\javac.exe'), (Join-Path $AndroidSdkPath 'platforms\android-35\android.jar'), $GradleExecutable)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required build path is missing: $requiredPath"
    }
}

$previousEnvironment = @{}
foreach ($environmentName in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'PATH')) {
    $previousEnvironment[$environmentName] = [Environment]::GetEnvironmentVariable($environmentName, 'Process')
}

$gradleExitCode = 1
try {
    $env:JAVA_HOME = $JavaHomePath
    $env:ANDROID_HOME = $AndroidSdkPath
    $env:ANDROID_SDK_ROOT = $AndroidSdkPath
    $env:PATH = (Join-Path $JavaHomePath 'bin') + ';' + (Join-Path $AndroidSdkPath 'platform-tools') + ';' + $previousEnvironment['PATH']
    Push-Location -LiteralPath $ProjectPath
    try {
        $effectiveGradleArgs = @($GradleArgs)
        if ($BuildOutputRoot -and -not ($effectiveGradleArgs | Where-Object { $_ -like '-PinkBuildRoot=*' })) {
            $effectiveGradleArgs = @('-PinkBuildRoot=' + $BuildOutputRoot.Replace('\', '/')) + $effectiveGradleArgs
        }
        & $GradleExecutable @effectiveGradleArgs
        $gradleExitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}
finally {
    foreach ($environmentName in $previousEnvironment.Keys) {
        if ($null -eq $previousEnvironment[$environmentName]) {
            Remove-Item -LiteralPath ('Env:\' + $environmentName) -ErrorAction SilentlyContinue
        }
        else {
            [Environment]::SetEnvironmentVariable($environmentName, $previousEnvironment[$environmentName], 'Process')
        }
    }
}

if ($gradleExitCode -ne 0) {
    throw "Gradle exited with code $gradleExitCode"
}
