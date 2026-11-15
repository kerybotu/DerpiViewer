# 视频专项修复验证（2026-11-15）

## 四项根因与修复

| 问题 | 已确认原因 | 生产代码修复 |
| --- | --- | --- |
| 视频页底栏黑底 | `MainActivity.updateShellGlass()` 为嵌入视频页选用了通用滚动内容背景源，没有使用已有的视频帧背景源 | 视频标签选择 `EmbeddedVideoView.glassBackdropSource`。不修改玻璃引擎、材质参数或预加载数量。视频本身的黑边仍是真实背景，不能承诺黑边区域也有彩色画面。 |
| 三个作品的 HTTP 错误 | 350706、486065、561551 的 API MIME 均为 `image/gif`；API 宣告的 MP4 和 WebM 实际返回 404，原始 GIF 可用。旧候选策略遗漏显式 WebM，且过滤掉原始 GIF，无法回到有效媒体 | `VideoSourcePolicy` 加入 API WebM；`VideoFeedController` 在替代流耗尽且收到 404/410 后，使用已有 Glide 播放原始 GIF，停用不适用于 GIF 的视频 seekbar，处理离页停止和返回恢复。没有忽略 HTTP 错误、伪造视频状态或无限重试。 |
| 详情页控件 | 整个 PlayerView 的触摸监听消费普通视频触摸，视频点击监听又打开大图页；独立播放器也没有复用视频网络客户端和配置生命周期 | `ImageDetailActivity` 将普通视频触摸交还原生控件，只保留既有水平翻页处理；翻页接管时取消控件手势。`MediaPreviewPlayer` 复用 `VideoPlayerPool`，绑定生命周期与网络配置刷新。 |
| 大图页错误识别 | “查看原图” Intent 遗漏 MIME；有完整 URL 时大图页直接进入 Glide 图片路径，不再取得 API 元数据 | 入口复用 `openLargePreview()` 携带 MIME；缺 MIME 且有 ID 时查询 API。`MediaTypePolicy` 规范化 API MIME，区分 GIF 和视频，不按 URL 后缀判断媒体类型。另移除 GIF 请求中的 `.dontAnimate()`：该选项禁止 GIF 解码，与 `.asGif()` 冲突，真实 GIF 测试据此由失败转为通过。 |

## 调用链与资源

- 视频页：API → `VideoSourcePolicy` → `VideoFeedController` → `VideoPlayerPool` → 当前 `videoHttpClient`/共享缓存 → TextureView。仅已证实转码缺失的原始 GIF 转交现有 Glide；仍有正常错误提示。
- 详情页、大图页：API MIME → 视频分支 → `MediaPreviewPlayer` → 同一 `VideoPlayerPool` 实现/共享缓存/视频 HTTP 客户端 → PlayerView 原生控件。各可见页面持有自己的有界播放器池，不让同一个播放器同时绑定多个 Surface。静态图与 GIF 保留图片查看路径。
- `ResourceCoordinator.enterVideoTab()`、离页停用及既有预加载策略未改。预览页 stop 暂停、start 按原意图恢复、destroy 解绑并释放；网络配置变化时保存进度并重建播放器。
- 两个预览 PlayerView 改用与视频页一致的 TextureView，显示真实缓冲状态。
- `MediaHttpDiagnostics` 在每个请求解析当前视频客户端，记录去掉用户信息、查询参数和 fragment 的 URL、状态、Content-Type/Length/Range、Accept-Ranges、请求 Range、重定向次数、最终域名、节点/路由/配置版本和阶段。播放器另记错误码、状态与异常类型链。没有 Cookie、Authorization、API Key 值或异常正文。

## 实际验证

环境为 API 36 Android 模拟器 emulator-5554，竖屏 720×1280、横屏 1280×720。没有连接小米实机。

| 验证 | 结果与证据 |
| --- | --- |
| 编译/单元测试 | Debug、androidTest、签名 Release 均构建成功；MediaTypePolicy 3 项和 VideoSourcePolicy 6 项单元测试全部通过，XML 结果附本目录。 |
| 完整集成组 | `video-final-suite.log`：4/4 通过，覆盖静态图/GIF、视频页底栏与切页/主题、详情及大图控件、真实字节的 HTTP/Range。 |
| H.264 原生控件 | 实际 CDN 视频 3868041 的 MP4（H.264/AAC，10.665 秒）下载后由本地 HTTP 服务提供。详情和大图页真实 DOWN/MOVE/UP，验证暂停、拖动到约 70%（容差 15%）、保持暂停、继续播放；不是 performClick。竖屏见完整组，横屏见 `video-actual-landscape.log`。 |
| VP9 原生控件 | 同作品真实 WebM（VP9/Opus）实际播放及触摸 seek 通过，见 `video-vp9-native.log`。服务 MIME 为 video/mp4，播放器仍按媒体字节识别 WebM，未根据后缀选择解码器。 |
| 竖版测试视频 | FFmpeg testsrc2 360×640、12fps、8 秒、H.264，实际编码视频经过生产播放器与背景采样链路；不是覆盖在玻璃上的静态图。竖屏玻璃见 `embedded-bottom-light.png`、`embedded-bottom-dark.png`；同媒体在详情与大图页的控件测试见 `video-pattern-controls-final.log`，通过。 |
| 横屏视频页切换/返回 | `video-embedded-landscape.log`：两个不同 URL 的本地媒体项切换并返回已看媒体，暂停/播放、浅/深色组件刷新、主页切回视频页均通过；见两张 `embedded-bottom-landscape-*.png`。 |
| HTTP 状态与 Range | 本地服务提供真实视频字节，验证 200、206 精确字节区间、不支持 Range 返回 200 时正确跳过字节、重定向后 206、403、404、200 HTML 被拒绝。失败分类保持 HTTP/传输/格式/解码区别，未吞掉异常。 |
| 用户三个作品 | 使用未改写的真实 API JSON，经生产解析器放入列表；生产播放器实际请求 CDN 并得到转码 404，然后 Glide 原始 GIF 成功播放。3 项、重访第一项、离页停止与返回恢复通过，见 `video-reported-gifs.log`、`reported-http.txt` 和三张作品截图。这里只绕过列表 API 获取，不绕过媒体 HTTP 请求或认证。 |
| 静态图/GIF | 本地 PNG 与真实 350706 原始 GIF 经过大图生产分支，成功解码且没有播放器控件，见完整组及 `fullscreen-gif.png`。 |

测试 APK 的 localhost HTTP 例外仅位于 debug source set；Release 没有该例外。签名 APK 输出在 `app/release/app-release.apk`，apksigner 验证 v2=true、1 个签名者。签名密码来自仓库外脚本，本提交没有签名材料或脚本。

## 限制与复现方法

- 尚未验证小米/其他真机、API 28 等旧系统、硬件 VP9 解码器兼容性、真实 IP 优选节点切换、生产 CDN 验证页/TLS/DNS 故障以及所有编码组合。模拟器 VP9 通过不代表这些设备均已通过。
- 本地 HTTP 异常测试可证明数据源处理，不能代替真实 CDN 的所有策略；三个真实作品的缺失转码已单独实测。
- 没有声称端到端列表 API 加载全部通过；三个报错样本通过真实 API 快照注入测试，详情页控件使用本地真实媒体，未依赖站点详情请求成功。
- 原工作区的布局调整和构建配置未提交；视频底栏横屏截图包含原有工作区布局。对 VideoFeedAdapter 仅提交 `useOriginalAnimation` 状态字段，未夹带已有横屏布局变更。

运行测试前向目标 app external-files 放入：`video-p0.mp4`、`video-p0.webm`、`video-pattern.mp4`、`video-original.gif` 和 `video-350706.json` / `video-486065.json` / `video-561551.json`。JSON 取站点 `/api/v1/json/images/{id}`；媒体取该 API 的真实 representations。不能以伪造登录或伪造 READY 替代。

```text
gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests *MediaTypePolicyTest --tests *VideoSourcePolicyTest
adb shell am instrument -w -r -e class com.kerybotu.derpibooru.mirror.VideoMediaAcceptanceTest com.kerybotu.derpiviewer.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e orientation landscape -e class com.kerybotu.derpibooru.mirror.VideoMediaAcceptanceTest#detailAndFullscreenNativeControlsReceiveRealTouches com.kerybotu.derpiviewer.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class com.kerybotu.derpibooru.mirror.ReportedGifPlaybackTest com.kerybotu.derpiviewer.test/androidx.test.runner.AndroidJUnitRunner
```

本地测试应临时关闭模拟器全局 HTTP 代理；本次结束已恢复原代理 `10.0.2.2:7897` 和旋转设置。未清理或覆盖已有工作区修改。
