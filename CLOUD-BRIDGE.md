# Cloud Music / Lyricon Cloud Bridge v1

本分支基于 [tomakino/lyricon](https://github.com/tomakino/lyricon) 的 master（`c93da0eef8b7aee015d2670b48e9e262fcf72cd1`），保留 Apache-2.0 许可证。新增显式 ContentProvider 接口，把云听 Expressive 的结构化歌词与播放状态交给 Lyricon 原有 Provider SDK，再由 SDK 交给已激活的 SystemUI 模块。此分支没有绕过模块激活或系统权限，也不在网络上开放服务。

## 安装与启用

1. 音乐端使用云听 Expressive `0.5.0-md3e` 或以上，包名 `io.github.cloudmusic.expressive`。
2. 词幕端使用本分支 `1.0.40.cloudbridge.2`，音乐端推荐 `0.5.1-md3e`；包名仍为 `io.github.proify.lyricon`，以兼容原模块桥接。安装到手机，不安装到 Pixel Watch。0.5.1 增加提供者元数据，云听可出现在词幕的歌词提供服务列表。
3. 在模块管理器中启用词幕并按其原有要求配置 SystemUI 作用域、重启使模块生效；在音乐端设置中开启 Lyricon 对接。
4. 播放一首有歌词的歌曲，检查播放、暂停、切歌、拖动进度和逐字歌词。系统模块未生效时，接口可接收数据，但状态栏不会显示歌词。

调试 APK 使用本机 Android 调试证书。若原有词幕使用官方签名，Android 无法原地覆盖；请先使用原版提供的方式保存设置，再决定是否卸载原版安装此分支。没有实现跨签名迁移，不能保证原版 1.0.20 的配置格式与当前上游兼容。

## 接口

`ContentResolver.call(Uri.parse("content://io.github.proify.lyricon.cloudbridge"), method, null, extras)`。

接口仅接受 UID 所属包名包含 `io.github.cloudmusic.expressive` 的调用者，其他 UID 抛出 SecurityException。这是本机已安装包身份检查，不是开发者证书白名单。

`update` 的 Bundle：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| version | Int | 固定为 1 |
| session | String | 发送端进程生成的 UUID，重启后更换 |
| stamp | Long | `SystemClock.elapsedRealtimeNanos()`；同一手机启动周期内单调递增 |
| song | String，可省略 | Song JSON；首次连接、切歌、歌词更新或缓存失效时必须提供 |
| position | Long | 当前歌曲位置，毫秒，负数归零 |
| playing | Boolean | 播放或暂停 |

Song JSON 使用当前上游的 `Song` / `RichLyricLine` / `LyricWord` 模型；包含 `id`、`name`、`artist`、`duration`、`lyrics`。歌词行为 `begin`、`end`、`text`、`translation`、`roma`、`isAlignedRight`、`words`，逐字为 `begin`、`end`、`text`。所有时间均为歌曲内的绝对毫秒，逐字开始时间不是相对行偏移。接收端忽略未知字段。

限制：Song JSON UTF-8 最多 200,000 字节，最多 2,000 行；音乐端最多 500 字/行。超限先丢弃逐字信息，再降级到歌曲信息与空歌词，避免 Binder 大事务。接收端会拒绝空歌曲 ID、负时长及超限载荷。曲目切换先发送新曲目与空歌词，异步歌词就绪后再补发，防止旧歌词残留。

返回 `accepted=true, version=1` 表示已接收并排队，不表示 SystemUI 模块已连上或歌词已渲染。`accepted=false` 时发送端清除发送缓存，下次更新携带完整 Song。SDK 重连时词幕重发歌曲、状态与校正后的进度。播放中音乐端持续发送位置；10 秒没有新消息则词幕暂停该流，防止音乐进程异常退出后歌词继续滚动。

`clear` 使用同一 session 与 stamp；只有当前会话能清除流，并销毁 SDK Provider。较早时间戳及超过本机当前时间 1 秒的消息被拒绝。旧会话不能清除新会话的歌词。接口不开放查询、插入、删除或更新数据库功能。

## Pixel Watch 封面

封面由音乐端发布到 Android MediaSession 的内嵌 artworkData，词幕接口不承担手表传图。音乐端对封面进行缩放与压缩（上限 256 KiB），切歌清理旧图并拒绝旧异步请求。手表通过手机现有媒体同步获取封面，需要手机与手表的蓝牙/媒体连接正常。

## 构建

JDK 17、Android SDK 37（平台目录可能为 `android-37.0`）、Gradle Wrapper 9.3.1；运行 `gradlew.bat :lyricon:assembleDebug --no-configuration-cache`。调试构建无需官方签名私钥。发行构建沿用原来的 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 环境变量，必须使用维护者自己的签名。

输出：`lyricon/build/outputs/apk/debug/lyricon-arm64-v8a-debug.apk`。保留上游包名是模块集成要求，因此此 APK 不能与官方词幕并行安装。

## cloudbridge.2 实时设置修复

SystemUI 的远程偏好包装器强引用变更监听器，避免 WeakHashMap/GC 使监听器消失；变更标记跨线程可见，样式读取与应用统一派发到主线程。应用侧的包样式管理器按当前服务连接取偏好实例，避免启动阶段缓存本地实例。系统界面重启失败时不再将新版本标记为已载入。

安装此版本后，需要一次重新加载 SystemUI 才能使用新的模块代码；以后修改样式不应需要反复重启。若应用提示 Root 未授予，请在自己的管理器授权词幕后重试，或手动重启手机。设备测试使用强制 GC 验证监听器存活与即时通知，并检查已安装云听的提供者发现元数据。
