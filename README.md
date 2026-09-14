# SmartMeal · 智能膳食规划与生鲜导购平台

用户填一份健康档案，系统调用大模型生成一周食谱，再把食谱自动折算成「该买什么菜、买多少、多少钱」的购物清单，并直连生鲜 SKU 下单。

和常见的「外卖/菜谱 CRUD 项目」相比，这个项目真正的难点集中在三处：**大模型输出的可靠性与安全边界**、**SSE 流式链路的工程化**、**从「食谱」到「商品 SKU」的量纲换算**。这三点在下面的「关键设计」里有详细说明。

---

## 一、技术栈

| 层次 | 选型 | 版本 | 为什么是它 |
|---|---|---|---|
| 运行时 | Java | **17 LTS** | 不是 21/24。Lombok 与 MyBatis-Plus 的反射在 JDK 23+ 上有坑，17 是生态最稳的 LTS |
| 框架 | Spring Boot | **3.5.16** | 不用 4.x。MyBatis-Plus / Knife4j / Sa-Token 对 Boot 4 的支持尚未稳定 |
| AI | Spring AI | **1.1.8** | 用它的 OpenAI 兼容客户端对接 DeepSeek，省掉手写 HTTP + SSE 解析 |
| 大模型 | DeepSeek `deepseek-chat` | — | OpenAI 兼容协议、成本低。**注意它没有 Embedding 模型**，见「接入 pgvector」 |
| ORM | MyBatis-Plus | **3.5.17** | 需要额外引入 `mybatis-plus-jsqlparser`，见「踩过的坑」 |
| 鉴权 | Sa-Token | **1.46.0** | 比 Spring Security 轻，适合单体 |
| 文档 | springdoc + Knife4j | **2.9.1 / 4.5.0** | 两者有版本冲突，处理方式见「踩过的坑」 |
| 缓存 | Caffeine / Redis | — | 通过 `CacheService` 抽象切换，本机不装 Redis 也能跑 |
| 数据库 | MySQL 8.0 | — | |

---

## 二、模块结构

9 个 Maven 模块，依赖链是**严格线性**的，没有循环：

```
smartmeal-common       统一返回体、异常、缓存抽象（CacheService）
      ↓
smartmeal-domain       20 张表的实体 + 枚举 + DTO（含 MealPlanResult）
      ↓
smartmeal-repository   19 个 Mapper + MyBatis-Plus 配置
      ↓
smartmeal-service      业务逻辑：健康档案、营养计算、购物清单换算
      ↓
smartmeal-ai           ★ 重头戏：DeepSeek 客户端、Prompt、RAG、校验、SSE
      ↓
smartmeal-job          订单超时取消、知识库同步
      ↓
smartmeal-app-api      用户端（:8080）
smartmeal-admin-api    管理端（:8081）
smartmeal-deploy       建表 SQL、Docker Compose、Nginx、Dockerfile
```

**为什么 `MealPlanResult` 放在 domain 而不是 ai 模块？**
因为 `ShoppingListService`（在 service 模块）也要消费它来生成购物清单。如果放在 ai 模块，就会变成 `service → ai → service` 的循环依赖。

---

## 三、本地启动

### 前置条件

- **JDK 17**（`java -version` 确认；本仓库用 Maven Wrapper，**不需要**单独装 Maven）
- **MySQL 8.0**（可选，不装也能启动，只是接口会报连库失败）

### 1. 初始化数据库

```bash
mysql -uroot -p < smartmeal-deploy/sql/mysql-init.sql
```

脚本会创建 `smartmeal` 库、20 张表，并灌入演示数据：

- 15 个食材，其中**花生 1012 / 花生油 1013 / 花生酱 1014** 专门用来验证过敏原衍生品拦截
- 8 条过敏原映射（`PEANUT → 花生/花生油/花生酱`）
- 10 道菜谱 + 关联食材 + 步骤
- 13 个商品 + 14 个 SKU（带 `conversion_rate` 换算率）
- 演示用户 `id=1`：170cm / 80kg / 减脂目标，**花生过敏**，冰箱里有番茄 300g、鸡蛋 4 枚

### 2. 配置大模型 Key（可选）

```bash
cp .env.example .env
# 编辑 .env，填入 DEEPSEEK_API_KEY（申请：https://platform.deepseek.com/api_keys）
```

**不配也能跑**。`LlmClientConfig` 在装配期发现 Key 是占位值，会自动降级到 `MockLlmClient`，
返回结构合法的假数据，整条链路（SSE → 解析 → 校验 → 落库 → 购物清单）依然走得通。

### 3. 启动

```bash
# 数据库账号密码和默认值不同时，用环境变量覆盖
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=你的密码

./mvnw -DskipTests package
java -jar smartmeal-app-api/target/smartmeal-app-api-1.0.0-SNAPSHOT.jar
```

启动后：

| 地址 | 说明 |
|---|---|
| **http://localhost:8080/** | **演示页面（单页，无需前端构建）** |
| http://localhost:8080/doc.html | Knife4j 接口文档 |
| http://localhost:8080/v3/api-docs | OpenAPI JSON |
| http://localhost:8080/api/app/ai/status | **看当前走的是真实模型还是 Mock** |
| http://localhost:8080/actuator/health | 健康检查 |

打开根路径就是完整的演示界面：健康档案 → 生成表单 → SSE 打字机 → 计划详情 → 购物清单，
全部由 `smartmeal-app-api/src/main/resources/static/` 下的三个文件提供
（`index.html` + `css/app.css` + `js/app.js`），没有 npm、没有打包步骤，`java -jar` 起完就能点。

`application-local.yml` 里默认 **`smartmeal.auth.enabled: false`**，所以调接口不用先登录，
`CurrentUserResolver` 会固定返回 `dev-user-id: 1`。

**演示账号**（种子数据里已经带好，`t_user` 表）：

| 用户名 | 口令 | 说明 |
|---|---|---|
| `demo` | `demo123456` | 已对**花生**过敏、冰箱里有 300g 番茄 + 4 枚鸡蛋 |

登录接口是 `POST /api/app/auth/login`，请求体 `{"username":"demo","password":"demo123456"}`。
`t_user.password` 里存的是这个口令的 **BCrypt 哈希**（strength=10，60 字符），
不是明文——所以这份 SQL 即使进了公开仓库也不泄露口令，照着上表就能登进去验证。

```bash
# 验证登录（注意 --noproxy，Windows 上代理会劫持 localhost）
curl --noproxy '*' -X POST http://localhost:8080/api/app/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123456"}'
# 口令错 5 次会被锁 15 分钟；期间即使输对也拒绝，且不再查库
```

### 4. 快速验证

```bash
# 确认 AI 客户端状态：mock=true 说明没配 Key，走的是 Mock
curl --noproxy '*' http://localhost:8080/api/app/ai/status
# {"llmClient":"MockLlmClient","mock":true,"retrievalBackend":"in-memory-keyword",...}

# 流式生成一周食谱（SSE）
curl --noproxy '*' -N -X POST http://localhost:8080/api/app/ai/meal-plan/stream \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-001","goal":"loss_fat","days":3}'

# 演示页用到的两个读接口（页面刷新后靠它们恢复状态）
curl --noproxy '*' http://localhost:8080/api/app/meal-plan/latest
curl --noproxy '*' http://localhost:8080/api/app/shopping-list/by-plan/1
```

> Windows 下如果配了系统代理，`curl` 访问 localhost 会被代理劫持导致超时，
> 加 `--noproxy '*'` 即可。

### 5. 跑测试

```bash
./mvnw test
```

**144 个单元测试，全部不依赖数据库**，覆盖项目里所有「算错了就出事」的逻辑：

| 测试类 | 数量 | 钉住的是什么 |
|---|---|---|
| `NutritionCalculatorTest` | 13 | Mifflin-St Jeor 公式、男女差异、活动系数、1200/4000 千卡钳制、缺字段兜底 |
| `PlanValidatorTest` | 18 | **过敏原双判据**（ID 精确 + 名称兜底）、映射表缺行时的告警、天数不符判硬错误、热量/重复度/实体软校验 |
| `PlanJsonParserTest` | 12 | 剥代码块围栏、前后解释文字截取、多字段忽略、截断 JSON 的失败路径 |
| `ShoppingListServiceImplTest` | 22 | **整包向上取整**、跨天聚合、单位隔离、SKU 比价、冰箱按真实数量扣减 |
| `MockLlmClientDataTest` | 19 | 演示数据的「像样程度」：菜谱 ID 真实存在、天数与请求一致、**每道菜挂的是自己的食材**、用量随份数放大 |
| `UserAuthServiceImplTest` | 25 | **防用户名枚举**（三种失败同码同文案）、**失败 5 次锁定且锁定期内不再查库**、成功登录清零计数、注册只落哈希、弱密码与非法用户名边界、占位符哈希不喂给 BCrypt |
| `LocalCacheServiceTest` | 11 | per-entry TTL、`increment` 是固定窗口而非滑动窗口、前缀批量清理 |
| `MockLlmClientResolveDaysTest` | 10 | 从 Prompt 反查天数的边界：缺失/负数/超大值/多处匹配 |
| `CalcAgeTest` | 7 | 生日未到/已到的周岁计算、闰日边界 |
| `GeneratePlanNoTest` | 3 | 计划单号生成不依赖时区、同毫秒不撞号 |
| `MealPlanServiceImplSaveResultTest` | 3 | **落库的热量目标取后端实算值**，不采信模型回传 |

期望值全是手算公式得出的（比如 170cm/80kg/30 岁男性：BMR 1718 → TDEE 2663 → 减脂目标 2130），
公式或系数一旦被改动，测试立刻失败。

---

## 四、关键设计

### 1. 过敏原是硬约束，不是「提示一下模型」

最常见的错误做法是把「用户对花生过敏」写进 Prompt 就完事。模型可能漏掉**花生油、花生酱**这类衍生品。

本项目用 `t_allergen_ingredient` 映射表把「花生」展开成一个**食材 ID 集合** `{1012, 1013, 1014}`，
`PlanValidator` 用**双重判据**校验：

1. 食材 ID 命中映射表（精确，能抓住衍生品）
2. 名称包含过敏原关键词（兜底，防映射表漏配）

命中即判为**硬错误**，整个方案作废并触发重试——不是警告。

### 2. 模型给的数字一律不信任

`MealPlanServiceImpl` 落库时会**后端重算**每日营养合计，不采信模型自己写的汇总值。
热量则由 `NutritionCalculator` 用 Mifflin-St Jeor 公式在后端算（含 1200–4000 千卡的钳制），
根本不交给模型。

### 3. SSE 用 POST，不用 EventSource

浏览器原生的 `EventSource` **只支持 GET**，而生成食谱的请求体（健康档案、过敏原、冰箱库存）太大，
塞进 URL 不现实。所以接口是 `POST`，前端用 `fetch` + `ReadableStream` 手动解析 SSE 帧。
完整的前端解析示例写在 `AiPlanController` 的 Javadoc 里。

另外做了三件容易被忽略的事：

- **心跳**：每 15 秒发一个 `heartbeat` 事件。Nginx / 网关默认 60 秒掐断空闲连接，没有心跳长任务必断
- **独立线程池**：AI 生成跑在 `aiTaskExecutor`（core 4 / max 16 / queue 100），不占用 Tomcat 请求线程
- **拒绝而非排队**：线程池满了直接 `AbortPolicy` 拒绝，避免请求堆积把内存拖垮

### 4. 购物清单的量纲换算

这是从「食谱」跨到「电商」的关键一步，四步链路：

1. **聚合**：把所有餐次的食材按 `食材ID|单位` 归并
2. **扣减冰箱**：用户冰箱里已有的先减掉（做名称归一，处理别名）
3. **匹配 SKU**：按价格升序取有库存的 SKU
4. **整包换算**：`needBuy.divide(conversion, 0, RoundingMode.CEILING)`

第 4 步最关键：需要 400g、商品规格是 500g/份时，要买 **1 份**而不是 0.8 份。
生鲜只能整包卖，`Math.max(quantity, 1)` 保证至少买 1 份。

### 5. 三层降级，任何一层挂掉服务都可用

```
真实 DeepSeek API
   ↓ 失败/未配 Key
MockLlmClient（Java 对象序列化，保证 JSON 合法）
   ↓ 解析仍失败
TemplatePlanFactory 模板食谱
```

`PlanStatusEnum` 记录方案最终是 `SUCCESS` / `FALLBACK` / `FAILED`，前端能看出这份方案是真是假。

### 6. Prompt 注入防护

用户的自由文本（比如「不吃什么」）是注入入口。`PromptBuilder.sanitizeInputs()` 用正则
`[^\u4e00-\u9fa5a-zA-Z0-9\s,.，。、/\-]` 清洗掉特殊字符，再包进 JSON 结构，
配合 System Prompt 的 11 条约束，构成三重防护。

### 7. 缓存抽象：一套代码适配本地与生产

`CacheService` 接口有两个实现，靠 `smartmeal.cache.type` 切换：

- `local`（默认）→ Caffeine，**零依赖可启动**
- `redis` → Redis，`deleteByPrefix` 用 `SCAN` 游标而不是 `KEYS`（`KEYS` 会阻塞整个实例），
  限流用 `INCR` + 首次 `EXPIRE` 实现固定窗口

Caffeine 实现里自定义了 `Expiry`，`expireAfterUpdate` 返回原剩余时间，
使得 `increment` 表现为固定窗口而不是滑动窗口。

### 8. 幂等

生成接口要求带 `requestId`，配合唯一索引，重复提交不会重复落库。

### 9. 演示页刻意不做前端工程化

前端就三个文件，放在 `app-api` 的 `static/` 下：`index.html` + `app.css` + `app.js`，
原生 JS，没有 npm、没有构建、没有框架。这不是偷懒，是权衡：

- **这个项目要讲的是后端**。引入 Vite + React 会让仓库多出一整套 `node_modules`、
  构建产物和版本漂移，而它能证明的东西只是「我会用脚手架」——简历上写不出增量。
- **`java -jar` 起完就能点**，面试时不需要先 `npm install` 等两分钟。
- 演示页要展示的核心是 **SSE 的消费方式**，而它恰好是框架最容易盖住的部分：
  用 fetch + ReadableStream 手写事件块解析（`\n\n` 切块、`event:` / `data:` 两行），
  比 `EventSource` 一行搞定更能说明「我知道为什么不能那么写」。
- **渲染路径只有一条**：页面初始化（拉 `/meal-plan/latest`）和生成结束（收到 `result` 后拉
  `/meal-plan/{id}`）走的是同一个 `renderPlan()`。这样「刚生成完显示得好好的、
  刷新一下就少东西」这类问题从结构上就不会出现。

代价也很明确：**没有组件复用，样式是手写的**。页面再复杂一点就该换框架了，
但目前这个体量下，手写的总行数（HTML 150 + CSS 590 + JS 450）比一个 `package.json` 的配置还少。

### 10. 登录：BCrypt + 防用户名枚举 + 失败锁定

鉴权是 Sa-Token 负责的（签发 token、拦截 `/api/app/**` 的白名单），但**校验口令**这件事属于
业务规则，放在 `UserAuthService` 里，不碰 Web 层。这样 `UserAuthServiceImplTest` 不需要
任何 Web 上下文就能单测，也是项目里所有「算错了就出事」逻辑的统一位置。

三个安全细节是各自针对攻击面加的，不是顺手写的：

1. **三种失败对外完全一致**。用户不存在、密码错、账号被禁用，都返回同一个
   `PASSWORD_ERROR`（「用户名或密码错误」）。如果给「用户不存在」单独一个提示，
   登录接口就成了用户名枚举器——攻击者拿常见字典跑一遍，就能筛出哪些账号真实存在。
2. **用户不存在时也要跑一次 BCrypt**。BCrypt 一次几十毫秒，如果「查无此人」直接返回、
   跳过哈希计算，响应时间会明显短于「密码错误」，攻击者用响应时间差同样能枚举用户名
   （时序侧信道）。代价是每次不存在的登录都多算一次哈希，由失败计数与上游限流兜住。
3. **失败计数成功后必须清零**。否则「前 4 次输错、第 5 次输对」这种正常行为会把计数器留在 4，
   用户下次手滑一次就被锁，看起来像系统坏了。

失败的判定走 `CacheService.increment()` 的**返回值**而不是「自增完再 `get` 回来」：
本地实现存 `AtomicLong`、Redis 实现存 `INCR` 的裸整数，读回来靠的是「裸数字恰好是合法 JSON」
这个巧合。`increment` 的返回值是接口契约，不依赖任何存储细节——这条在 Redis 下尤其重要。

BCrypt 哈希只存哈希、不存 salt 列：salt 编码在哈希串自身（`$2a$10$<22位salt><31位摘要>`）。
口令只存哈希这一条，通过 `register` 里「`user.setPassword(encoder.encode(raw))` 是唯一写库点」
来保证——任何路径都不会把明文落到数据库、日志或返回值。

---

## 五、接入 pgvector

当前 RAG 用的是 `InMemoryRetrievalService`：关键词 + 结构化加权打分，**不是向量检索**。

这是一个有意为之的过渡方案。RAG 里真正难的部分——**过敏原过滤、Prompt 注入格式、引用溯源**——
和底层是不是向量库无关。先用手写打分把骨架跑通，换向量库时只需要替换 `score()` 的实现。

切换步骤：

1. 起 PostgreSQL + pgvector：`docker compose --profile vector up -d postgres`
2. 执行 `smartmeal-deploy/sql/pgvector-init.sql`（建 `vector(1024)` 字段 + HNSW 索引，m=16 / ef_construction=64）
3. **Embedding 要另配一家**：DeepSeek 官方没有 Embedding 模型。
   常见组合是阿里云百炼 `text-embedding-v3`（1024 维，和建表维度对齐）或本地 Ollama
4. 把 `spring.ai.model.embedding` 从 `none` 改成实际模型
5. 新增 `PgVectorRetrievalService implements RetrievalService`，用 `@ConditionalOnProperty` 替换实现

---

## 六、Docker 部署

```bash
cd smartmeal-deploy
docker compose up -d                    # mysql + redis + app-api + admin-api + nginx
docker compose --profile vector up -d   # 额外起 postgres（pgvector）
```

`nginx.conf` 里对 SSE 接口单独做了处理，**关闭缓冲三件套**：

```nginx
proxy_buffering off;
proxy_cache off;
proxy_request_buffering off;
add_header X-Accel-Buffering no;
proxy_read_timeout 300s;
```

少任何一条，SSE 都会变成「等全部生成完才一次性吐出来」，流式效果全无。

---

## 七、踩过的坑

这些都是实际编译/运行时报错后才定位到的，记下来省得重复踩。

### 1. MyBatis-Plus 找不到 `PaginationInnerInterceptor`

MyBatis-Plus **从 3.5.9 起**把依赖 JSqlParser 的拦截器拆到了独立模块，starter 不再传递引入。
必须在 `smartmeal-repository/pom.xml` 显式加：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-jsqlparser</artifactId>
    <version>${mybatis-plus.version}</version>
</dependency>
```

### 2. Knife4j 4.5.0 与 springdoc 2.9.1 冲突，`/v3/api-docs` 直接 500

报错：

```
java.lang.NoSuchMethodError:
'java.util.List org.springdoc.core.properties.SpringDocConfigProperties.getGroupConfigs()'
    at com.github.xiaoymin.knife4j.spring.extension.Knife4jOpenApiCustomizer.addOrderExtension
```

原因：Knife4j 4.5.0（已是最后一个版本）内部按 **springdoc 2.3.0** 编译，
而 springdoc **从 2.4.0 起**把 `getGroupConfigs()` 的返回类型从 `List` 改成了 `Set`。

把 springdoc 降回 2.3.0 也不行——springdoc < 2.7.0 使用了 Spring 6.2 已删除的
`ControllerAdviceBean` 构造器，在 Boot 3.5 上会以另一种 `NoSuchMethodError` 挂掉。

**解法**：保留 springdoc 2.9.1，在配置里关掉 Knife4j 的增强自动配置：

```yaml
knife4j:
  enable: false   # 只关增强，/doc.html 静态界面不受影响
```

`/doc.html` 是 Knife4j 的静态资源，仍然可用，文档数据由 springdoc 正常生成。

### 3. 本地没 Redis，但 `/actuator/health` 一直在连 6379

`spring-boot-starter-data-redis` 在 classpath 上，Boot 会自动注册 Redis 健康检查，
哪怕业务上根本没用 Redis。在 `application-local.yml` 关掉：

```yaml
management:
  health:
    redis:
      enabled: false
```

### 4. 数据库没起来时，`/actuator/health` 要等 30 秒

Hikari 的 `connection-timeout` 默认 30s，健康检查会一直等到超时。
本地覆盖成 3s 快速失败（`application-local.yml`）。

### 5. `@Scheduled` 的异常会打一整屏堆栈

`@Scheduled` 默认把异常交给 `TaskUtils$LoggingErrorHandler`，数据库抖动一次就刷一屏，
业务日志全被埋掉。定时任务里必须自己 `try-catch` 降级成一行 WARN。

### 6. Spring AI 的 api-key 不能为空

`spring.ai.openai.api-key` 为空时，Spring AI 自动配置**直接抛异常**，导致「没配 Key 就连服务都起不来」。
必须给一个非空占位值（如 `sk-not-configured-placeholder`），
是否真的走大模型由 `smartmeal.ai.api-key` 决定。

### 7. MyBatis-Plus 的 Wrapper 在调用时拿不到参数值

写单元测试时想从 `LambdaQueryWrapper` 里反推 `eq(ProductSku::getIngredientId, 1001L)` 的值，
结果 `getParamNameValuePairs()` 返回的是**空 Map**。原因是 MyBatis-Plus 3.5.x 把参数填充
推迟到了 SQL 生成阶段，`eq()` 只是登记条件、不填值。

**应对**：不要反射框架内部。把真正有风险的算法抽成纯函数（`packsNeeded`）直接测，
其余用例每个只涉及一种数据，让 mock 无需过滤也能正确返回。

---

## 八、跑起来之后发现的七个逻辑 bug

这七个都不是编译错误。前三个是写完单元测试才暴露的，后四个更隐蔽 ——
只有真正把服务跑起来、发一次请求、再回头查库（或者盯着页面看）才看得见。
记在这里是因为它们恰好都是「看起来没问题、实际会出事」的典型。

### 1. 过敏原校验会因为映射表缺行而**静默失效**（最严重）

`PlanValidator` 的守卫是 `if (!profile.hasAllergy()) return;`，
而 `hasAllergy()` 原本判断的是「展开后的食材 Map 是否为空」。

于是当 `t_allergen_ingredient` 里没有某个过敏原的映射记录时（运营漏配，真实会发生），
展开结果为空 → `hasAllergy()` 返回 false → **整段过敏原校验被跳过**。
讽刺的是 `UserProfileServiceImpl` 那行日志还写着「校验将退化为按名称匹配」，
但代码根本走不到名称匹配那一步。

**修复**：`hasAllergy()` 改为同时看「申报的过敏原编码」和「展开结果」；
并在展开为空时显式抛出一条警告，告诉用户这次无法精确校验。

### 2. 冰箱扣减只扣了 1 克

`t_user_fridge_ingredient` 里存的是 `amount=300, unit='g'`，
但 `UserProfile.fridgeIngredients` 是 `List<String>` —— **只带了名字，数量在读取时就被丢掉了**。
下游 `buildFridgeStock` 于是无脑扣 `BigDecimal.ONE`，即「冰箱有 300g 番茄」只扣 1g。

**修复**：新增 `fridgeStock`（名称 + 数量 + 单位）字段，购物清单按真实数量扣减。
数量缺失时才退化为扣 1 个单位；**单位对不上则完全不扣** —— 少扣最多是多买一点，
乱扣会让用户该买的没买，直接做不成菜。

### 3. 「模型返回结果为空」这个分支走不到

`validateStructure()` 里写了 `if (result == null)` 的分支并 `return`，
但 `validate()` 并没有短路，紧接着的 `validateEntities()` 直接 `result.getDays()` 抛 NPE。
结果是这个错误分支形同虚设，用户看到的是 500 而不是一条可读的失败原因。

**修复**：在 `validate()` 入口就把 `null` 短路返回。

### 4. 落库的热量目标被模型回传值覆盖

把 SSE 链路跑通后回头查库，发现 `t_meal_plan.daily_calorie_target` 落的是 **1600**，
而同一份计划返回给前端的警告里却写着「偏离目标 **2155** 千卡 47%」—— 两个数对不上。

根因是 `MealPlanServiceImpl.saveResult()` 的写法是「result 优先、否则回落 profile」。
但 `MealPlanResult.dailyCalorieTarget` 只是**模型对 Prompt 输入的复述**，没有独立信息量，
却被当成了真相源。结果是三套标准各说各话：校验器按 2155 判偏离度、数据库存 1600、
前端拿到 1600，用户无从判断哪个才对。

**修复**：落库与 SSE 透传统一改用 `profile.getDailyCalorieTarget()`
（由 `NutritionCalculator` 按 Mifflin-St Jeor 实算）；`MockLlmClient` 不再设置该字段 ——
它拿不到 `UserProfile`，填任何值都是编的。

### 5. 请求 3 天，落库 7 天

请求体写的是 `"days":3`，`t_meal_plan.days` 也确实是 3，
但 `t_meal_plan_day` 里有 **7 行**明细。

两个原因叠加：

1. `MockLlmClient` 硬编码 `buildJson(7)`，**完全忽略请求天数**；
2. `PlanValidator` 只检查「天数不为空」，**不检查「是否等于请求天数」**。

于是产出一份自相矛盾的数据：前端按主表渲染会多出 4 天，按明细渲染又对不上主表。

**修复**：Mock 改为从 Prompt 里解析天数（`LlmClient.chat()` 的签名里没有天数参数，
只能从 `PromptBuilder` 拼好的用户 JSON 反查 `"days":N`，并做 1~14 的防御性收敛）；
`PlanValidator.validateStructure()` 增加天数一致性校验，且**判硬错误而非警告** ——
少给天数是「需求没被满足」，不是「建议优化」，而且重试很可能就拿到完整的了。

### 6. 演示数据本身失真，让 demo 看起来像坏了

`MockLlmClient` 返回的数据结构完全合法，但有三处失真，跑起来一眼假：

1. **菜谱 ID 是编的** —— 用 `5000 + index` 算出来，而 `t_recipe` 是从 **5001** 开始的，
   于是每份计划都夹带一个不存在的菜谱 5000，前端点详情会 404。
   更麻烦的是数组顺序和库里的 ID 顺序也不一致，即便把 ID 改对了，
   还会出现「ID 指向香煎龙利鱼、名称却写着番茄鸡蛋汤面」的错配。
2. **热量离目标太远** —— 3 餐合计只有 1000 千卡上下，对 2155 的目标偏离约 50%，
   前端刷一屏「第 N 天总热量偏离目标 xx%」的警告。这不是系统算错了，
   而是演示数据太素，但用户看到的就是「这系统好像有问题」。

**修复**：菜谱池照抄 `t_recipe` 的种子数据（ID / 名称 / 餐次 / 营养值全对齐），
并按餐次筛选，避免「早餐推荐蒜蓉炒菠菜」；从 Prompt 反查 `dailyCalorieTarget`，
用 `servings` 把单餐热量凑到目标附近（份数同步放大食材用量与营养值），
让日均热量偏离压进 `PlanValidator` 的 ±20% 容差内。

### 7. 菜名和食材对不上（有界面之后才暴露）

第 6 条修完之后，日志干净了、校验通过了、测试全绿了 —— 直到把演示页做出来，
第一眼看过去就是：

| 餐次 | 菜名 | 页面上挂的食材 |
|---|---|---|
| 早餐 | 番茄炒蛋 | 鸡蛋、**鸡胸肉** |
| 午餐 | 清蒸鸡胸肉配西兰花 | 西兰花、**燕麦** |
| 晚餐 | 豆腐菌菇煲 | **牛奶**、糙米 |

原因在 Mock 里的一行「聪明」代码：

```java
// 用菜谱 ID 做偏移：同一道菜每次配的食材一致，不同菜之间又有差异
int offset = (int) (recipe.id() % INGREDIENT_POOL.size());
```

它看起来是个合理的「伪随机」，但食材池的每个元素只有一个固定的 `amountPerServing` ——
**这个数据结构本身就表达不了真实需求**：同一种食材在不同菜里用量本来就不同
（鸡胸肉在「清蒸鸡胸肉配西兰花」是 150g，在「凉拌鸡丝黄瓜」是 120g；
菠菜在「豆腐菌菇煲」是 100g，在「蒜蓉炒菠菜」是 200g）。
把用量塞进「食材主数据」，就只能取其中一个值，另一道菜必然是错的。

**这个 bug 特别值得记下来，因为它躲过了所有自动检查**：JSON 合法、菜谱 ID 存在、
热量达标、测试全绿、日志无异常 —— 唯一的发现方式是**人眼看页面**。
换句话说，不做前端就永远发现不了；而一旦做前端，它就是第一眼看到的那个问题。

**修复**：照 `t_recipe_ingredient` 的结构拆成两张表 ——
`INGREDIENT_POOL`（食材主数据，只有 ID / 名称 / 单位）+ `RECIPE_INGREDIENTS`
（菜谱 → 每份用量），用量随 `servings` 放大。同时加了一个类加载时的静态自检：
菜谱缺食材、或引用了不存在的食材 ID，直接抛异常让服务起不来 ——
宁可启动就炸，也不要静默产出一份自相矛盾的演示数据。

---

## 九、已知限制

- **RAG 是关键词检索，不是向量检索**（见「接入 pgvector」）
- **登录失败计数只按用户名维度，不按来源 IP**：知道用户名的人可以故意输错把账号锁死
  （定向 DoS）。生产应改成「用户名 + IP」双维度或指数退避。之所以没做，是因为 Service 层
  拿不到请求 IP（那需要把 Web 层的东西一路透传下来），取舍是「先记录清楚，不假装解决了」
- **`AuthController` 没有验证码 / 二次验证**，锁定是唯一的暴力破解防线
- **`KnowledgeSyncJob` / `EmbeddingRetryJob` 是占位实现**，默认关闭
- **订单支付没有接真实支付**，只有状态流转和超时取消
- **`MealPlanTaskRegistry` 是进程内内存表**，多实例部署时会查不到彼此的任务，需换成 Redis
- **`/api/app/ai/meal-plan/stream` 的请求体只能传冰箱食材名称，传不了数量**，
  所以「按真实数量扣减冰箱」只在档案从数据库读取时生效；请求体传入的冰箱项会退化为
  「扣 1 个基准单位」。要彻底解决得把请求体字段从 `List<String>` 改成带数量的结构
- **过敏原映射表要人工维护**：新增过敏原时必须同步往 `t_allergen_ingredient` 补记录，
  否则系统只能给出「无法精确校验」的警告，拦不住衍生品
