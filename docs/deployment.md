# Windows部署、证书与排障

## MySQL安装与启动

零基础步骤见 [新电脑启动指南附录 C](新电脑启动指南.md)，包含MySQL8.4安装、Windows服务启动、专用数据库账号、连接配置和实际写入检查。MySQL独立运行，顺序是先数据库，再后端和前端。

本机先验证MySQL业务连接时，可使用 `scripts/start.ps1 -Dev -BusinessOnly -MySql`。这仍是本机HTTP开发入口，不要求先装算法或Nginx；正式HTTPS部署继续按下文执行。只传 `-Dev` 时默认使用随Java启动的H2，不能靠单改 `DB_URL` 切换。

## HTTPS证书

安装可信开发CA工具（例如mkcert）和Nginx。mkcert项目说明：<https://github.com/FiloSottile/mkcert>。先确定部署电脑稳定IP，例如 `192.168.1.20`：

```powershell
New-Item -ItemType Directory -Path deploy\certs -Force
mkcert -install
mkcert -cert-file deploy\certs\localhost.pem -key-file deploy\certs\localhost-key.pem localhost 127.0.0.1 ::1 192.168.1.20
```

`mkcert -install` 会修改当前电脑的受信任证书存储，应由部署管理员主动执行。通过 `mkcert -CAROOT` 找到根证书；只向演示设备分发公开根证书 `rootCA.pem`，**不分发 `rootCA-key.pem`**。Android/iOS安装和启用信任的步骤依系统版本操作；iOS安装描述文件后通常还需在证书信任设置中启用根证书完全信任。最后从手机实际打开HTTPS页面确认浏览器没有证书警告。

不建议靠忽略证书错误来演示摄像头。局域网HTTP通常不是摄像头所需的安全上下文；本机 `localhost` 开发属于浏览器支持的特殊情况。浏览器规则参考 <https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia>。

`.env` 配置：

```dotenv
NGINX_EXE=C:/tools/nginx/nginx.exe
COOKIE_SECURE=true
APP_ALLOWED_ORIGIN=https://localhost,https://192.168.1.20
```

如需防火墙放行，只对可信局域网开放TCP443；不要开放8080、8000或3306。部署脚本不自动修改防火墙、服务、执行策略或证书存储。`deploy/nginx.conf` 不含80端口重定向，访问时请明确输入 `https://`。

## 启动和状态判断

顺序为MySQL（管理员启动）→Java→Python→Nginx。Python单进程加载模型，不能直接增加Uvicorn worker数量，否则内存人员库与会话状态会分裂且模型会被重复加载。

`scripts/check-env.ps1` 检查工具、配置、模型文件和端口；它不能证明数据库密码正确、模型真实可用、证书已被手机信任或识别准确率达标。`scripts/start.ps1` 的“监听端口可用”只表示服务进程启动，最终就绪状态需在管理员页面检查算法模型和人员库。

日志在 `.runtime/`。反馈问题时提供请求编号、动作和时间，不要粘贴 `.env`、图像、完整模板、密码或Cookie。Nginx访问日志不记录URL和查询字符串，避免保留WebSocket会话编号。

| 现象 | 优先检查 |
| --- | --- |
| Java提示版本不支持 | 当前会话 `JAVA_HOME` 是否指向Java21；`java -version` 是否仍是旧Java |
| MySQL认证失败 | JDBC地址与专用账号、密码、授权来源主机；只监听回环地址 |
| Flyway校验失败 | 是否手工改动已执行迁移；先备份，不直接清空数据库 |
| 页面能打开但API返回404 | 前端是否通过Vite/Nginx代理，是否直接双击打开HTML |
| 登录后仍未登录 | HTTP环境误用了Secure Cookie；开发用 `-Dev`，正式用HTTPS |
| WebSocket拒绝 | 设备Cookie是否存在/到期、Origin是否与允许值完全一致、会话是否过期 |
| 找不到模型/模型清单失败 | `MODEL_DIR`是否指向包含manifest与task文件的目录；核对算法说明 |
| 人员库不同步 | Python是否就绪，内部密钥是否相同，Java同步是否失败；恢复后重新开始会话 |
| 模型版本与模板不兼容 | 切换模型后先由管理员删除旧模型的人脸模板，再逐人现场重新录入；不能直接复用旧特征 |
| 端口已占用 | 项目脚本不会终止其他程序；确认对应进程，或先停止之前启动的项目 |
| 手机相机不能打开 | HTTPS证书IP、CA信任、浏览器相机权限、相机是否被占用 |
| 补卡日期不能选 | 只允许最近7个已结束自然日中的工作日，且员工已入职、日期未超出资格 |

## 备份恢复与保留

模型文件和清单变化可能改变模型版本；有效模板版本与当前模型不一致时，系统拒绝同步人员库和新的刷脸提交。计划更换模型时先记录变更并安排重新录入，删除旧模型模板后再用新模型采集。已有历史考勤保留；新的识别阈值必须重新校准，不能沿用旧实验结论。

`backup.ps1` 使用MySQL一致性事务导出；SQL只包含当前配置数据库，秘钥留在独立配置中。`-PruneExpired` 只删除 `backups/` 目录内、匹配本脚本命名规则且超过7天的普通SQL文件，不递归删除目录，不处理符号链接。调度周期备份可在Windows任务计划程序中配置，本项目不会未经请求创建系统计划任务。

恢复步骤：

1. 停止Java、Python和Nginx；保持MySQL运行。
2. 创建一份当前数据库备份，确认要恢复的SQL来源可信。
3. 明确核对 `.env` 中的目标库；执行 `restore.ps1 -BackupFile ... -ConfirmOverwrite`。
4. 恢复原AES密钥，启动服务，验证账号、人员、历史考勤、报表和人脸模板。
5. 检查新会话可用、旧会话不能重放，再恢复演示或试用。

恢复是覆盖操作，失败后不要立刻开放访问。首次部署必须做一次独立数据库恢复演练，不能仅凭导出文件存在就认为备份有效。密钥丢失时无法从数据库恢复人脸模板，应重新核实身份并录入。

