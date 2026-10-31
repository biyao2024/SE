# Python 人脸算法服务

FastAPI 内部服务，提供 InsightFace + ONNX Runtime CPU 的真实人脸特征提取、NumPy 1:N 检索、MediaPipe 动作检测。浏览器必须通过 Java 访问。生产入口没有模拟识别、固定身份或绕过模型的模式。

## 环境与启动

目标环境：Windows x64、Python **3.11**、单进程。48 项纯策略、API 和实验数据约束测试已在目标 Python 3.11.9 通过；真人识别和摄像头实验仍需独立验收。

本次目标环境使用 Python Software Foundation 官方 NuGet `python/3.11.9` 的 x64 运行时，解压至项目 `.toolchain/python311`，其 `python.exe` 已检查有效的 Python Software Foundation 数字签名。基础解释器也可使用自己安装的官方 Python 3.11。虚拟环境必须在目标电脑创建；本机完整环境目录为 `algorithm/.venv-runtime`，旧 `.venv` 是 Python 3.12 的轻量测试环境，不能代替它。根目录 `.env` 的 `PYTHON_EXE` 应为 `algorithm/.venv-runtime/Scripts/python.exe`。

在项目根目录使用统一安装入口（已准备好指定版本的 Java、Node、pnpm 和 Python）：

```powershell
.\scripts\install-target-deps.ps1
```

入口会创建本机 `.venv-runtime`、固定 pip 24.0，并在退出时恢复构建约束环境变量。`build-constraints-win-py311.txt` 将 InsightFace 源码编译的隔离环境 NumPy 也限定为1.26.4，防止构建隔离自动选择NumPy2而与运行时混用。约束使用文件 URI，兼容包含空格的目录。它不更改任何指定直接依赖版本。已验证 wheel 的来源、安装方式和编译前提见[目标环境说明](../docs/目标环境构建说明.md)。若官方 PyPI 反复超时，可使用清华 HTTPS 镜像安装相同版本，保留证书校验；本次安装来源与输出记录于 `.runtime/python311-pip-*.log`。

InsightFace 0.7.3 使用官方源码包本机编译，不使用不明第三方 wheel。缺少编译器时，通过微软官方 Build Tools 安装器安装 `Microsoft.VisualStudio.Component.VC.Tools.x86.x64` 和 `Microsoft.VisualStudio.Component.Windows11SDK.26100`，不需要 IDE/CUDA；主工具和包缓存可指定项目 `.toolchain`，Installer/SDK 共享组件仍可能需要系统目录和 Windows UAC 授权。安装失败时保留官方日志，不能把“安装器已启动”写成“编译已通过”。

在本目录执行 PowerShell：

```powershell
$env:INTERNAL_API_KEY = '<与Java相同的随机密钥，至少32字符>'
$env:MODEL_DIR = "$PWD\models"
.\.venv-runtime\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1 --ws-max-size 204800 --ws-max-queue 2
```

InsightFace 0.7.3 的最小编译组件见上文。不要把监听地址改为 `0.0.0.0`。不要增加 worker；每个 worker 会复制模型、人员库及会话状态。

`requirements.txt`、`requirements-api.txt` 和 `requirements-test.txt` 固定**直接依赖**。本次完整 Python 3.11.9 模型环境记录在 `requirements-win-py311.lock.txt`，包含 80 个包，已通过导入、`pip check`、48 项测试和真实 CPU 模型推理；真人动作及独立样本校准另行验收。锁文件使用 UTF-8，并将本机 wheel 路径转为 `insightface==0.7.3`，安装入口先验证并安装同目录 wheelhouse 中的产物。`requirements-test-win-py312.lock.txt` 仅为历史 Python 3.12.10 轻量测试记录，不用于本次目标安装。MediaPipe/InsightFace 间存在 OpenCV 包名重叠，已统一到 4.11.0.86，并固定 NumPy 1.26.4；安装后仍须验证导入与实际推理。

`.env.example` 是配置说明，程序不会自动读取 `.env`；根目录启动脚本负责注入环境变量。模型后台初始化一次；缺模型、缺依赖、密钥不足或校验失败时，进程可以启动，但健康状态为 `ready=false`，识别和创建会话返回 503。准备好模型后重新启动。

## 模型准备与许可

1. 从 [InsightFace 官方 v0.7 发布页](https://github.com/deepinsight/insightface/releases/tag/v0.7) 下载 `buffalo_l.zip`，解压到 `models/insightface/models/buffalo_l/`。注意避免出现双层 `buffalo_l/buffalo_l`。
2. 从 [MediaPipe Face Landmarker 官方模型页](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker#models) 下载 Face Landmarker task bundle，保存为 `models/face_landmarker.task`。
3. 核对官方来源及下载后的文件，保留下载日期、版本和许可记录，再生成本地校验清单：

```powershell
.\.venv-runtime\Scripts\python.exe scripts\model_manifest.py --model-dir models
```

必须存在以下布局；完整 buffalo_l 包中的其他 ONNX 文件也允许保留，并会一并登记、验证：

```text
models/
  manifest.json
  face_landmarker.task
  insightface/models/buffalo_l/
    det_10g.onnx
    w600k_r50.onnx
    1k3d68.onnx
```

服务在导入模型前检查每个模型文件的 SHA-256。模型版本包含文件内容指纹、策略版本和推理库版本。清单脚本记录**本机实际下载文件**的散列；它能检测后续文件变动，不等同于官方签名或来源真实性证明。仓库不伪造官方校验值、不包含模型权重、不在服务运行时自动联网下载。当前适配器仅验证 `buffalo_l` 的固定文件布局；替换模型需要同步修改适配器、重录模板并重新校准。

[InsightFace 0.7 官方说明](https://github.com/deepinsight/insightface/tree/v0.7/python-package) 区分代码与权重许可：代码为 MIT，官方预训练权重限非商业研究。毕业设计研究使用应保留说明，真实商业部署需另行取得合适授权。MediaPipe 模型与库的适用条款以官方模型页及下载包附带文件为准。

## 内部接口契约

所有 HTTP/WS 请求均须携带 `X-Internal-Key`，使用常量时间比较，健康接口也不例外。普通成功响应为裸 JSON，失败为 `{"code":"...","message":"..."}`。Java 不得将内部特征、分数和候选 ID 直接暴露在公共设备页面。

| 接口 | 请求 | 响应 |
|---|---|---|
| `GET /internal/health` | 无 | `{ready,modelVersion,galleryVersion,detail}`，HTTP 200；`galleryVersion=-1` 为未同步 |
| `POST /internal/face/extract` | multipart：3个 `files`，可选 `employeeId` | `{embeddings:[[512个浮点数]×3],modelVersion}` |
| `PUT /internal/gallery` | `{version,employees:[{employeeId,templates:[[512]×3]}]}` | `{version}` |
| `POST /internal/sessions` | 见下例 | `{sessionId}` |
| `WS /internal/sessions/{id}/stream` | JPEG 二进制消息 | `PROMPT`、`RESULT` 或 `ERROR` |
| `DELETE /internal/sessions/{id}` | 无 | `{sessionId}`；幂等取消 |

创建会话请求示例（过期时间必须由 Java 实时生成）：

```json
{
  "sessionId": "a-random-session-identifier",
  "galleryVersion": 12,
  "configVersion": 3,
  "livenessRequired": true,
  "actions": ["MOUTH", "BLINK"],
  "threshold": 0.50,
  "margin": 0.08,
  "expiresAt": "2026-10-22T12:00:30Z"
}
```

Java 负责生成不可预测的 ID、随机动作顺序和全局递增配置版本。启用时 `actions` 必须恰好包含一次 `BLINK` 和一次 `MOUTH`；关闭时建议传空数组。会话最多存活 60 秒，服务用单调时钟计算余下期限，首次图像开始另计 12 秒采集期限。

```json
{"type":"PROMPT","message":"请自然眨眼，然后睁眼","action":"BLINK"}
{"type":"RESULT","message":"识别通过，等待业务服务确认","employeeId":17,"score":0.72,"margin":0.18,"livenessStatus":"PASSED","modelVersion":"buffalo_l-实际指纹-policy2-if0.7.3-mp0.10.21","galleryVersion":12,"configVersion":3}
{"type":"ERROR","code":"IDENTITY_UNSTABLE","message":"身份不稳定，请保持同一人面对镜头"}
```

单候选人员库的 `margin` 返回有限哨兵值 `2.0`，不参与分差门槛判断。关闭动作检测的成功结果只能是 `livenessStatus=SKIPPED`。结果只是算法判断；Java 必须再次验证设备、员工状态、人员库版本、配置版本、时间窗口及会话未消费，事务提交后才能报告考勤成功。

人员库整体原子替换，最多 100 人，每人恰好 3 个 512 维模板；禁止版本倒退和同版本不同内容。同版本相同内容允许重试。版本变化立即使所有当前会话失效；同步可在模型未就绪时进行。Java 设置变更时必须逐一调用 DELETE 取消未完成会话；Python 在收到更高 `configVersion` 的新会话时也会清理旧会话。Python 不知道尚未发送过来的 Java 配置变更。

## 检测策略与资源限制

- 先校验 JPEG 头和尺寸，再解码；尺寸范围 160×120 至 1920×1080，建议 640×480。单帧不超过 200KiB、最多 80 帧、累计不超过 12MiB；WS 启动参数也在协议层限制帧大小及排队数量。
- 单人脸、最短边至少 96 像素、亮度 35–220、Laplacian 方差至少 60；头部俯仰/偏航/侧倾分别不超过 30°/35°/25°。质量门槛也是待真人实测调整的工程值。
- InsightFace 执行检测、对齐及特征提取，所有向量归一化；NumPy 计算全库余弦相似度，对每人三模板取最高分，再检查第一候选阈值和第一/第二候选分差。
- 连续至少 3 个合格画面确认身份；每帧同时检查候选 ID 和初始人脸向量的一致性。动作期间以及完成后每帧仍复核身份，多人或换人立即失败。
- 先建立自然睁眼、闭嘴基准，再验证睁眼→闭眼→睁眼及闭嘴→张嘴→闭嘴。眨眼闭合允许 1 帧，睁开阶段要求 2 帧；嘴部各阶段要求 2 帧；全部动作后要求 3 个自然状态画面。眼部系数闭合阈值 0.55/睁开阈值 0.20，张嘴阈值 0.35/闭嘴阈值 0.15，需针对设备和人群校准。
- 同时最多两个待完成会话；模型推理使用单锁，避免多线程调用模型。MediaPipe 使用 IMAGE 模式，避免两个会话共享视频跟踪状态；其性能应在目标 CPU 上实测。
- 内部认证发生于解析请求体之前。普通 HTTP 请求体限制 8MiB；录入请求体限制约 664KiB，小于 multipart 的内存暂存阈值，防止原图写入磁盘。上传超时 15 秒。原图、动作视频和特征不写入日志或文件。

动作检测用于毕业设计验证，不能宣称可抵抗预录视频、虚拟摄像头、实时换脸等攻击。[MediaPipe 官方 Python 文档](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/python) 将 Face Landmarker 定义为关键点与表情输出组件；这里的动作状态机并非商业防伪认证。

## 校准与测试

所有默认相似度、分差和动作阈值都是工程初值，不是已经测得的准确率结果。在参与者同意下，分别采集录入、校准、测试三组图像；不可复用图像。保留未知人员并覆盖照明、距离、眼镜等条件。用固定人员库对校准/测试图像做原始检索，输出以下 CSV（这里仅给表头，不提供虚构实验数据）：

```csv
sample_id,split,truth_id,candidate_id,score,runner_up_score,model_version
```

`sample_id` 建议使用独立采集图像 SHA-256；`split` 为 `calibration` 或 `test`；未入库人员 `truth_id=UNKNOWN`。`score` 为第一候选分数，`runner_up_score` 为第二候选分数，单人员库留空。`model_version` 必须一致。两组都需要已入库和未知人员样本；脚本拒绝重复样本 ID。原图及特征不要写入 CSV。

```powershell
.\.venv-runtime\Scripts\python.exe scripts\calibrate.py --samples experiments\scores.csv --output experiments\calibration-result.json --max-unknown-acceptance 0.01 --max-wrong-identity 0.01
```

脚本仅使用 calibration 分组选择参数，固定参数后再评估 test，输出正确识别率、错认率、未知接纳率、已知拒绝率及错误接受率的 95% Wilson 区间。阈值搜索范围 0.20–0.95，分差 0–0.30，步长 0.01；相同校准正确率时选更保守的阈值。该报告只评价单帧检索，不代表活体、连续帧或整套业务指标。录入同人一致性和重复绑定阈值需另外用带标签的成对数据校准。小样本下零错误不能证明总体错误率为零。

无需下载任何模型即可运行策略和 API 测试：

```powershell
.\.venv-runtime\Scripts\python.exe -m pip install -r requirements-test.txt
.\.venv-runtime\Scripts\python.exe -m unittest discover -s tests -v
```

测试用替身仅通过 `create_app(..., engine=...)` 注入于测试代码；生产 `app.main:app` 固定创建真实 `FaceEngine`。单元测试验证协议与拒绝策略，不能代替真实模型推理、真人动作、照片/视频攻击、双设备 CPU 性能及手机兼容性实验。模型未提供时，实际运行和准确率应明确记录为**未验证**。

## 在目标电脑运行真实模型诊断

目标为 Windows x64 / Python 3.11 / CPU。请在该电脑新建虚拟环境，不复制其他电脑的 `.venv`。完成依赖安装及模型清单后，在本目录执行：

```powershell
.\.venv-runtime\Scripts\python.exe -m scripts.diagnose_models --model-dir models --output experiments\model-load.json
.\.venv-runtime\Scripts\python.exe -m scripts.diagnose_models --model-dir models --image experiments\pilot\images\一张已获同意的JPEG.jpg --output experiments\model-image.json
```

第一条验证真实模型加载与 CPU provider；第二条走与服务相同的单人、质量、姿态、512维特征及 MediaPipe 表情推理。报告不包含人脸向量或图像；包含依赖/平台版本、模型指纹、耗时和输出维度。图片成功仅表示 `INFERENCE_OK`，**不表示身份识别正确、活体通过或考勤成功**。图像不合格保留明确拒绝原因并返回非零退出码。报告文件必须是新路径，避免覆盖先前证据。

`policy2` 增加了实际模型模块/CPU provider 校验、有限数值校验和统一忽略 EXIF 方向的解码规则。与旧 `policy1` 版本保存的模板不得混用；迁移时需先删除全部在职员工的旧版本模板，再重新录入、同步并校准，否则旧模板会使同步及新录入被拒绝。正式摄像头画面没有 EXIF 旋转；用于离线诊断的 JPEG 应先保存为正确的像素方向。

## 从摄像头或已有图像生成真实 scores.csv

提供三步离线工具：`capture_samples` 采集、`score_samples` 固定人员库检索、`calibrate` 选择参数并评估独立测试集。它们不会写业务数据库、确认系统已校准或放行正式打卡。

在线录入与打卡仍然不落盘。**离线实验采集会暂存人脸原图**，只在执行带 `--consent-confirmed` 的命令时开启摄像头。运行前应取得参与者同意、约定保存期限，并把实验目录保留在本机受控位置；不要上传原图、模板或数据清单到 Git/公共网盘。实验完成后按约定删除原图，保留脱敏统计即可。

从本机摄像头开始一个数据集（以下 `employee-id=1` 是员工档案内部 ID，须替换为真实 ID；并非工号）：

```powershell
.\.venv-runtime\Scripts\python.exe -m scripts.capture_samples --manifest experiments\pilot\dataset.json --subject-id p01 --employee-id 1 --split enrollment --capture-session enroll-day1 --consent-confirmed
.\.venv-runtime\Scripts\python.exe -m scripts.capture_samples --manifest experiments\pilot\dataset.json --subject-id p01 --employee-id 1 --split calibration --capture-session calibration-day2 --count 15 --consent-confirmed
.\.venv-runtime\Scripts\python.exe -m scripts.capture_samples --manifest experiments\pilot\dataset.json --subject-id p01 --employee-id 1 --split test --capture-session test-day3 --count 15 --consent-confirmed
.\.venv-runtime\Scripts\python.exe -m scripts.capture_samples --manifest experiments\pilot\dataset.json --subject-id u01 --unknown --split calibration --capture-session calibration-day2 --count 15 --consent-confirmed
.\.venv-runtime\Scripts\python.exe -m scripts.capture_samples --manifest experiments\pilot\dataset.json --subject-id u01 --unknown --split test --capture-session test-day3 --count 15 --consent-confirmed
```

预览窗口按空格保存一帧、Q 或 Esc 退出；录入依次采正脸、轻微左转、轻微右转，恰好3张。其他员工分别更换 `subject-id` 和 `employee-id` 重复前三条。采集工具保存的是未画提示文字的 JPEG，自动限制到640×480、200KiB以内。不同组必须另开采集批次，重新调整光照/距离后采集；不可把同一段连拍拆成三组。取消会保留已采进度，录入重新运行同命令会补齐剩余张数。异常退出遗留 `.capture.lock` 时，先确认没有其他采集进程，再手动移除该锁文件。

若已有获同意的图像，可以复制 `scripts/dataset.example.json` 为 `experiments/pilot/dataset.json`，填写真实相对路径、人员代号、员工 ID 和采集批次，并在核实同意后将 `consentConfirmed` 改为 `true`。示例只有格式与文件名，不含图像或实验分数。清单格式示意：

```json
{"schemaVersion":1,"datasetId":"pilot-001","consentConfirmed":true,"samples":[
  {"path":"images/p01-front.jpg","split":"enrollment","subjectId":"p01","employeeId":1,"captureSession":"enroll-day1"}
]}
```

上面仅展示一个条目；完整清单须包括全部3张录入图像，以及校准/测试各自的已知和未知人员图像。对已有文件不自动裁剪、压缩或旋转，确保与在线200KiB和分辨率限制一致。`employeeId=null` 表示未入库人员，未知者不允许出现在 enrollment。不同未知参与者也应使用不同代号。允许同一参与者重新采集进入三组，但不能复用同一图像或同一采集批次。

生成真实评分，再固定参数测试：

```powershell
.\.venv-runtime\Scripts\python.exe -m scripts.score_samples --manifest experiments\pilot\dataset.json --output-dir experiments\run-001 --model-dir models
.\.venv-runtime\Scripts\python.exe -m scripts.calibrate --samples experiments\run-001\scores.csv --run-report experiments\run-001\run.json --output experiments\run-001\calibration-result.json --max-unknown-acceptance 0.01 --max-wrong-identity 0.01
```

评分工具仅用 enrollment 建立一份固定三模板人员库，然后对 calibration/test 每张图像运行真实推理，不按默认相似度预筛选分数。录入图像仍要通过与在线相同的质量、同人一致性和重复绑定检查。最多100人、10000张样本；两组分别覆盖全部录入人员及至少一个未知人员。输出目录须为新目录，向量仅驻留内存。

工具拒绝文件 SHA-256 相同或解码 RGB 像素相同的图像，能识别改名、改元数据复用；拒绝人员标签冲突、同一采集批次跨组、路径越界和评分期间文件变化。**不能自动识别重新压缩、裁剪或翻拍后的近似重复图像**，采集人员仍须保证独立采样。标签及采集批次也需要真实填写。

`scores.csv` 在原7列基础上增加 `status,rejection_code`。正常帧为 `SCORED`，分数来自模型；质量拒绝为 `REJECTED`，候选及分数留空，拒绝原因保留。未知人员和已知人员的质量失败都留在各自分母内，不静默丢弃。模型运行故障会中止整批，不把故障当作正常拒绝。录入失败也会中止，要求重新采集。

`run.json` 记录模型版本、固定人员库指纹、输入清单/输出 CSV 指纹、各组人数与质量失败数。`calibrate --run-report` 校验 CSV 未被修改和未混入录入图像，将来源指纹写入结果。旧7列表仍可读取，但没有该评分运行证据时报告的 `sourceRun` 为 `null`。若已知或未知校准样本全部被质量检查拒绝，或可选参数无法正确识别任何已知人员，脚本不会生成可用参数。

0.01 是筛选目标，不是实测错误率承诺。多个画面来自同一参与者存在相关性，Wilson区间的独立试验假设可能不成立；不能凭几名组员的样本宣称商业准确率。试验不含动作/照片重放结论，默认参数也不会自动标记为已校准。

离线 `gallerySha256` 只标识这些 enrollment 图像建立的固定库，不读取业务库的加密模板，也不比对业务 `galleryVersion`；相同员工 ID 不代表两份模板相同。当前没有自动导入离线模板或对业务人员库自动评分的桥接。报告参数属于业务库候选值；管理员还须针对实际业务模板库做独立已知/未知样本验证，记录模型版本、人员库版本和结果后，才可确认网页设置。模板或候选人数变化后重新验证，不能只凭离线报告勾选已校准。
