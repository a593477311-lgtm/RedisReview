package com.dianping.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final long BEGIN_TIMESTAMP = 1779321600L;
    private static final long COUNT_BITS = 32;


    public long nextId(String keyPrefix) {
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long newSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = newSecond - BEGIN_TIMESTAMP;
        //生成序列号
        //获取当前日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //获取当天序列号
        long count =stringRedisTemplate.opsForValue().increment("icr:"+ keyPrefix + ":"+date);

        //拼接key

        return timestamp<<COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2026,5,21,0,0,0);
        Long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second = " + second);

    }

}
