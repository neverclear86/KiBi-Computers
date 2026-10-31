-- ホスト呼び出しの多いもの: バッファなしの小さな書き込みと、時計の読み出し
io.stdout:setvbuf("no")
local n = 0
for i = 1, 2000 do
    io.write(".")
    if os.time() > 0 then n = n + 1 end
end
io.write("\n")
print(n)
