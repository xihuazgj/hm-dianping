# redis的项目实战

### 1.基于redis实现短信登录

#### 1.1验证码的存储及设置有效期

##### 生成验证码并存储到redis

```
    @Resource
    public StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //1.1 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //1.2 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //2.保存验证码
//        session.setAttribute("code",code);
        //2.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //3.发送验证码
        log.debug("发送短信验证码成功，验证码：{}",code);
        //4.返回ok
        return Result.ok();
    }
```

首先接受前端传来的手机号，利用正则表达式判断手机号格式是否正确，符合则生成验证码，存入redis中。因为验证码的格式简单
就用的stringRedisTemplate.opsForValue(),以最简单的键值存储，并设置有效期为2分钟。

用户收到验证码后点击登录，前端传来用户表单，

##### 1.2用户点击登录，校验用户手机号及验证码，并保存用户到redis中

@Override
public Result login(LoginFormDTO loginForm, HttpSession session) {
//1.校验手机号
String phone = loginForm.getPhone();
if (RegexUtils.isPhoneInvalid(phone)){
//1.1 如果不符合，返回错误信息
return Result.fail("手机号格式错误");
}
//2.从session获取校验验证码
//        Object cachecode = session.getAttribute("code");
//2.从redis中获取验证码,并校验
String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
String code = loginForm.getCode();
if (cachecode == null || !cachecode.equals(code)){
//3.不一致，报错，返回错误信息
return Result.fail("验证码错误");
}
//4.一致，根据手机号查询用户 select * from tb_user where phone = ?
User user = query().eq("phone", phone).one();
//5.判断用户是否存在
if (user == null) {
//6.不存在，创建用户并保存
user = createUserWithPhone(phone);
}
//7.保存用户信息到session中
//        session.setAttribute("user",BeanUtil.copyProperties(user,UserDTO.class));
//7.保存用户信息到redis中
//7.1生成token，作为登录令牌
String token = UUID.randomUUID().toString(true);
//7.2将User对象转为Hash存储
UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
//7.3存储
Map<String, Object> usermap = BeanUtil
.beanToMap(userDTO,new HashMap<>()
,CopyOptions.create().setIgnoreNullValue(true)
.setFieldValueEditor((fieldName,fieldValue)
->fieldValue.toString() ));//将userDTO转换为map，并将所有字段转换为String类型
stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,usermap);
//7.4给token设置有效期
stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL,TimeUnit.MINUTES);
//返回token
return Result.ok(token);
}
这里我们用redis中的Hash数据结构来存储用户信息很合适，很适用map。
我们所要注意的就是这个，因为这种redis这种数据结构只支持String类型的字段

     Map<String, Object> usermap = BeanUtil
    .beanToMap(userDTO,new HashMap<>()
    ,CopyOptions.create().setIgnoreNullValue(true)
    .setFieldValueEditor((fieldName,fieldValue)
    ->fieldValue.toString() ));//将userDTO转换为map，并将所有字段转换为String类型
#### 1.3 对用户登录进行校验，并设置token刷新

token刷新是为了方便用户

    //这是我们的第一层拦截器，作用是只对用户信息进行保存到Threadlocal中，并刷新token
    public class RefreshTokenInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate stringRedisTemplate;
    
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        //1.获取session
    //        HttpSession session = request.getSession();
    //1.获取请求头中的token
    String token = request.getHeader("authorization");
    if (StrUtil.isBlank(token)){
    return true;
    }
    //2.基于token获取redis中的用户
    String key = RedisConstants.LOGIN_USER_KEY + token;
    Map<Object, Object> usermap = stringRedisTemplate.opsForHash()
    .entries(key);
    //2.获取session中的用户
    //        Object user = session.getAttribute("user");
    //        System.out.println("******************************"+ user);
    //3.判断用户是否存在
    if (usermap.isEmpty()) {
    return true;
    }
    //5.将查询到的Hash数据转为userDTO对象
    UserDTO userDTO = BeanUtil.fillBeanWithMap(usermap, new UserDTO(), false);
    //6.存在，保存用户信息到ThreadLocal
    UserHolder.saveUser(userDTO);
    //7.刷新token有效期
    stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
    log.debug("用户token刷新：重置为：{}",RedisConstants.LOGIN_USER_TTL);
    //8.放行
    return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        //移除用户
        UserHolder.removeUser();
    }
    }
第二层拦截器是真正的拦截

    public class LoginInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        //1.判断是否需要拦截（Threadlocal中是否有用户）
        if (UserHolder.getUser() == null){
            //拦截，设置状态码
            response.setStatus(401);
            //拦截
            return false;
        }
        //有用户，放行
        return true;
    }
    }
    
    配置MvcConfig，使拦截器生效
    
    @Configuration
    public class MvcConfig implements WebMvcConfigurer {
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**",
                        "/upload/**"
                ).order(1);//order(value),value越大，越后执行
        //token的刷新拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
    }
    }
### 2.基于redis实现缓存作用

#### 2.3 缓存更新策略

缓存更新策略的最佳实践方案：

1.低一致性需求：使用Redis自带的内存淘汰机制

2.高一致性需求：主动更新，并以超时剔除作为兜底方案

* 读操作：
* 缓存命中则直接返回
* 缓存未命中则查询数据库，并写入缓存，设定超时时间
* 写操作：
* 先写数据库，然后再删除缓存
* 要确保数据库与缓存操作的原子性

缓存穿透产生的原因是什么？

用户请求的数据在缓存中和数据库中都不存在，不断发起这样的请求
给数据库带来巨大压力
  缓存穿透的解决方案有哪些？
*   缓存nul值：缺点：会带来额外的内存压力和短暂的数据不一致性
*   布隆过滤：使用特殊算法过滤，但是不能保证完全准确
*   增强id的复杂度，避免被猜测id规律
*   做好数据的基础格式校验
*   加强用户权限校验
*   做好热点参数的限流

      
       //缓存穿透解决方案
      @Override
      public Result queryById(Long id) {
      String key = CACHE_SHOP_KEY + id;
      //1.从redis查询商铺缓存
      String shopJson = stringRedisTemplate.opsForValue().get(key);
      //2.判断是否存在
      if (StrUtil.isNotBlank(shopJson)){
      //3.存在，直接返回
      Shop shop = JSONUtil.toBean(shopJson, Shop.class);
      return Result.ok(shop);
      }
      //判断命中的是否为空值
      if (shopJson != null){
      //返回一个错误信息
      return Result.fail("店铺不存在");
      }
      //4.不存在，则根据id查询数据库
      Shop shop = getById(id);
      //5.不存在，返回错误404
      if (shop == null){
      //将空值写入redis，防止缓存穿透
      stringRedisTemplate.opsForValue()
      .set(key,""
      ,CACHE_NULL_TTL, TimeUnit.MINUTES);
      return Result.fail("店铺不存在！");
      }
      //6.存在，写入redis
      stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
      //7.返回
      return Result.ok(shop);
      }

缓存雪崩

缓存雪崩是指在同一时段大量的缓存ky同时失效或者Redis服务宕机，导致大量请求到达数据库，带来巨大压力。

解决方案：
* ◆给不同的Key的TTL添加随机值
* ◆利用Redis:集群提高服务的可用性
* ◆给缓存业务添加降级限流策略
* ◆给业务添加多级缓存


缓存击穿

缓存击穿问题也叫热点Key问题，就是一个被高并发访问并且缓存重建业务较复杂的key突然失效了，无数的请求访问会
在瞬间给数据库带来巨大的冲击。

常见的解决方案有两种：

* 互斥锁:
* 缺点:性能比较差一点，在互斥锁没有释放之前，其他线程只能等待,可能会有死锁风险
* 优点：没有额外的内存消耗 保证一致性 实现简单


* 逻辑过期
* 缺点:不保证一致性 有额外内存消耗 实现复杂
* 优点：线程无须等待  性能较好


### 基于互斥锁解决缓存击穿

    public Shop queryWithMutex(Long id) {

        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否为空值
        if (shopJson != null) {
            //返回一个错误信息
            return null;
        }
        //开始实现缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop;
        try {
            boolean isLock = tryLock(lockKey);
            //判断是否获取成功
            if (!isLock) {
                //失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id); //递归
            }
            //4.不存在，则根据id查询数据库
            shop = getById(id);
            Thread.sleep(200);
            //5.不存在，返回错误404
            if (shop == null) {
                //将空值写入redis，防止缓存穿透
                stringRedisTemplate.opsForValue()
                        .set(key, ""
                                , CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入redis
            stringRedisTemplate.opsForValue()
                    .set(key, JSONUtil.toJsonStr(shop)
                            , CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }
        //7.返回
        return shop;

    }

### 基于逻辑过期解决缓存击穿