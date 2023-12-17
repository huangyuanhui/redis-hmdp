package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //Shop shop = cacheClient.queryByPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 缓存击穿：分布式锁
        //Shop shop = queryWithMutex(id);
        //Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 缓存击穿：逻辑过期
        //Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryByLogicExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 缓存重建线程池
     */
    private static final ExecutorService CACHE_REBUILD_ES = Executors.newFixedThreadPool(10);

    /**
     * 缓存击穿：逻辑过期方案
     *
     * @param id
     * @return
     */
    private Shop queryWithLogicalExpire(Long id) {
        // 从Redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断商铺是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 不存在，说明不是热点商铺，直接返回空
            return null;
        }
        // 存在，判断逻辑过期时间
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        if (LocalDateTime.now().isBefore(redisData.getExpireTime())) {
            // 没过期，直接返回
            return shop;
        }
        // 过期，需要缓存重建
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
            // 获取锁成功，开启独立线程重建缓存
            CACHE_REBUILD_ES.submit(() -> {
                try {
                    // 注意DoubleCheck，因为其他线程可能重建了缓存
                    String redisDataJsonDoubleCheck = stringRedisTemplate.opsForValue().get(key);
                    RedisData redisDataDoubleCheck = JSONUtil.toBean(redisDataJsonDoubleCheck, RedisData.class);
                    if (LocalDateTime.now().isAfter(redisDataDoubleCheck.getExpireTime())) {
                        // DoubleCheck缓存中还是旧数据，重建缓存
                        this.saveShop2Redis(id, 10L);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放互斥锁
                    unlock(lockKey);
                }
            });
        }
        // 获取锁失败，直接返回过期商铺信息
        return shop;
    }

    /**
     * 缓存击穿：分布式锁方案
     *
     * @param id
     * @return
     */
    private Shop queryWithMutex(Long id) {
        // 从Redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断商铺是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断是否命中空值
        if (shopJson != null) {
            // 命中空值, 返回错误信息
            return null;
        }
        // 不存在，实现缓存重建
        // 尝试获取分布式锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            Boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 获取分布式锁失败，休眠一会再重试
                Thread.sleep(50L);
                queryWithMutex(id);
            }
            // 获取分布式锁成功，注意DoubleCheck，可能其他线程重建好缓存了
            String doubleCheckShopJson = stringRedisTemplate.opsForValue().get(key);
            if (doubleCheckShopJson == null) {
                // 获取成功，重建缓存
                shop = getById(id);
                // 判断商铺是否存在
                if (shop == null) {
                    // 不存在，缓存空对象
                    stringRedisTemplate.opsForValue()
                            .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    // 返回错误信息
                    return null;
                }
                // 存在，缓存商铺信息到Redis，添加超时剔除
                stringRedisTemplate.opsForValue()
                        .set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            } else {
                // DoubleCheck其他线程重建好缓存了
                shop = JSONUtil.toBean(doubleCheckShopJson, Shop.class);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unlock(lockKey);
        }
        // 返回商铺信息
        return shop;
    }

    /**
     * 缓存穿透代码
     *
     * @param id
     * @return
     */
    private Shop queryWithPassThrough(Long id) {
        // 从Redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断商铺是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断是否命中空值
        if (shopJson != null) {
            // 命中空值, 返回错误信息
            return null;
        }
        // 不存在，根据id从数据库获取
        Shop shop = getById(id);
        // 判断商铺是否存在
        if (shop == null) {
            // 不存在，缓存空对象
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 存在，缓存商铺信息到Redis，添加超时剔除
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回商铺信息
        return shop;
    }

    /**
     * 尝试获取分布式锁
     *
     * @return
     */
    private Boolean tryLock(String key) {
        // 过期时间一般比你业务执行时间长一点，这里是缓存重建业务时间
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放分布式锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    /**
     * 缓存预热
     */
    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 查询商铺数据
        Shop shop = getById(id);
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺ID不能为空！");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        // 返回成功
        return Result.ok();
    }
}
