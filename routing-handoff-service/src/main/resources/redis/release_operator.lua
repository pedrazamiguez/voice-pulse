local presenceKey = KEYS[1]
local heartbeatKey = KEYS[2]
local operatorId = ARGV[1]
local nextStatus = ARGV[2]
local releasedAt = ARGV[3]
local ttlMillis = tonumber(ARGV[4])

if redis.call('EXISTS', heartbeatKey) == 0 or redis.call('EXISTS', presenceKey) == 0 then
    for i = 3, #KEYS do
        redis.call('SREM', KEYS[i], operatorId)
    end
    return 0
end

redis.call('HSET', presenceKey,
    'status', nextStatus,
    'activeCallId', '',
    'lastHeartbeatAt', releasedAt)
redis.call('PEXPIRE', presenceKey, ttlMillis)
redis.call('SET', heartbeatKey, releasedAt, 'PX', ttlMillis)

local skillsCsv = redis.call('HGET', presenceKey, 'skills') or ''
local paddedSkills = ',' .. skillsCsv .. ','

for i = 3, #KEYS do
    local skillName = ARGV[2 + i]
    if nextStatus == 'AVAILABLE' and string.find(paddedSkills, ',' .. skillName .. ',', 1, true) ~= nil then
        redis.call('SADD', KEYS[i], operatorId)
    else
        redis.call('SREM', KEYS[i], operatorId)
    end
end

return 1
