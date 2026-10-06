# DerpiViewer

一款基于官方 JSON API 构建的原生 Android 客户端，面向 Derpibooru / Trixiebooru，提供优选连接、离线可用的本地收藏、全站翻译等增强能力，弥补官方无原生客户端的空白。

当前应用版本：`1.3.0`

## 项目定位

本项目不是网页套壳，而是完全基于 [Derpibooru 官方 JSON API](https://derpibooru.org/pages/api) 重新实现的原生界面，遵循官方 API 使用条款（合理缓存、速率限制退避、内容署名等）。在此基础上补充官方 API 未覆盖的能力（如互动写操作）时，均基于对官方页面真实网络请求的分析实现，并在代码中标注了不确定性与实测验证要求。

## 核心功能

### 网络与连接
- **Cloudflare IP 优选**：从 Cloudflare 官方 IPv4/IPv6 网段随机抽样，结合本地兜底节点进行受限并发 TCP 测速，绕过 DNS 污染
- **深度 IP 优选**：在设置中手动启动，执行四次 TCPing 均值、主站连通性验证和 CDN 图片下载测速，并提供详细过程日志；结果可同时应用到主站与 CDN
- **本地透明代理**：仅接管连接层 IP 路由，不解析 TLS 内容，保证站点人机验证等行为与真实网络环境一致
- **多站点切换**：支持在 Derpibooru / Trixiebooru 间切换、手动指定 IP，以及在设置中选择 `derpicdn.net` 直连
- **速率限制与反爬退避**：内置符合官方文档规则的请求限流器与 Challenge 状态机（501 短退避 / 500 长封禁的精确处理）

### 浏览与发现
- 首页图片流、标签搜索（中英双语自动补全与语法参考）、高级筛选（数值/日期/文本/布尔字段结构化输入）
- 搜索支持随机排序、随机 seed 分页和无限滚动；多标签输入时按光标所在逗号片段提供补全
- 热门精选（头图 + 近期优质内容筛选）
- 竖版视频流（独立播放器池、预缓冲策略、长按倍速、随机/多维度排序与筛选）
- 论坛、标签浏览、最近评论、图集（Galleries）浏览
- 用户主页（自己与他人两种模式，权限边界清晰区分）
- 过滤器支持按过滤器 ID 查询官方 JSON 端点

### 翻译系统
- 静态 UI 文本：本地/远程 HTML 片段规则库（支持占位符捕获），启动早期即完成注入，不等待整页加载
- 动态内容（评论、简介等）：按可配置选择器识别，实时调用翻译中转服务
- 图片简介、图片评论、论坛评论和视频评论支持逐条翻译，并可在原文与译文之间切换
- 图片详情页使用本地标签词典显示中文标签，按标签优先级排序；数据来自随包携带的 `derpibooru_tag.csv`
- 规则来源支持本地内置兜底 + GitHub 远程清单，可在不发版的情况下更新

### 账号与互动
- 登录流程复用官方登录页面，登录后一次性引导获取 API Key，加密存储
- 点赞 / 收藏与官方账号双向同步（基于对官方内部接口的分析实现，标注了 CSRF 令牌处理细节）
- 本地收藏夹（多文件夹分类、离线可用，独立于账号存在）与 Derpibooru 云端收藏并行展示

### 下载与素材管理
- 持久化下载队列（限并发、失败重试、聚合通知）
- 详情页多尺寸下载、列表页长按多选批量下载
- 更新中心支持 GitHub Pages 主下载源与备用下载源，显示下载进度和速度，并在下载完成后打开系统 APK 安装器

### 界面与个性化
- 深色 / 浅色 + 9 种强调色算法生成主题（基于色相驱动，兼容 Android 12+ 动态取色）
- 面向移动端重新设计的信息架构（Bottom Sheet 化的复杂表单、渐进式标签编辑、乐观 UI 反馈等）

### 应用更新
- 启动时按设置的频率静默检查更新，也可在设置中立即检查
- 使用 `a.b.c` 三段数字比较远程版本和当前已安装 APK 版本
- 支持“跳过此版本”和“稍后提醒”；更新频率可设置为永不、每小时、每天、每周或每月
- 更新清单地址：[`sources.json`](https://kerybotu.github.io/appuploads/sources.json)
- 主下载源优先使用 GitHub Pages，失败时自动切换备用下载地址；下载完成前会校验 APK 文件格式

## 技术栈

| 类别 | 选型 |
|---|---|
| 网络 | OkHttp（HTTP/2 连接复用）+ 自定义限流 / 重试 / Challenge 拦截器；更新下载使用 HttpURLConnection |
| 图片加载 | Coil，独立缩略图持久化缓存用于收藏夹离线展示 |
| 视频播放 | ExoPlayer（Media3），固定容量播放器池 |
| 本地存储 | Room（下载任务、本地收藏夹）+ EncryptedSharedPreferences（凭据） |
| 标签与翻译 | 随包标签词典（中英搜索、别名、优先级）+ WebView 规则注入 + 小牛翻译 API |

## 已知限制

- 部分互动写操作（点赞、收藏、评论发布等）不在官方公开 API 范围内，基于对真实网络请求的分析实现，接口行为如有变更需要重新适配
- 委托目录（Commissions）等无官方 JSON 端点支撑的页面，通过内嵌网页方式提供
- 翻译中转服务依赖第三方免费接口，无 SLA 保证
- 内置标签词典为 2024 年数据快照，新标签可能无法提供中文名称；查不到翻译时客户端保留原文
- 深度 IP 优选会产生较多网络请求，仅建议在用户主动操作时使用；测速结果受运营商、地区和当前网络状况影响
- 应用内 APK 更新依赖更新清单和下载源可访问，并且需要用户允许当前应用安装未知来源 APK

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
