-- 参数列表
--优惠券id
local voucherId = ARGV[1]
--用户id
local userId = ARGV[2]
--订单id
local orderId = ARGV[3]

--库存key
local stockKey = "seckill:stock:" .. voucherId
--订单key
local orderKey = "seckill:order:" .. voucherId

--脚本业务
--1.判断库存是否足够
if(tonumber(redis.call('get',stockKey))<=0) then
    --库存不足
    return 1
end
--2.判断用户是否下单SISMEMBER
if(redis.call('sismember',orderKey,userId)==1) then
    --存在
    return 2
end
--扣库存 incrby stockKey -1
redis.call('incrby',stockKey,-1)
--记录用户下单 sadd orderKey userId
redis.call('sadd',orderKey,userId)
--发送消息到队列XADD stream.orders *
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)

return 0