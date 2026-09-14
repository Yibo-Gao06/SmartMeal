-- =============================================================
--  SmartMeal 业务库初始化脚本 (MySQL 8.0)
--
--  执行方式：
--    mysql -uroot -p < mysql-init.sql
--  或先建库再执行：
--    CREATE DATABASE smartmeal DEFAULT CHARACTER SET utf8mb4;
--
--  说明：
--    * 所有表统一 utf8mb4，避免 emoji 与生僻字插入报错
--    * 金额统一 DECIMAL(10,2)，绝不用 FLOAT（浮点误差会导致对账不平）
--    * 订单明细冗余商品快照，商品改价不影响历史订单
-- =============================================================

CREATE DATABASE IF NOT EXISTS smartmeal
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE smartmeal;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =============================================================
-- 一、用户与画像
-- =============================================================

DROP TABLE IF EXISTS t_user;
CREATE TABLE t_user (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    username       VARCHAR(64)  NOT NULL COMMENT '登录名',
    password       VARCHAR(128) NOT NULL COMMENT 'BCrypt 密文，禁止明文',
    nickname       VARCHAR(64)           COMMENT '昵称',
    phone          VARCHAR(20)           COMMENT '手机号',
    avatar         VARCHAR(255)          COMMENT '头像地址',
    gender         TINYINT      DEFAULT 0 COMMENT '0未知 1男 2女',
    birth_date     DATE                  COMMENT '出生日期',
    height_cm      DECIMAL(5,1)          COMMENT '身高cm',
    weight_kg      DECIMAL(5,1)          COMMENT '体重kg',
    activity_level VARCHAR(32)           COMMENT '活动量 low/middle/high',
    goal           VARCHAR(32)           COMMENT '目标 loss_fat/gain_muscle/balance/low_sugar',
    weekly_budget  DECIMAL(10,2)         COMMENT '周预算',
    family_size    INT          DEFAULT 1 COMMENT '家庭人数',
    status         TINYINT      DEFAULT 1 COMMENT '1正常 0禁用',
    create_time    DATETIME,
    update_time    DATETIME,
    UNIQUE KEY uk_username (username),
    KEY idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

DROP TABLE IF EXISTS t_user_allergy;
CREATE TABLE t_user_allergy (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    allergen_code VARCHAR(64) NOT NULL COMMENT '标准化编码 PEANUT/SEAFOOD/MILK/EGG/NUT',
    allergen_name VARCHAR(64) NOT NULL COMMENT '中文名，仅用于展示',
    severity      VARCHAR(16)          COMMENT 'mild/moderate/severe',
    create_time   DATETIME,
    update_time   DATETIME,
    UNIQUE KEY uk_user_allergen (user_id, allergen_code),
    KEY idx_allergen_code (allergen_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户过敏原';

DROP TABLE IF EXISTS t_user_fridge_ingredient;
CREATE TABLE t_user_fridge_ingredient (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    ingredient_id   BIGINT               COMMENT '归一后的食材ID，匹配不到时为NULL',
    ingredient_name VARCHAR(64)          COMMENT '用户手填名称',
    amount          DECIMAL(10,2),
    unit            VARCHAR(16)          COMMENT 'g/ml/piece',
    expire_date     DATE                 COMMENT '过期日期，已过期的不会用于抵扣',
    create_time     DATETIME,
    update_time     DATETIME,
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户冰箱库存';

-- =============================================================
-- 二、食材与过敏原映射
-- =============================================================

DROP TABLE IF EXISTS t_category;
CREATE TABLE t_category (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(64) NOT NULL,
    parent_id   BIGINT      DEFAULT 0,
    sort        INT         DEFAULT 0,
    status      TINYINT     DEFAULT 1,
    create_time DATETIME,
    update_time DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分类';

DROP TABLE IF EXISTS t_ingredient;
CREATE TABLE t_ingredient (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    name              VARCHAR(64)  NOT NULL,
    alias             VARCHAR(255)          COMMENT '别名，多个用逗号分隔，如「西红柿,洋柿子」',
    category_id       BIGINT,
    unit              VARCHAR(16)  DEFAULT 'g' COMMENT '基准单位',
    calories_per_100g DECIMAL(10,2)         COMMENT '每100g热量(千卡)',
    protein_per_100g  DECIMAL(10,2),
    fat_per_100g      DECIMAL(10,2),
    carb_per_100g     DECIMAL(10,2),
    allergen_tags     VARCHAR(255)          COMMENT 'JSON数组，如 ["PEANUT"]',
    suitable_goals    VARCHAR(255)          COMMENT 'JSON数组，如 ["loss_fat","balance"]',
    status            TINYINT      DEFAULT 1,
    create_time       DATETIME,
    update_time       DATETIME,
    UNIQUE KEY uk_name (name),
    KEY idx_category (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='食材';

-- 过敏原安全链路的基石：把过敏原展开成食材集合，才能拦住「花生油」这类衍生品
DROP TABLE IF EXISTS t_allergen_ingredient;
CREATE TABLE t_allergen_ingredient (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    allergen_code VARCHAR(64) NOT NULL,
    ingredient_id BIGINT      NOT NULL,
    relation_type VARCHAR(16) DEFAULT 'EXACT' COMMENT 'EXACT直接命中/DERIVED衍生制品/TRACE可能含痕量',
    create_time   DATETIME,
    update_time   DATETIME,
    UNIQUE KEY uk_allergen_ingredient (allergen_code, ingredient_id),
    KEY idx_ingredient (ingredient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='过敏原→食材映射';

-- =============================================================
-- 三、菜谱
-- =============================================================

DROP TABLE IF EXISTS t_recipe;
CREATE TABLE t_recipe (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    name          VARCHAR(128) NOT NULL,
    cover_image   VARCHAR(255),
    description   TEXT,
    cuisine_type  VARCHAR(32)           COMMENT '菜系',
    meal_type     VARCHAR(32)           COMMENT 'breakfast/lunch/dinner/snack',
    cook_time     INT                   COMMENT '烹饪时间(分钟)',
    difficulty    TINYINT               COMMENT '1-5',
    calories      DECIMAL(10,2),
    protein       DECIMAL(10,2),
    fat           DECIMAL(10,2),
    carb          DECIMAL(10,2),
    tags          VARCHAR(255)          COMMENT '如 loss_fat,high_protein',
    allergen_tags VARCHAR(255)          COMMENT 'JSON数组',
    status        TINYINT      DEFAULT 1,
    create_time   DATETIME,
    update_time   DATETIME,
    KEY idx_meal_type (meal_type),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜谱';

DROP TABLE IF EXISTS t_recipe_ingredient;
CREATE TABLE t_recipe_ingredient (
    id                       BIGINT PRIMARY KEY AUTO_INCREMENT,
    recipe_id                BIGINT NOT NULL,
    ingredient_id            BIGINT NOT NULL,
    amount                   DECIMAL(10,2) COMMENT '单份用量',
    unit                     VARCHAR(16),
    optional                 TINYINT DEFAULT 0 COMMENT '1为可选配料，不计入购物清单',
    substitute_ingredient_id BIGINT          COMMENT '默认替代食材',
    create_time              DATETIME,
    update_time              DATETIME,
    UNIQUE KEY uk_recipe_ingredient (recipe_id, ingredient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜谱食材';

DROP TABLE IF EXISTS t_recipe_step;
CREATE TABLE t_recipe_step (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    recipe_id   BIGINT NOT NULL,
    step_no     INT    NOT NULL,
    content     TEXT,
    image       VARCHAR(255),
    create_time DATETIME,
    update_time DATETIME,
    KEY idx_recipe (recipe_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜谱步骤';

-- =============================================================
-- 四、商品与库存
-- =============================================================

DROP TABLE IF EXISTS t_product;
CREATE TABLE t_product (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(128) NOT NULL,
    category_id BIGINT,
    image       VARCHAR(255),
    description TEXT,
    status      TINYINT DEFAULT 1,
    create_time DATETIME,
    update_time DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SPU';

-- ingredient_id + conversion_rate 是「菜谱食材 → 可购买商品」的桥梁
DROP TABLE IF EXISTS t_product_sku;
CREATE TABLE t_product_sku (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id      BIGINT NOT NULL,
    sku_name        VARCHAR(128),
    ingredient_id   BIGINT          COMMENT '关联食材',
    price           DECIMAL(10,2),
    stock           INT DEFAULT 0,
    unit            VARCHAR(16),
    spec            VARCHAR(64)     COMMENT '如 500g/份',
    conversion_rate DECIMAL(10,2)   COMMENT '1个销售单位 = 多少基准单位',
    status          TINYINT DEFAULT 1,
    create_time     DATETIME,
    update_time     DATETIME,
    KEY idx_product (product_id),
    KEY idx_ingredient (ingredient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SKU';

-- =============================================================
-- 五、购物车与订单
-- =============================================================

DROP TABLE IF EXISTS t_cart;
CREATE TABLE t_cart (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT NOT NULL,
    sku_id      BIGINT NOT NULL,
    quantity    INT    NOT NULL,
    selected    TINYINT DEFAULT 1,
    plan_id     BIGINT          COMMENT '来自哪份AI膳食计划，用于溯源',
    create_time DATETIME,
    update_time DATETIME,
    UNIQUE KEY uk_user_sku_plan (user_id, sku_id, plan_id),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车';

DROP TABLE IF EXISTS t_order;
CREATE TABLE t_order (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no        VARCHAR(64) NOT NULL COMMENT '对外订单号，主键不外泄',
    user_id         BIGINT NOT NULL,
    plan_id         BIGINT               COMMENT '关联的AI计划',
    source_type     VARCHAR(32) DEFAULT 'NORMAL' COMMENT 'AI_PLAN/NORMAL，用于统计AI转化率',
    total_amount    DECIMAL(10,2),
    pay_amount      DECIMAL(10,2),
    freight_amount  DECIMAL(10,2) DEFAULT 0,
    discount_amount DECIMAL(10,2) DEFAULT 0,
    status          VARCHAR(32)          COMMENT 'PENDING_PAYMENT/PAID/DELIVERING/COMPLETED/CANCELLED/REFUNDING/REFUNDED',
    pay_type        VARCHAR(32),
    pay_time        DATETIME,
    delivery_time   DATETIME,
    finish_time     DATETIME,
    cancel_time     DATETIME,
    remark          VARCHAR(255),
    create_time     DATETIME,
    update_time     DATETIME,
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_status (user_id, status),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

DROP TABLE IF EXISTS t_order_item;
CREATE TABLE t_order_item (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id     BIGINT NOT NULL,
    order_no     VARCHAR(64),
    sku_id       BIGINT NOT NULL,
    product_name VARCHAR(128) COMMENT '下单时快照，商品改名不影响历史订单',
    sku_name     VARCHAR(128),
    image        VARCHAR(255),
    price        DECIMAL(10,2) COMMENT '下单时快照',
    quantity     INT,
    amount       DECIMAL(10,2),
    create_time  DATETIME,
    update_time  DATETIME,
    KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

-- =============================================================
-- 六、AI 膳食计划
-- =============================================================

-- request_id 唯一索引是幂等的基础：重复提交会被数据库直接拦掉
DROP TABLE IF EXISTS t_meal_plan;
CREATE TABLE t_meal_plan (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL,
    plan_no             VARCHAR(64),
    goal                VARCHAR(32),
    daily_calorie_target INT,
    days                INT DEFAULT 7,
    status              VARCHAR(32) COMMENT 'GENERATING/SUCCESS/FALLBACK/FAILED',
    request_id          VARCHAR(64) COMMENT '幂等键，前端生成',
    prompt_tokens       INT,
    completion_tokens   INT,
    total_tokens        INT         COMMENT '用于成本统计与异常检测',
    model_name          VARCHAR(64),
    error_msg           TEXT,
    warnings            TEXT        COMMENT '生成过程的警告，按行分隔；含过敏原映射缺失等安全提示，必须持久化',
    create_time         DATETIME,
    update_time         DATETIME,
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_user (user_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI膳食计划';

DROP TABLE IF EXISTS t_meal_plan_day;
CREATE TABLE t_meal_plan_day (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    plan_id        BIGINT NOT NULL,
    day_no         INT    NOT NULL,
    plan_date      DATE,
    summary        VARCHAR(255),
    total_calories DECIMAL(10,2),
    total_protein  DECIMAL(10,2),
    total_fat      DECIMAL(10,2),
    total_carb     DECIMAL(10,2),
    create_time    DATETIME,
    update_time    DATETIME,
    UNIQUE KEY uk_plan_day (plan_id, day_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计划每日';

DROP TABLE IF EXISTS t_meal_plan_meal;
CREATE TABLE t_meal_plan_meal (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    plan_day_id BIGINT NOT NULL,
    meal_type   VARCHAR(32),
    recipe_id   BIGINT,
    recipe_name VARCHAR(128),
    servings    INT DEFAULT 1,
    calories    DECIMAL(10,2),
    protein     DECIMAL(10,2),
    fat         DECIMAL(10,2),
    carb        DECIMAL(10,2),
    reason      VARCHAR(255) COMMENT '推荐理由',
    source_ids  VARCHAR(255) COMMENT 'RAG引用来源，如 RECIPE_5001,INGREDIENT_1001',
    create_time DATETIME,
    update_time DATETIME,
    KEY idx_plan_day (plan_day_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计划每餐';

DROP TABLE IF EXISTS t_meal_plan_meal_item;
CREATE TABLE t_meal_plan_meal_item (
    id                       BIGINT PRIMARY KEY AUTO_INCREMENT,
    meal_id                  BIGINT NOT NULL,
    ingredient_id            BIGINT,
    ingredient_name          VARCHAR(64),
    amount                   DECIMAL(10,2),
    unit                     VARCHAR(16),
    substitute_ingredient_id BIGINT,
    sku_id                   BIGINT,
    create_time              DATETIME,
    update_time              DATETIME,
    KEY idx_meal (meal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计划每餐食材';

DROP TABLE IF EXISTS t_shopping_list;
CREATE TABLE t_shopping_list (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT NOT NULL,
    plan_id     BIGINT NOT NULL,
    title       VARCHAR(128),
    total_price DECIMAL(10,2),
    status      VARCHAR(32) COMMENT 'DRAFT/CONFIRMED/ADDED_TO_CART',
    create_time DATETIME,
    update_time DATETIME,
    KEY idx_plan (plan_id),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物清单';

-- required(需要) - fridge(冰箱有) = need_buy(要买)，这是整条链路的核心计算
DROP TABLE IF EXISTS t_shopping_list_item;
CREATE TABLE t_shopping_list_item (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    list_id          BIGINT NOT NULL,
    ingredient_id    BIGINT,
    ingredient_name  VARCHAR(64),
    required_amount  DECIMAL(10,2) COMMENT '菜谱需要总量',
    unit             VARCHAR(16),
    fridge_amount    DECIMAL(10,2) DEFAULT 0 COMMENT '冰箱已有量',
    need_buy_amount  DECIMAL(10,2) COMMENT '实际需购买量',
    sku_id           BIGINT,
    sku_name         VARCHAR(128),
    quantity         INT           COMMENT '购买份数，已向上取整',
    price            DECIMAL(10,2) COMMENT '单价',
    substitute_sku_id BIGINT       COMMENT '缺货时的替代SKU',
    status           VARCHAR(32)   COMMENT 'AVAILABLE/OUT_OF_STOCK/SUBSTITUTED',
    create_time      DATETIME,
    update_time      DATETIME,
    KEY idx_list (list_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物清单明细';

-- =============================================================
-- 七、知识库（RAG 语料）
-- =============================================================

DROP TABLE IF EXISTS t_knowledge_document;
CREATE TABLE t_knowledge_document (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_type VARCHAR(32) COMMENT 'RECIPE/INGREDIENT/NUTRITION/GUIDE',
    source_id   BIGINT,
    title       VARCHAR(255),
    content     TEXT,
    metadata    JSON        COMMENT '结构化元信息，检索时可用于过滤',
    version     INT DEFAULT 1 COMMENT '版本号，用于增量同步到向量库',
    status      TINYINT DEFAULT 1,
    create_time DATETIME,
    update_time DATETIME,
    KEY idx_source (source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档';

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================
-- 八、种子数据（让骨架一启动就能跑通完整链路）
-- =============================================================

INSERT INTO t_category (id, name, parent_id, sort) VALUES
(1,'蔬菜',0,1),(2,'水果',0,2),(3,'肉禽蛋',0,3),
(4,'水产',0,4),(5,'粮油',0,5),(6,'调味',0,6);

INSERT INTO t_ingredient
(id,name,alias,category_id,unit,calories_per_100g,protein_per_100g,fat_per_100g,carb_per_100g,allergen_tags,suitable_goals,status) VALUES
(1001,'番茄','西红柿,洋柿子',1,'g',18,0.90,0.20,3.90,'[]','["loss_fat","balance","low_sugar"]',1),
(1002,'鸡蛋',NULL,3,'piece',144,13.30,8.80,2.80,'["EGG"]','["gain_muscle","balance"]',1),
(1003,'鸡胸肉','鸡脯肉',3,'g',133,19.40,5.00,2.50,'[]','["loss_fat","gain_muscle"]',1),
(1004,'西兰花','绿花菜',1,'g',36,4.10,0.60,4.30,'[]','["loss_fat","low_sugar"]',1),
(1005,'燕麦','燕麦片',5,'g',377,15.00,6.70,61.00,'[]','["loss_fat","balance"]',1),
(1006,'牛奶','鲜奶',3,'ml',54,3.00,3.20,3.40,'["MILK"]','["gain_muscle","balance"]',1),
(1007,'糙米',NULL,5,'g',348,7.70,2.70,72.00,'[]','["loss_fat","low_sugar"]',1),
(1008,'豆腐',NULL,1,'g',82,8.10,3.70,4.20,'["SOY"]','["loss_fat","balance"]',1),
(1009,'菠菜',NULL,1,'g',28,2.60,0.30,4.50,'[]','["loss_fat","low_sugar"]',1),
(1010,'龙利鱼','巴沙鱼',4,'g',83,17.70,1.40,0.50,'["SEAFOOD"]','["loss_fat","gain_muscle"]',1),
(1011,'虾','基围虾',4,'g',93,18.60,0.80,2.80,'["SEAFOOD"]','["loss_fat","gain_muscle"]',1),
(1012,'花生','落花生',5,'g',567,25.80,49.20,16.10,'["PEANUT"]','["balance"]',1),
(1013,'花生油',NULL,6,'ml',899,0.00,99.90,0.00,'["PEANUT"]','[]',1),
(1014,'花生酱',NULL,6,'g',600,25.00,50.00,20.00,'["PEANUT"]','[]',1),
(1015,'黄瓜',NULL,1,'g',16,0.80,0.20,2.90,'[]','["loss_fat","low_sugar"]',1);

-- 过敏原映射：花生过敏必须同时拦住花生油和花生酱，否则安全校验是假的
INSERT INTO t_allergen_ingredient (allergen_code, ingredient_id, relation_type) VALUES
('PEANUT',1012,'EXACT'),('PEANUT',1013,'DERIVED'),('PEANUT',1014,'DERIVED'),
('SEAFOOD',1010,'EXACT'),('SEAFOOD',1011,'EXACT'),
('MILK',1006,'EXACT'),
('EGG',1002,'EXACT'),
('SOY',1008,'EXACT');

INSERT INTO t_recipe
(id,name,description,cuisine_type,meal_type,cook_time,difficulty,calories,protein,fat,carb,tags,allergen_tags,status) VALUES
(5001,'番茄炒蛋','经典家常菜，制作简单，蛋白质与维生素兼顾','家常','breakfast',15,1,220,14.00,14.00,8.00,'loss_fat,quick','["EGG"]',1),
(5002,'燕麦牛奶粥','低 GI 早餐，饱腹感强','西式','breakfast',10,1,310,12.00,7.00,52.00,'loss_fat,low_sugar','["MILK"]',1),
(5003,'清蒸鸡胸肉配西兰花','高蛋白低脂，减脂期主力餐','轻食','lunch',25,2,420,46.00,12.00,26.00,'loss_fat,high_protein','[]',1),
(5004,'香煎龙利鱼配糙米','优质蛋白 + 复合碳水','轻食','lunch',30,2,520,42.00,14.00,52.00,'loss_fat,high_protein','["SEAFOOD"]',1),
(5005,'豆腐菌菇煲','植物蛋白，清淡易消化','家常','dinner',20,2,300,22.00,14.00,22.00,'balance,low_sugar','["SOY"]',1),
(5006,'凉拌鸡丝黄瓜','低卡高蛋白，适合晚餐','凉菜','dinner',15,1,280,32.00,10.00,12.00,'loss_fat,high_protein','[]',1),
(5007,'蒜蓉炒菠菜','补铁补叶酸，快手素菜','家常','dinner',8,1,120,5.00,8.00,8.00,'loss_fat,low_sugar','[]',1),
(5008,'白灼虾配时蔬','高蛋白低脂，海鲜爱好者首选','粤菜','dinner',18,2,350,40.00,8.00,20.00,'loss_fat,high_protein','["SEAFOOD"]',1),
(5009,'番茄鸡蛋汤面','暖胃主食，适合早餐或夜宵','家常','breakfast',20,1,480,20.00,12.00,72.00,'balance,quick','["EGG"]',1),
(5010,'全麦鸡蛋三明治','便携早餐，蛋白质充足','西式','breakfast',12,1,340,18.00,12.00,40.00,'balance,quick','["EGG"]',1);

INSERT INTO t_recipe_ingredient (recipe_id, ingredient_id, amount, unit, optional) VALUES
(5001,1001,200,'g',0),(5001,1002,2,'piece',0),
(5002,1005,50,'g',0),(5002,1006,250,'ml',0),
(5003,1003,150,'g',0),(5003,1004,150,'g',0),
(5004,1010,180,'g',0),(5004,1007,80,'g',0),
(5005,1008,200,'g',0),(5005,1009,100,'g',0),
(5006,1003,120,'g',0),(5006,1015,100,'g',0),
(5007,1009,200,'g',0),
(5008,1011,200,'g',0),(5008,1004,100,'g',0),
(5009,1001,150,'g',0),(5009,1002,2,'piece',0),
(5010,1002,2,'piece',0),(5010,1005,60,'g',0);

INSERT INTO t_recipe_step (recipe_id, step_no, content) VALUES
(5001,1,'番茄切块，鸡蛋打散加少许盐'),
(5001,2,'热锅冷油炒散鸡蛋盛出'),
(5001,3,'下番茄炒出汁，回锅鸡蛋翻炒均匀'),
(5003,1,'鸡胸肉用盐和黑胡椒腌制10分钟'),
(5003,2,'上锅蒸15分钟'),
(5003,3,'西兰花焯水1分钟，摆盘即可');

-- 商品与 SKU：conversion_rate 是「份 → 基准单位」的换算比例
INSERT INTO t_product (id,name,category_id,description,status) VALUES
(8001,'新鲜番茄',1,'当日直采',1),(8002,'鲜鸡蛋',3,'散养土鸡蛋',1),
(8003,'冷鲜鸡胸肉',3,'冰鲜配送',1),(8004,'有机西兰花',1,'有机认证',1),
(8005,'即食燕麦片',5,'免煮即食',1),(8006,'纯牛奶',3,'全脂灭菌乳',1),
(8007,'五常糙米',5,'东北产区',1),(8008,'卤水豆腐',1,'非转基因黄豆',1),
(8009,'新鲜菠菜',1,'当日采摘',1),(8010,'冷冻龙利鱼柳',4,'去刺鱼柳',1),
(8011,'鲜活基围虾',4,'活鲜',1),(8012,'新鲜黄瓜',1,'当日采摘',1),
(8013,'纯正花生油',6,'压榨一级',1);

INSERT INTO t_product_sku (product_id,sku_name,ingredient_id,price,stock,unit,spec,conversion_rate,status) VALUES
(8001,'新鲜番茄 500g/份',1001,5.90,200,'份','500g/份',500,1),
(8001,'新鲜番茄 1kg/份',1001,10.90,100,'份','1kg/份',1000,1),
(8002,'鲜鸡蛋 10枚/盒',1002,9.90,150,'盒','10枚/盒',10,1),
(8003,'冷鲜鸡胸肉 250g/盒',1003,12.90,80,'盒','250g/盒',250,1),
(8004,'有机西兰花 300g/份',1004,6.50,120,'份','300g/份',300,1),
(8005,'即食燕麦片 500g/袋',1005,15.90,90,'袋','500g/袋',500,1),
(8006,'纯牛奶 1L/盒',1006,12.50,200,'盒','1L/盒',1000,1),
(8007,'五常糙米 1kg/袋',1007,19.90,60,'袋','1kg/袋',1000,1),
(8008,'卤水豆腐 400g/盒',1008,4.50,100,'盒','400g/盒',400,1),
(8009,'新鲜菠菜 300g/份',1009,4.90,110,'份','300g/份',300,1),
(8010,'冷冻龙利鱼柳 200g/袋',1010,18.90,70,'袋','200g/袋',200,1),
(8011,'鲜活基围虾 300g/份',1011,32.90,40,'份','300g/份',300,1),
(8012,'新鲜黄瓜 500g/份',1015,4.20,150,'份','500g/份',500,1),
(8013,'纯正花生油 900ml/瓶',1013,29.90,50,'瓶','900ml/瓶',900,1);

-- 演示用户（密码字段是占位，登录接口目前不校验密码）
INSERT INTO t_user (id,username,password,nickname,phone,gender,birth_date,height_cm,weight_kg,activity_level,goal,weekly_budget,family_size,status)
VALUES (1,'demo','$2a$10$PLACEHOLDER_PLEASE_REPLACE_WITH_BCRYPT','演示用户','13800000000',1,'2000-05-20',170.0,80.0,'middle','loss_fat',300.00,1,1);

-- 演示用户对花生过敏：生成结果里只要出现花生/花生油/花生酱就会被硬拦截
INSERT INTO t_user_allergy (user_id, allergen_code, allergen_name, severity)
VALUES (1,'PEANUT','花生','severe');

INSERT INTO t_user_fridge_ingredient (user_id, ingredient_id, ingredient_name, amount, unit)
VALUES (1,1001,'番茄',300,'g'),(1,1002,'鸡蛋',4,'piece');

-- 知识库种子：饮食指南类语料（菜谱与食材由同步任务生成）
INSERT INTO t_knowledge_document (source_type, source_id, title, content, version, status) VALUES
('GUIDE',9001,'减脂饮食原则','减脂期建议每日热量缺口 300-500 千卡，保证每公斤体重 1.6-2.0g 蛋白质摄入，优先选择低脂高蛋白食材，减少精制糖与油炸食品。','1',1),
('GUIDE',9002,'增肌饮食原则','增肌期建议每日热量盈余 200-400 千卡，蛋白质摄入每公斤体重 1.6-2.2g，训练后 1 小时内补充蛋白质与碳水效果更佳。','1',1),
('GUIDE',9003,'控糖饮食原则','控糖期应优先选择低 GI 主食，如糙米、燕麦，避免白米白面与含糖饮料，餐后适度活动有助于平稳血糖。','1',1),
('GUIDE',9004,'食物过敏原分类','常见八大过敏原包括：花生、坚果、牛奶、鸡蛋、大豆、小麦、鱼类、甲壳类海鲜。花生过敏者需同时避免花生油、花生酱等衍生制品。','1',1);

SELECT '初始化完成' AS message,
       (SELECT COUNT(*) FROM t_ingredient) AS 食材数,
       (SELECT COUNT(*) FROM t_recipe)     AS 菜谱数,
       (SELECT COUNT(*) FROM t_product_sku) AS SKU数;
