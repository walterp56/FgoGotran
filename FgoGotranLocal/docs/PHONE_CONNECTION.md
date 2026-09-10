# 手机连接

## 正确地址

控制页面 `http://127.0.0.1:18081` 只能在电脑打开。手机应连接页面“总览”显示的翻译 Endpoint，例如：

```text
http://<PC-LAN-IP>:18080/v1/chat/completions
```

`192.168.3.1` 通常是路由器，不是电脑。

## 检查顺序

1. 手机和电脑连接同一个可信 Wi-Fi。
2. Windows 网络类型设为“专用网络”。
3. Profile 的网络访问选择“可信局域网 + 本机”。
4. llama-server 状态为“已就绪”。
5. 在手机浏览器访问页面显示的 Health 地址，例如 `http://<PC-LAN-IP>:18080/health`。
6. 在 FgoGotran 填入完整 Endpoint、相同 Model ID 和完整 API Key。

如果 Health 一直加载，检查 Windows 防火墙。首次弹窗只允许专用网络；不要允许公共网络。

## 地址变化

路由器可能在电脑重连后分配新的局域网地址。发生连接失败时，以“总览”当前显示的 Endpoint 为准。需要固定地址时，在路由器内为电脑设置 DHCP 地址保留，而不是建立公网端口转发。
