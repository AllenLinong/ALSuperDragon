# 安装与升级

[返回 Wiki 首页](README.md)

## 安装

本版以 **Paper 26.1.2 / Java 25** 为编译目标，未声明支持 Folia 或 Spigot。无需数据包或客户端资源包；不要让 True Ending 数据包同时接管同一场龙战。

1. 在项目根目录运行 `./gradlew build`（Windows PowerShell：`.\gradlew.bat build`）。成品为 `build/libs/ALSuperDragon-1.0.0.jar`。
2. 停服，将 JAR 放入 `plugins/`，然后启动服务器。
3. 首次启动会生成 `plugins/ALSuperDragon/config.yml`、`lang/zh_CN.yml` 和 `lang/en_US.yml`。编辑后执行 `/alsuperdragon reload` 重读配置和语言。

## 旧版升级

停服并备份插件目录和世界存档，再移除旧 JAR。将正在使用的 `plugins/ALDragon/` 或 `plugins/ALEnhancedEnderDragon/` 改名为 `plugins/ALSuperDragon/`。若目标目录已存在，备份后再合并要保留的配置和语言文件。已有配置值不会被新默认值覆盖；龙蛋保底存在世界存档中，不需单独迁移。

## 排查

- **插件不加载**：检查服务端实际使用的 Java 版本及 Paper 版本，并查看控制台首条加载错误。
- **重载失败**：检查日志里的 YAML 错误，修正缩进或字段格式后重新重载。
- **末影龙未强化或未出蛋**：确认是原版龙战主龙，不是独立召唤的龙；核对 `dragon.include-first-dragon`、[龙蛋规则](dragon-egg.md)，以及是否有其他插件接管龙战。
