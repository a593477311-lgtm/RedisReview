package com.dianping.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dianping.dto.Result;
import com.dianping.dto.ScrollResult;
import com.dianping.dto.UserDTO;
import com.dianping.entity.Blog;
import com.dianping.entity.Follow;
import com.dianping.entity.User;
import com.dianping.mapper.BlogMapper;
import com.dianping.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dianping.service.IFollowService;
import com.dianping.service.IUserService;
import com.dianping.utils.SystemConstants;
import com.dianping.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IFollowService followService;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        // 根据id查询博客
        Blog blog = getById(id);
        if(blog == null)
        {
            return Result.fail("博客不存在");
        }
        // 查询用户
        queryBlogUser(blog);
        //查询blog是否被登录用户点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user == null)
        {
            // 未登录无事发生
            return;
        }
        Long userId = UserHolder.getUser().getId();
        //1判断用户是否已经点赞
        String key = "blog:liked:"+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        //1判断用户是否已经点赞
        String key = "blog:liked:"+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null) {
            //2未点赞，则增加点赞数
            //3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2 保存用户到redis的set集合
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }else {
            //4 如果已经点赞，则取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.1 数据库点赞数-1
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
            //4.2 从redis的set集合中移除用户
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 查询top5的点赞用户 zrange 0 4
        String key = "blog:liked:"+id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5.isEmpty() || top5 == null){
            return Result.ok(Collections.emptyList());
        }
        // 解析出用户id，查询用户信息
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //users转UserDTO
        List<UserDTO> userDTOs = userService.query().in("id", ids).last(" ORDER BY FIELD(id, " + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOs);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("保存失败");
        }
        //查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送给粉丝
        for(Follow follow : follows){
            //获得粉丝id
            Long userId = follow.getUserId();
            //推送消息
            String key = "feed:"+userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(long max, Integer offset) {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //查询收件箱 zrevrangebyscore key max min limit  offset count
        String key = "feed:"+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        //判断是否为空
        if(typedTuples.isEmpty() || typedTuples == null){
            return Result.ok();
        }
        //解析数据:blogId、minTime、offsert
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime =0;
        int os = 1;
        for(ZSetOperations.TypedTuple<String> tuple : typedTuples)
        {
            //获取blogId
            String idStr = tuple.getValue();
            ids.add(Long.valueOf(idStr));
            //获取minTime
            long time = tuple.getScore().longValue();
            if(time==minTime)
            {
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        //根据id查询博客详情
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list();
        for (Blog blog : blogs) {
            //查询blog创建者用户信息
            queryBlogUser(blog);
            //查询blog是否被登录用户点赞
            isBlogLiked(blog);
        }
        //返回结果
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);
        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
