package com.dianping.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.dianping.dto.Result;
import com.dianping.entity.Voucher;
import com.dianping.entity.VoucherOrder;
import com.dianping.mapper.VoucherOrderMapper;
import com.dianping.service.ISeckillVoucherService;
import com.dianping.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dianping.utils.RedisIdWorker;
import com.dianping.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    String queueName = "stream.orders";


    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private ISeckillVoucherService  seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    //秒杀脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
//    //队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //初始化秒杀订单执行器
    @PostConstruct
    private void init()
    {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //执行秒杀订单的线程
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true)
            {
                try {
                    //获取消息队列中的订单 XREADGROUP　GROUP 组名 流名称 COUNT 0 BLOCK 1000 STREAMS 阻塞时间
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断有没有订单
                    if(list == null || list.isEmpty())
                    {
                        //失败了就继续循环
                        continue;
                    }
                    //取出订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //成功执行订单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("秒杀订单执行器异常",e);
                    //处理pending-list中的订单
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (true)
        {
            try {
                //获取pending-list中的订单 XREADGROUP　GROUP 组名 流名称 COUNT 0 BLOCK 1000 STREAMS 阻塞时间
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                //判断有没有订单
                if(list == null || list.isEmpty())
                {
                    //pending-list中没有订单了结束循环
                    break;
                }
                //取出订单
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                //成功执行订单
                handleVoucherOrder(voucherOrder);
                //ACK确认
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            } catch (Exception e) {
                log.error("处理pending-list的订单异常",e);
            }
        }
    }

    //    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while (true)
//            {
//                try {
//                    //获取阻塞队列中的订单
//                    VoucherOrder voucherOrder= orderTasks.take();
//                    //执行订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("秒杀订单执行器异常",e);
//                }
//            }
//        }
//    }
    //锁
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        //获取锁
        boolean islock = lock.tryLock();
            //代理对象调用自己的方法，防止死锁
        if (!islock) {
            log.error("不允许重复下单");
            return ;
        }
        try {
            //真正下单
            IVoucherOrderService proxy = applicationContext.getBean(IVoucherOrderService.class);
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }

    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //设置订单信息
        long orderId = redisIdWorker.nextId("order:");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userId.toString(),
                String.valueOf(orderId)
        );
        //判断结果是否为0
        int r =result.intValue();
        if(result != 0)
        {
            //不为0，没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //返回秒杀结果
        return Result.ok(orderId);


    }
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断是否可以秒杀
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now()))
//        {
//            return Result.fail("秒杀还未开始");
//        }
//        if(voucher.getEndTime().isBefore(LocalDateTime.now()))
//        {
//            return Result.fail("秒杀已经结束");
//        }
//        //判断库存是否充足
//        if(voucher.getStock()< 1)
//        {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
//        //老办法白雪SimpleRedisLock lock  = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:"+userId);
//        //获取锁
//        boolean islock = lock.tryLock();
//            //代理对象调用自己的方法，防止死锁
//        if (!islock) {
//            return Result.fail("无法重复购买");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            if (count > 0) {
                log.error("不允许重复下单");
                return ;
            }
            //扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")//set
                    .eq("voucher_id", voucherOrder.getVoucherId())//where
                    .gt("stock", 0)//防止超卖
                    //.eq("stock",voucher.getStock())
                    .update();
            if (!success) {
                log.error("库存不足");
                return ;
            }
            save(voucherOrder);
            //返回订单信
        }
}
