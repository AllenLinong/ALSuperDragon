# 指令与权限

| 指令 | 权限（默认 OP） | 用途 |
| --- | --- | --- |
| `/aldragon reload` | `alsuperdragon.reload` | 重读主配置、四份龙配置和语言；失败时保留当前配置 |
| `/aldragon summon random` | `alsuperdragon.summon` | 出龙时按主配置概率选种，也可省略 `random` 或写 `随机` |
| `/aldragon summon scout` | `alsuperdragon.summon` | 指定巡界龙，也可写 `巡界龙` |
| `/aldragon summon storm` | `alsuperdragon.summon` | 指定雷霆龙，也可写 `雷霆龙` |
| `/aldragon summon abyss` | `alsuperdragon.summon` | 指定深渊龙，也可写 `深渊龙` |
| `/aldragon summon sovereign` | `alsuperdragon.summon` | 指定终末龙，也可写 `终末龙` |

`/alsuperdragon` 仍可用。有召唤权限的末地玩家可以用 Tab 补全中英文龙种。指定召唤只跳过龙种抽签，不改变该龙的龙蛋掉率。

召唤指令须由玩家在已完成首杀的末地执行，且不能有主龙或仪式正在进行。指令启动原版仪式，不会立即生成独立龙；手动摆四个末影水晶复活仍按概率选种。新版不再接受数字倍率，也没有 `resetpity` 指令。
