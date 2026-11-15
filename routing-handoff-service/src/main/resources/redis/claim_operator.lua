local presenceKey = KEYS[1]
local heartbeatKey = KEYS[2]
local operatorId = ARGV[1]
local callId = ARGV[2]
local claimedAt = ARGV[3]
local ttlMillis = tonumber(ARGV[4])

if redis.call('EXISTS', heartbeatKey) == 0 or redis.call('EXISTS', presenceKey) == 0 then
    for i = 3, #KEYS do
        redis.call('SREM', KEYS[i], operatorId)
    end
    return 0
end

local status = redis.call('HGET', presenceKey, 'status')
if status ~= 'AVAILABLE' then
    return 0
end

redis.call('HSET', presenceKey,
    'status', 'BUSY',
    'activeCallId', callId,
    'lastHeartbeatAt', claimedAt)
redis.call('PEXPIRE', presenceKey, ttlMillis)
redis.call('SET', heartbeatKey, claimedAt, 'PX', ttlMillis)

for i = 3, #KEYS do
    redis.call('SREM', KEYS[i], operatorId)
end

return 1
