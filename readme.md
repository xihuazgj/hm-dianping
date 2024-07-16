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
