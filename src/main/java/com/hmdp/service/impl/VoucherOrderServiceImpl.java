package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.VOUCHER_ORDER_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 根据id查询秒杀优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 判断秒杀是否开始或结束
        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀未开始！");
        }
        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀已结束！");
        }
        // 判断库存
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("秒杀卷库存不足！");
        }

        // 限定锁的范围：锁的名称根据业务，即同一个用户下单加锁避免一人下多单，所以key设置为：order:userID
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        // SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("order:" + userId);
        // 获取分布式锁
        // boolean isLock = simpleRedisLock.tryLock(10L);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取失败，根据业务需求返回错误信息或重试
            return Result.fail("一个只允许下一单！");
        }
        // 获取锁成功，创建订单
        try {
            // 获取有事务功能的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 创建订单
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            // simpleRedisLock.unlock();
            lock.unlock();
        }
    }

    /**
     * 创建订单
     *
     * @param voucherId
     * @return
     */
    /**
     * 事务范围只需要更新数据库的范围，即减库存和新增订单，查询不需要事务，所以加在创建订单这里就行
     */
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {

        // 一人一单：查询订单，判断该用户是否已经抢购过该秒杀特价券
        Long userId = UserHolder.getUser().getId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            // 用户下过单则不能再下单
            return Result.fail("用户已经购买过一次！");
        }
        // 扣减库存：update table set stock = stock -1 where voucher_id = 2 and stock > 0;
        boolean isSuccess = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!isSuccess) {
            return Result.fail("扣减库存失败！");
        }
        // 创建订单存入数据库
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId(VOUCHER_ORDER_KEY);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 返回订单ID
        return Result.ok(orderId);
    }
}
