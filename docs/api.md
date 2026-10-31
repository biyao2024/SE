# 接口与安全边界

业务前缀 `/api/v1`。请求和响应为UTF-8 JSON；人脸录入用multipart，连续采集用JPEG二进制WebSocket。接口实现以 `backend/src/main/java/cn/grad/attendance/` 中控制器为准，动态OpenAPI位于 `/v3/api-docs`，Swagger UI入口 `/api/docs`，仅管理员可读取。生产Nginx已代理文档资源；本地开发可登录后在同一 `localhost` 浏览器访问Java8080端口查看。

## 通用响应与登录

```json
{
  "code": "OK",
  "message": "操作成功",
  "data": {"items": [], "total": 0, "page": 0, "size": 20},
  "requestId": "服务端生成的请求编号"
}
```

列表页码从0开始，`size`最大100。失败使用相应HTTP状态和业务码，不能仅判断HTTP是否收到响应。Excel导出例外，成功直接返回xlsx二进制文件。

Java为每个HTTP请求生成 `X-Request-ID`，响应头、JSON中的 `requestId` 与日志一致，并向Python内部调用转发同一编号；不信任浏览器提交的编号。日志仅记录方法、路径、状态和耗时，不记录请求体、查询字符串、密码、图像或特征。

登录采用服务端会话Cookie，公共设备使用独立 `PUNCH_DEVICE` Cookie；Cookie为HttpOnly，正式部署启用Secure和SameSite=Strict。设备Cookie只授权打卡API，不赋予人员管理或考勤查询权限。

写操作包括登录需要CSRF：先 `GET /auth/csrf`，保存同一会话Cookie，再将响应 `data.headerName` / `data.token` 放到写请求头。账号登录后刷新CSRF状态；前端已封装该步骤。不能把内部算法密钥发送到浏览器。

| 方法与路径 | 访问范围 | 核心输入/作用 |
| --- | --- | --- |
| `GET /auth/csrf` | 公开 | 获取写请求所需令牌 |
| `POST /auth/login` | 公开 + CSRF | `username`、`password`；账号/来源限流 |
| `GET /auth/me` | 已登录 | 身份、角色、关联员工、`mustChangePassword` |
| `POST /auth/logout` | 已登录 + CSRF | 结束账号会话；不撤销公共设备授权 |
| `PUT /auth/password` | 已登录 + CSRF | `oldPassword`、`newPassword`；至少12字符、UTF-8编码最多72字节且含字母数字，保留首尾空格 |
| `GET /health` | 公开 | 仅返回服务 `UP`，不披露人员或模型细节 |

首次登录要求改密时只能访问认证相关接口。已停用员工账号的现有会话也不能继续访问业务；服务端发现停用后会清除该登录会话，浏览器可以重新获取CSRF并使用其他账号登录。

## 管理功能

以下接口均要求管理员，写请求同时要求CSRF。

| 方法与路径 | 输入或返回 |
| --- | --- |
| `GET /departments` | `page`、`size`；部门列表 |
| `POST /departments` | `name` |
| `PUT /departments/{id}` | `name`、`active` |
| `GET /employees` | `keyword`、`departmentId`、分页；返回脱离模板内容的档案 |
| `POST /employees` | `employeeNo`、`name`、`departmentId`；可给 `startDate`、`username`、`password`、`role` |
| `PUT /employees/{id}` | `name`、`departmentId`、`active`；不允许改变工号或入职日期 |
| `POST /employees/{id}/face` | multipart：3个同名 `files` 图像字段与 `consent=true`；单张≤200KB |
| `DELETE /employees/{id}/face` | 删除全部模板并更新人员库版本 |
| `GET /devices` | 授权浏览器列表，不返回凭证明文/摘要 |
| `POST /devices` | `name`；授权当前浏览器7天，并设置打卡Cookie |
| `DELETE /devices/{id}` | 撤销授权并取消未完成会话 |
| `GET /rules` | `activeRule` 与 `pendingRule` |
| `PUT /rules` | 日班时间、两个窗口、迟到/早退宽限；从次日生效 |
| `GET /calendar` | `from`、`to`、分页；返回日历例外 |
| `PUT /calendar/{YYYY-MM-DD}` | `workday`、`note`；仅未来日期 |
| `GET /settings` | 动作开关、阈值、校准确认和版本 |
| `PUT /settings` | `livenessEnabled`、`recognitionThreshold`、`candidateMargin`、`calibrationConfirmed`；更新后失效旧会话 |
| `GET /audit-logs` | `action`精确过滤及分页审计；不含图像、模板、密码或凭证 |
| `GET /system/health` | 数据库、算法、模型、人员库版本与同步状态 |
| `POST /system/sync-gallery` | 主动重试人员库同步 |

新建员工返回一次 `initialPassword`，仅供管理员现场交付，不可作为日志内容。默认角色EMPLOYEE；创建另一位ADMIN时也应关联独立员工档案。第一版最多100名在职员工，建档入职日期在过去365天至今天之间；停用后重入职须新工号档案，以保留原在职区间。

规则JSON示例：

```json
{
  "startTime": "09:00:00", "endTime": "18:00:00",
  "lateGraceMinutes": 5, "earlyGraceMinutes": 5,
  "inWindowStart": "06:00:00", "inWindowEnd": "11:59:59",
  "outWindowStart": "12:00:00", "outWindowEnd": "23:59:59"
}
```

## 考勤、申请与审批

| 方法与路径 | 访问范围/输入 |
| --- | --- |
| `GET /attendance` | 员工仅本人，管理员可筛全部；`from`、`to`、`employeeId`、`departmentId`、`status`、分页 |
| `GET /attendance/summary` | 与明细同样的范围与权限，返回统计指标和图表数据 |
| `GET /attendance/export` | 与明细同样的范围与权限，返回“考勤明细”“月度汇总”两表 |
| `GET /leave-requests` | 员工仅本人；管理员全部；分页 |
| `POST /leave-requests` | 本人：`startDate`、`endDate`、`type`、`reason` |
| `POST /leave-requests/{id}/cancel` | 本人且待审批 |
| `POST /leave-requests/{id}/review` | 管理员：`approved`、`comment`，不能审批自己 |
| `GET /correction-requests` | 员工仅本人；管理员全部；分页 |
| `POST /correction-requests` | 本人：`date`、`type`为IN/OUT、`requestedTime`、`reason` |
| `POST /correction-requests/{id}/cancel` | 本人且待审批 |
| `POST /correction-requests/{id}/review` | 管理员：`approved`、`comment`，不能审批自己 |

统计查询单次范围最多366天；默认本月1日至今天，不生成未来日报。请假区间必须在在职日期内，跨度最多366天且距当前时间一年内。补卡限定最近7个已结束自然日中的工作日，并处于对应打卡窗口。

申请状态：`PENDING`、`APPROVED`、`REJECTED`、`CANCELLED`。日报状态与迟到/早退标志分开：`NORMAL`、`PENDING`、`ABSENT`、`MISSING_IN`、`MISSING_OUT`、`LEAVE`、`REST`，同时可有 `late=true` 和 `early=true`。计数中的“完成打卡”允许迟到早退。

## 公共打卡与WebSocket

| 方法与路径 | 作用 |
| --- | --- |
| `GET /kiosk/status` | 当前浏览器设备授权与公共可见状态 |
| `POST /kiosk/sessions` | `{ "type": "IN" }` 或OUT；返回 `sessionId` 和动作设置 |
| `GET /kiosk/sessions/{id}` | 查询属于当前设备的会话结果，网络断开后核对是否已写入 |
| `DELETE /kiosk/sessions/{id}` | 取消当前设备会话 |
| `WS /kiosk/stream?sessionId={id}` | 上传JPEG二进制帧、接收JSON提示和脱敏结果 |

浏览器不提交可信员工ID、识别通过标记或最终打卡时间。WebSocket握手校验Origin、设备Cookie、会话归属和有效期，Java再与Python建立带内部密钥的WebSocket。单帧≤200KB，单会话≤80帧/12MB，会话有效期60秒，采集流程最多12秒。建议640×480、约6fps，Uvicorn单worker，最多同时处理两个算法会话。

服务端事件类别：

```json
{"type":"PROMPT","message":"请单人正对镜头"}
{"type":"PROMPT","action":"BLINK","message":"请眨眼并恢复自然睁眼"}
{"type":"ERROR","code":"TIMEOUT","message":"验证超时，请重新开始"}
{"type":"RESULT","status":"SUCCESS","message":"打卡成功","employeeName":"张*","employeeNo":"***001"}
```

以上展示消息字段和类别，实际成功状态/文案以返回值为准。公共消息不会包含相似度、候选列表、完整人员档案或特征模板。只有Java成功完成事务并消费会话后才能显示最终成功。断网时通过查询会话核对结果；不能将前端计时结束、相机关闭或算法识别通过直接视为考勤写入成功。

## Python内部协议

只监听 `127.0.0.1:8000`，所有HTTP/WS请求都要求 `X-Internal-Key`。不提供浏览器CORS、公开Swagger或直接连接数据库。

| 方法与路径 | 请求/返回 |
| --- | --- |
| `GET /internal/health` | `ready`、`modelVersion`、`galleryVersion`、`detail` |
| `POST /internal/face/extract` | multipart：3个 `files`、可选 `employeeId`；返回3份512维 `embeddings` 与模型版本 |
| `PUT /internal/gallery` | `{version, employees:[{employeeId,templates:[3份512维向量]}]}`，原子替换 |
| `POST /internal/sessions` | `sessionId`、人员库/配置版本、`livenessRequired`、随机 `actions`、阈值/分差、含时区 `expiresAt` |
| `WS /internal/sessions/{id}/stream` | JPEG二进制输入；PROMPT/ERROR/RESULT事件 |
| `DELETE /internal/sessions/{id}` | 取消并释放会话 |

内部RESULT包含候选员工、识别分数、分差、动作结果以及模型/配置/人员库版本，Java复核后转换为公共脱敏消息。未知模型、无效清单或未就绪不会自动启用替代模型。内部请求校验错误不会回显输入向量。

常见失败码包括 `NO_FACE`、`MULTIPLE_FACES`、`LOW_QUALITY`、`IDENTITY_UNSTABLE`、`TIMEOUT`、`STALE_GALLERY`、`MODEL_UNAVAILABLE`；初始未识别到已录入人员时也可能返回可重试的PROMPT。业务层另有 `DEVICE_UNAUTHORIZED`、`OUTSIDE_WINDOW`、`CALIBRATION_REQUIRED`、`CONFLICT`、`ALGORITHM_UNAVAILABLE` 等码。排查时以具体响应的 `code`、`message`、`requestId` 为准，保留可读文案而不暴露模型输出细节。
