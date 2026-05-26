package com.dianping.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dianping.dto.Result;
import com.dianping.dto.UserDTO;
import com.dianping.entity.Follow;
import com.dianping.entity.User;
import com.dianping.mapper.FollowMapper;
import com.dianping.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dianping.service.IUserService;
import com.dianping.utils.UserHolder;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, @NonNull Boolean isFollow) {
        // 获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;

        // 1. 根据当前用户id和商铺id查询是否已经关注
        if(isFollow)
        {
            // 2. 如果关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if(isSuccess)
            {
                // 3. 缓存关注数
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else {
            // 3. 取关，删除数据
            boolean isSuccess= remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            if(isSuccess) {
                // 4. 缓存关注数
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        //确保缓存存在
        ensureFollowCache(userId);
        ensureFollowCache(id);
        //求交集
        //自己的关注集合
        String key = "follows:" + userId;
        //目标的关注集合
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key2, key);
        if(intersect == null || intersect.isEmpty())
        {
            return Result.ok(Collections.emptyList());
        }
        //解析出id
        Set<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toSet());
        //查询用户信息
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        //查询是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        //判断
        return Result.ok(count > 0);
    }


    private void ensureFollowCache(Long userId) {
        // 1. 构建 Redis 的 key
        String key = "follows:" + userId;

        // 2. 检查 Redis 中是否存在这个 key
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) {

            // 3. 如果不存在，从 MySQL 查询该用户的所有关注记录
            List<Follow> follows = query().eq("user_id", userId).list();

            // 4. 如果数据库有记录，将它们写回 Redis
            if (follows != null && !follows.isEmpty()) {
                String[] ids = follows.stream()
                        .map(f -> f.getFollowUserId().toString())
                        .toArray(String[]::new);
                stringRedisTemplate.opsForSet().add(key, ids);
            }
        }
    }
}
