-- 获取锁中的线程标识
local id = redis.call('GET', KEYS[1])
-- 比较线程标识是否一致
if (id == ARGV[1]) then
    -- 一致释则放锁
    return redis.call('DEL', KEYS[1])
end
-- 不一致则返回0
return 0
