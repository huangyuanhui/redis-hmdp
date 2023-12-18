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
import org.springframework.aop.framework.AopContext;
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
        /**
         * 存在问题：此时事务是失效的！
         * 我们是对当前是createVoucherOrder()加的事务，没有给seckillVoucher()加事务。
         * 我们知道事务要生效，其实是Spring对事务所在的VoucherOrderServiceImpl类做了动态代理，
         * 由动态代理对象去做事务的处理，而当前是createVoucherOrder()方法是VoucherOrderServiceImpl对象
         * 调用的，VoucherOrderServiceImpl对象是非代理对象，是没有事务功能的！
         * 这是我们知道的Spring事务可能失效的几种原因之一。
         *
         * 解决方案：
         * 获取VoucherOrderServiceImpl类的代理对象，动态代理对象去调用createVoucherOrder()
         */

        /**
         * 如果锁加在在createVoucherOrder方法内部，事务加在方法上，那么方法执行顺序是先释放锁，再去提
         * 交事务（@Transaction是被Spring管理的，事务的提交是在方法执行完之后，由Spring去做事务提交），
         * 那么存在就会发生这种情况的可能：线程一锁释放了，事务还未提交，线程二这个时候进来了，线程一新增的
         * 订单很有可能还未写入数据库，那么先线程二查询订单时依然不存在，那么仍然存在一个用户下多单的情况，
         * 依然存在并发安全问题！即当前锁的范围有点小，应该把整个函数锁起来，即应该在事务提交之后，再去释放锁！
         *
         * 现在我们是新获取锁，再进入创建订单的createVoucherOrder函数，当createVoucherOrder函数执行完
         * 说明新订单一定是写入数据库了，因为事务已经提交了吗，等事务提交完我再去释放锁，愿望就是说我们释放锁的
         * 这一刻我们可以确保数据库是有订单的，此时再有其他线程来，就不会有线程安全问题！
         * 所以我们只有先获取锁、再提交事务，再释放锁这样的步骤，才能确保我们线程是安全的。
         */
        Long userId = UserHolder.getUser().getId();
        synchronized (userId) {
            // 获取有事务功能的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 创建订单
            return proxy.createVoucherOrder(voucherId);
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
