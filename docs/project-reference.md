# 基于深度学习的人脸识别考勤系统

按照上级目录的 `plan.md` 创建的毕业设计项目，[原始计划副本](plan-original.md)与原文件完全一致。采用 Vue 3 / TypeScript、Java 21 / Spring Boot、Python / FastAPI，提供人员管理、现场人脸录入、授权设备刷脸、固定日班考勤、请假补卡审批、报表导出及审计。

**本次构建以用户提供的[目标电脑环境清单](目标电脑环境与计划原文.md)为准。** 先看[目标环境构建与启动说明](目标环境构建说明.md)：固定 Node 24.18.0、pnpm 11.25.0、JDK 21、Python 3.11 x64，使用 CPU 推理和 H2 开发数据库。目标模式会检查实际版本，版本不符时停止。

本机完成安装后，按[真人刷脸操作步骤](真人刷脸操作步骤.md)进行人员录入、设备授权、独立样本校准和动作打卡；技术验证与真人验收分别记录，不把公开样本测试算作真人打卡。

本次实际版本、模型推理结果和限制见[环境对齐与模型验证记录](本次环境对齐与模型验证.md)。

**在另一台电脑第一次运行：** [新电脑启动指南](新电脑启动指南.md)和 [HTML 阅读版](新电脑启动指南.html)解释安装、数据库及登录操作；指定环境与新增入口以目标环境说明为准。本次源码与目标 JAR 转移包为 `delivery/face-attendance-target.zip`；不包含模型权重或工具链，请按说明单独准备。此前的 `face-attendance-preview.zip` 是旧业务预览包，不要混用旧包和新源码。复制项目到其他电脑时重新创建虚拟环境和配置，不复制本机 `.env`、数据库或个人样本。

本仓库同时包含可独立运行的业务开发模式和接入真实模型的完整部署方式。**未安装模型、未同步人员库或未完成阈值校准时，不能把业务页面能打开当作刷脸功能已可用。系统不使用随机向量或固定身份伪造识别成功。**

## 项目结构

```text
face-attendance/
├─ backend/                 Java 21 业务服务、Flyway迁移、业务测试
├─ frontend/                Vue 3 响应式网站、pnpm锁文件
├─ algorithm/               Python算法服务、模型校验及阈值校准工具
├─ deploy/
│  ├─ nginx.conf            HTTPS、静态页面、HTTP与WebSocket反向代理
│  ├─ mysql-init.sql        数据库及本地最小权限账号配置示例
│  └─ certs/                本地证书，需自行生成，不进入版本管理
├─ scripts/                 Windows PowerShell环境、构建、启停、备份恢复
├─ docs/                    架构、接口说明、操作手册、验收清单和计划调整
├─ .env.example             无真实凭证的配置示例
└─ Readme.md
```

## 环境要求

| 组件 | 版本/要求 |
| --- | --- |
| 操作系统 | Windows 10/11；PowerShell 5.1或7 |
| Java | JDK 21 x64；参考清单为 21.0.10，须包含 javac |
| Java构建 | Maven 3.9+ |
| 前端 | Node.js **24.18.0 x64**、pnpm **11.25.0**；直接依赖与 pnpm 锁文件固定 |
| 算法 | Python 3.11、独立虚拟环境；部分包在Windows安装时需要Visual C++ Build Tools |
| 本机数据库 | 默认 H2，随 Java 服务启动，无需独立安装或启动数据库 |
| 正式数据库（可选） | MySQL 8.4 LTS，仅允许本机连接；本次目标路线不要求 |
| HTTPS入口 | Windows Nginx；匹配实际域名/IP的可信开发证书 |
| 初始硬件建议 | 四核CPU、16GB内存、可用摄像头；实际并发与耗时需测量 |

依赖安装需要访问软件源。目标工具放在 `.toolchain`，Python 完整环境放在 `algorithm/.venv-runtime`；不修改系统 PATH，不混用已有 Python 3.12 环境。算法模型较大且带有使用条件，不进入源码仓库；来源、目录和校验步骤见 [algorithm/Readme.md](../algorithm/Readme.md)。

## 1. 初始化配置

已有 `.env` 时保留它，避免变更加密密钥；目标模式优先使用项目内指定版本的工具。首次登录账号在该文件的 `BOOTSTRAP_ADMIN_USERNAME`、`BOOTSTRAP_ADMIN_PASSWORD` 中查看。以下初始化命令仅用于没有 `.env` 的新目录；复制项目到其他机器时应重新初始化。

在项目根目录执行，以下命令均为PowerShell：

```powershell
Set-Location D:\SE\face-attendance
.\scripts\init-env.ps1
```

脚本创建被Git忽略的 `.env`，独立生成AES密钥、内部服务密钥和管理员初始密码，并限制文件为当前Windows用户可读写。密码不打印到终端；使用本地编辑器打开 `.env` 查看。已有 `.env` 时脚本拒绝覆盖，防止现有人脸模板因密钥变化无法解密。

需要配置：

| 配置 | 说明 |
| --- | --- |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | MySQL JDBC地址及账号；密码不能保留 `CHANGE_ME` |
| `DB_HOST`、`DB_PORT`、`DB_NAME` | 备份恢复工具使用，须与JDBC连接指向相同数据库 |
| `FACE_ENCRYPTION_KEY` | Base64编码的32字节密钥；单独安全备份，不随数据库备份存放 |
| `INTERNAL_API_KEY` | Java和Python共用的内部接口密钥，至少32字符 |
| `BOOTSTRAP_ADMIN_USERNAME`、`BOOTSTRAP_ADMIN_PASSWORD` | 空数据库首次启动创建的管理员账号；修改配置不会重置已有账号密码 |
| `APP_ALLOWED_ORIGIN` | 允许的浏览器完整Origin，多个用逗号分隔，如 `https://localhost,https://192.168.1.20` |
| `COOKIE_SECURE` | 正式HTTPS部署设 `true`；开发脚本仅在 `-Dev` 模式覆盖为 `false` |
| `MODEL_DIR` | 模型目录，脚本将项目相对路径转为绝对路径 |
| `PYTHON_EXE` | 默认 `algorithm/.venv-runtime/Scripts/python.exe`，使用 Python 3.11 |
| `NGINX_EXE` | 正式部署时填 `nginx.exe` 的绝对路径 |
| `MAVEN_EXE`、`PNPM_EXE` | 可执行文件路径或PATH中的命令名；pnpm也支持 `.cjs` CLI路径 |

`.env` 是简单的 `名称=值` 文件，不执行变量展开或Shell命令。不能把真实密码填入 `.env.example`。如果使用开发数据库，也要保留独立且随机的加密和内部密钥。

## 2. 按目标环境构建前后端

按[目标环境说明](目标环境构建说明.md)准备项目内工具后，在项目根目录执行：

```powershell
.\scripts\check-target-env.ps1 -BusinessOnly -ToolsOnly
.\scripts\install-target-deps.ps1 -BusinessOnly
.\scripts\build.ps1 -TargetEnvironment
```

构建脚本核对 JDK 21、Node 24.18.0、pnpm 11.25.0，执行 Maven 测试和打包、按冻结锁文件安装前端依赖，再执行类型检查与构建。产物为 `backend/target/face-attendance-1.0.0.jar` 和 `frontend/dist/`。`MAVEN_EXE` 指向本机 Maven 3.9.x；不要照抄其他电脑的绝对路径。

Maven 同时用 `-gs` 和 `-s` 指定项目 `maven-settings.xml`，避免机器旧镜像配置影响构建。若官方源不可达，可显式使用附带的 HTTPS 镜像配置；应在验证记录说明实际使用的源：

```powershell
.\scripts\build.ps1 -TargetEnvironment -MavenSettings '.\backend\maven-settings-aliyun.xml'
```

## 3. 先运行业务开发模式

无需安装MySQL、Nginx或算法模型即可检查业务页面、权限、考勤和审批流程：

```powershell
.\scripts\start.ps1 -Dev -BusinessOnly -TargetEnvironment
```

这条命令默认使用随 Java 自动启动的 H2 数据库。若想单独安装和启动 MySQL，按 [新电脑启动指南的附录 C](新电脑启动指南.md) 完成安装、建库、账号授权和 `.env` 配置，然后改用：

```powershell
.\scripts\start.ps1 -Dev -BusinessOnly -MySql -TargetEnvironment
```

`-MySql` 显式用 `.env` 的 `DB_URL`、`DB_USERNAME` 和 `DB_PASSWORD` 覆盖开发配置中的 H2 连接，不需要重编 JAR。MySQL 必须提前启动；`stop.ps1` 不会停止 MySQL。只修改 `DB_URL` 而不加该参数，默认开发配置仍使用 H2。两种数据库不会自动互相迁移数据。

浏览器打开 <http://localhost:5173>，使用所选数据库的当前管理员账号登录；空库首次启动时使用 `.env` 中的初始账号。业务服务监听 `127.0.0.1:8080`，前端开发服务器监听 `127.0.0.1:5173` 并代理 `/api`（包括WebSocket）。默认H2数据保存在 `backend/data/`，重启不会自动清空；与MySQL数据相互独立。

此模式不启动Python：人脸录入/刷脸应明确提示算法不可用，不产生成功打卡。人员、规则、整天请假、补卡审批、查询和导出可独立验证。补卡范围为最近7个已结束的工作日，可为测试员工设置较早的入职日期后演示申请审批，不能凭空制造“刷脸成功”。

停止项目启动的进程：

```powershell
.\scripts\stop.ps1
```

脚本核对PID、启动时间、可执行路径与所属项目后才停止进程，不接管占用相同端口的其他程序。启动失败时请先查看 `.runtime/*.log`，再执行停止脚本清理已经启动的项目进程；MySQL由系统服务或管理员独立管理。

## 4. 完整模型识别

目标模式仅使用 Python 3.11 x64 的 `algorithm/.venv-runtime`。安装完整依赖：

```powershell
.\scripts\install-target-deps.ps1
```

脚本优先采用经过目标环境验证的完整锁文件，并执行依赖兼容性和核心模块导入检查。旧 `algorithm/.venv` 是此前 Python 3.12 轻量测试环境，不能用它替代本次真实模型构建。

按[算法说明](../algorithm/Readme.md)准备官方 InsightFace buffalo_l 与 MediaPipe Face Landmarker 模型，核对来源后生成 SHA-256 清单。运行诊断时会真实加载全部 CPU 模型；可传入自愿提供的 JPEG 检查特征和表情分数，不输出人脸向量：

```powershell
.\scripts\check-target-env.ps1
Set-Location algorithm
.\.venv-runtime\Scripts\python.exe -m scripts.diagnose_models --model-dir models
Set-Location ..
.\scripts\stop.ps1
.\scripts\start.ps1 -Dev -TargetEnvironment
```

也可双击 `启动完整刷脸.cmd`。数据库仍为 H2，随 Java 启动。模型就绪后，在管理页面给人员录入三张合格照片，并完成独立样本校准，正式刷脸才会开放。

新增离线实验工具串起真实照片到校准报告的流程，详见算法 Readme：

- `capture_samples`：显式运行后使用本机摄像头采集实验 JPEG；实验图像保存在本机被忽略的目录。
- `score_samples`：固定人员库，每人三张模板，分别处理独立校准、测试及未知人员样本；拒绝跨组重复图片，保留质量拒绝，生成真实 `scores.csv` 和来源记录。
- `calibrate`：只在校准组选择阈值和候选分差，再对测试组评估，不用测试数据调参。

默认阈值不会自动标成“已校准”。模型加载、同图技术冒烟和自动化测试都不能替代真人摄像头动作及识别率验收。

## 5. MySQL + HTTPS局域网部署

1. 安装并启动MySQL8.4，限制监听地址为 `127.0.0.1`。参考 `deploy/mysql-init.sql` 交互式创建数据库和专用账号，使用强随机密码并同步到 `.env`。Java启动时使用Flyway创建数据结构，不需要手工建表。
2. 安装Windows版Nginx，在 `.env` 设置 `NGINX_EXE`。完整构建前端与后端，准备算法模型。
3. 为部署电脑分配稳定的局域网IP，用可信开发CA签发覆盖该IP和 `localhost` 的证书。参考 [HTTPS部署说明](deployment.md)。私钥保存在 `deploy/certs/localhost-key.pem`，证书为 `deploy/certs/localhost.pem`。
4. 修改 `.env` 的 `APP_ALLOWED_ORIGIN`，包含实际访问地址；保持 `COOKIE_SECURE=true`。
5. 停止开发进程，检查环境并启动：

```powershell
.\scripts\stop.ps1
.\scripts\check-env.ps1
.\scripts\start.ps1
```

在同一局域网其他设备上访问 `https://部署电脑IP`。浏览器必须信任证书才能可靠使用摄像头；每台演示手机/电脑都要单独建立信任。Nginx占用443，Java8080、Python8000和MySQL3306不应暴露到局域网。开发服务器只监听本机，手机验证应走正式HTTPS入口。

## 验证与交付状态

自动化测试、构建和本机业务冒烟的实际执行结果统一记录在 [验证记录](validation.md)。[验收清单](acceptance.md) 列出尚需真实模型、MySQL、摄像头、手机或多人样本才能完成的验收项，未验证项不会标为通过。

Python测试（在安装测试依赖后，下面使用新建的完整环境路径）：

```powershell
Set-Location algorithm
.\.venv-runtime\Scripts\python.exe -m unittest discover -s tests -v
Set-Location ..
```

详细文档：

- [对原计划的调整与实施边界](plan-adjustments.md)
- [系统架构与数据库ER图](architecture.md)
- [接口、权限与WebSocket约定](api.md)
- [管理员/员工操作与答辩演示](user-guide.md)
- [HTTPS、环境排查和备份恢复](deployment.md)

## 备份与恢复

仅生产MySQL数据使用以下脚本，H2开发数据库不适用：

```powershell
# 备份成功后只清理backups目录中本项目生成且超过7天的SQL文件
.\scripts\backup.ps1 -PruneExpired

# 恢复会覆盖目标数据库数据；先停服务并保留新备份
.\scripts\stop.ps1
.\scripts\backup.ps1
.\scripts\restore.ps1 -BackupFile 'D:\SE\face-attendance\backups\face-attendance-YYYYMMDD-HHmmss-fff.sql' -ConfirmOverwrite
```

数据库密码经临时、当前用户专属权限的MySQL选项文件传递，完成后删除，不放在进程参数中。恢复必须使用原 `FACE_ENCRYPTION_KEY`，否则不能解密旧人脸模板。备份包含人员和加密生物特征，也应限制访问；不要把数据库SQL、`.env`、证书私钥、模型或真实图像上传到公开仓库。验收后的数据保存与清理日期须由实际试用者确认，项目不会自行假设“验收已结束”并删除记录。

