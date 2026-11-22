# 安装与升级

1. Paper 26.1.2 / Java 25。构建：`gradlew.bat build`（Linux/macOS：`./gradlew build`）。
2. 把 `build/libs/` 下的 JAR 放入 `plugins/` 并重启。首次启动生成 `config.yml`、`rewards.yml`、`dragons/` 四份档案和 `lang/` 两份语言文件。
3. 确认 `rewards.money.command` 与经济插件匹配。没有经济插件则把四份 `rewards.money.total` 设为 `0`；实物奖仍能照常发放。
4. 修改配置后执行 `/alsuperdragon reload`。已出场的龙继续使用旧战斗和奖励配置；语言立即更新。

空末地不会定时查询主龙。插件在启动、世界加载或龙实体加载、生成时发现主龙，只在有主龙或指令召唤仪式待处理时运行战斗任务。

旧版升级前先备份并移走旧 `config.yml`，让插件生成新版。若保留旧文件，插件会读取原语言、回退到新版默认概率并提示迁移，不会覆盖旧文件或继承旧保底。启用复活龙蛋前请检查 [Paper 世界设置](dragon-egg.md)。伤害榜只在内存中记录，世界卸载或服务端重启后不续算。奖励命令在主线程分批派发，每 tick 最多四条；失败或停服时尚未发出的命令不会自动重发，建议领奖完成后再重启。
