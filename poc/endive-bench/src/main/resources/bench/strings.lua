-- 文字列: 組み立て、書式、パターンマッチ
local parts = {}
for i = 1, 10000 do
    parts[#parts + 1] = string.format("%05d:%s", i, ("x"):rep(i % 7))
end
local s = table.concat(parts, ",")
local n = 0
for num in s:gmatch("(%d+):x+") do n = n + tonumber(num) % 7 end
local replaced, count = s:gsub("x+", function(x) return #x end)
print(#s, n, count, #replaced)
