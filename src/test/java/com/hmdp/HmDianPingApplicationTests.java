package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    public void testSaveShop() {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicExpire(CACHE_SHOP_KEY + 1L, shop, 30L, TimeUnit.SECONDS);
    }


    private ExecutorService es = Executors.newFixedThreadPool(500);

    /**
     * 测试并发情况下生成ID的性能和生成ID的值的情况（是否唯一、是否递增）
     */
    @Test
    public void testIdWorker() throws InterruptedException {
        // 300个任务计算300次
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            // 生成100个id
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            // 每一个任务执行完计算一次
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        // 提交执行300个任务，总的id数=300*100
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        // (end - begin) = 7027
        System.out.println("(end - begin) = " + (end - begin));
    }

}
