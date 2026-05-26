package com.dianping.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dianping.entity.Shop;
import com.dianping.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //把对象写入redis并设置过期时间
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //把对象写入redis并设置逻辑过期时间
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断缓存是否存在，isNotBlank只针对非null的字符串，如果是空串也会认为是存在的
        if (StrUtil.isNotBlank(json)) {
            // 存在，直接返回
            return JSONUtil.toBean(json,type);
        }
        // 判断缓存是否存在空值
        if (json != null) {
            return null;
        }
        // 不存在，查询数据库
        R r = dbFallback.apply(id);
        // 判断商铺是否存在
        if (r == null) {
            //防止缓存击穿，设置空值到缓存中
            stringRedisTemplate.opsForValue().set(key, "", 5, TimeUnit.MINUTES);
            // 不存在，返回错误信息
            return null;
        }
        // 写入缓存
        this.set(key, r, time, unit);
        // 返回商铺信息
        return r;
    }


    private static final ExecutorService cache_rebuild_executor = Executors.newFixedThreadPool(10);

    //逻辑过期
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        // 查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断缓存是否存在，isNotBlank只针对非null的字符串，如果是空串也会认为是存在的
        if(StrUtil.isBlank(json)){
            // 存在，直接返回
            return null;
        }
        //命中逻辑是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject)redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now()))
        {
            return r;
        }

        String lockKey = "lock:shop:"+id;
        boolean islock = trylock(lockKey);

        if(islock){
            if(expireTime.isAfter(LocalDateTime.now()))
            {
                return r;
            }
            cache_rebuild_executor.submit(() -> {
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return r;
    }

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
}
