package com.dianping.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dianping.dto.Result;
import com.dianping.entity.Shop;
import com.dianping.mapper.ShopMapper;
import com.dianping.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dianping.utils.CacheClient;
import com.dianping.utils.RedisData;
import com.dianping.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private  StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 普通查询缓存和缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough("cache:shop",id,Shop.class,this::getById,30L,TimeUnit.MINUTES);

        //互斥锁
        Shop shop = queryWithMutex(id);

        //逻辑过期
//        Shop shop = queryWithLogicalExpire(id);
        //Shop shop = cacheClient.queryWithLogicalExpire("cache:shop",id,Shop.class,this::getById,10L,TimeUnit.SECONDS);
        if(shop == null){
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }
//        // 查询缓存
//        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:"+id);
//        // 判断缓存是否存在，isNotBlank只针对非null的字符串，如果是空串也会认为是存在的
//        if(StrUtil.isNotBlank(shopJson)){
//            // 存在，直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return Result.ok(shop);
//        }
//        // 判断缓存是否存在空值
//        if(shopJson != null){
//            return Result.fail("商铺不存在");
//        }
//        // 不存在，查询数据库
//        Shop shop = getById(id);
//        // 判断商铺是否存在
//        if (shop == null){
//            //防止缓存击穿，设置空值到缓存中
//            stringRedisTemplate.opsForValue().set("cache:shop:"+id, "",5, TimeUnit.MINUTES);
//            // 不存在，返回错误信息
//            return Result.fail("商铺不存在");
//        }
//        // 写入缓存
//        stringRedisTemplate.opsForValue().set("cache:shop:"+id, JSONUtil.toJsonStr(shop),30, TimeUnit.MINUTES);
//        // 返回商铺信息
//        return Result.ok(shop);
//    }
    private static final ExecutorService cache_rebuild_executor = Executors.newFixedThreadPool(10);

    //逻辑过期
//    public Shop queryWithLogicalExpire(Long id) {
//        // 查询缓存
//        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:"+id);
//        // 判断缓存是否存在，isNotBlank只针对非null的字符串，如果是空串也会认为是存在的
//        if(StrUtil.isBlank(shopJson)){
//            // 存在，直接返回
//            return null;
//        }
//        //命中逻辑是否过期
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        JSONObject data = (JSONObject) redisData.getData();
//        Shop shop = JSONUtil.toBean(data, Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//
//        if(expireTime.isAfter(LocalDateTime.now()))
//        {
//            return shop;
//        }
//
//        String lockKey = "lock:shop:"+id;
//        boolean islock = trylock(lockKey);
//
//        if(islock){
//            if(expireTime.isAfter(LocalDateTime.now()))
//            {
//                return shop;
//            }
//            cache_rebuild_executor.submit(() -> {
//                try {
//                    this.saveShoptoRedis(id,20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    unlock(lockKey);
//                }
//            });
//        }
//        return shop;
//    }
    //互斥锁
    public Shop queryWithMutex(Long id) {
        // 查询缓存
        String shopJson1 = stringRedisTemplate.opsForValue().get("cache:shop:"+id);
        // 判断缓存是否存在，isNotBlank只针对非null的字符串，如果是空串也会认为是存在的
        if(StrUtil.isNotBlank(shopJson1)){
            // 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson1, Shop.class);
            return shop;
        }
        // 判断缓存是否存在空值
        if(shopJson1 != null){
            return null;
        }
        // 尝试获取锁
        String lockKey = "lock:shop:"+id;
        Shop shop = null;
        try {
            boolean islock = trylock(lockKey);
            // 判断是否获取到锁
            if(!islock) {
                // 失败休眠,重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 成功，查询数据库
            // 不存在，查询数据库
            // 查询缓存
            String shopJson2 = stringRedisTemplate.opsForValue().get("cache:shop:"+id);
            // 判断缓存是否存在，isNotBlank只针对非null的字符串，如果是空串也会认为是存在的
            if(StrUtil.isNotBlank(shopJson2)){
                // 存在，直接返回
                return JSONUtil.toBean(shopJson2, Shop.class);
            }
            // 判断缓存是否存在空值
            if(shopJson2 != null){
                return null;
            }
            shop = getById(id);
            Thread.sleep(300);
            // 判断商铺是否存在
            if (shop == null){
                //防止缓存击穿，设置空值到缓存中
                stringRedisTemplate.opsForValue().set("cache:shop:"+id, "",5, TimeUnit.MINUTES);
                // 不存在，返回错误信息
                return null;
            }
            // 写入缓存
            stringRedisTemplate.opsForValue().set("cache:shop:"+id, JSONUtil.toJsonStr(shop),30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unlock(lockKey);
        }
        // 返回商铺信息
        return shop;
    }
//    public Shop queryWithPassThrough(Long id) {
//        // 查询缓存
//        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:"+id);
//        // 判断缓存是否存在，isNotBlank只针对非null的字符串，如果是空串也会认为是存在的
//        if(StrUtil.isNotBlank(shopJson)){
//            // 存在，直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        // 判断缓存是否存在空值
//        if(shopJson != null){
//            return null;
//        }
//        // 不存在，查询数据库
//        Shop shop = getById(id);
//        // 判断商铺是否存在
//        if (shop == null){
//            //防止缓存击穿，设置空值到缓存中
//            stringRedisTemplate.opsForValue().set("cache:shop:"+id, "",5, TimeUnit.MINUTES);
//            // 不存在，返回错误信息
//            return null;
//        }
//        // 写入缓存
//        stringRedisTemplate.opsForValue().set("cache:shop:"+id, JSONUtil.toJsonStr(shop),30, TimeUnit.MINUTES);
//        // 返回商铺信息
//        return shop;
//    }
//
//
    // 尝试获取锁
    private boolean trylock(String key){
        // 尝试获取锁，如果获取成功，则返回true；否则，返回false。
        Boolean flag =  stringRedisTemplate.opsForValue().setIfAbsent(key, "1",10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    // 释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    // 缓存预热，test用
    public void saveShoptoRedis(Long id,Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(300);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set("cache:shop:"+id, JSONUtil.toJsonStr(redisData));
    }


    @Override
    @Transactional
    public Result updateshop(Shop shop) {
        Long id = shop.getId();
        if (id == null || id <= 0){
            return Result.fail("商铺id不合法");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete("cache:shop:"+shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要根据坐标查询
        if(x == null && y == null)
        {
            // 根据类型分页查询
            Page<Shop> page =query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current*SystemConstants.DEFAULT_PAGE_SIZE;
        String key = "shop:geo:type:"+typeId;
        //查询redis按照距离排序，分页
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        //解析出id
        if(results==null)
        {
            return Result.ok(Collections.emptyList());
        }
        //获取距离和商铺id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from)
        {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        // 计算距离和商铺id
        Map<String,Distance> distances = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
                    String shopIdStr = result.getContent().getName();
                    ids.add(Long.valueOf(shopIdStr));
                    Distance distance = result.getDistance();
                    distances.put(shopIdStr, distance);
                });
        //查询数据库
        String idStr = StrUtil.join(",",ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distances.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
