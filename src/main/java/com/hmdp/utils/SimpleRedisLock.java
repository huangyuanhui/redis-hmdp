package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 基于Redis实现非阻塞式分布式锁
 */
public class SimpleRedisLock implements ILock {

    /**
     * key的统一前缀
     */
    private static final String KEY_PREFIX = "lock:";

    /**
     * 获取机器的UUID
     */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private StringRedisTemplate stringRedisTemplate;
    /**
     * 锁名
     */
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
         * 线程标识：UUID + 线程ID
         */
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean isSuccess = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);
        // 返回获取锁是否成功结果
        return BooleanUtil.isTrue(isSuccess);
    }

    @Override
    public void unlock() {
        String key = KEY_PREFIX + name;
        // 获取线程ID
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取缓存的线程标识
        String cacheThreadId = stringRedisTemplate.opsForValue().get(key);
        // 判断线程标识是否一致
        if (threadId.equals(cacheThreadId)) {
            // 释放锁
            stringRedisTemplate.delete(key);
        }
    }
}
