# **Minecraft 插件 - Boss 傷害統計與獎勵分配**

## **功能概述**
1. **傷害統計**
   - 記錄玩家對每個 Boss 的傷害數據，包括總傷害、單次最高傷害、最小傷害、攻擊次數等。
   - 提供傷害排名計算功能，支持按玩家對 Boss 的總傷害進行排序。
   - 支持清除特定 Boss 的傷害數據。

2. **獎勵分配**
   - 根據玩家對 Boss 的傷害排名分配獎勵。
   - 支持配置不同 Boss 的獎勵策略，包括排名獎勵和參與獎勵。
   - 支持廣播獎勵信息到遊戲內或 Discord。

3. **配置管理**
   - 使用 YAML 文件管理獎勵配置（`RewardDistribution.yml`）和訊息配置（`Message.yml`）。
   - 支持動態重新載入配置。

4. **資料庫支持**
   - 使用 MySQL 和 Redis 作為後端資料存儲，配置文件為 `DataBase.yml`。

---

## **主要模組與文件**
1. **傷害計算模組**
   - 文件：`src/main/java/org/weiwei/mythicDistribute/distribute/DamageCalculation.java`
   - 功能：負責記錄玩家對 Boss 的傷害數據，並提供傷害排名、參與度計算等功能。

2. **獎勵分配模組**
   - 文件：
     - `src/main/java/org/weiwei/mythicDistribute/entity/RewardDistribution.java`
     - `src/main/java/org/weiwei/mythicDistribute/entity/RewardDistributionManager.java`
   - 功能：
     - 定義獎勵分配的結構（如排名獎勵、參與獎勵）。
     - 管理獎勵配置的載入與查詢。

3. **配置文件**
   - `RewardDistribution.yml`：定義每個 Boss 的獎勵策略。
   - `Message.yml`：定義插件的訊息模板，用於顯示給玩家的訊息。
   - `DataBase.yml`：定義資料庫連線配置。

4. **訊息管理**
   - 文件：`src/main/resources/Message.yml`
   - 功能：定義插件的訊息格式，支持多語言與動態參數替換。

5. **資料庫配置**
   - 文件：`src/main/resources/DataBase.yml`
   - 功能：配置 MySQL 和 Redis 資料庫，用於存儲插件的數據。

---

## **專案運作流程**
1. 玩家與 Boss 戰鬥時，插件記錄玩家的傷害數據。
2. 當 Boss 被擊殺時：
   - 計算玩家的傷害排名。
   - 根據 `RewardDistribution.yml` 中的配置分配獎勵。
   - 如果配置了廣播，則向所有玩家或 Discord 發送獎勵信息。
3. 插件支持管理員通過指令重新載入配置文件，無需重啟伺服器。

---

## **技術特點**
- **Lombok**：簡化 Java 類的 Getter/Setter 等代碼。
- **YAML 配置**：使用 YAML 文件進行靈活的配置管理。
- **資料庫支持**：通過 MySQL 和 Redis 提供高效的數據存儲與查詢。
- **Java Stream API**：用於傷害排名與數據處理的高效實現。

---
