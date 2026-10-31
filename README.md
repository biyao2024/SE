# SE · 人脸识别考勤系统

软件工程课程项目。使用 Vue 3、Spring Boot 和 FastAPI，实现人员建档、摄像头人脸录入、浏览器设备授权、刷脸签到/签退、请假与补卡审批、考勤查询及报表导出。

**本次上传来自最后实际运行的 `face-attendance2/face-attendance`，不是旧的业务预览目录。** 包含中文路径下 MediaPipe 模型加载修复，以及电脑本机管理与手机 HTTPS 打卡双入口。项目使用真实模型推理，没有用固定身份或随机特征伪造识别。

## 功能与运行边界

- 业务预览：H2 数据库与 Java 一起启动，可独立体验管理、审批和报表。
- 完整模式：InsightFace `buffalo_l` 提取人脸特征，MediaPipe 提供眨眼/张嘴信息；算法使用 CPU 推理。
- 设备授权绑定当前浏览器 Cookie，默认有效期 7 天，不等于绑定手机硬件。
- 模型缺失、人员库未同步或管理员未确认校准时，公共打卡会阻断。
- 人脸识别阈值默认值只是工程初值。离线校准结果与网页实际模板库的验证应分开记录。
- 随机动作检测存在 12 秒采集时限，CPU 推理及帧积压可能造成超时，当前没有把真人动作成功率或防伪准确率标记为验收通过。

## 技术环境

| 部分 | 环境 |
|---|---|
| 系统 | Windows 10/11 x64，PowerShell |
| 后端 | JDK 21，Spring Boot 3.5.7，Maven 3.9.x |
| 前端 | Node 24.18.0，pnpm 11.25.0，Vue 3.5.44，Vite 7.3.7，TypeScript 5.9.3 |
| 算法 | Python 3.11 x64，InsightFace 0.7.3，ONNX Runtime 1.20.1，MediaPipe 0.10.21 |
| 数据库 | 默认内置 H2；独立 MySQL 8.4 是可选部署路线 |

前端以 `frontend/pnpm-lock.yaml` 为准；算法以 requirements 文件及 Python 3.11 锁文件为准。目标启动模式会检查固定版本，完整工具路径见 `scripts/target-environment.json`。

## 目录

```text
backend/       Java 业务服务、数据库迁移和测试
frontend/      Vue 管理页面与公共打卡页面
algorithm/     模型适配器、动作验证、样本采集和校准工具
scripts/       环境检查、安装、构建、启停及备份脚本
deploy/        Nginx 与 MySQL 部署配置
docs/          启动、架构、接口和真人验证说明
.env.example   配置模板，不包含真实密钥
```

## 首次运行

### 1. 获取代码和生成配置

```powershell
git clone https://github.com/biyao2024/SE.git
Set-Location SE
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\init-env.ps1
```

在新电脑独立生成 `.env`，配置本机 `JAVA_HOME`、`NODE_HOME`、`PNPM_EXE`、`MAVEN_EXE`。不要复制别人电脑的路径和密钥。只修改工具位置，不随意轮换已有数据使用的加密密钥。

### 2. 构建业务服务

GitHub 存放源码，不携带原电脑的 JAR、node_modules 或工具链。准备 JDK、Maven、Node、pnpm 后，在根目录执行：

```powershell
.\scripts\build.ps1
.\scripts\start.ps1 -Dev -BusinessOnly
```

首次下载依赖需要联网。pnpm 若提示 esbuild 构建脚本需要批准，应在 `frontend` 中核对并批准该依赖，再重新安装。详细安装和目标工具布局见[目标环境构建说明](docs/目标环境构建说明.md)。

打开 <http://localhost:5173>。初始账号及密码从本机 `.env` 的 `BOOTSTRAP_ADMIN_USERNAME`、`BOOTSTRAP_ADMIN_PASSWORD` 查看，首次登录后修改密码。已修改密码的数据库使用新密码；编辑 `.env` 不会重置旧账号。

### 3. 配置完整刷脸环境

参照[算法说明](algorithm/Readme.md)和[目标环境构建说明](docs/目标环境构建说明.md)，在本机创建 `algorithm/.venv-runtime`，安装固定依赖，并从官方渠道准备模型：

```text
algorithm/models/
  manifest.json
  face_landmarker.task
  insightface/models/buffalo_l/
    det_10g.onnx
    w600k_r50.onnx
    1k3d68.onnx
```

使用模型清单工具生成并校验 SHA-256。InsightFace 官方预训练权重限非商业研究使用，下载与许可说明见算法文档。

依赖及模型准备好后，先停止业务模式，再执行：

```powershell
.\scripts\stop.ps1
.\scripts\check-target-env.ps1
.\scripts\start.ps1 -Dev -TargetEnvironment
```

确认“系统运行”中的模型正常；创建员工，录入三张人脸并同步人员库，再进行校准、浏览器授权与真人测试。具体见[真人刷脸步骤](docs/真人刷脸操作步骤.md)。

## 手机与电脑同时使用

手机不能访问电脑的 localhost；手机摄像头需要可信 HTTPS。局域网演示使用以下入口：

| 设备 | 地址 |
|---|---|
| 电脑管理页面 | `http://localhost:5174`，仅绑定本机 |
| 手机 | `https://电脑当前局域网IP:5173/kiosk` |

先生成包含当前 IP 的网站证书，将 `server.pem`、`server-key.pem` 放在 `deploy/certs/lan/`，并在手机安装自己生成的公开 CA。仓库不会提供任何人的 CA 私钥、网站私钥或预置证书。

准备好证书及目标工具环境，停止已有服务后：

```powershell
.\scripts\stop.ps1
.\scripts\start-phone.ps1 -LanIp 192.168.1.20
```

上面的 IP 是示例，必须换成电脑当前地址，并与证书一致。防火墙脚本同样使用 `-LanIp`，需管理员执行。详见[手机部署说明](docs/repository-phone-setup.md)。不要直接复制历史文档中的本机 IP。

两个入口共用同一后端和数据库，各自登录和授权。手机模式中的 HTTP 回环代理只对电脑端兼容会话 Cookie；手机 HTTPS 入口仍保留 Secure 标志。不将这个开发入口当作正式公网部署。

## 测试

```powershell
# 后端测试、打包及前端测试、构建
.\scripts\build.ps1

# 算法策略、API 和校准测试（算法环境安装完毕后）
Push-Location algorithm
.\.venv-runtime\Scripts\python.exe -m unittest discover -s tests -v
Pop-Location
```

脚本与自动测试不能替代真实摄像头、已知/未知人员、手机和动作检测验收。环境检查通过也不代表参数已校准。

## 数据与安全

- `.env`、数据库、日志、依赖、模型权重、实验照片、证书与私钥均不进入 Git。
- H2 数据在 `backend/data`，通过项目脚本运行以避免生成另一份数据库。
- 手机安装公开 CA 不等于上传私钥；测试结束后可移除用户 CA、停止服务并移除临时防火墙规则。
- 离线实验会保存人脸原图，先取得同意、约定删除期限，不上传到仓库。
- 规则修改次日生效，签到/签退窗口不能重叠，不支持跨午夜班次。

## 进一步阅读

- [架构](docs/architecture.md) / [接口](docs/api.md) / [部署与备份](docs/deployment.md)
- [完整参考说明](docs/project-reference.md)
- [本次源码上传说明及排除范围](docs/repository-upload.md)

部分文档保留开发过程中的路径及测试记录，用于参考；新电脑以本 README、实际环境检查和当前配置为准。未分发的本机日志、测试证据和转移压缩包不属于 GitHub 源码交付物。
