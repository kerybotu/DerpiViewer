# DerpiViewer

一款基于 Derpibooru API 的 Derpibooru 第三方客户端，通过 Cloudflare 优选 IP 绕过 DNS 污染，并内置翻译、下载、文件上传等增强功能。

（懒得改了）

## 功能特性

- **Cloudflare IP 优选**：应用启动时自动从 Worker 拉取优选 IP 列表，并发测速选出当前最快节点，绕过 DNS 污染导致的访问异常
  - IP 列表缓存 1 小时，测速结果缓存 5 分钟，短时间内重复启动直接复用，节省 Worker 请求配额
  - Worker 不可用时自动回退到本地预编码的兜底 IP 列表，保证任何情况下都能启动
  - 支持在设置面板手动触发重新优选
- **透明代理转发**：基于 `ProxyController` + 本地 TCP 转发代理，仅接管 DNS/连接层，不解析 TLS 内容，保证 Cloudflare 人机验证、Cookie、表单提交等行为与原生浏览器完全一致
- **整页翻译**：通过 JS 注入原地替换网页文本节点，翻译请求经 Cloudflare Worker 中转 Google 翻译接口
  - 会话内翻译缓存，重复文本（如标签）只请求一次
  - `MutationObserver` 监听滚动加载等动态插入的新内容，自动增量翻译
  - 防抖批量处理，避免高频小请求浪费 Worker 额度
- **登录状态保持**：基于 WebView 原生 CookieManager 持久化存储，关闭应用后无需重复登录
- **文件下载**：图片等资源下载走本地代理通道，不受目标域名 DNS 状态影响，保存至系统下载目录并发送完成通知
- **文件上传**：支持网页 `<input type="file">` 触发系统文件选择器，可多选
- **顶栏工具菜单**：
  - 字体大小调整（多级缩放）
  - 翻译整页 / 恢复原文
  - 复制当前页面链接
  - 设置面板：查看当前节点 IP、手动重新优选、开发者信息

## 技术实现概览

| 模块 | 说明 |
|---|---|
| `MainActivity.kt` | 主界面、WebView 配置、状态栏适配、三点菜单、文件上传/下载入口 |
| `LocalProxyServer.kt` | 本地透明转发代理，处理 HTTPS CONNECT 隧道，将目标域名连接指向优选 IP |
| `IpOptimizer.kt` | IP 列表获取（Worker + 缓存 + 兜底）与并发测速优选 |
| `TranslateBridge.kt` | JS 与原生的翻译桥接，实际网络请求经 Cloudflare Worker 中转 |
| `DownloadHelper.kt` | 走本地代理的文件下载与系统媒体库写入 |
| `assets/translate.js` | 页面内文本节点扫描、替换、动态内容监听 |

## 已知限制

- 依赖 `androidx.webkit` 的 `ProxyOverride` 特性，部分极旧设备的系统 WebView 版本可能不支持，此时会自动回退为不经过代理的直连模式
- 翻译功能依赖非官方免费接口中转，无 SLA 保证，未来存在失效或限流的可能
- Cloudflare 优选 IP 列表由外部 Worker 提供，其可用性与延迟表现依赖该服务本身

## 开发者

**KeryBotu**

## 许可证

本项目基于 [MIT License](https://opensource.org/licenses/MIT) 开源发布。

```
MIT License

Copyright (c) 2026 KeryBotu

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
