-- テーブル: 乱数の配列を作って並べ替え、連想配列に数える
local seed = 12345
local function rand()
    seed = (seed * 1103515245 + 12345) % 2147483648
    return seed
end
local t = {}
for i = 1, 30000 do t[i] = rand() % 100000 end
table.sort(t)
local counts = {}
for i = 1, #t do
    local k = t[i] % 1000
    counts[k] = (counts[k] or 0) + 1
end
local distinct = 0
for _ in pairs(counts) do distinct = distinct + 1 end
print(t[1], t[#t], distinct)
