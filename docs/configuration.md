# 配置指南

主配置 `config.yml` 包含语言、随机召唤和龙蛋掉率。四种龙的 `spawn-chance-percent` 和 `egg-chance-percent` 范围为 0–100，四项召唤概率合计必须为 100。召唤概率为 0 只跳过随机，不限制管理员指定该龙。新配置的基础召唤概率依次为巡界 35%、雷霆 34%、深渊 23%、终末 8%；旧配置的概率不自动覆盖。

`random-summon.scout-streak-limit` 默认 2，旧配置没写也生效。同一末地连续两次随机抽中巡界龙后，下次按其余龙种的权重抽签。计数按世界保存，指定龙种不影响计数。设为 0 关闭保底；如果只有巡界龙权重非零，继续按原权重召唤。

## 单龙档案

`combat.idle-reset-seconds` 默认 `3600`（1 小时），`0` 禁用。自上一次有效受伤起算，到期后龙回满血，并清空本场金币和骰子结算用的伤害记录；已领取的次数不受影响。旧龙档案缺少此项时也使用默认值。修改后重载配置，对新初始化的龙生效。

编辑 `dragons/<龙种>.yml`：`combat.health` 为最大生命值（1–1024，2 点 = 1 颗心）；`combat.damage-multiplier` 同时影响原版与插件技能伤害；`combat.max-damage-per-hit-percent` 控制单次对龙伤害的上限，`0` 不限。`skills.<技能>.enabled` 控制该技能是否使用，`.damage` 调整基础伤害；`skills.enabled` 同时控制插件技能及主动 AI。`ai.enabled` 和 `ai.charge-interval-seconds` 控制主动冲锋开关与冷却。

升级时，旧龙档案缺少的新技能段会在内存中按该龙的默认值补齐，不会改写文件。要调整伤害、预警、冷却或关闭新技能，从 `src/main/resources/dragons/` 下对应的模板复制技能段即可；显式设置 `enabled: false` 会保留。

雷霆万钧的 `radius` 是每道雷的命中半径，不是轰炸范围。每波优先在范围内一名玩家脚下标记一道雷，预警开始后不再追踪，单人每波最多被该技能命中一次。默认雷霆、雷霆万钧、雷链冷却分别为 12、26、20 秒；仍受全局起手间隔和其他技能影响。旧服的显式值不会覆盖，要使用新数值请按新模板更新 `dragons/storm.yml`（含雷霆和雷霆万钧的命中半径 3.0 和 2.75）。水晶复活的 `heal-per-second` 是复活水晶提供的额外回血，`cooldown-seconds` 用于限制没有空塔顶时的重试频率。

四种龙的随身特效独立于战斗，只向 64 格内玩家发送，不增加实体、不造成伤害；创造和旁观模式也可见。雷霆龙默认用蓝白电弧替换尾迹，可用 `skills.electric-aura.enabled` 关闭，`skills.electric-aura.interval-ticks` 默认 5（范围 4–40）；其余尾迹由 `skills.trail.enabled` 控制。两个开关都关闭时不显示随身特效。

雷霆龙的 `skills.entrance-storm.damage` 控制登场雷暴的基础伤害，默认 `3.0`（含默认龙种倍率为 3.75 点，减免前）。设为 `0` 或关闭 `skills.enabled` 则只保留登场特效。旧档案未写此项时使用默认值。

`skills.min-cast-gap-seconds` 设置最短起手间隔；`skills.interval-seconds` 设置冲击波、俯冲、火球、激光等常规攻击的起手间隔，巡界龙、雷霆龙、深渊龙、终末龙默认分别为 11、9、8、7 秒。实际起手仍受目标距离、施法和休整限制。旧服已写入的间隔不会覆盖，要使用新数值请修改各龙配置并重载，对新初始化的龙生效。`skills.major-cooldown-seconds` 设置两个大招的最短起手间隔；`skills.recovery-seconds` / `skills.major-recovery-seconds` 控制结束后休整。雷链的 `radius` 是断链距离，开始连线距离为其 75%。

环形扫射可重复施放，`skills.laser.sweep-cooldown-seconds` 默认 25 秒；追踪激光的 `skills.tracking-laser.cooldown-seconds` 默认 20 秒。旧档案显式设置的冷却不会被覆盖。

巡界龙默认开启怒吼：血量首次降到 80%、60%、40%、20% 时各解锁一次，怒吼间隔至少 18 秒，仍受大招间隔影响。每次最多指挥附近 24 只现有末影人攻击场内的生存或冒险玩家，不会额外生成末影人。`skills.ender-roar.range` 默认 100 格，以龙为中心。旧服的 `dragons/scout.yml` 如果仍有 `skills.ender-roar.enabled: false`，请手动开启后执行 `/aldragon reload`，对新初始化的龙生效。

## 奖金与骰子

全局奖励上限在 `plugins/ALSuperDragon/rewards.yml`（文件不存在时自动生成）：

```yaml
limits:
  money-max-times: 5
  dice-max-times: 3
  small-party-money-cap: 300
```

已有 `rewards.yml` 的显式值不会被覆盖；若旧服仍设为 `1`，请手动改为 `3` 并执行 `/aldragon reload`。

金币领取和骰子中奖按玩家 UUID 分开累计，四条龙和所有末地世界共用额度，不按天重置。`-1` 不限次数，`0` 禁止领取；修改后执行 `/aldragon reload`。已累计的次数不会因重载或正常重启而清零，升级前的奖励不追溯。奖励历史保存在 `reward-counts.tsv`，计数根据奖励指令是否被服务器接受，无法确认第三方经济插件实际到账。

金币达到上限的玩家不领取自己原本的份额，该份额不转发；骰子次数用完的玩家不进入抽奖，未中奖不扣次数。一次中奖无论执行几条奖品指令都最多计一次；所有指令均未被接受则不计。如果停服时仍有奖励等待派发，请先等发奖完成。

如果本场对龙造成的有效伤害占比严格超过 10% 的玩家不足三人，则按原伤害比例分配后，每人每场最多领 300 金币（多余部分不转发）。人数按伤害记录计算，旁观或轻打几下不能解除上限。旧配置缺少 `limits.small-party-money-cap` 时也默认 300；设为 `-1` 取消此金额上限，设为 `0` 则不发小队金币。骰子与领取次数规则不变。

每条龙的奖励金额和实物命令仍在各自的 `dragons/<龙种>.yml`：

```yaml
rewards:
  money:
    total: 2400
    command: 'eco give {player} {amount}'
  dice:
    enabled: true
    commands:
      - 'minecraft:give {player} minecraft:diamond 5'
```

奖金按玩家对龙本体造成的最终有效伤害占比分配，从高到低结算。过量伤害、只打水晶和无法归属玩家的伤害不计。先向下取整，再按小数余数从大到小补齐整数，余数相同优先伤害排名。例如预算 1000、伤害比 60:30:10，则发 600/300/100。`total: 0` 关闭奖金。离线玩家能否收钱取决于经济插件。

骰子仅对结算时在线、占**所有玩家已计入伤害严格超过 10%** 的玩家开放；恰好 10% 不参与。每人掷 1–100，最高点赢，最高同点者重掷；无人符合则不发。所有奖品指令只执行给同一赢家。

指令均由控制台执行。占位符：`{player}` 玩家名、`{uuid}` 玩家 UUID、`{dragon}` 龙种、`{amount}` 奖金、`{roll}` 骰子点数。
