# DerpiViewer

一款基于官方 JSON API 构建的原生 Android 客户端，面向 Derpibooru / Trixiebooru，提供优选连接、离线可用的本地收藏、全站翻译等增强能力，弥补官方无原生客户端的空白。

## 项目定位

本项目不是网页套壳，而是完全基于 [Derpibooru 官方 JSON API](https://derpibooru.org/pages/api) 重新实现的原生界面，遵循官方 API 使用条款（合理缓存、速率限制退避、内容署名等）。在此基础上补充官方 API 未覆盖的能力（如互动写操作）时，均基于对官方页面真实网络请求的分析实现，并在代码中标注了不确定性与实测验证要求。

## 核心功能

### 网络与连接
- **Cloudflare IP 优选**：合并 Worker 下发列表、Cloudflare 官方 IP 段随机采样、本地兜底列表三路数据源，TCP 测速选出最优节点，绕过 DNS 污染
- **本地透明代理**：仅接管连接层 IP 路由，不解析 TLS 内容，保证站点人机验证等行为与真实网络环境一致
- **多站点切换**：支持在 Derpibooru / Trixiebooru 间切换，及手动指定 IP
- **速率限制与反爬退避**：内置符合官方文档规则的请求限流器与 Challenge 状态机（501 短退避 / 500 长封禁的精确处理）

### 浏览与发现
- 首页图片流、标签搜索（含自动补全与语法参考）、高级筛选（数值/日期/文本/布尔字段结构化输入）
- 热门精选（头图 + 近期优质内容筛选）
- 竖版视频流（独立播放器池、预缓冲策略、长按倍速、随机/多维度排序与筛选）
- 论坛、标签浏览、最近评论、图集（Galleries）浏览
- 用户主页（自己与他人两种模式，权限边界清晰区分）

### 翻译系统
- 静态 UI 文本：本地/远程 HTML 片段规则库（支持占位符捕获），启动早期即完成注入，不等待整页加载
- 动态内容（评论、简介等）：按可配置选择器识别，实时调用翻译中转服务
- 规则来源支持本地内置兜底 + GitHub 远程清单，可在不发版的情况下更新

### 账号与互动
- 登录流程复用官方登录页面，登录后一次性引导获取 API Key，加密存储
- 点赞 / 收藏与官方账号双向同步（基于对官方内部接口的分析实现，标注了 CSRF 令牌处理细节）
- 本地收藏夹（多文件夹分类、离线可用，独立于账号存在）与 Derpibooru 云端收藏并行展示

### 下载与素材管理
- 持久化下载队列（限并发、失败重试、聚合通知）
- 详情页多尺寸下载、列表页长按多选批量下载

### 界面与个性化
- 深色 / 浅色 + 9 种强调色算法生成主题（基于色相驱动，兼容 Android 12+ 动态取色）
- 面向移动端重新设计的信息架构（Bottom Sheet 化的复杂表单、渐进式标签编辑、乐观 UI 反馈等）

## 技术栈

| 类别 | 选型 |
|---|---|
| 网络 | OkHttp（HTTP/2 连接复用）+ 自定义限流 / 重试 / Challenge 拦截器 |
| 图片加载 | Coil，独立缩略图持久化缓存用于收藏夹离线展示 |
| 视频播放 | ExoPlayer（Media3），固定容量播放器池 |
| 本地存储 | Room（下载任务、本地收藏夹）+ EncryptedSharedPreferences（凭据） |
| 翻译渲染 | WebView content script 注入（静态规则）+ 中转 API（动态内容） |

## 已知限制

- 部分互动写操作（点赞、收藏、评论发布等）不在官方公开 API 范围内，基于对真实网络请求的分析实现，接口行为如有变更需要重新适配
- 委托目录（Commissions）等无官方 JSON 端点支撑的页面，通过内嵌网页方式提供
- 翻译中转服务依赖第三方免费接口，无 SLA 保证

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

