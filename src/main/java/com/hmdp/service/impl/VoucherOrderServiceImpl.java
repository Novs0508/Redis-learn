package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;

    //秒杀优惠券下单
    @Override
    public Result seckillVoucher(Long id) {
        //根据id查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(id);
        if (seckillVoucher == null) {
            return Result.fail("优惠券不存在");
        }
        // 判断优惠券是否在有效期内
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("优惠券开始时间未到");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("优惠券已过期");
        }
        // 判断优惠券是否已售罄
        if (seckillVoucher.getStock() <= 0) {
            return Result.fail("优惠券已售罄");
        }
        //悲观锁实现一人一单
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()){
            //获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createOrder(id);
        }
    }
    //创建订单方法
    @Transactional(rollbackFor = Exception.class)
    public Result createOrder(Long id) {
        //一人一单
        //判断用户是否已经下过单
        int count = query().eq("user_id", UserHolder.getUser().getId()).eq("voucher_id", id).count();
        if (count > 0) {
            return Result.fail("用户已经下过单");
        }
        // 扣减优惠券库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("id", id).gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("优惠券库存不足");
        }
        // 创建订单
        //获取订单id
        long orderId = redisIdWorker.nextId(SECKILL_STOCK_KEY,id);
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(id);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
