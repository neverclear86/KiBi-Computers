# 開発クライアント（ウィンドウタイトルが "Minecraft" で始まる java プロセス）を前面に出して撮影する。
# 使い方: pwsh -File .claude/skills/run-minecraft/scripts/screenshot.ps1 [-Out <png のパス>]
param(
    [string]$Out = "platform-neoforge-1.21.1/run/screenshots/claude-$(Get-Date -Format 'yyyyMMdd-HHmmss').png"
)
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class KibiWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
'@

$proc = Get-Process java, javaw -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -like 'Minecraft*' } |
    Select-Object -First 1
if (-not $proc) { Write-Error 'Minecraft のウィンドウが見つかりません（runClient は起動していますか？）' }

[KibiWin]::SetProcessDPIAware() | Out-Null
$hwnd = $proc.MainWindowHandle
[KibiWin]::ShowWindow($hwnd, 9) | Out-Null   # SW_RESTORE
[KibiWin]::SetForegroundWindow($hwnd) | Out-Null
Start-Sleep -Milliseconds 500

$r = New-Object KibiWin+RECT
[KibiWin]::GetWindowRect($hwnd, [ref]$r) | Out-Null
$w = $r.Right - $r.Left
$h = $r.Bottom - $r.Top
$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)

$full = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path (Get-Location) $Out }
New-Item -ItemType Directory -Force (Split-Path $full) | Out-Null
$bmp.Save($full, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output $full
