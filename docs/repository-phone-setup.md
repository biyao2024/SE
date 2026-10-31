# GitHub 源码版：手机 HTTPS 演示

先按根目录 README 跑通本机业务及完整模型环境。以下命令的 IP 仅为示例，替换为 `ipconfig` 中电脑 WLAN 的当前 IPv4。

## 证书

使用自己信任的本地 CA 工具，例如官方 [mkcert](https://github.com/FiloSottile/mkcert)。由部署者审阅后执行 CA 信任安装，不要导入他人的私钥：

```powershell
New-Item -ItemType Directory -Path deploy/certs/lan -Force
mkcert -install
mkcert -cert-file deploy/certs/lan/server.pem -key-file deploy/certs/lan/server-key.pem localhost 127.0.0.1 192.168.1.20
mkcert -CAROOT
```

`mkcert -install` 会改变电脑用户的证书信任。通过最后一条命令找到公开 `rootCA.pem`，复制到 `deploy/certs/lan/rootCA.pem` 可供验证脚本使用；向手机仅传公开 CA，不传 `rootCA-key.pem` 或 `server-key.pem`。Android 在系统设置搜索“安装证书/CA证书”，安装公开 CA；实际菜单依型号而异。访问时必须无证书错误，不能靠跳过警告验收相机。

## 启动

```powershell
.\scripts\stop.ps1
.\scripts\start-phone.ps1 -LanIp 192.168.1.20
```

普通用户运行服务。在管理员 PowerShell 单独配置防火墙：

```powershell
.\scripts\allow-phone-firewall.ps1 -LanIp 192.168.1.20
```

脚本仅放行项目 `.toolchain/node/node.exe` 的 TCP 5173、指定本机地址、LocalSubnet 来源。如果使用自定义 Node 路径，先核对并调整规则中的程序路径。已有同名规则时脚本停止，需先审阅旧规则，不会静默扩大权限。

- 电脑：`http://localhost:5174`，无需安装手机 CA。
- 手机：`https://192.168.1.20:5173`，登录管理员并“授权本浏览器”，再退出管理员进入 `/kiosk`。
- 手机和电脑连接同一允许客户端互访的局域网。热点或校园网络可能隔离设备。
- 服务两入口共用数据库和人员库，不共用浏览器登录状态。

## 网络变化与清理

端口变化不需要重装 CA，但需要更新启动参数、允许来源及防火墙。IP 变化需在同一 CA 下重新签发包含新 IP 的网站证书，并更新启动 IP、规则和手机访问地址；更换 IP 后需重新授权浏览器。

结束时运行 `scripts/stop.ps1`。不再需要手机演示时，在手机的用户证书列表移除自己的测试 CA；只删下载文件不会移除信任。在管理员 PowerShell 中移除本项目规则：

```powershell
Remove-NetFirewallRule -Name FaceAttendance2-LAN-HTTPS-5173
```

本机保管证书私钥，绝不上传 Git。使用 mkcert 安装的电脑信任可按其文档执行 `mkcert -uninstall` 移除；这不等于删除手机的信任。
