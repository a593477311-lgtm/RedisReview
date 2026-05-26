package com.dianping.utils;

import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;
//一个理解原理的简单分布式锁实现
public class SimpleRedisLock implements ILock {
    private String name;
    private static final String KEY_PREFIX = "lock:";
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean trylock(long timeoutSec){
        long threadId = Thread.currentThread().getId();
        // 尝试获取锁，如果获取成功，则返回true；否则，返回false。
        Boolean success =  stringRedisTemplate
                .opsForValue().setIfAbsent(KEY_PREFIX+name, threadId + "",timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }
    // 释放锁
    public void unlock(){
        // 删除锁
        stringRedisTemplate.delete(KEY_PREFIX+name);
    }
}
