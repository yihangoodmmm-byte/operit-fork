# inmyfeelings code

在 [Operit](https://github.com/AAswordman/Operit) 基础上魔改的 Android AI Agent 客户端。

## 与上游的差异

- **角色卡 / 标签 / 群组 / waifu 全部移除**，人设只有一个来源：系统提示词
- **系统提示词**：留空 = 不注入任何人设（使用模型自身默认）；填写后作为系统级提示词注入
- **内置开发环境改为外部导入**，支持多个环境包并存与切换
- **移除联网检查**：更新检查、公告拉取、终端自更新均已断开
- 底栏精简为：聊天 / 工具箱 / 设置
- 应用名、包名（com.inmyfeelings.ai）、图标、版本号已替换

## 构建

推送到 main 即触发 GitHub Actions（.github/workflows/android-build.yml），
约 20 分钟产出 app-debug.apk，在 Actions run 的 Artifacts 里下载。产物保留 3 天。

## 环境包（内置 Linux 开发环境）

APK 不内置 rootfs，需要手动放置：

    files/rootfs-packs/
    ├── ubuntu-dev.tar.xz     # 环境 1
    ├── alpine.tar.xz         # 环境 2
    └── CURRENT               # 当前环境名，一行文本

- 环境名 = 文件名去掉 .tar.xz
- 每个环境独立解压到 files/usr/var/lib/proot-distro/installed-rootfs/<环境名>/
- 不写 CURRENT 时按名字排序取第一个
- 换环境：改 CURRENT 一行，重开终端

完整路径：/data/user/0/com.inmyfeelings.ai/files/rootfs-packs/

环境包内含：Python 3.12 + pip、Node 22 + npm 10、OpenJDK 21、Gradle 8.13、
kotlinc 2.2.21、clang/cmake/make、git、android.jar、arm64 aapt2、d8/apksigner。

## 目录

| 路径 | 说明 |
|---|---|
| app/ | 主应用（Compose + Kotlin） |
| terminal/ | 内置 Linux 终端与环境管理 |
| .github/workflows/ | CI 构建流水线 |

## 上游

https://github.com/AAswordman/Operit
