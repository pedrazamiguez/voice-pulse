local popped = redis.call('ZPOPMIN', KEYS[1], 1)
if not popped or #popped == 0 then
    return nil
end

local member = popped[1]
local callId = string.match(member, '([^:]+)$')
if not callId then
    return nil
end

local payloadJson = redis.call('HGET', KEYS[3], callId)
redis.call('HDEL', KEYS[2], callId)
redis.call('HDEL', KEYS[3], callId)

return payloadJson
