# Seamless Server Transfers(無縫換伺服器)

本 fork 讓玩家在**同一份世界的分流後端**之間切換時,**看不到「正在載入地形」畫面**:
不回伺服器列表、不重連、不進 config 階段、視角直接維持。

## 原理

1. proxy 代替客戶端回答目標伺服器的 **config 階段**(客戶端全程留在 PLAY,繼續在來源伺服器遊玩)。
2. 緩衝目標伺服器的初始封包。
3. 換手(commit)時:清掉來源伺服器的實體(避免殘留)、tab / boss bar / title,
   再把緩衝的封包一次送給客戶端。**不送 JoinGame / Respawn**,所以客戶端保留現有的世界畫面。
4. 目標伺服器的「開始等待區塊」事件**照常轉發**——關鍵在這裡:因為 **playerdata 同步讓玩家
   停在完全相同的座標**,客戶端腳下的區塊本來就載入好了,所以那個「等待區塊」在下一幀就
   自我了結,你根本看不到畫面。

> **注意**:早期版本嘗試「抑制」這個事件,結果讓客戶端卡在載入畫面(1.21+)。
> 正解是**不要抑制、讓同步好的位置自然關閉它**。這也是為什麼位置同步是硬性前提。

## 前提條件(缺一不可)

1. 同一組(group)內的伺服器**共用同一份世界模板**(維度、世界名稱一致)。
2. **玩家資料同步,且包含精確座標**(例如 Paper 的 playerdata 同步插件)。
   這是無縫的核心——**位置只要有落差,客戶端就得真的載入新地形,畫面就無法消除**。
3. 客戶端為 vanilla **1.20.2+**(不符合的玩家自動走原生切換,不會壞)。
4. Velocity modern forwarding 照常設定。

## 設定(velocity.toml)

```toml
[seamless-transfers]
enabled = true
server-groups = [ ["TW-0", "TW-1", "TW-2"] ]   # 填 [servers] 裡的名稱;只有同組互切才無縫
verbose = false                                 # 需要調參 / 觀察時間軸時設 true
```

就這樣——**不需要設定任何封包 id**。實體清理需要的封包 id 會依每條連線的協定版本
自動選擇(內建對照表,涵蓋 1.20.2 ~ 26.2)。

進階選項(通常不用動):`commit-chunk-packets = 25`、`chunk-packet-min-bytes = 4096`、
`commit-grace-ms = 150`、`commit-timeout-ms = 2000`、`prepare-timeout-ms = 5000`。

## 建置與安裝

```bash
./gradlew build
# 產物:proxy/build/libs/velocity-proxy-*-all.jar(要用 -all 那顆)
```

取代原本的 Velocity jar 即可;`enabled = false`(預設)時行為與官方版完全相同。

## verbose log 範例

```
Seamless transfer for <player> -> TW-1 committed (grace): prepare=26ms,
buffer=196ms, commit=36ms, bufferedTargetPackets=184, chunkSizedPackets=3,
droppedSourcePackets=0
```

## 安全設計(fallback)

寧可有畫面、絕不斷線:

| 情況 | 行為 |
|---|---|
| 不在同一 group / 版本不符 / 客戶端 <1.20.2 | 直接走原生切換 |
| B 要求未安裝的資源包 / cookie / code of conduct | 透明降級成原生 config 切換 |
| B 斷線 / 逾時 / kick | 中止,玩家原地留在 A |
| A 在切換中要求 reconfiguration | 中止 seamless,跟隨 A |
| 協定版本不在實體 id 對照表 | 實體清理停用(不 crash,殘留實體由 B 覆蓋) |

## 相容性

- **third-party 封包外掛**(如 velocity-scoreboard-api + TAB):緩衝期間目標封包只當資料收集、
  不執行任何封包的 `handle()`,所以外掛的封包 hook 不會在換手途中誤觸(避免反射假設
  `BackendPlaySessionHandler` 而 crash)。
- **跨版本(ViaVersion)**:後端維持單一版本、在 Velocity 裝 ViaVersion/ViaBackwards 即可。
  封包 id 只跟後端版本綁定,不受客戶端版本影響。

## 已知限制

- **玩家自身 entity id 不變**:切換後目標伺服器用新 id 指涉玩家;以 entity id 指涉玩家的
  封包(metadata、attributes、藥水效果)可能被忽略或錯掛,直到下次更新覆蓋。
- **Scoreboard 殘留**:來源伺服器建立的計分板不會被清除,需靠目標伺服器重建覆蓋。
- **位置必須逐幀一致**:見前提條件 2。這是整個技術的硬性邊界。

## 改動檔案

| 檔案 | 內容 |
|---|---|
| `connection/backend/SeamlessSwitchController.java` | 狀態機、緩衝、commit 觸發、abort |
| `connection/backend/SeamlessConfigSessionHandler.java` | 代答 config + 透明降級 |
| `connection/backend/SeamlessTransitionSessionHandler.java` | 緩衝目標 play 封包 |
| `connection/backend/SeamlessPacketIds.java` | 依協定版本查表選封包 id |
| `connection/client/ClientPlaySessionHandler.java` | `doSeamlessSwitch()`(清理 + 快照 flush) |
| `connection/MinecraftConnection.java` + `MinecraftSessionHandler.java` | 緩衝期間的封包攔截點 |
| `connection/backend/BackendPlaySessionHandler.java` | 實體 id 追蹤、reconfig 防護 |
| `config/VelocityConfiguration.java` + `default-velocity.toml` | `[seamless-transfers]` 設定 |
