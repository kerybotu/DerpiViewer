# 登录凭据备份与恢复验证（2026-11-15）

设置页新增“登录凭据备份与恢复”入口，独立于点赞、踩、收藏等功能。生产代码位置：

- `app/src/main/java/com/kerybotu/derpibooru/mirror/ui/CredentialBackupActivity.kt`：密码强度、Cookie/API Key 勾选、SAF CREATE/OPEN、确认页和错误提示。
- `app/src/main/java/com/kerybotu/derpibooru/mirror/auth/CredentialBackupCodec.kt`：可移植文件格式、PBKDF2 和 AES-GCM。
- `app/src/main/java/com/kerybotu/derpibooru/mirror/auth/CredentialBackupManager.kt`：通过现有存储 API 捕获与选择性恢复。
- `app/src/main/java/com/kerybotu/derpibooru/mirror/auth/SiteCookieStore.kt`、`ApiKeyStore.kt`、`SharedCookieJar.kt`：保存完整 Cookie 属性并接回原生请求层。

加密文件是 JSON：外层仅公开格式、版本、KDF 算法/迭代次数/Salt、AES-GCM 算法/Nonce/Tag 长度和密文。Payload 在密文内包含版本、时间、HTTPS origin、选择标记、Cookie 属性和 API Key。KDF 为 `PBKDF2WithHmacSHA256`、600,000 次、256 bit、16 字节随机 Salt；加密为 `AES/GCM/NoPadding`、12 字节随机 Nonce、128 bit Tag。规范化文件头作为 AAD，修改元数据或密文均认证失败。未使用 Android Keystore 派生备份密钥。

Cookie 通过 AndroidX WebKit `GET_COOKIE_INFO` 读取 Domain、Path、Secure、HttpOnly、host-only 和 Expires；Cloudflare/Anubis 挑战 Cookie 被过滤。没有完整属性读取能力时，Cookie 导出会失败而不会伪造属性。导入要求当前 HTTPS origin 完全匹配，按 Cookie 名称/域名/Path 删除旧值，写入成功后才更新 `SiteCookieStore`；API Key 通过 `ApiKeyStore` 更新。只选择一种凭据时另一种保持不变。写入任一存储失败会恢复 Cookie、API Key 及 WebView 会话的原有状态。

验证证据：

- `CredentialBackupCodecTest` 3 项通过：三种选择组合、不同 Salt/Nonce、错误密码、密文/元数据篡改、截断/大小/嵌套限制、Cookie 域名/挑战/过期检查、密码强度规则。
- `CredentialBackupDeviceTest#selectiveRestoreScopeAndNativeCookieAttributes` 通过：合成 Cookie 的 Path/Secure/HttpOnly/host-only、选择性恢复、跨站拒绝以及 API Key 写入失败后的回滚。
- API Key 使用 origin 派生存储槽并由独立 Keystore alias 加密；旧全局 Key 只在首次使用时迁移到当时选定源站。不同站点不共享 API Key。
- `CredentialBackupDeviceTest#independentlyEncryptedFixtureIsPortable` 通过：Python 独立实现解密 Android 文件，Python 重新生成 Salt/Nonce 的文件再由 Android 解密。
- `CredentialBackupDeviceTest#exportAndImportThroughSystemDocumentsWithResponsivePasswordUi` 通过：真实 Android DocumentsUI 保存、重新打开、输入密码、确认页和恢复。关键界面截图见 `backup-export-strong.png` 和 `backup-import-confirm.png`。

当前使用 API 36 模拟器完成真实 SAF/UI 验证；尚未在第二台实体设备上手动完成账号登录后的真实凭据迁移。测试使用合成域名和凭据，不会接触或输出真实 Cookie/API Key。系统文件选择器可能保留旧的测试文件，均为加密 JSON，非明文凭据。

独立互操作脚本：`tools/test_credential_backup_portability.py`。它只接受测试 fixture，密码和值是合成测试数据，不能用于真实账号文件。
