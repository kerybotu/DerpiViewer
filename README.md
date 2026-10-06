# DerpiViewer

基于 Derpibooru 官方 JSON API 的原生 Android 客户端，支持 Derpibooru / Trixiebooru 浏览、搜索、视频流、翻译、本地收藏和网络优化。

当前应用版本：`1.4.0`

## 1.3.0 → 1.4.0 完整更新内容

以下记录以仓库中的 `README.md.txt`（1.3.0 版本备份）为基线，只列出本次实际纳入 1.4.0 的更新：

1. **本地词库校对**：校对部分标签中文翻译、组合名和相关词条，修正明显不合理的译名。
2. **防社死开关**：设置中增加“防社死”开关，开启后启动应用时自动使用指定过滤器，默认过滤器为 `Default`。
3. **图片剧透遮罩**：为图片卡片和图片预览页增加剧透遮罩，支持通过显示按钮查看原图，并保持正常进入详情页的交互。
4. **过滤器 ID 展示**：在设置过滤器页的每个过滤器卡片中显示过滤器 ID。
5. **图片卡片媒体标识**：图片为 GIF 动图时在右上角显示 `ic_gif.xml` 的“GIF”图标，图片为视频时显示“视频”图标。
6. **图片详情页实时跟手**：左右切图支持手指实时跟随，拖动过程中同步更新位移和透明度，并优化释放手指后的回弹与切换动画。
7. **图片详情页缩放与平移**：在 `ImageDetailActivity` 中加入双指缩放、矩阵平移和双击缩放；放大后手势用于查看图片内容，不与左右切图、单击查看大图和长按菜单冲突。
8. **图片详情页切图动画**：优化左右滑动切换的过渡、回弹和进出场动画，改善滑动手感。
9. **视频流缓存稳定性**：将共享 `SimpleCache` 接入 `CacheDataSource`，仅由当前 ExoPlayer 在正常播放时安全写入并复用缓存。移除可取消的后台 `CacheWriter` 预测写入，避免快速翻页时留下锁定缓存片段导致回翻播放失败；播放器使用优选 IP 网络链路。
10. **评论组件统一重构**：统一评论数据模型、评论卡片布局、主题配色和翻译入口，并接入所有现有评论展示入口。
11. **视频页收藏修复**：修复视频页点击收藏后收藏无效的问题。
12. **视频页筛选修复**：修复视频页筛选条件中的比较符号未正确转换为 API 语法的问题。
13. **视频链接复制修复**：修复视频页“复制视频链接”无法复制正确链接的问题。
14. **视频原页面跳转修复**：修复视频页“查看原页面”无法跳转的问题，改为跳转到对应的图片预览/详情页。
15. **外观跟随系统**：外观设置增加“跟随系统”选项并设为默认，随系统在浅色和暗色方案之间切换。
16. **其他问题修复**：修复本版本范围内的显示、交互、状态同步和稳定性问题。
17. **搜索页顶栏跟手优化**：快捷搜索区域根据 RecyclerView 的滚动距离实时调整实际布局高度并同步渐隐，搜索框始终保留；松手后按展开比例平滑吸附，避免阈值触发造成的跳动，同时让结果区域自动填充释放的空间，修复收起时的黑块和空白区。
18. **底栏一级页面嵌入**：视频、消息和“我的”改为 `MainActivity` 底栏的一级嵌入式页面，与首页、热门共享同一个主内容宿主和 Bottom Island；切换只改变页面可见性，不重新创建页面实例。
19. **嵌入页面状态保持**：视频页保留 `ViewPager2`、播放器池、播放位置和向下预加载任务；消息页保留已加载评论和滚动位置；我的页面保留资料、图片列表和当前滚动状态。其他用户资料与消息详情仍可作为二级页面打开。
20. **视频底部空间避让**：视频流根据 Bottom Island 的实际测量高度动态设置底部安全区域，播放进度和视频信息不会被底栏覆盖；横屏时使用侧边导航布局并释放底部占用空间。

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
- 设置中可启用“使用新版界面（Beta）”：当前阶段将主页顶部栏、底部导航和 FAB 切换为统一的漂浮岛式表面，并沿用首页、视频、热门、评论、我的五个入口；开关默认关闭，返回主页时即时生效，关闭后恢复旧版主页。
- Beta 主页使用 [Liquid-Glass-Android v2.0.11](https://github.com/QWEA0/Liquid-Glass-Android#chinese)：页眉保持固定，底栏整条替换为库内 `LiquidGlassTabBar`（五个分页、玻璃滴滑动／拖拽选择、重复点击首页刷新），加号替换为 `LiquidGlassFab`。保留旧版界面的 Material 底栏与按钮。液态玻璃组件文字在深浅色模式下统一为白色，图标保持浅色黑、深色白，不随图片亮度闪变；分页点击无涟漪。加号仍支持上传／下载切换，玻璃表面固定 56dp，仅图标参与动画。
- 搜索页统一使用库内按钮、Chip／ChipGroup、ListItem／ListGroup 和玻璃弹窗；输入框、加载指示器使用 `LiquidGlassView` 承载，保留原生输入法。搜索补全、快捷条件、排序、高级筛选、结果卡片及其统计／选中／媒体类型标记均使用玻璃表面，保留点击详情和批量下载。搜索框常驻，附加筛选区域随滚动收起并释放空间。
- 标签页使用液态玻璃输入区域、搜索按钮与可回收列表项，页眉复用首页的玻璃材质、60dp 高度和 30dp 圆角；保留数量、说明、分页和跳转图片搜索，支持空结果与失败重试提示，加载动画复用 `IosActivityIndicator`。不可见列表项和后台页面停止持续渲染。
- 底栏评论、全站评论和图集页共用 `GlassFeedLayout`，采用首页同款固定玻璃页眉、白色文字、可回收玻璃卡片及加载／重试提示。评论翻译、原文切换和查看图片使用玻璃按钮，翻译状态按评论保存；底栏评论保留滚动位置并避让底部／侧边导航。图集筛选使用玻璃弹窗，保留标题、描述、创建者和打开图集功能。
- 新版首页竖屏信息流在顶部预留状态栏、页眉及间距，首屏图片从页眉下方开始；向上滚动后仍可透过玻璃看到图片。
- 首页、标签页、图片搜索页、评论和图集页共用 `IosPullRefreshLayout`：列表到顶后继续下拉，顶部留白随手势展开，`IosActivityIndicator` 的 12 条短线逐条显现；达到阈值后松手开始旋转并刷新，未到阈值或取消手势则回弹。刷新期间保留已有内容，结束后收起留白；页眉保持固定。
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
