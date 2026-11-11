# Seamless Server Transfers(無縫換伺服器)

本 fork 讓玩家在**同一份世界的分流後端**之間切換時,**看不到「正在載入地形」畫面**:
不回伺服器列表、不重連、不進 config 階段、視角直接維持。

## 原理

1. proxy 代替客戶端回答目標伺服器的 **config 階段**(客戶端全程留在 PLAY,繼續在來源伺服器遊玩)。
2. 緩衝目標伺服器的初始封包。
3. 換手(commit)時:清掉來源伺服器的實體(避免殘留)、tab / boss bar / title,
   再把緩衝的封包一次送給客戶端。**不送 JoinGame / Respawn**,所以客戶端保留現有的世界畫面。
4. 目標伺服器的「開始等待區塊」事件(game event 13)在**同世界切換**時被攔下——
   ≤1.21.1 的客戶端收到它會無條件開啟「正在載入地形」(哪怕世界早已載入,
   造成間歇性的一幀閃屏;新版客戶端已修掉這行為)。攔截涵蓋緩衝期與 commit 後
   10 秒的 live 窗口。
5. **真實世界切換自動讓路**:若 join 過程中後端送出 Respawn(例如同步插件把玩家
   跨世界傳送),代表客戶端必須真實重載——此時事件照常放行、不做任何攔截,
   客戶端走正常載入流程。Respawn 以封包 id 偵測(Velocity 將其註冊為 encode-only,
   後端送來的一律是未解碼封包)。
6. commit 後 proxy 代客戶端向目標伺服器送出 **Player Loaded**(1.21.4+):客戶端沒有真的
   重載世界,不會自己送這個封包;不補的話伺服器會凍結玩家移動直到約 3 秒的逾時。
   若 join 過程有真實 Respawn 則不代送,由客戶端重載後自行回報。
7. **玩家 entity id 對調**:客戶端沒收到 JoinGame,會一直沿用第一台伺服器給的 id。
   之後 proxy 在「後端給玩家的 id」與「客戶端認得的 id」之間雙向對調
   (`SeamlessEntityIdRemapper`):OP 權限等級、擊退、屬性、藥水效果、死亡畫面、騎乘等
   都正確作用在玩家身上;若後端剛好有實體用到客戶端的 id,也會被移開,不會把玩家本人換掉。
8. **聊天已讀確認橋接**:客戶端的「已讀訊息」視窗還停在來源伺服器,目標伺服器卻是空的,
   原樣轉發會被判定聊天驗證失敗而踢出。橋接後 proxy 對目標只確認「目標自己送過的簽名訊息」
   (`ChatQueue.ChatState`)。
9. **天氣歸零**:換手前送雨量 / 雷電等級 0,目標若在下雨會在 join 時重送自己的天氣。

## 前提條件(缺一不可)

1. 同一組(group)內的伺服器**共用同一份世界模板**(維度、世界名稱一致)。
2. **玩家資料同步,且包含精確座標**(例如 Paper 的 playerdata 同步插件)。
   這是無縫的核心——**位置只要有落差,客戶端就得真的載入新地形,畫面就無法消除**。
3. 客戶端為 vanilla **1.20.2+**(不符合的玩家自動走原生切換,不會壞)。
4. Velocity modern forwarding 照常設定。
5. 後端 **`enforce-secure-profile=false`**。客戶端只在收到 JoinGame 後才會把聊天 session
   送給伺服器,熱切換不送 JoinGame,所以強制簽名聊天的伺服器無法在熱切換後聊天。
   偵測到這種伺服器(1.20.5+ 從 JoinGame 得知)時,proxy 會自動改用原生切換重連,
   log 會出現警告,直到 proxy 重啟前都不再對它走無縫。

## 設定(velocity.toml)

```toml
[seamless-transfers]
enabled = true
server-groups = [ ["TW-0", "TW-1", "TW-2"] ]   # 填 [servers] 裡的名稱;只有同組互切才無縫
verbose = false                                 # 需要調參 / 觀察時間軸時設 true
```

就這樣——**不需要設定任何封包 id**。需要的封包 id 會依每條連線的協定版本自動選擇
(內建對照表,涵蓋 1.20.2 ~ 26.3;表裡沒有的版本自動走原生切換)。

換手時機:目標伺服器送出第一批區塊(Chunk Batch Finished)就換手。
進階選項(通常不用動):`commit-timeout-ms = 2000`(等不到第一批區塊時的上限)、
`prepare-timeout-ms = 5000`。舊版的 `commit-chunk-packets`、`chunk-packet-min-bytes`、
`commit-grace-ms` 已移除,留在設定檔裡也不影響。

## 支援新的 Minecraft 版本

封包 id 表由 `scripts/seamless-packet-ids.py` 從 ViaVersion 的封包 enum 產生(用法寫在
腳本開頭)。上游加入新版本後:把版本加進腳本的 `VERSIONS`、重新產生並貼進
`SeamlessPacketIds.java` 的 GENERATED 區塊。`SeamlessPacketIdsTest` 會在表缺少任何
Velocity 支援的版本時讓建置失敗,不會悄悄退化。Respawn 的 id 直接向 Velocity 的
`StateRegistry` 查詢,不需要維護。

## 建置與安裝

```bash
./gradlew build
# 產物:proxy/build/libs/velocity-proxy-*-all.jar(要用 -all 那顆)
```

取代原本的 Velocity jar 即可;`enabled = false`(預設)時行為與官方版完全相同。

## verbose log 範例

```
Seamless transfer for <player> -> TW-1 committed (chunk-batch): prepare=26ms,
buffer=96ms, commit=36ms, bufferedTargetPackets=184, suppressedLoadScreenEvents=1,
worldChange=false, droppedSourcePackets=0
```

## 安全設計(fallback)

寧可有畫面、絕不斷線:

| 情況 | 行為 |
|---|---|
| 不在同一 group / 版本不符 / 客戶端 <1.20.2 / 版本不在對照表 | 直接走原生切換 |
| 目標強制簽名聊天(`enforce-secure-profile=true`) | 自動以原生切換重連目標 |
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

- **後端 join 時的跨世界傳送無法隱藏**:若後端讓玩家先出生在預設世界、再由插件傳送到
  目標世界(log 會出現 `live Respawn ... genuine world change`),客戶端必須真實重載,
  ≤1.21.1 會短暫看到「正在載入地形」(新版客戶端不顯示)。**根治方法在伺服器端**:
  讓後端直接出生在正確世界(`level-name` 設為目標世界,或讓同步插件在 spawn 前套用
  位置),Respawn 消失後即為零畫面。
- **entity id 對調只涵蓋常用封包**:玩家本人相關的封包都已涵蓋。若目標伺服器某個實體
  剛好用到客戶端的 id,該實體的少數冷門封包(音效、動畫)不會對調,只影響外觀。
- **來源伺服器的藥水效果**:目標伺服器只會補送它自己有的效果;來源伺服器給、目標沒有的
  效果會在客戶端顯示到自然過期(玩家資料同步時兩邊一致,通常不會發生)。
- **Scoreboard 殘留**:來源伺服器建立的計分板不會被清除,需靠目標伺服器重建覆蓋。
  刻意不由 proxy 清除:proxy 端的計分板外掛(如 velocity-scoreboard-api)自己管理
  team / objective,proxy 擅自移除會讓外掛的狀態與客戶端不一致。
- **位置必須逐幀一致**:見前提條件 2。這是整個技術的硬性邊界。

## 改動檔案

| 檔案 | 內容 |
|---|---|
| `connection/backend/SeamlessSwitchController.java` | 狀態機、緩衝、commit 觸發、abort |
| `connection/backend/SeamlessConfigSessionHandler.java` | 代答 config + 透明降級 |
| `connection/backend/SeamlessTransitionSessionHandler.java` | 緩衝目標 play 封包 |
| `connection/backend/SeamlessPacketIds.java` | 依協定版本查表選封包 id(產生的表) |
| `connection/backend/SeamlessEntityIdRemapper.java` | 玩家 entity id 雙向對調 |
| `protocol/packet/chat/ChatQueue.java` | 聊天已讀確認橋接 |
| `scripts/seamless-packet-ids.py` | 從 ViaVersion 產生封包 id 表 |
| `connection/client/ClientPlaySessionHandler.java` | `doSeamlessSwitch()`(清理 + 快照 flush) |
| `connection/MinecraftConnection.java` + `MinecraftSessionHandler.java` | 緩衝期間的封包攔截點 |
| `connection/backend/BackendPlaySessionHandler.java` | join 窗口過濾、reconfig 防護 |
| `connection/backend/VelocityServerConnection.java` | 實體追蹤、id 對調、簽名聊天計數 |
| `config/VelocityConfiguration.java` + `default-velocity.toml` | `[seamless-transfers]` 設定 |
