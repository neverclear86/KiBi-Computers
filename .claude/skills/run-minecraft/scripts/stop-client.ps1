# 開発クライアント（ウィンドウタイトルが "Minecraft" で始まる java プロセス）を終了する。
$procs = Get-Process java, javaw -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -like 'Minecraft*' }
if (-not $procs) {
    Write-Output 'Minecraft のクライアントは起動していません'
    exit 0
}
$procs | Stop-Process -Force
Write-Output "終了しました: $($procs.Id -join ', ')"
