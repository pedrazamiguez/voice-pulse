local targetQueueKey = KEYS[1]
local callIndexKey = KEYS[2]
local itemsKey = KEYS[3]
local sequenceKey = KEYS[4]

local callId = ARGV[1]
local targetSkill = ARGV[2]
local score = tonumber(ARGV[3])
local timestampPrefix = ARGV[4]
local payloadJson = ARGV[5]
local maxCapacity = tonumber(ARGV[6])

local existing = redis.call('HGET', callIndexKey, callId)
local existingSkill = nil
local existingMember = nil
if existing then
    local sep = string.find(existing, '|', 1, true)
    if sep then
        existingSkill = string.sub(existing, 1, sep - 1)
        existingMember = string.sub(existing, sep + 1)
    end
end

local currentDepth = tonumber(redis.call('ZCARD', targetQueueKey))
if (not existing or existingSkill ~= targetSkill) and currentDepth >= maxCapacity then
    return -1
end

if existingSkill and existingMember then
    for i = 5, #KEYS do
        if ARGV[2 + i] == existingSkill then
            redis.call('ZREM', KEYS[i], existingMember)
            break
        end
    end
end

local seq = redis.call('INCR', sequenceKey)
local member = timestampPrefix .. ':' .. string.format('%015d', seq) .. ':' .. callId

redis.call('ZADD', targetQueueKey, score, member)
redis.call('HSET', callIndexKey, callId, targetSkill .. '|' .. member)
redis.call('HSET', itemsKey, callId, payloadJson)

local rank = redis.call('ZRANK', targetQueueKey, member)
return tonumber(rank) + 1
