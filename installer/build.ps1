$ErrorActionPreference = "Stop"

$compiler = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler)) {
    $compiler = Join-Path $env:WINDIR "Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path -LiteralPath $compiler)) {
    throw "The .NET Framework C# compiler (csc.exe) is required to build KpsInstaller.exe."
}

$installerDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourcePath = Join-Path $installerDirectory "KpsInstaller.cs"
$profileScriptPath = Join-Path $installerDirectory "KpsProfile.ps1"
$outputPath = Join-Path $installerDirectory "KpsInstaller.exe"
$resourceArgument = $profileScriptPath + ",KittyPC.KpsProfile.ps1"

& $compiler /nologo /target:winexe /out:$outputPath /reference:System.Windows.Forms.dll /resource:$resourceArgument $sourcePath
if ($LASTEXITCODE -ne 0) {
    throw "KpsInstaller.exe build failed with exit code $LASTEXITCODE."
}

Write-Host "Built $outputPath"
