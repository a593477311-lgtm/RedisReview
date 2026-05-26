package com.dianping;

import com.dianping.entity.Shop;
import com.dianping.service.impl.ShopServiceImpl;
import com.dianping.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
public class RedisWarmupTest {
    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopServiceImpl shopService;

    @Test
    public void testSaveShopToRedis() throws InterruptedException {
        // 把 id=1 的店铺写入 Redis，逻辑过期时间 30 秒（方便测试）
        Shop shop = shopService.getById(1);
        cacheClient.setWithLogicalExpire("cache:shop"+1L,shop, 10L, java.util.concurrent.TimeUnit.SECONDS);

    }
}