function kps {
    $opt = if ($args.Count) { [string]$args[0] } else { "" }
    $dir = '__KPS_SERVER_DIRECTORY__'
    $logFile = Join-Path $dir "server.log"
    $serverScript = Join-Path $dir "server.py"

    if (-not (Test-Path -LiteralPath $serverScript)) {
        Write-Host "kps: server.py not found in $dir" -ForegroundColor Red
        return
    }

    function Get-KpsServerProcs {
        Get-CimInstance Win32_Process | Where-Object {
            $_.Name -in @("python.exe", "pythonw.exe", "uv.exe") -and
            $_.CommandLine -and
            $_.CommandLine.Contains($serverScript)
        }
    }

    function Stop-KpsServer {
        $procs = @(Get-KpsServerProcs)
        foreach ($process in $procs) {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
        }
        if ($procs.Count) {
            Write-Host "server stopped" -ForegroundColor Yellow
        }
        Start-Sleep -Milliseconds 300
    }

    function Start-KpsServer {
        $uv = Get-Command uv -CommandType Application -ErrorAction SilentlyContinue
        if (-not $uv) {
            Write-Host "kps: uv was not found; install uv and ensure it is on PATH" -ForegroundColor Red
            return
        }

        $quotedServerScript = '"' + $serverScript + '"'
        Start-Process -FilePath $uv.Source -ArgumentList @("run", $quotedServerScript) -WorkingDirectory $dir -WindowStyle Hidden
        Write-Host "server started (logs: kps -l)" -ForegroundColor Green
    }

    switch ($opt) {
        "-o" {
            if (@(Get-KpsServerProcs).Count) { Stop-KpsServer } else { Start-KpsServer }
        }
        "-r" { Stop-KpsServer; Start-KpsServer }
        "-s" {
            if (@(Get-KpsServerProcs).Count) {
                Write-Host "server: RUNNING" -ForegroundColor Green
            } else {
                Write-Host "server: stopped" -ForegroundColor Red
            }
        }
        "-l" {
            if (-not (Test-Path -LiteralPath $logFile)) {
                New-Item -ItemType File -Path $logFile -ErrorAction Stop | Out-Null
            }
            Write-Host "streaming $logFile (Ctrl+C to stop)" -ForegroundColor Cyan
            Get-Content -Path $logFile -Tail 50 -Wait
        }
        default {
            Write-Host "usage: kps -o (toggle) | -r (restart) | -s (status) | -l (stream logs)"
        }
    }
}
