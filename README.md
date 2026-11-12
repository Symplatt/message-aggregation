# 讯息聚合

面向 Android 16 的本地消息收件箱。Flutter 负责中文界面，Kotlin 负责通知监听、短信读取、IMAP 同步与本地存储。

当前版本：[1.0.0](https://github.com/Symplatt/message-aggregation/releases/tag/v1.0.0)，构建号 17。QQ / 网易 IMAP 已接入，Gmail OAuth 尚未实现。工程只包含 Android 平台。

## 当前功能

- ALL 与六个图标标签提供来源筛选，已安装来源优先显示对应应用图标；长按标签将该来源全部标为本地已读，操作提示显示来源名称。
- 收件箱、六来源筛选、标题 / 内容 / 发件人关键词搜索；搜索默认折叠，通过右上角搜索按钮展开。
- 有本地未读消息的来源标签显示小圆点。关闭消息详情会标为本地已读，手动标为未读后退出仍保留未读。
- 右上角全部已读按钮处理当前所选来源，ALL 处理所有来源；搜索关键词不限制批量已读范围。
- 归档功能已移除，旧版本归档消息继续显示在消息列表。
- 消息卡片与详情使用各来源独立的双色简化图标，浅色背景配深色图形。
- 消息详情、跳转原应用、本地标为已读 / 未读。
- 详情顶部名称与来源图标同排，来源和时间置于名称下方。底部固定显示自动已读提示、打开原应用和已读切换按钮，上方内容独立滚动。
- 未读消息先于已读消息，未读按微信、QQ、短信、邮箱排序，各邮箱混排；每组内部按置顶、时间倒序排列。已读消息继续按置顶、时间倒序排列。
- 卡片左滑删除当前本地消息，合并会话会删除卡片内当前汇总的记录，保留接收标识防止补收重现。右上角菜单可置顶或取消置顶，右上角边框显示斜向图钉；同会话新消息沿用置顶状态。
- QQ、微信按来源和会话标题合并为一张卡片，QQ 标题末尾的“(N条新消息)”计数不参与分组。卡片显示最新摘要、时间，任一消息未读则提示未读。详情保留该会话已收集的通知，搜索也匹配历史内容。关闭详情将已展示的消息标为本地已读，手动已读切换作用于当前会话。短信和邮件仍逐条显示。同来源的同名会话无法仅凭通知标题区分。
- QQ、微信：仅处理官方 Android 包的系统通知，读取 MessagingStyle 当前及历史消息、分组通知和普通文本，空消息数组回退到文本。后台接收服务每 15 秒检查连接并补读当前通知；断线先请求重连，5 秒后仍未连接时，Android 14 及以上通过系统接口释放失效绑定，1 秒后重新连接，连续失败时两次释放至少间隔 60 秒。较旧系统继续普通重连；不关闭通知访问权限或停用监听组件。监听重新连接时恢复后台巡检，另有每 15 分钟周期补偿。
- 邮箱网络操作与本地消息加载分开排队，接入状态读取不解密授权码；重复通知回放不触发消息刷新，通知突发事件合并刷新。
- 短信轮询仅在新增或合并记录时刷新消息列表；应用进入后台后暂停界面数据重载，返回前台再加载最新记录，后台接收继续运行。来源图标复用未变化的字节，会话分组只在消息变化后重新计算，减少临时对象和重复图片缓存。
- 邮箱授权码输入时明文显示，仍使用 Android Keystore 加密保存。连接失败显示 DNS、TLS、超时、身份验证或客户端标识等原因；点击错误下的“查看详情”可查看失败阶段、服务器及脱敏后的返回信息，已添加账号的同步错误同样支持查看。网易邮箱在打开收件箱前发送客户端标识。
- QQ 邮箱（qq.com / foxmail.com）、网易邮箱（163.com / 126.com / yeah.net）：IMAP over TLS，连接验证、多个账号、授权码更新、账号移除、同步错误提示。
- 邮箱首次导入收件箱最近 200 封未读邮件；后续按 UIDVALIDITY + UID 增量获取，每轮最多 200 封，后续轮次继续。只获取信封信息（主题、发件人、时间），不下载正文或附件，不修改服务端已读。
- 短信：用户明确同意后请求 READ_SMS / RECEIVE_SMS；首次导入近 7 天未读短信，之后增量读取收件箱。广播直接保存并拼接多段正文；后台监听短信库变化并每 15 秒补读，增量读取重叠最近扫描前 5 分钟，升级后首次补读近 7 天，按原有消息身份去重并保留本地已读选择。短信库暂时不可用时不推进同步进度。
- QQ、微信或短信接入开启且有相应权限时，显示“后台接收已开启”常驻通知；全部关闭后停止后台服务。重启手机或更新安装后尝试恢复接收，系统限制启动时需重新打开应用。
- 接入管理按通知、短信、邮箱排序，来源开关与图标名称同排；右上角齿轮与卡片右边界对齐并进入系统应用设置，垃圾桶与邮箱号顶部对齐，用于移除邮箱。QQ / 网易邮箱卡片内的灰色加号按钮用于添加邮箱，按钮铺满卡片内容区宽度，高度36。
- Android 13+ 首次打开本版本请求通知发送权限，并创建“消息通知”类别，可在系统通知设置中开关；读取 QQ / 微信通知仍需单独授予通知访问权限。
- Gmail 显示“待接入”，添加按钮提示授权接入尚未实现，当前不会获取 Gmail 邮件。

## 启动与验证

环境：Flutter 3.47.5 / Dart 3.13.4、JDK 17 或更高、Android SDK 36、NDK 28.2.13676358。
工程固定 Gradle 8.14、AGP 8.11.1、Kotlin 2.2.20。最低 Android 8（API 26），目标 Android 16（API 36）。

~~~powershell
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
flutter run -d <你的 Android 设备编号>
~~~

调试 APK：`build/app/outputs/flutter-apk/app-debug.apk`。GitHub 预览版提供分架构 release 构建，使用与本机调试安装兼容的开发签名。正式生产签名尚未配置。

原生测试（在 android 目录，JAVA_HOME 指向 JDK）：

~~~powershell
.\gradlew.bat :app:testDebugUnitTest
# 构建设备测试包，然后使用明确的模拟器编号安装和运行
.\gradlew.bat :app:assembleDebugAndroidTest
adb -s emulator-5580 install -r ../build/app/outputs/flutter-apk/app-debug.apk
adb -s emulator-5580 install -r ../build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w cn.local.message.message_aggregation.test/androidx.test.runner.AndroidJUnitRunner
~~~

## 第一次使用

1. 打开“接入管理”，开启微信或 QQ 的开关进入系统授权页面，手动允许讯息聚合。确认 QQ / 微信自身允许显示新消息通知和详情。
2. 在 QQ / 网易邮箱网页端开启 IMAP，生成客户端授权码。在应用中添加邮箱，填写完整邮箱地址和授权码（不是网页登录密码）。验证成功后才保存。
3. 需要短信时，阅读应用内说明并点击“继续授权”。若系统拒绝权限，在系统应用设置查看原因；应用不会请求成为默认短信应用或绕过限制。
4. 下拉收件箱可刷新。点击消息查看本地详情，选择“打开原应用”继续处理；关闭消息详情会标为本地已读；也可手动标为未读。

## 数据与隐私

- 消息保存在应用私有 SQLite 数据库，不建立云端数据库、遥测或分析服务。
- 邮件连接直接到对应提供商的 IMAP 服务器，固定 TLS 993、校验证书主机名，不接受自定义不可信证书。
- 授权码使用 Android Keystore 密钥 + AES-GCM 加密后持久化；不返回 Flutter 界面、不写入日志。
- 消息数据库依靠 Android 应用沙箱保护，**未另做数据库级加密**。
- 关闭系统云备份及设备迁移备份。卸载应用删除本地消息和配置。
- 停止通知 / 短信来源保留已有记录；移除邮箱同时删除该账号的本地邮件和凭据，操作前有确认。
- 本地已读从不回写原应用、短信数据库或邮件服务器。

## 行为边界

- QQ / 微信只获取通知栏提供的内容，不读取完整聊天记录，不自动回复，不 Hook、逆向或破解协议。授权之前已消失的通知无法恢复；首次连接会处理当前仍然可见的通知。
- Android 15+ 可能向通知监听器隐藏 OTP 等敏感内容，静音或关闭详情的消息也可能不可见。应用遵守系统的可见内容限制。
- 邮件后台使用 WorkManager 的 15 分钟周期。不是实时推送，省电 / 网络 / 强行停止应用均可能延迟。重新打开应用可手动同步。
- 通知跳转优先使用仍有效的 Activity PendingIntent，Android 14+ 按前台点击授权启动；广播 / 服务类型及失效通知回退打开原应用首页。邮件只打开对应邮箱客户端，不能保证定位特定邮件；网易兼容两个常见客户端包。未安装时显示提示。
- 短信权限属于 hard-restricted 权限，安装器和系统必须允许授予。开发侧载也不保证在所有厂商系统上可用。上架 Google Play 前还需满足其默认处理程序或例外用例要求；本项目未完成上架审核。
- 通知在后台由原生服务持久化，界面通过事件通道更新；系统断开监听或强行停止应用期间可能缺失消息。
- 所有首次导入记录默认本地未读，之后服务端 / 原应用状态变化不会同步覆盖本地决定。关闭详情会本地已读，手动标为未读可保留。
- Gmail OAuth 客户端配置、授权流程、token 刷新与邮件拉取尚未实现。没有“假连接”或默认演示消息。

## 结构

- `lib/models.dart`：统一消息、来源、账号状态及组合筛选。
- `lib/aggregation_service.dart`：可注入的原生通道与状态控制器。
- `lib/screens/`：收件箱、详情、接入管理与账号表单。
- `android/app/src/main/kotlin/cn/local/message/message_aggregation/`：
  `MessageNotificationListener` 通知服务；`SmsSync` 短信接入；
  `MailSync` IMAP 与 WorkManager；`AccountStore` 凭据；
  `MessageDatabase` SQLite 去重与本地状态；`MainActivity` 通道、权限和跳转。
- `test/`：Flutter 筛选、持久化交互、窄屏及用户操作测试。
- `android/app/src/test/`：来源白名单、消息身份与邮箱域名校验。
- `android/app/src/androidTest/`：设备上的 SQLite 重放、账号删除隔离与 Keystore 测试。
- `docs/acceptance.md`：实机验收清单和验证记录。

## 平台依据

0.1.0 初始开发完成静态分析、10 项自动测试及 Android 16 模拟器短信冒烟测试。0.1.1 按本次改动进行简短真机检查，未重复执行全量测试；最终 ARM64 发布包已保留数据覆盖安装且校验一致。真实账号与厂商设备验证状态见 [验收记录](docs/acceptance.md)。

- [Android 敏感通知保护](https://developer.android.com/about/versions/15/behavior-changes-all#otp-redaction)
- [READ_SMS 受限权限](https://developer.android.com/reference/android/Manifest.permission#READ_SMS)
- [默认处理程序与短信权限](https://developer.android.com/guide/topics/permissions/default-handlers)
- [WorkManager 周期任务](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Gmail OAuth / IMAP](https://developers.google.com/workspace/gmail/imap/xoauth2-protocol)
