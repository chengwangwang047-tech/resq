-- KEYS[1]: 候补 ZSet Key
-- KEYS[2]: 递增序号 Key
-- ARGV[1]: 用户 ID
-- ARGV[2]: 过期秒数

local waitlistKey = KEYS[1]
local sequenceKey = KEYS[2]
local userId = ARGV[1]
local ttl = tonumber(ARGV[2])

if not ttl or ttl < 1 or ttl % 1 ~= 0 then
    return redis.error_reply("Invalid TTL")
end

-- 1. 已经加入过候补，不改变原有排名
if redis.call("ZSCORE", waitlistKey, userId) then
    return 0
end

-- 2. 为新用户分配递增序号
local sequence = redis.call("INCR", sequenceKey)

-- 3. 按序号加入候补队列
redis.call("ZADD", waitlistKey, "NX", sequence, userId)

-- 4. 设置候补数据的过期时间
redis.call("EXPIRE", waitlistKey, ttl)
redis.call("EXPIRE", sequenceKey, ttl)

return 1