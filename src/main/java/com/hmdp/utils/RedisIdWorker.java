package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1672531200L;
    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 返回全局唯一ID
     *
     * @param keyPrefix 不同业务肯定基于不同的Key自增
     * @return
     */
    public long nextId(String keyPrefix) {
        // 生成时间戳
        LocalDateTime nowTime = LocalDateTime.now();
        long nowSecond = nowTime.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 生成序列号
        /**
         * Redis单个Key的自增长对应的数值是有上限的，上限是2^64，虽然很大，但归根结底也是有上限，
         * 万一超过了怎么办？
         * 而且我们的全局唯一ID生成策略里面，真正用来记录序列号的只有32位。Redis的key自增长
         * 对应的数值上限是64位，key自增的数值超过64位很难，超过32还是有一定可能的哦。将来
         * 如果永远是同一个key，key自增的数值超过32位，序列号这部分32位就存不下key自增的数值了。
         * 所以说哪怕是同一个业务也不能使用同一个Key！
         *
         * 解决方案：在业务前缀后边拼上时间戳，比如说：inc:order:20231218，那么代表20231218这一天
         * 下的订单的id就会以这个inc:order:20231218作为key去自增。也就是说这个key自增的上限就是这
         * 一天的下的订单量，一天的订单量怎么可不可能超过2^32！
         * 这样做还有好处，比如我想看某天的订单量直接看这个key的值就行了，即还有统计的好处
         *
         */
        String dateStr = nowTime.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        String key = "icr:" + keyPrefix + ":" + dateStr;
        long count = stringRedisTemplate.opsForValue().increment(key);
        // 拼接时间戳和序列号
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime basicTime = LocalDateTime.of(2023, 1, 1, 0, 0, 0);
        long basicSecond = basicTime.toEpochSecond(ZoneOffset.UTC);
        // basicSecond = 1672531200
        System.out.println("basicSecond = " + basicSecond);
    }
}
