# 办公用品出入库管理 MVP

库存台账、入库单预览与确认、领用申请、审批、流水、Excel 导出和侧边聊天助手。项目按附件中的业务规则 R1–R12 实现；聊天栏也支持拖入或选择入库单 Excel。

代码分为 `frontend/`（HTML、CSS、JavaScript 和本地图标文件）与 `backend/`（Spring Boot、测试、数据初始化和迁移脚本）。后端使用 MyBatis-Plus 访问数据库：物料基础查询使用 `BaseMapper`，关联查询及库存行锁、条件扣减放在 `LedgerMapper`。Maven 构建会把 `frontend/` 复制进应用 JAR，由后端在同一端口提供页面和 `/api`；无需单独启动前端服务。根目录保留容器配置、文档和运行数据。

## 启动

数据库现已迁移到 MySQL 8。首次在新环境启动前，在被 Git 忽略的 `.env` 中设置 `DB_PASSWORD`（应用用户密码）和 `MYSQL_ROOT_PASSWORD`（数据库管理员密码）；两者应使用不同的随机密码。镜像可正常拉取时，在项目目录执行：

```powershell
docker compose up -d --build
```

打开 `http://localhost:8080`。首次启动从 `backend/src/main/resources/data/物料主数据.xlsx` 导入 12 种物料，库存为零。MySQL 使用 Compose 命名卷 `mysql_data` 保存数据，重建容器不会清空库存。MySQL 仅在本机 `127.0.0.1:3306` 监听，页面仅在本机 `127.0.0.1:8080` 监听。查看日志用 `docker compose logs -f`，停止服务用 `docker compose down`；不要使用 `down -v`，否则会删除 MySQL 数据卷。常规 `mvn -f backend/pom.xml test` 使用独立的 H2 内存数据库，不改运行数据。

如需直接运行源码，需本机 JDK 21 和 Maven 3.9+；先启动 MySQL 容器，并把 `.env` 中的 `DB_PASSWORD` 加载到当前终端环境变量，再执行 `mvn -f backend/pom.xml spring-boot:run`。不要与容器中的应用同时占用 8080 端口。如果 Docker Hub 不可用，可从可用镜像源拉取 `maven:3.9.11-eclipse-temurin-21` 与 `eclipse-temurin:21-jre` 并标记为同名本地镜像后再构建。

当 Maven 构建镜像无法下载、但本机已有 `eclipse-temurin:21-jre` 时，可使用已验证的预打包路径：

```powershell
mvn -f backend/pom.xml package
docker build -f Dockerfile.prebuilt -t office-supplies-ledger:local .
docker compose up -d --no-build
```

该预打包路径已在本机验证。`Dockerfile.prebuilt` 只复制本机刚打好的 JAR；更新代码后须重新执行上述打包和构建命令。
页面图标使用随项目保存的 Lucide 文件，运行页面不依赖 CDN。

## 用 Navicat 查看数据

在 Navicat Premium Lite 17 中新建 **MySQL** 连接，填入：主机 `127.0.0.1`、端口 `3306`、用户名 `office_app`、密码为本项目 `.env` 里的 `DB_PASSWORD`。测试连接后打开数据库 `office_ledger` →「表」，即可查看 `material`、`receipt`、`issue_request`、`issue_item`、`movement`。这些是实际运行数据；查看时避免直接改库存或删除流水。

原 H2 数据已复制到 MySQL，逐字段核对通过：物料 12、入库单 2、申请 6、申请明细 7、流水 23。原文件 `data/office.mv.db` 仍保留；迁移前备份位于 `data/backups/office-h2-before-mysql-20261108.mv.db`。两者均被 Git 忽略。若在其他环境迁移，先设置 `.env` 中的密码并执行 `docker compose up -d --wait db`，然后运行 `./backend/scripts/migrate-h2-to-mysql.ps1`。脚本会另存 H2 备份、拒绝覆盖已有的 MySQL 数据并逐字段核对。

模型服务可选。要启用 AgentScope 对自然语言的处理，在启动前设置环境变量：

| 变量 | 用途 | 默认值 |
| --- | --- | --- |
| `MODEL_API_KEY` | 模型 API 密钥 | 不设置则使用固定示例语句处理 |
| `MODEL_NAME` | OpenAI 兼容模型名 | `deepseek-chat` |
| `MODEL_BASE_URL` | 模型 API 地址 | `https://api.deepseek.com` |

不要把密钥写进代码、受 Git 管理的配置文件或 Git；本机可使用被 Git 忽略的 `.env`。未配置密钥时，聊天仍支持库存查询、附件示例的领用语句、查看待审批、准备审批和导出台账；开放式语句需要模型服务。聊天栏可将一张 `.xlsx` 入库单拖入右侧助手区域，或点击回形针选择文件；助手展示所有校验错误和明细，校验通过后点击“确认入账”才更新库存。文件直接调用与页面导入相同的预览、确认接口，不经模型处理，单文件上限 5MB。聊天接口使用事件流接收模型文本，页面每 35 毫秒显示一个字符，完整显示后再同步确认按钮或下载链接。模型调用失败或超时会提示使用页面操作。已用本地模拟的 OpenAI 兼容服务验证分段输出，并用真实模型验证只读库存查询；申请、审批确认等模型路径仍待验证。

## 业务与数据

| 表 | 用途与约束 |
| --- | --- |
| `material` | 物料主数据、当前库存、安全库存；库存非负 |
| `receipt` | 已入账单号；单号主键确保不能重复入库 |
| `issue_request` | 申请人、状态、审批人、驳回原因和时间 |
| `issue_item` | 申请明细；每张申请每种物料一行 |
| `movement` | 入库、出库流水及变动后库存 |

入库单先由 Apache POI 读取 B2 单号及第 7 行起的明细。预览只保存 30 分钟的临时文件数据，不改库存；确认时重新解析，在事务里写入单号、库存和流水。编码去首尾空格；文本数字按十进制整数解析；重复物料提示并合并入账。任一行有错误则整单拒绝。第二张原始单的 `M013` 未登记、`-10` 非法，故不能入账；备注文本作为普通数据，不参与执行。

申请提交时检查当前库存，但不扣减。申请人可撤回本人仍待审批的申请，撤回不扣库存。审批时对申请加锁，再按物料编码顺序锁定库存并重新检查；库存不足则整个审批回滚。只有审批人可审批，已处理申请不可再次审批。驳回必须有原因。聊天审批先产生 10 分钟有效的确认操作，页面显示申请人、物料、数量和当前库存，点击确认后才调用同一审批服务。

身份用右上角用户切换模拟，无登录。这是题目指定的演示机制，不能作为生产环境的认证或授权方案。三个内置用户的助手聊天内容在当前页面内分别保留，切换后只显示对应用户的对话；刷新页面后聊天内容消失。由于仍可自由切换身份，这只是演示界面的隔离，不提供防止冒用身份查看的安全保证。入库页允许三个内置用户操作，题目未指定入库专属角色。安全库存用 `当前库存 < 安全库存` 判定。流水按入库单的每种物料聚合记录一条，出库按申请明细记录一条。台账导出包含页面同源的物料、库存和告警字段。

## 模型与代码的边界

AgentScope Java 2.0.4 的 `ReActAgent` 只负责理解用户意图及选择工具。注册工具：`listMaterials` 查物料库存，`listPending` 查待审批，`createIssue` 创建申请，`prepareApproval` 展示审批详情并生成确认令牌，`exportLedger` 返回下载链接。每次聊天请求都把当前用户绑定在工具对象上，模型不能自行指定更高权限用户。库存增减、单号去重、状态变更、权限与数量校验都由 Java 服务和数据库事务完成；模型不能直接写数据库或计算库存。

## 验证与演示

执行 `mvn -f backend/pom.xml test`。`WorkflowTest` 用提供的两张原始入库单验证：预览不入账、首张单入账后的库存与流水、第二张单列出全部错误且零变动、重复入库拒绝、两个并发审批只允许一个成功、导出库存与台账一致，以及 AgentScope 工具注册。MySQL 环境可用时，设置终端环境变量 `DB_PASSWORD`，再执行 `mvn -f backend/pom.xml '-Dtest=MySqlSmokeTest' '-Drun.mysql.tests=true' test`；此测试在事务中验证申请、审批和库存变动，结束时回滚。本地模拟模型返回两段文本时，HTTP 客户端分别收到两个 `delta` 事件和最终 `done` 事件；浏览器显示了完整回复，未见 JavaScript 异常。真实模型的只读查询返回 A4 复印纸库存 50 包，与台账接口一致；其余工具路径仍待验证。

现场演示顺序：先上传第二张单看整单错误，再上传第一张单确认入账；用李华申请 A4 复印纸；切换王经理查看详情并审批；回到台账核对库存和流水；最后导出 Excel。已有数据会保留，演示前可使用全新数据库目录。

## AI 使用记录与已知限制

使用 Codex 辅助梳理原始文档和表格、生成 Java 与前端代码、编写测试并运行浏览器检查。曾由 AI 写出使用 `GeneratedKeyHolder.getKey()` 取申请 ID 的代码；真实测试发现 H2 同时返回 ID 与创建时间，随后改为只请求 `ID` 列并重跑测试。其他关键业务规则均由原始 Excel 样例与自动化测试核对。

当前限制：无 API Key 时只识别有限的示例表达；外部模型服务仅实测了只读库存查询；内置用户切换不提供真实身份认证；预览令牌和聊天确认令牌保存在进程内，重启后失效；数据库结构由 `schema.sql` 初始化，未引入 Flyway。以上均不影响页面主流程的本地演示。
