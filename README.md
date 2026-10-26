# DerpiViewer

基于 Derpibooru 官方 JSON API 的原生 Android 客户端，支持 Derpibooru / Trixiebooru 浏览、搜索、视频流、翻译、本地收藏和网络优化。

当前应用版本：`2.0.0`

## 1.4.0 → 2.0.0 完整更新内容

2.0.0 是一次 UI 2.0 和公共主题基础设施更新，同时保留原有浏览、搜索、视频、翻译、收藏、下载及互动功能：

1. **统一 Liquid Glass 基础设施**：整理公共玻璃材质、主题 Token、Accent Palette 和组件刷新路径，TopIsland、BottomIsland、菜单、弹出层及共享卡片使用同一套主题解析。
2. **浅色与深色主题修复**：玻璃表面、文字、图标、边框、高光和阴影会随浅色/深色模式及蓝、紫、绿等强调色同步更新，避免组件保留过期或固定的深色背景。
3. **首页一级页面与浮岛布局**：首页、视频、精选、消息和个人页复用主宿主中的导航浮岛，保留页面状态、滚动位置和底部安全区域。
4. **图片详情页结构重构**：图片、上传者、描述、来源、标签、互动操作和评论组织为连续的可滚动内容流，并使用独立的玻璃信息岛承载实际内容。
5. **详情页操作菜单定位**：下载、查看原图、复制链接等操作菜单以触发按钮为锚点，根据窗口 Insets 和可用空间调整展开方向及边缘对齐。
6. **详情页交互与响应式布局**：保留图片缩放、平移、切换、点赞、点踩、收藏、分享、评论翻译和标签跳转，并适配横屏、平板、窄屏及大字体场景。
7. **共享 UI2.0 加载动画**：精选页等加载状态统一使用项目的 `IosActivityIndicator`，并在主题切换时同步更新指示器颜色。
8. **声明与版本信息页面**：设置页新增“关于与开发者声明”，展示项目介绍、MIT License、官方仓库、第三方客户端关系、作品版权、API/依赖说明及反馈入口。
9. **网络与渲染稳定性修复**：限制玻璃位移图资源分配，改善页面切换、滚动及背景采样过程中的稳定性，并保留不支持实时模糊设备上的统一降级材质。
10. **兼容性与回归覆盖**：补充公共材质、六组主题组合、详情页分区和响应式布局的自动化回归检查。
11. **视频播放稳定性**：视频卡片在 Media3 确认首帧渲染前持续显示作品预览图；长时间缓冲不会露出黑屏，播放失败会保留预览图并提供重试入口。主播放链路恢复为直接复用项目网络客户端的 OkHttp 数据源，缓存只用于有界的前向预取；视频优先使用服务端生成的 `large`/`medium` 代理源，避免 API 28 等设备尝试解码超出能力的原始高分辨率 VP9。播放器池在刷新列表时可安全复用，预加载限制为当前项之后的少量媒体，并通过作品 ID 校验异步回调，降低快速滑动和卡片复用造成的错位风险。

## 项目定位

DerpiViewer 使用原生 Android UI 和官方 JSON API 构建，不是网页套壳。网络请求统一经过项目的连接管理、限流和反爬退避逻辑；少数官方 API 未公开的互动操作会复用官方页面请求，并在代码中保留相应的兼容性处理。

## 主要功能

### 浏览与搜索

- 首页、热门精选、标签页、论坛、图集、最新评论和用户主页。
- 标签搜索支持中英文输入、中文标签补全、别名匹配和多标签补全。
- 搜索支持随机排序、随机 seed、无限滚动，以及按光标所在逗号片段显示补全。
- 搜索页快捷搜索栏支持手指实时跟随滚动，滚动停止后平滑收起或展开；收起时通过实际布局高度回收空间，不遮挡结果内容，也不会留下空白区块。
- 过滤器页支持通过过滤器 ID 调用 `GET /api/v1/json/filters/:filter_id` 查询，并显示过滤器 ID。
- 视频流支持随机/多维度排序、筛选、收藏、评论、复制视频链接和跳转图片详情页。
- 外观支持浅色、深色和彩色主题，并可选择跟随系统主题（默认）。
- DerpiViewer 统一使用 2.0 浮岛界面：主页顶部栏、底部导航和 FAB 共享圆角、间距、表面描边与过渡参数，并适配当前主题颜色。旧版界面已弃用，不再提供切换入口。
- 现阶段采用半透明表面效果，不包含真实背景模糊；搜索、详情、视频等独立页面会继续沿用统一设计系统迁移。
- 首页底栏使用 `HomeGlassTabBar` 和 Liquid Glass 组件，五个一级页面共享同一个浮岛与动态选中状态；不再通过 Beta 开关切换旧版界面。
- 首页默认使用 [Liquid-Glass-Android v2.0.11](https://github.com/QWEA0/Liquid-Glass-Android#chinese)：TopIsland、五个一级页面共用的 `HomeGlassTabBar` 和 `LiquidGlassFab` 使用统一玻璃材质。底栏选中液滴按实际 tab bounds 定位，支持点击、拖动和重复点击首页刷新；导航玻璃表面使用 inset 圆角，图标和文字绘制在采样层上方。FAB 保持 56dp 表面，上传／下载动作只动画图标，不再提供 Beta/旧版 UI 切换。
- 搜索页统一使用库内按钮、Chip／ChipGroup、ListItem／ListGroup 和玻璃弹窗；输入框、加载指示器使用 `LiquidGlassView` 承载，保留原生输入法。搜索补全、快捷条件、排序、高级筛选、结果卡片及其统计／选中／媒体类型标记均使用玻璃表面，保留点击详情和批量下载。搜索框常驻，附加筛选区域随滚动收起并释放空间。
- 标签页使用液态玻璃输入区域、搜索按钮与可回收列表项，页眉复用首页的玻璃材质、60dp 高度和 30dp 圆角；保留数量、说明、分页和跳转图片搜索，支持空结果与失败重试提示，加载动画复用 `IosActivityIndicator`。不可见列表项和后台页面停止持续渲染。
- 底栏评论、全站评论和图集页共用 `GlassFeedLayout`，采用首页同款固定玻璃页眉、白色文字、可回收玻璃卡片及加载／重试提示。评论翻译、原文切换和查看图片使用玻璃按钮，翻译状态按评论保存；底栏评论保留滚动位置并避让底部／侧边导航。图集筛选使用玻璃弹窗，保留标题、描述、创建者和打开图集功能。
- 论坛、收藏夹和下载管理页也复用固定玻璃页眉及 `LiquidGlassListItem` 卡片，收藏夹与下载管理使用库内 `LiquidGlassTabBar` 分页。论坛保留板块／主题／帖子浏览、翻译及分页；收藏夹保留文件夹管理、导入站点收藏、长按多选和批量下载；下载管理保留进度、取消、重试、打开文件和删除确认，删除文件选项使用玻璃 Chip。输入框及弹窗由玻璃表面承载，按钮采用 `LiquidGlassButton`；下载进度条保留在玻璃卡片内，进度变化只更新数值与进度条。
- 新版首页竖屏信息流在顶部预留状态栏、页眉及间距，首屏图片从页眉下方开始；向上滚动后仍可透过玻璃看到图片。
- 首页、标签页、图片搜索页、评论、图集、论坛和收藏夹页共用 `IosPullRefreshLayout`：列表到顶后继续下拉，顶部留白随手势展开，`IosActivityIndicator` 的 12 条短线逐条显现；达到阈值后松手开始旋转并刷新，未到阈值或取消手势则回弹。刷新期间保留已有内容，结束后收起留白；页眉保持固定。
- 搜索页与主页玻璃组件统一采用首页页眉的材质配置：模糊、染色透明度、饱和度、折射、色散和高光共用同一套参数，各控件保留各自的尺寸与圆角。底栏文字与图标绘制在玻璃层上方，选中玻璃与底栏共同采样页面，避免重复模糊和染色。玻璃采样包含主题底色，卡片上的玻璃只采入图片与剧透遮罩；保留圆角裁剪，不可见控件及后台页面停止持续渲染。横屏主页侧栏仍使用半透明表面，详情等其他独立页面尚未迁移。
- 设置中可独立开启或关闭标签翻译，默认开启；关闭后图片详情页显示 API 返回的原始标签。

### 图片、标签与剧透

- 图片详情页支持中文标签、标签优先级排序和多列标签布局。
- 标签翻译会兼容 API 返回的空格格式；搜索请求保留 Derpibooru 认可的标签格式。
- 图片卡片统一处理 GIF、视频标识和剧透显示。
- 设置中可选择剧透内容的显示方式：`直接隐藏`、`点击显示`、`直接显示`。直接隐藏会从列表中移除剧透卡片；点击显示只遮盖列表卡片，进入详情页不受影响。
- 图片详情页和全屏大图不受列表剧透遮罩限制。

### 翻译

- 图片简介、图片评论、论坛评论和视频评论支持逐条翻译，并可在原文和译文之间切换。
- WebView 页面支持静态规则翻译和动态内容翻译。
- 标签词典随应用打包，包含中文名、英文名、别名、优先级和图片数量；词典为 2024 年数据快照。
- 远程规则清单支持从 GitHub Pages 更新，内置规则用于离线兜底。

### 网络与连接

- Cloudflare IP 优选、深度 IP 优选和本地透明代理。
- 本地代理为 `challenges.cloudflare.com` 单独测速并路由 Turnstile 资源；其优选 IP 使用独立缓存键，TLS 仍保留原始主机名/SNI，避免复用主站或 CDN 节点造成验证 Cookie 失效。
- 支持手动指定 IP、主站/CDN 分开配置，以及 `derpicdn.net` 直连选项。
- 请求包含限流、重试、Challenge 检测和 500/501 退避逻辑。
- 人机验证页面仅在检测到 Derpibooru challenge 表单时进入验证流程，并显示加载进度。

### 账号、收藏与下载

- 登录官方页面并安全保存 API Key。
- 支持点赞、收藏、评论等互动操作，以及本地多文件夹收藏。
- 下载任务持久化保存，支持失败重试、队列通知和多尺寸下载。
- “防社死”开关可在每次启动时自动切换到指定过滤器，默认过滤器为 `Default`。

### 更新中心

- 启动时按设置频率静默检查更新，也可在设置中手动检查。
- 当前版本和远程版本按 `a.b.c` 三段数字比较：大版本优先，其次是小版本和修订版本。
- 支持“跳过此版本”和“稍后提醒”，更新频率可选永不、每小时、每天、每周或每月。
- 更新清单：[`sources.json`](https://kerybotu.github.io/appuploads/sources.json)
- APK 优先从 GitHub Pages 下载，失败后使用备用地址；下载过程显示进度和速度，完成后交给系统 APK 安装器。

## 技术栈

| 类别 | 选型 |
| --- | --- |
| UI | Android Views、ViewBinding、Material Components |
| 网络 | OkHttp、Retrofit、自定义限流/重试/Challenge 拦截器 |
| 图片 | Glide，本地缓存用于离线收藏预览 |
| 视频 | AndroidX Media3 ExoPlayer、OkHttpDataSource、单播放器池 |
| 本地数据 | SharedPreferences、应用私有文件和缓存 |
| 翻译 | 本地标签词典、WebView 规则注入、小牛翻译 API |
| 构建 | Kotlin、Android Gradle Plugin、JDK 11 |

## 构建与运行

环境要求：

- Android Studio（推荐使用项目当前兼容版本）
- JDK 11
- Android SDK 36
- Android 7.0（API 24）或更高版本的设备/模拟器

在项目根目录执行：

```powershell
./gradlew.bat :app:assembleDebug
```

生成的调试 APK 位于 `app/build/outputs/apk/debug/`。应用需要网络权限；登录、翻译和应用内更新等功能还依赖对应的远程服务可访问。

## 数据与配置说明

- `app/src/main/assets/derpibooru_tag.csv` 是随包携带的标签词典快照。
- 远程规则和更新清单由 `sources.json` 提供，网络不可用时使用本地内置规则。
- 小牛翻译服务依赖第三方接口，不保证服务稳定性或长期可用性。
- 部分点赞、收藏、评论发布等操作不属于官方公开 JSON API，站点接口变化时可能需要重新适配。
- 应用内 APK 安装需要用户允许当前应用安装未知来源应用。

## 开发者

**KeryBotu**

## 许可证

本项目基于 [MIT License](https://opensource.org/licenses/MIT) 开源发布。

```text
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
### Cloudflare 验证稳定性

Cloudflare/Turnstile 验证页与旧 Derpibooru 验证页使用独立 Activity。验证页 WebView 与 OkHttp 使用同一设备 User-Agent，并继续通过 `challenges.cloudflare.com` 的独立优选 IP 路由加载资源；TLS 仍保留原始主机名和 SNI。

验证完成判定不会在“页面刚出现 JSON”的同一时刻立即重试 API：页面 JSON 出现后会有限等待 WebView 的 `cf_clearance` Cookie 同步到共享 CookieJar，最多进行有限次 Cookie 传播重试；即使某些 WebView 环境延迟暴露 Cookie，只要 JSON 内容稳定仍会自动返回，且协调器会在短时间内合并后续验证请求，避免连续弹出验证窗口。若仍无法通过，通常需要更换网络、关闭不稳定代理或等待 Cloudflare 风险评分恢复。

### Anubis 验证

Anubis Proof-of-Work 页面通过独立的 Anubis WebView 验证界面处理；验证计算由 Anubis 官方页面脚本在浏览器环境中完成，应用等待其回跳到原 API 页面，并以有效 JSON 或 Anubis 授权 Cookie 确认完成。Anubis 检测要求页面同时包含 `anubis_challenge` 与 Anubis 专属资源标记，避免将普通 HTML 错误页误判为验证页面。Anubis、Cloudflare/Turnstile 和旧 Derpibooru 表单流程保持独立。

### Home TopIsland 动画与按钮规范

首页 TopIsland 使用独立的连续滚动控制器：首次进入采用轻量 Spring 入场，RecyclerView 滚动时按 1:1 位移跟手并联动缩放、透明度、阴影和内容视差；停止滚动后才按当前位置吸附到展开或完全移出状态，回到列表顶部会恢复完整岛屿。该动画只作用于 Home，不改变 Video、Hot、消息和我的页面。按钮样式由 `PaletteManager` 统一管理，使用圆角、约 86% 不透明度的主题色玻璃背景和统一按压反馈。

## 致谢

感谢不愿透露姓名的人士对代码的贡献，以及社区成员提供的建议。
