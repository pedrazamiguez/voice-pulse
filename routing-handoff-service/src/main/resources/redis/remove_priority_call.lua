local callId = ARGV[1]
local indexEntry = redis.call('HGET', KEYS[1], callId)
local payloadJson = redis.call('HGET', KEYS[2], callId)

redis.call('HDEL', KEYS[1], callId)
redis.call('HDEL', KEYS[2], callId)

if not indexEntry then
    return nil
end

local sep = string.find(indexEntry, '|', 1, true)
if sep then
    local skillName = string.sub(indexEntry, 1, sep - 1)
    local member = string.sub(indexEntry, sep + 1)
    for i = 3, #KEYS do
        if ARGV[i - 1] == skillName then
            redis.call('ZREM', KEYS[i], member)
            break
        end
    end
end

return payloadJson
