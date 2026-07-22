# Seamless Server Transfers(無縫換伺服器)

本 fork 讓玩家在「同世界模板」的後端之間切換時**完全沒有載入畫面**:
不回伺服器列表、不重連、不進 config 階段、不出現「正在載入地形」。

原理:proxy 代替客戶端回答新伺服器的 config 階段、緩衝其初始世界快照,
然後**不送任何世界切換封包**直接換手(hot-swap)——因為兩邊世界相同,
畫面上的地形本來就是對的,新伺服器的位置同步、區塊、實體直接接管。

## 前提條件

1. 同一組(group)內的伺服器**共用同一份世界模板**(維度、世界名稱一致)。
2. **玩家資料同步**(位置、血量、背包),例如 playerdata 同步插件。
3. 客戶端為 vanilla **1.20.2+**(不符合的玩家自動走原生切換,不會壞)。
4. Velocity modern forwarding 照常設定。

## 設定(velocity.toml)

```toml
[seamless-transfers]
enabled = true
server-groups = [ ["SRV1", "SRV2"] ]   # 填 [servers] 裡的名稱;只有同組互切才無縫

# 後端版本的兩個封包 id(十進位),查 minecraft.wiki 的 Protocol 頁。
# Minecraft 26.2:Spawn Entity = 0x01 → 1,Remove Entities = 0x4D → 77
# 沒填(-1)時舊伺服器的實體會殘留(ghost),並有 id 衝突使客戶端 crash 的風險。
add-entity-packet-id = 1
remove-entities-packet-id = 77

verbose = true                          # 印出每次切換的時間軸,調參用
```

進階選項(通常不用動):`commit-chunk-packets = 25`(收到幾個區塊就換手)、
`chunk-packet-min-bytes = 4096`(多大算區塊封包)、`commit-grace-ms = 150`(安靜視窗)、
`commit-timeout-ms = 2000`(強制換手上限)、`prepare-timeout-ms = 5000`(準備逾時)。

> 這兩個封包 id 綁定**後端版本**,與玩家客戶端版本無關;
> 只有升級後端 Minecraft 版本時才需要重查更新。

## 建置與安裝

```bash
./gradlew build
# 產物:proxy/build/libs/velocity-proxy-*-all.jar(要用 -all 那顆)
```

取代原本的 Velocity jar 即可;`enabled = false`(預設)時行為與官方版完全相同。

## 運作流程

```
玩家在 A ── /server B
   │
   ▼ PREPARE   B 登入後,proxy 自己回答 B 的 config(客戶端繼續在 A 遊玩)
   ▼ BUFFER    B 進入 PLAY,快照封包進緩衝;proxy 代答 KeepAlive;
   │           「開始等待區塊」事件被攔下(它是載入畫面的唯一觸發源)
   ▼ COMMIT    收到約 25 個區塊封包即換手:清 A 的實體(RemoveEntities)、
   │           tab、boss bar、title → 一次 flush 整份快照 → 斷開 A
   ▼ LIVE      之後 B 的封包正常轉發
```

安全設計:B 斷線/逾時/kick → 玩家原地留在 A;B 要求資源包/cookie → 透明降級成
原生切換(閃一下但不斷線);切換絕不把玩家踢回伺服器列表。

verbose log 範例:

```
Seamless transfer for <player> -> SRV2 committed (chunks): prepare=100ms,
buffer=736ms, commit=8ms, bufferedTargetPackets=164, chunkSizedPackets=25,
suppressedLoadScreenEvents=2, droppedSourcePackets=0
```

## 跨版本(Hypixel 式)

後端全部維持單一版本,在 Velocity 的 `plugins/` 裝 **ViaVersion + ViaBackwards**
(要支援 1.8 再加 ViaRewind)。封包 id 設定只跟後端版本綁定,不受客戶端版本影響;
1.20.2 以下的客戶端自動走原生切換。

## 已知限制

- **玩家自身 entity id 不變**:切換後新伺服器用新 id 指涉玩家,以 entity id 指涉的
  封包(metadata、attributes、藥水效果)會被忽略或錯掛,極端情況下可能與其他實體
  id 相撞。完整解需要 player-id 重寫層(TODO)。
- **Scoreboard 殘留**:舊伺服器建立的計分板不會被清除,需靠新伺服器重建覆蓋。
- 兩台後端 `/time`、天氣不同時,切換瞬間會看到天色跳變(正常,B 的封包接管)。
- seamless 路徑不觸發 `PlayerEnterConfigurationEvent` 系列事件(客戶端從未進 config);
  `ServerPreConnectEvent` / `ServerConnectedEvent` / `ServerPostConnectEvent` 照常。

## 改動檔案

| 檔案 | 內容 |
|---|---|
| `connection/backend/SeamlessSwitchController.java` | 狀態機、緩衝、commit 觸發、abort |
| `connection/backend/SeamlessConfigSessionHandler.java` | 代答 config + 透明降級 |
| `connection/backend/SeamlessTransitionSessionHandler.java` | 緩衝 B 的 play 封包 |
| `connection/client/ClientPlaySessionHandler.java` | `doSeamlessSwitch()`(清理 + 快照 flush) |
| `connection/backend/BackendPlaySessionHandler.java` | 實體 id 追蹤、reconfig 防護 |
| `connection/backend/LoginSessionHandler.java` | seamless 資格判斷分支 |
| `connection/backend/VelocityServerConnection.java` | 追蹤實體 id 集合 |
| `config/VelocityConfiguration.java` + `default-velocity.toml` | `[seamless-transfers]` 設定 |
