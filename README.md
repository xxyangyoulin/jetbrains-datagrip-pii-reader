# Workbench PII Reader

DataGrip 插件，通过 Workbench 隐私接口解密查询结果，并将明文查询条件转换为查询指纹。
当前版本：0.7.0。支持 DataGrip 2026.2（262.*）、MySQL / MariaDB。

## 功能

- `decr(column)`：在原生结果表中显示解密值。
- `hash('value')`：根据条件字段推导用途，调用后端计算查询指纹。
- 支持显式字段、多表 INNER / LEFT / RIGHT JOIN、表别名及自关联。
- 按真实表前缀分别选择项目接口：`js_` → JSST，`business_` → ERP。
- 复用原生查询入口、结果页和导出路径，token 存储在 Password Safe。

```sql
SELECT id, decr(mobile) AS mobile
FROM js_member
WHERE mobile_hash = hash('13800138000');
```

示例手机号为测试占位值。

## 后端前提

本仓库仅包含插件，不包含 Workbench 后端，也不是独立解密工具。
需要部署对应项目的 `privacy_decrypt_batch` 和 `privacy_lookup_batch` 接口，
并完成字段用途注册、密钥绑定和数据迁移。插件不接收密钥材料。
ERP 路由选择已支持；实际使用仍需 ERP 后端提供对应服务。

## 构建与测试

需要 Python 3，以及已安装、带有 JBR Java 编译器的 DataGrip 2026.2。
构建使用本地 IDE SDK，不下载依赖。

```bash
python3 build.py --ide /path/to/datagrip --test
```

也可设置 `DATAGRIP_HOME` 后运行 `python3 build.py --test`。
安装包生成至 `dist/workbench-pii-reader-0.7.0.zip`。
在 DataGrip「设置 → 插件 → 齿轮 → 从磁盘安装」选择 ZIP，安装后重启。
`build/`、`dist/` 为本地构建产物，不提交到 Git。

## 使用范围

包含虚拟函数的 SQL 使用受限解析器，暂不支持 `SELECT *`、子查询、CTE、UNION、聚合及模糊搜索。
不包含虚拟函数的 SQL 直接交给原生执行。
当前解密逐单元格调用接口，大量导出可能较慢。
自动测试使用本地模拟接口；真实 IDE 的分页、刷新、导出仍需实际环境验收。

详见 [使用说明](GUIDE.md)。
