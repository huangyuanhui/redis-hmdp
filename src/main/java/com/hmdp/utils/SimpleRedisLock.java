package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 基于Redis实现非阻塞式分布式锁
 */
public class SimpleRedisLock implements ILock {

    // key的统一前缀
    private static final String KEY_PREFIX = "lock:";

    private StringRedisTemplate stringRedisTemplate;
    // 业务名
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取锁
        String key = KEY_PREFIX + name;
        /**
         * value值最好不要随便设，因为后面要判断哦，但是一般设置成一个有意义的值比较好！
         * 同一个JVM内，线程ID是唯一的
         */
        long threadId = Thread.currentThread().getId();
        String value = threadId + "";
        // 获取锁
        Boolean isSuccess = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);
        // 返回获取锁是否成功结果
        return BooleanUtil.isTrue(isSuccess);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
