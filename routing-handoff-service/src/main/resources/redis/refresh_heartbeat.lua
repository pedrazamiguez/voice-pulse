local presenceKey = KEYS[1]
local heartbeatKey = KEYS[2]

if redis.call('EXISTS', heartbeatKey) == 0 or redis.call('EXISTS', presenceKey) == 0 then
    return 0
end

local ttlMillis = tonumber(ARGV[2])
redis.call('HSET', presenceKey, 'lastHeartbeatAt', ARGV[1])
redis.call('PEXPIRE', presenceKey, ttlMillis)
redis.call('SET', heartbeatKey, ARGV[1], 'PX', ttlMillis)

return 1
