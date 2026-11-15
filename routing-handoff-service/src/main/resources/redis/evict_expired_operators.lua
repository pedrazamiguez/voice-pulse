local expiredCount = tonumber(ARGV[1])
if not expiredCount or expiredCount <= 0 then
    return 0
end

local removedTotal = 0
for i = 1, expiredCount do
    removedTotal = removedTotal + redis.call('DEL', KEYS[i])
end

for keyIdx = expiredCount + 1, #KEYS do
    local setKey = KEYS[keyIdx]
    for idx = 1, expiredCount, 500 do
        local batchEnd = math.min(idx + 499, expiredCount)
        removedTotal = removedTotal + redis.call('SREM', setKey, unpack(ARGV, 1 + idx, 1 + batchEnd))
    end
end

return removedTotal
