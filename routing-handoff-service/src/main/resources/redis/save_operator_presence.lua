local presenceKey = KEYS[1]
local heartbeatKey = KEYS[2]
local operatorId = ARGV[1]
local ttlMillis = tonumber(ARGV[7])
local isAvailable = ARGV[8] == '1'
local skillCount = tonumber(ARGV[9])

redis.call('HSET', presenceKey,
    'operatorId', operatorId,
    'displayName', ARGV[2],
    'status', ARGV[3],
    'skills', ARGV[4],
    'activeCallId', ARGV[5],
    'lastHeartbeatAt', ARGV[6])
redis.call('PEXPIRE', presenceKey, ttlMillis)
redis.call('SET', heartbeatKey, ARGV[6], 'PX', ttlMillis)

for i = 1, skillCount do
    local operatorsSetKey = KEYS[2 + i]
    local availableSetKey = KEYS[2 + skillCount + i]
    if ARGV[9 + i] == '1' then
        redis.call('SADD', operatorsSetKey, operatorId)
        if isAvailable then
            redis.call('SADD', availableSetKey, operatorId)
        else
            redis.call('SREM', availableSetKey, operatorId)
        end
    else
        redis.call('SREM', operatorsSetKey, operatorId)
        redis.call('SREM', availableSetKey, operatorId)
    end
end

return 1
