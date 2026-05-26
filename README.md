# 类大众点评项目

基于 SpringBoot + Redis 的点评类应用实战项目，涵盖了 Redis 在实际业务场景中的多种应用模式。

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| SpringBoot | 2.6.0 | 基础框架 |
| MyBatis-Plus | 3.4.3 | ORM框架 |
| Redis | - | 缓存中间件（Lettuce连接池） |
| Redisson | 3.17.6 | 分布式锁 |
| Hutool | 5.7.17 | Java工具库 |
| MySQL | 5.1.47 | 数据库 |
| Lombok | 1.18.30 | 简化代码 |

## 功能模块详解

### 1. 用户登录（短信验证码登录）

**实现方式**：
- 验证码存储：Redis String 类型，key 为 `login:code:{phone}`，有效期 5 分钟
- 用户登录态：Redis Hash 类型，key 为 `login:token:{token}`，存储用户基本信息，有效期 30 分钟
- 拦截器：`LoginInterceptor` 校验登录状态，`RefreshTokenInterceptor` 刷新 token 有效期

**核心代码**：[UserServiceImpl.java](src/main/java/com/dianping/service/impl/UserServiceImpl.java)

### 2. 商户查询（缓存优化）

**解决的问题**：
- **缓存穿透**：查询不存在的数据，缓存空值（有效期 5 分钟）
- **缓存击穿**：热点数据过期，使用互斥锁方案
- **缓存雪崩**：设置随机 TTL

**实现方案**：
- 互斥锁：使用 Redis 的 `SETNX` 实现分布式锁
- 缓存空值：防止恶意请求穿透到数据库
- 缓存有效期：30 分钟

**核心代码**：[ShopServiceImpl.java](src/main/java/com/dianping/service/impl/ShopServiceImpl.java)

### 3. 优惠券秒杀

**核心技术**：
- **Lua 脚本**：保证原子性，一次执行库存判断、库存扣减、订单记录
- **Redis Stream**：消息队列实现异步下单，支持消费者组
- **Redisson 分布式锁**：防止一人一单重复下单

**秒杀流程**：
```
1. 执行 Lua 脚本（原子操作）：
   - 判断库存是否充足
   - 判断用户是否已下单（Set 集合）
   - 扣减库存
   - 记录用户已下单
   - 发送订单消息到 Stream 队列

2. 后台线程消费消息：
   - 从 Stream 队列获取订单
   - 使用分布式锁防止重复下单
   - 创建订单写入数据库
   - ACK 确认消息消费
```

**核心代码**：
- [VoucherOrderServiceImpl.java](src/main/java/com/dianping/service/impl/VoucherOrderServiceImpl.java)
- [seckill.lua](src/main/resources/seckill.lua)

### 4. 点赞功能

**实现方式**：
- 使用 Redis ZSet 存储：key 为 `blog:liked:{blogId}`
- member 为用户 ID，score 为点赞时间戳
- ZSet 优势：可按时间排序，快速查询 Top N 点赞用户

**核心代码**：[BlogServiceImpl.java](src/main/java/com/dianping/service/impl/BlogServiceImpl.java)

### 5. 关注功能

**实现方式**：
- 使用 Redis Set 存储：key 为 `follows:{userId}`
- Set 中存储关注的用户 ID
- **共同关注**：使用 Set 的 `SINTER` 命令求交集

**核心代码**：[FollowServiceImpl.java](src/main/java/com/dianping/service/impl/FollowServiceImpl.java)

### 6. Feed 流（推模式）

**实现方式**：
- 使用 Redis ZSet 存储：key 为 `feed:{userId}`
- member 为博客 ID，score 为发布时间戳
- **推模式**：发布博客时，推送到所有粉丝的收件箱
- **滚动分页**：使用 `ZREVRANGEBYSCORE` 实现按时间滚动分页

**核心代码**：[BlogServiceImpl.java](src/main/java/com/dianping/service/impl/BlogServiceImpl.java)

### 7. 用户签到

**实现方式**：
- 使用 Redis BitMap：key 为 `sign:{userId}:{yyyyMM}`
- offset 为日期（第几天），value 为是否签到（0/1）
- **连续签到天数**：使用 `BITFIELD` 命令获取签到数据，位运算统计连续签到

**核心代码**：[UserServiceImpl.java](src/main/java/com/dianping/service/impl/UserServiceImpl.java)

### 8. 附近商户（GEO）

**实现方式**：
- 使用 Redis GEO 数据结构：key 为 `shop:geo:type:{typeId}`
- 存储商户经纬度坐标
- 使用 `GEOSEARCH` 命令按距离搜索附近商户

**核心代码**：[ShopServiceImpl.java](src/main/java/com/dianping/service/impl/ShopServiceImpl.java)

## Redis Key 设计

| Key 模式 | 类型 | 说明 | TTL |
|----------|------|------|-----|
| `login:code:{phone}` | String | 短信验证码 | 5 分钟 |
| `login:token:{token}` | Hash | 用户登录信息 | 30 分钟 |
| `cache:shop:{id}` | String | 商户缓存 | 30 分钟 |
| `lock:shop:{id}` | String | 商户缓存互斥锁 | 10 秒 |
| `seckill:stock:{voucherId}` | String | 秒杀库存 | - |
| `seckill:order:{voucherId}` | Set | 秒杀已购用户 | - |
| `blog:liked:{blogId}` | ZSet | 博客点赞用户 | - |
| `follows:{userId}` | Set | 用户关注列表 | - |
| `feed:{userId}` | ZSet | 用户收件箱 | - |
| `shop:geo:type:{typeId}` | GEO | 商户地理位置 | - |
| `sign:{userId}:{yyyyMM}` | BitMap | 用户签到记录 | - |

## 项目配置

```yaml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/hmdp?useSSL=false&serverTimezone=UTC
    username: root
  redis:
    host: localhost
    port: 6379
    lettuce:
      pool:
        max-active: 10
        max-idle: 10
        min-idle: 1
```

## 运行前准备

1. **创建数据库**：创建 MySQL 数据库 `hmdp`，导入 `src/main/resources/db/hmdp.sql`
2. **启动 Redis**：确保 Redis 服务运行在 `localhost:6379`
3. **修改配置**：根据需要修改 `application.yaml` 中的数据库和 Redis 配置
4. **初始化秒杀库存**：将秒杀券库存预热到 Redis（key: `seckill:stock:{voucherId}`）
5. **初始化商户 GEO**：将商户坐标导入 Redis GEO
6. **运行项目**：运行 `DianPingApplication.java`

## 项目结构

```
src/main/java/com/dianping/
├── config/                      # 配置类
│   ├── MvcConfig.java          # MVC配置（拦截器）
│   ├── MybatisConfig.java      # MyBatis配置
│   ├── RedissonConfig.java     # Redisson配置
│   └── WebExceptionAdvice.java # 全局异常处理
├── controller/                  # 控制器
│   ├── UserController.java     # 用户相关
│   ├── ShopController.java     # 商户相关
│   ├── VoucherController.java  # 优惠券相关
│   ├── VoucherOrderController.java  # 秒杀订单
│   ├── BlogController.java     # 博客相关
│   └── FollowController.java   # 关注相关
├── service/                     # 业务逻辑
│   └── impl/                   # 实现类
├── mapper/                      # MyBatis Mapper
├── entity/                      # 实体类
├── dto/                         # 数据传输对象
│   ├── Result.java             # 统一返回结果
│   ├── UserDTO.java            # 用户DTO
│   └── ScrollResult.java       # 滚动分页结果
└── utils/                       # 工具类
    ├── RedisConstants.java     # Redis常量
    ├── RedisIdWorker.java      # 分布式ID生成器
    ├── CacheClient.java        # 缓存客户端
    ├── SimpleRedisLock.java    # 简单Redis锁
    ├── UserHolder.java         # 用户上下文
    └── LoginInterceptor.java   # 登录拦截器

src/main/resources/
├── db/hmdp.sql                 # 数据库脚本
├── seckill.lua                 # 秒杀Lua脚本
├── application.yaml            # 配置文件
└── mapper/                     # MyBatis XML
```

## 核心技术要点

### 缓存穿透解决方案
```java
// 缓存空值，防止穿透
if (shop == null) {
    stringRedisTemplate.opsForValue().set(key, "", 5, TimeUnit.MINUTES);
    return null;
}
```

### 缓存击穿解决方案（互斥锁）
```java
// 获取互斥锁
Boolean locked = stringRedisTemplate.opsForValue()
    .setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
if (locked) {
    // 查询数据库，写入缓存
} else {
    // 等待重试
    Thread.sleep(50);
    return queryWithMutex(id);
}
```

### Lua 脚本保证原子性
```lua
-- 判断库存
if(tonumber(redis.call('get', stockKey)) <= 0) then
    return 1  -- 库存不足
end
-- 判断是否已下单
if(redis.call('sismember', orderKey, userId) == 1) then
    return 2  -- 重复下单
end
-- 扣库存、记录订单
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0
```

### BitMap 签到统计
```java
// 获取本月签到数据
List<Long> result = stringRedisTemplate.opsForValue().bitField(
    key,
    BitFieldSubCommands.create()
        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
        .valueAt(0)
);
// 统计连续签到天数
while ((num & 1) != 0) {
    count++;
    num >>>= 1;
}
```

## API 接口概览

| 模块 | 接口 | 说明 |
|------|------|------|
| 用户 | POST /user/code | 发送验证码 |
| 用户 | POST /user/login | 登录 |
| 商户 | GET /shop/{id} | 查询商户 |
| 商户 | PUT /shop | 更新商户 |
| 优惠券 | POST /voucher-order/seckill/{id} | 秒杀优惠券 |
| 博客 | POST /blog/like/{id} | 点赞博客 |
| 博客 | GET /blog/of/follow | 查询关注博客 |
| 关注 | PUT /follow/{id}/{isFollow} | 关注/取关 |
| 关注 | GET /follow/common/{id} | 共同关注 |
| 签到 | POST /user/sign | 签到 |
| 签到 | GET /user/sign/count | 连续签到天数 |
| 附近 | GET /shop/of/type | 附近商户 |

## 学习要点

本项目适合学习以下 Redis 应用场景：

1. **缓存设计**：穿透、击穿、雪崩问题的解决方案
2. **分布式锁**：Redisson 的使用与原理
3. **Lua 脚本**：保证 Redis 操作原子性
4. **消息队列**：Redis Stream 实现异步处理
5. **数据结构应用**：Set、ZSet、BitMap、GEO 的实际应用
6. **分布式 ID**：Redis 自增实现唯一 ID 生成
#   R e d i s R e v i e w  
 #   R e d i s R e v i e w  
 