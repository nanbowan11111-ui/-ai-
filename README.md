# 办公用品出入库管理

用于现场演示的办公用品库存台账：Excel 入库单预览与确认、领用申请与撤回、审批、库存流水、台账导出，以及侧边聊天助手。右上角可切换李华、陈晨、王经理；这是演示身份，不是登录认证。

`frontend/` 是静态页面，`backend/` 是 Spring Boot 服务。Maven 打包时会把前端放入 JAR，由同一服务提供页面和 `/api`。运行数据库为 MySQL 8；常规自动化测试使用独立的 H2 内存库。

## 启动

需要 Docker Compose。首次启动前在项目根目录创建被 Git 忽略的 `.env`：

```dotenv
DB_PASSWORD=为应用用户设置随机密码
MYSQL_ROOT_PASSWORD=为数据库管理员设置另一个随机密码
```

然后执行：

```powershell
docker compose up -d --build
```

打开 <http://localhost:8080>。页面和数据库分别只绑定本机 `127.0.0.1:8080`、`127.0.0.1:3306`。首次启动从 `backend/src/main/resources/data/物料主数据.xlsx` 导入 12 种物料，初始库存为零。MySQL 数据保存在 Compose 命名卷 `mysql_data` 中；`docker compose down` 停止服务，`docker compose logs -f` 查看日志。执行 `docker compose down -v` 会删除该数据卷。

若镜像源暂时无法提供 Maven 构建镜像，而本机已有 JDK 21、Maven 3.9+ 和 `eclipse-temurin:21-jre`，可在修改代码后用本机构建：

```powershell
mvn -f backend/pom.xml package
docker build -f Dockerfile.prebuilt -t office-supplies-ledger:local .
docker compose up -d --no-build --force-recreate
```

直接运行源码时，先启动 MySQL，向当前终端设置与 `.env` 相同的 `DB_PASSWORD`，再执行 `mvn -f backend/pom.xml spring-boot:run`。避免与容器中的应用同时占用 8080 端口。

## 操作流程与规则

1. 上传 `.xlsx` 入库单并查看预览；右侧聊天栏也可选取文件或拖入文件。校验通过后点击“确认入账”。上传上限为 5 MB。预览不修改库存，令牌 30 分钟后失效；确认时重新解析，并在事务中写入单号、库存和流水。
2. 李华或陈晨提交领用申请。提交时检查库存，但不扣库存；本人可以撤回仍待审批的申请。
3. 王经理查看待审批申请并审批或驳回。审批时重新检查库存并扣减，驳回需要原因；已处理的申请不能再次审批。
4. 查看台账和流水，或导出与页面台账字段一致的 Excel。

入库按 B2 单号及第 7 行起的明细解析。物料编码去首尾空格，数量须为正整数；同一物料重复行会提示并在确认时合并。任何错误都会拒绝整单，已入账单号不能重复导入。示例 `backend/src/test/resources/samples/入库单-RK-20261102-002.xlsx` 含未登记物料和非法数量，适合演示错误预览；样例文件不应为通过校验而修改。

库存和状态由 Java 服务与数据库事务维护。聊天助手只调用受限工具查询库存、列待审批、提交领用、准备审批和导出台账；聊天审批会先展示申请详情，用户点击确认后才执行，确认令牌 10 分钟后失效。右侧聊天内容按当前页面的用户分别显示，刷新页面后消失。

## 可选模型服务

不配置模型密钥时，聊天支持有限的示例表达；开放式自然语言需要 OpenAI 兼容模型服务。在 `.env` 中可设置：

| 变量 | 用途 | 默认值 |
| --- | --- | --- |
| `MODEL_API_KEY` | 模型 API 密钥 | 不设置时使用本地示例语句处理 |
| `MODEL_NAME` | 模型名 | `deepseek-chat` |
| `MODEL_BASE_URL` | 模型服务地址 | `https://api.deepseek.com` |

不要把密钥写入受 Git 管理的文件。聊天接口以事件流返回模型文本；页面逐字显示，并在完整显示后呈现确认按钮或下载链接。模型失败或超时会提示使用页面操作。

## 数据、迁移与验证

Navicat 可使用 `127.0.0.1:3306`、用户 `office_app`、`.env` 中的 `DB_PASSWORD` 连接 `office_ledger`。主要表为 `material`、`receipt`、`issue_request`、`issue_item`、`movement`。不要直接修改运行库存或删除流水。

本项目从 H2 迁移到 MySQL 后，原 H2 文件保留在被 Git 忽略的 `data/office.mv.db`。在另一环境迁移已有 H2 数据时，先配置 `.env` 并执行 `docker compose up -d --wait db`，然后运行 `./backend/scripts/migrate-h2-to-mysql.ps1`。脚本会另存 H2 备份、拒绝覆盖已有的 MySQL 业务数据并核对迁移结果。全新环境无需运行迁移脚本。

```powershell
mvn -f backend/pom.xml test
```

常规测试覆盖原始 Excel 样例、错误整单拒绝、重复入库、并发审批、撤回、导出及聊天工具注册，不修改 MySQL 运行数据。2026-11-08 本地执行通过：`WorkflowTest` 1 项通过，需显式启用的 `MySqlSmokeTest` 1 项跳过。若要运行 MySQL 专项测试，需已有库存和可用数据库，在终端设置 `DB_PASSWORD` 后执行：

```powershell
mvn -f backend/pom.xml '-Dtest=MySqlSmokeTest' '-Drun.mysql.tests=true' test
```

专项测试在事务中验证申请、审批与库存变动，结束时回滚。

## 演示边界

用户身份由页面选择，不能用于开放网络或真实权限控制。入库预览与聊天审批令牌保存在进程内，服务重启后失效。数据库结构由 `schema.sql` 初始化，尚无版本化迁移机制。真实模型目前只核验过只读库存查询；申请、审批确认、导出等模型路径以及聊天栏拖入动作仍需实际验收。
