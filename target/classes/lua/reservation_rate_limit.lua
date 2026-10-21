local key = KEYS[1]
local limit = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])

if not limit or limit < 1 or limit % 1 ~= 0
        or not ttl or ttl < 1 or ttl % 1 ~= 0 then
    return redis.error_reply("Invalid rate limit arguments")
end

local count = tonumber(redis.call("GET", key) or "0")

if count >= limit then
    return 0
end

local newCount = redis.call("INCR", key)

if newCount == 1 then
    redis.call("EXPIRE", key, ttl)
end

return 1