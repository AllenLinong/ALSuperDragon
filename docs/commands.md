# 指令与权限

[返回 Wiki 首页](README.md)

权限默认授予 OP，也可单独授权。控制台输入时不加 `/`。

| 指令 | 权限 | 使用条件与效果 |
| --- | --- | --- |
| `/alsuperdragon reload` | `alsuperdragon.reload` | 玩家或控制台均可执行；重新读取配置及语言。 |
| `/alsuperdragon summon <血量倍率>` | `alsuperdragon.summon` | 仅末地龙战所在世界的玩家可用；首龙必须已被击败，且没有主龙或正在进行的复活流程。无需摆放水晶。 |
| `/alsuperdragon resetpity [末地世界名]` | `alsuperdragon.resetpity` | 只清零指定世界的保底。末地玩家可省略世界名，控制台必填。 |

- `/alsuperdragon summon 3` 启动原版复活流程，设置血量 ×3、伤害 ×1.5，不会立即生成独立龙。合法血量倍率为 `0.1–5.12`，伤害倍率为所填数值的一半。
- `alsuperdragon resetpity world_the_end` 是控制台示例；请把 `world_the_end` 换成实际已加载的末地世界名。其他世界不受影响。
