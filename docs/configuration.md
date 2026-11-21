# 配置指南

[返回 Wiki 首页](README.md) · [带注释的默认配置](../src/main/resources/config.yml)

修改 `plugins/ALSuperDragon/config.yml` 后执行 `/alsuperdragon reload`。`config-version` 是配置格式版本，不是插件版本，不应为升级手动修改。`language` 默认 `zh_CN`，可设为 `en_US`；配置文件的注释仍为中文。

| 配置路径 | 默认行为 |
| --- | --- |
| `difficulty.preset` | `normal`。`easy` 的技能伤害 ×0.75、冷却 ×1.3；`hard` 的技能伤害 ×1.25、冷却 ×0.8。不改变龙蛋概率或原版世界难度。 |
| `difficulty.skill-damage-multiplier` | `1.0`。与难度和主龙伤害倍率相乘；如祭坛水晶的盾牌扣血等特殊机制另算。 |
| `dragon.include-first-dragon` | `true`。设为 `false` 后只强化复活龙，不改变原版首杀龙蛋。 |
| `dragon.health` / `dragon.damage` | 默认独立随机，血量 ×1.5–×3、伤害 ×1–×2.5。将各自的 `random` 设为 `false` 后改用对应的 `fixed`。每条龙只抽取一次。 |
| `dragon.idle-critical` | 主龙连续 30 秒未受到有效伤害时，下一次有效玩家攻击（含箭）伤害 ×3，再应用承伤上限。 |
| `dragon.max-damage-per-hit-percent` | `20.0`，按主龙最大生命值限制每次承伤事件，爆炸也受限；`0` 关闭。 |
| `notifications` | 祭坛附近 50 格显示战斗标题，龙附近 128 格播放死亡音效。 |
| `skills.enabled` / `skills.arena-radius` | 技能默认开启，竞技区半径 112 格。各技能配置参见[技能与应对](combat.md)。 |
| `egg` | 基础概率、倍率加成和保底，参见[龙蛋与保底](dragon-egg.md)。 |

## 固定复活龙倍率

将以下字段合并进原来的 `dragon` 段，避免重复键。这会保留原版首龙，普通复活龙固定为 400 点生命、×1.5 伤害；插件召唤指令指定的倍率依然优先。

```yaml
dragon:
  include-first-dragon: false
  health:
    random: false
    fixed: 2.0
  damage:
    random: false
    fixed: 1.5
```
