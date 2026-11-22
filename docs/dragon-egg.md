# 龙蛋规则

首杀龙蛋按原版规则生成。要允许复活龙掉蛋，在末地的 `paper-world.yml`（或 `config/paper-world-defaults.yml`）把 `entities.behavior.ender-dragons-death-always-places-dragon-egg` 设为 `true`，然后重启。Paper 默认会先取消复活龙蛋事件；本插件不会撤销 Paper 或其他插件的取消。

复活龙死亡时，插件按 `config.yml` 中该龙种的 `egg-chance-percent` 抽签。默认巡界 8%、雷霆 18%、深渊 35%、终末 60%。`0` 不掉，`100` 代表本插件不否决，而不是覆盖其他插件的保护。Paper 世界设置同样会影响未被本插件接管的原版龙战。

成功时在原版传送门处生成龙蛋，不走骰子或玩家背包。旧版倍率加成及保底已移除。
