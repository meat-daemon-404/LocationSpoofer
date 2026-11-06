# 开源说明与承诺

> 本文只写可以当场验证的事实。凡是没法验证的，一律标注「未验证」。

## 0. 一句话

本项目的**自研代码全部在本仓库里**，**发布的所有安装包都由本仓库源码在 CI 中构建**，**使用不设任何账号、会员或审核门槛**，协议 [GPL-3.0](../LICENSE)。

---

## 1. 仓库来源

本仓库**此前是一个 fork，之后独立演进**：不跟踪其他仓库，不合并外部提交，也不依赖任何外部仓库存在才能构建。

* 全部源码、构建脚本、CI 配置都在本仓库里，`clone` 下来就能编。
* **历史不改写**：`git log` 可以从根提交一路查到当前 HEAD，所有改动都留在提交历史里。
* 除"曾经是一个 fork"这一层关系外，本仓库与任何其他仓库没有维护、构建或分发上的关联。
* 更新检查、适配进度拉取、关于页跳转的目标地址统一收敛在 `ProjectLinks` 一个常量里，全部指向本仓库。

---

## 2. 三条可核验的承诺

### 2.1 代码即产物：没有二进制黑盒

* 仓库中**没有任何预编译库被提交**。`git ls-files` 里唯一的 `.jar` 是 `gradle/wrapper/gradle-wrapper.jar`，那是 Gradle Wrapper 自身的引导程序，属构建工具而非本项目产物。
* 发布流程完全走源码：`.github/workflows/release.yml` 在版本号变更时执行

  ```bash
  ./gradlew --no-daemon :app:assembleScopedRelease
  ./gradlew --no-daemon :app:assembleGlobalRelease
  ```

  校验 4 个产物（scoped / global × arm64-v8a / armeabi-v7a）齐全后才创建 Release，缺一个就终止发版。
* 也就是说：**Release 页面上每一个 APK，都能对应到某个 tag 的那份源码**。

### 2.2 无使用门槛

* 代码里**不存在**账号体系、激活码、会员分级、包名申请审核之类的门禁逻辑。当前源码中出现的「白名单」只有一种含义：LSPosed 作用域里由使用者自己勾选的目标应用列表，那是功能，不是授权。
* 需要外部数据的两个功能（Wi-Fi 指纹、基站小区）用的是公开数据库 **Wigle** 和 **OpenCellID**，**API token 由使用者自己在「设置 → 环境指纹 Token」里填写**，项目不代管、不代扣、不做二次中转。
* 换句话说：装上就能用，没有「付费解锁」。

### 2.3 不依赖私有后端

自研代码里出现的全部出网主机如下（可用第 3 节的命令自行复核）：

| 主机 | 用途 |
|---|---|
| `api.github.com` / `github.com` / `raw.githubusercontent.com` | 版本更新检查、跳转项目主页 |
| `cdn.jsdelivr.net` | 更新通道的静态资源与国内可达镜像 |
| `t.me` | 交流群链接 |
| `api.wigle.net` | Wi-Fi 指纹数据库查询（token 使用者自填） |
| `opencellid.org` | 基站小区数据库查询（token 使用者自填） |

没有自建服务器、没有埋点上报、没有「必须联网才能激活」。

---

## 3. 自己验一遍

```bash
# 1) 仓库里有没有提交的二进制产物（期望：只有 gradle-wrapper.jar）
git ls-files | grep -E '\.(jar|aar|so|apk|dex)$'

# 2) 自研代码里有没有门禁逻辑（期望：不出现账号/会员/激活判断）
grep -rn --include=*.kt -E 'isVip|isMember|activate|activationCode|licenseKey|会员|激活码|授权码' \
    app app-ui core-data core-geo xposed service tools

# 3) 出网主机清单（期望：与 2.3 节的表一致）
grep -rhoE 'https?://[a-zA-Z0-9.-]+' --include=*.kt \
    app app-ui core-data core-geo xposed service tools | sort -u

# 4) 从源码构建（不依赖任何私有仓库或密钥）
./gradlew :app:assembleScopedDebug
```

第 1、2、3 条在本文撰写时的当前树上均已执行，结果与上面的期望一致。第 4 条需要 Android SDK（`compileSdk = 37`）与 JDK 17，本机验证情况见下文。

---

## 4. 许可与再分发

本项目以 [GNU General Public License v3.0](../LICENSE) 发布，即：

* 可以自由使用、修改、再分发，**包括商用**；
* 再分发（含修改版）必须同样以 GPL-3.0 开源，并提供对应源码；
* **不允许**把本项目的代码拿走、改成闭源或二进制-only 分发。

`LICENSE` 中的 `Copyright (C) 2026 SuseOAA` 原样保留；本仓库后续修改同样以 GPL-3.0 发布，具体改动见提交历史与 `docs/` 目录。


---

## 5. 第三方依赖的边界

「自研代码全开源」不等于「零闭源依赖」，这两件事得分开说清楚：

* **自研部分**（`xposed` / `core-geo` / `core-data` / `service` / `app-ui` / `app` 六个模块的业务代码）：全部在本仓库，无二进制提交。
* **外部 SDK**：高德地图/定位/搜索（`com.amap.api`）、百度地图/定位/搜索（`com.baidu.lbsyun`）、Google Maps（`play-services-maps`）是各家公开分发的闭源 SDK，属运行时依赖，不是本项目代码，也不参与本项目授权。
* **其余依赖**：Kotlin 标准库、Jetpack Compose、Room、Koin、OkHttp、Kotlinx Serialization、Miuix、libxposed API 等均为公开开源项目或公开 API。

---

## 6. 参与方式

* Issue / PR 模板已就位（`.github/ISSUE_TEMPLATE`、`PULL_REQUEST_TEMPLATE.md`）。
* 欢迎的改动方向：新增机型/系统适配、检测对抗的实测数据、性能与功耗、文档与可复现的测试用例。
* 不适合本项目的改动：引入账号体系、私有服务器、功能分级收费，或把某部分做成二进制-only 分发——这与第 2 节的三条承诺冲突。
* 提 PR 时请附上：改了什么、是否影响 LSPosed 作用域语义、**怎么验证的**（真机 / 单测 / 测量脚本）。本仓库的文档风格是「没测过就写未验证」，请保持。

---

## 7. 边界与未验证项

* 机型覆盖不全：已适配与未适配的系统见 [ADAPTATION_PROGRESS.md](../ADAPTATION_PROGRESS.md)，未列出的系统一律视为**未验证**。
* CI 发版需要仓库 secrets 里的签名 keystore；没有 secrets 的环境只能产出 debug 包（`assembleScopedDebug`），这不影响任何人从源码构建自用。
* 本机（Windows + JDK 17 + 仅有 Android SDK 34 平台）未安装 Android SDK 37，因此第 3 节第 4 条的命令**未在本机完整跑通**；本仓库的编译正确性验证方式是：命令行 Kotlin 编译器全量编译 `core-geo` + `xposed`，以及 `core-geo` 的 JVM 单测（详见 [anti-detection-empirical.md](anti-detection-empirical.md) 的验证章节）。
