# Workbench PII Reader 0.7.0

适用 DataGrip 2026.2（262.*）。通过「设置 → 插件 → 齿轮 → 从磁盘安装」安装
`dist/workbench-pii-reader-0.7.0.zip`，然后重启。

## 配置与简单查询

在绑定 MySQL / MariaDB 数据源的 Query Console 用原生快捷键或运行按钮执行：

```sql
SELECT id, decr(mobile) AS mobile
FROM js_member
WHERE mobile_hash = hash('13800138000');
```

填写 Workbench 的完整 `/jsst/privacy_decrypt_batch` 地址及 user-token，允许 HTTP 和 HTTPS。
接口需工具权限及隐私权限，插件发送 `view-privacy: 1`。
token 默认保存在 Password Safe，按 IDE 项目与配置接口地址隔离；修改地址会清空输入框。
如果 Password Safe 设置为仅内存保存，重启后需重新填写。

## 多表、多字段

以下地址表和字段仅为示例，须替换为实际已注册的表和字段。

```sql
SELECT m.id,
       decr(m.mobile) AS member_mobile,
       decr(a.mobile) AS delivery_mobile,
       decr(a.receiver) AS receiver
FROM js_member m
LEFT JOIN js_member_address a ON a.member_id = m.id
WHERE a.mobile_hash = hash('13800138000')
ORDER BY m.id
LIMIT 100;
```

- 支持 JOIN / INNER JOIN、LEFT [OUTER] JOIN、RIGHT [OUTER] JOIN，必须带 ON。
- ON 支持字段间比较以及与 WHERE 相同的常量条件，包括 hash。
- 支持表别名、自关联和 FROM/JOIN 中的数据库限定表名；跨库查询请使用别名引用字段。
- 多表查询中的 decr 参数、hash 条件左侧字段必须带表名或别名，即使显式指定用途也一样。
- 结果列名必须唯一；多表同名字段使用不同的 AS 别名。
- 非隐私关联表不要求 js_ / business_ 前缀；涉及 decr/hash 的表必须能识别项目。

## 项目与用途推导

| 真实表名 | 项目 | 接口模块 | mobile 字段的默认用途 |
| --- | --- | --- | --- |
| js_member | JSST | /jsst/ | member.mobile |
| business_member | ERP | /business/ | member.mobile |
| js_member_address | JSST | /jsst/ | member_address.mobile |
| business_member_address | ERP | /business/ | member_address.mobile |

每个隐私字段单独推导项目，不以主表项目覆盖关联表项目。
同一条 JOIN 查询可以同时读取 JSST、ERP 字段，hash 按「项目＋用途」分组请求，每批最多200项。
解密结果按输出列保存对应项目接口，分页、导出和历史恢复沿用此映射。
接口保留已配置地址的协议、主机、端口和部署路径，只切换 jsst/business 模块。
所有表必须对应这个 Workbench 环境；表前缀不能识别测试/生产环境或另一个服务器。

decr 去掉表前缀后拼接原字段名；hash 根据条件左侧字段去掉 `_hash` 后拼接用途。
自定义列名或加密时使用了其他用途，可显式指定第二参数：

```sql
SELECT decr(a.phone_cipher, 'member_address.mobile') AS phone
FROM js_member_address a
WHERE a.phone_fingerprint = hash('13800138000', 'member_address.mobile');
```

第二参数只指定用途，不覆盖所属表的项目。
函数名不区分大小写；旧 PII_DECRYPT/PII_HASH 名称不再识别。
推导不等于注册：服务端仍校验白名单、权限及密钥绑定，不会动态创建字段或密钥。

## hash 查询范围

hash 的第一个参数必须是字符串常量。
支持 =、!=、<>、IN、NOT IN 的右侧，不支持投影、LIKE、范围比较、列参数和模糊搜索。
同样支持姓名等已配置查询指纹的字段：

```sql
SELECT a.id, decr(a.receiver) AS receiver
FROM js_member_address a
WHERE a.receiver_hash IN (hash('张三'), hash('李四'));
```

服务端使用当前绑定的 lookup 密钥，与业务写入规则保持一致。
未填充 hash 的记录不会被 hash 条件命中。迁移期间可显式同时匹配明文：

```sql
SELECT id, decr(mobile) AS mobile FROM js_member
WHERE mobile = '13800138000' OR mobile_hash = hash('13800138000');
```

项目接口不可用、权限不足、密钥未绑定或 hash 结果非法时，中止该次选中语句的执行，不切换到其他项目重试。

## 原生行为及执行入口

复用当前控制台会话与原生结果页，不使用独立结果窗口。
decr 输出转成 `CONCAT('', 字段)` 计算列，避免直接映射成原表字段回写。
普通 SQL 保留原生行为；仅抑制 decr/hash 函数名的无法解析误报，其他检查保留。

已适配默认执行、执行模式2/3、Execute Selection as One Statement、Query Console 的 Alt+Enter，
Explain Plan/Analyze 及其 Raw 形式。选择按一条语句执行时，PII 内容必须是单条受支持的 SELECT。
逐语句模式下先验证全部 PII 语句、解析全部 hash，再交给原生执行。
Explain 会解析 hash，但不会解密结果，因此计划不包含解密接口耗时。

原生分页、刷新、导出通过查询注释标记定位每列用途和接口。
关联写入项目 workspace，不保存 token、SQL 或数据；Password Safe 保存凭证。
保留旧单表查询关联的恢复能力；丢失关联时需重新运行原始 decr SQL。
当前解密仍逐单元格调用接口，大量导出会较慢；中途失败可能已输出部分数据，需按失败任务处理。

## 边界

使用受限 SELECT 解析器：显式列、上述 JOIN、WHERE、普通字段 ORDER BY、LIMIT。
不支持星号列、子查询、CTE、UNION、DISTINCT、聚合/GROUP BY、
CROSS/NATURAL JOIN、USING、任意函数或写入语句。
不支持对解密输出列排序或用 decr 做 WHERE 条件；数据库仍操作密文，不能提供明文模糊检索。
支持普通注释；拒绝 MySQL/MariaDB 可执行注释及优化器提示。

输入明文会存在于 SQL 编辑器、文件及可能的 IDE 历史中。
导出得到明文；不要将其生成 INSERT/UPDATE 后写回加密字段。
Query Console 之外由其他插件或客户端自行提交的 SQL 不属于这些执行适配入口。

## 后端部署前提

已有 JSST 批量解密和 hash 接口的环境，本次只需更新插件。
旧环境需部署 DataEncryption 控制器、JSST 路由、Privacy/BatchDecrypt.php 和 Privacy/BatchLookup.php。

新增地址表、收货人等字段，需要后端注册用途、绑定加密密钥并完成数据迁移；
需要 hash 查询的字段还要配置查询指纹列并绑定 lookup 密钥。
本次没有新增业务字段或执行数据库迁移。
当前仓库 ERP 尚无隐私接口及字段密钥注册；插件已支持 /business/ 路由选择，
但 ERP 实际使用仍需接入对应后端，绝不会回退至 JSST。

## 验证

```sh
python3 build.py --ide /path/to/datagrip --test
```

自动测试覆盖 SQL 重建、项目隔离、同名字段、自关联、用途推导、歧义拒绝、
批量 hash、HTTP 路由/权限失败、XML 历史恢复及现有执行入口。
使用本机模拟 HTTP 服务及合成凭证，未请求生产服务。
原生结果页、分页、刷新、全量导出仍需安装后实机联调；构建测试通过不等于 UI 验收。
