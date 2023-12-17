package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate redisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.redisTemplate = stringRedisTemplate;
    }

    /**
     * 添加缓存
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 添加缓存（逻辑过期）
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透：缓存空对象
     *
     * @param id
     * @return
     */
    public <R, ID> R queryByPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = redisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 命中数据
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            // 命中空对象
            return null;
        }
        // 从数据库中查询
        R r = dbFallback.apply(id);
        if (r == null) {
            // 缓存空对象
            this.set(key, "", time, unit);
            return null;
        }
        // 缓存
        this.set(key, r, time, unit);
        // 返回数据
        return r;
    }

    /**
     * 缓存击穿：互斥锁
     *
     * @param id
     * @return
     */
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, long timeout, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = redisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 命中数据
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            // 命中空对象
            return null;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            if (!tryLock(lockKey)) {
                // 获取分布式锁失败，睡眠后重试
                Thread.sleep(50L);
                queryWithMutex(keyPrefix, id, type, dbFallback, timeout, unit);
            }
            // 获取分布式锁成功，先DoubleCheck
            json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                // DoubleCheck还是没有，重建缓存
                r = dbFallback.apply(id);
                if (r == null) {
                    redisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                this.set(key, r, timeout, unit);
            } else {
                // DoubleCheck已经有缓存了
                r = JSONUtil.toBean(json, type);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return r;
    }

    /**
     * 缓存重建线程池
     */
    private static final ExecutorService CACHE_REBUILD_ES = Executors.newFixedThreadPool(10);

    /**
     * 缓存击穿：逻辑过期
     *
     * @param id
     * @return
     */
    public <R, ID> R queryByLogicExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String redisDataJson = redisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(redisDataJson)) {
            // 不是热点数据，直接返回空
            return null;
        }
        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        if (LocalDateTime.now().isBefore(redisData.getExpireTime())) {
            // 缓存的热点数据未过期，返回
            return r;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
            // 获取分布式锁成功，开辟独立线程去重建缓存
            CACHE_REBUILD_ES.submit(() -> {
                try {
                    // 注意DoubleCheck，因为其他线程可能重建了缓存
                    String redisDataJsonDoubleCheck = redisTemplate.opsForValue().get(key);
                    RedisData redisDataDoubleCheck = JSONUtil.toBean(redisDataJsonDoubleCheck, RedisData.class);
                    if (LocalDateTime.now().isAfter(redisDataDoubleCheck.getExpireTime())) {
                        // DoubleCheck还是旧数据，才重建缓存
                        this.setWithLogicExpire(key, dbFallback.apply(id), time, unit);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放分布式锁
                    unlock(lockKey);
                }
            });
        }
        // 返回过期旧数据
        return r;
    }

    /**
     * 获取锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        redisTemplate.delete(key);
    }

}
