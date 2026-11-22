# AL超级末影龙（ALSuperDragon）

Paper 26.1.2 / Java 25 插件。保留原版末地龙战及复活仪式，`/minecraft:summon ender_dragon` 生成的独立龙不受影响。

| 龙种 | ID | 生命值 | 攻击倍率 | 随机召唤率 | 复活龙蛋率 | 默认奖金 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 巡界龙 | `scout` | 240 | 1.00× | 55% | 8% | 1,000 |
| 雷霆龙 | `storm` | 420 | 1.25× | 30% | 18% | 2,400 |
| 深渊龙 | `abyss` | 680 | 1.50× | 12% | 35% | 5,000 |
| 终末龙 | `sovereign` | 1,000 | 1.80× | 3% | 60% | 10,000 |

执行 `gradlew.bat build`（Linux/macOS：`./gradlew build`），产物在 `build/libs/`。部署请读[安装与升级](docs/installation.md)，玩法和设置见[中文 Wiki](docs/README.md)。
