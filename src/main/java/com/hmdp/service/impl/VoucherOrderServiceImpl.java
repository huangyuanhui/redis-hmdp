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
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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
        // 创建订单
        return createVoucherOrder(voucherId);
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
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        /**
         * 悲观锁不建议加在方法上，加在方法上意味着所有线程都是同一把锁：this，那么这个创建订单方法就变成
         * 串行执行了，性能就变得很差了，所谓的一人一单，是指同一个用户来了我们才去判断并发安全问题，如果不是
         * 同一个用户，就不需要去加锁。因此，可以得出两个结论：一是悲观锁不应该加在方法上，二是锁的范围因该限定
         * 为同一用户！
         * 因此：锁应该是当前用户，我们可以把用户ID作为锁，把锁的范围缩小，也就是说同一个用户加同一把锁，
         * 不同用户加不同的锁。
         */
        // 一人一单：查询订单，判断该用户是否已经抢购过该秒杀特价券
        Long userId = UserHolder.getUser().getId();
        /**
         * 使用Long.toString()不能保证Long类型的同数值转成的字符串是一致的，不能保证一致，那么这个锁就不是同一把锁。
         * 为了保证Long类型的同数值转成的字符串是一致的，使用要使用String.intern()方法，作用是返回字符串对象的规范表示。
         */
        synchronized (userId.toString().intern()) {
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
}
