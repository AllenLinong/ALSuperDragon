# 配置指南

主配置 `config.yml` 只管 `language`（`zh_CN` / `en_US`）、四种龙各自的 `spawn-chance-percent` 和 `egg-chance-percent`。范围 `0–100`，四项召唤概率合计必须为 100。召唤概率为 0 只跳过随机，不限制管理员指定该龙。

## 单龙档案

`combat.idle-reset-seconds` 默认 `3600`（1 小时），`0` 禁用。自上一次有效受伤起算，到期后龙回满血，并清空本场金币和骰子结算用的伤害记录；已领取的次数不受影响。旧龙档案缺少此项时也使用默认值。修改后重载配置，对新初始化的龙生效。

编辑 `dragons/<龙种>.yml`：`combat.health` 为最大生命值（1–1024，2 点 = 1 颗心）；`combat.damage-multiplier` 同时影响原版与插件技能伤害；`combat.max-damage-per-hit-percent` 控制单次对龙伤害的上限，`0` 不限。`skills.<技能>.enabled` 控制该技能是否使用，`.damage` 调整基础伤害；`skills.enabled` 同时控制插件技能及主动 AI。`ai.enabled` 和 `ai.charge-interval-seconds` 控制主动冲锋开关与冷却。

升级时，旧龙档案缺少的新技能段会在内存中按该龙的默认值补齐，不会改写文件。要调整伤害、预警、冷却或关闭新技能，从 `src/main/resources/dragons/` 下对应的模板复制技能段即可；显式设置 `enabled: false` 会保留。

雷霆万钧的 `radius` 是每道雷的命中半径，不是轰炸范围。水晶复活的 `heal-per-second` 是复活水晶提供的额外回血，`cooldown-seconds` 用于限制没有空塔顶时的重试频率。

雷霆龙默认开启蓝白电弧，替换普通尾迹；只向 64 格内玩家发送，不增加实体、不造成伤害。可用 `skills.electric-aura.enabled` 关闭，`skills.electric-aura.interval-ticks` 默认 5（范围 4–40）。

雷霆龙的 `skills.entrance-storm.damage` 控制登场雷暴的基础伤害，默认 `3.0`（含默认龙种倍率为 3.75 点，减免前）。设为 `0` 或关闭 `skills.enabled` 则只保留登场特效。旧档案未写此项时使用默认值。

`skills.min-cast-gap-seconds` 设置最短起手间隔；`skills.interval-seconds` 设置常规间隔；`skills.major-cooldown-seconds` 设置两个大招的最短起手间隔；`skills.recovery-seconds` / `skills.major-recovery-seconds` 控制结束后休整。雷链的 `radius` 是断链距离，开始连线距离为其 75%。

## 奖金与骰子

全局次数上限在 `plugins/ALSuperDragon/rewards.yml`（首次加载新版本生成）：

```yaml
limits:
  money-max-times: 5
  dice-max-times: 3
```

已有 `rewards.yml` 的显式值不会被覆盖；若旧服仍设为 `1`，请手动改为 `3` 并执行 `/aldragon reload`。

金币领取和骰子中奖按玩家 UUID 分开累计，四条龙和所有末地世界共用额度，不按天重置。`-1` 不限次数，`0` 禁止领取；修改后执行 `/aldragon reload`。已累计的次数不会因重载或正常重启而清零，升级前的奖励不追溯。奖励历史保存在 `reward-counts.tsv`，计数根据奖励指令是否被服务器接受，无法确认第三方经济插件实际到账。

金币达到上限的玩家不领取自己原本的份额，该份额不转发；骰子次数用完的玩家不进入抽奖，未中奖不扣次数。一次中奖无论执行几条奖品指令都最多计一次；所有指令均未被接受则不计。如果停服时仍有奖励等待派发，请先等发奖完成。

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
