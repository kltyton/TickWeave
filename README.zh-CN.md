# TickWeave

<p align="center"><img src="common/src/main/resources/tickweave.png" alt="TickWeave" width="160"></p>

[English](README.md) | [简体中文](README.zh-CN.md)

TickWeave 将 Minecraft 的实体 tick 分配给多个 CPU 工作线程，旨在降低实体密集场景的服务器 tick 耗时。面向 **Minecraft 1.20.1**，提供 **Forge 与 Fabric** 两个版本，支持专用服务器和单人游戏的内置服务器。

## 安装

使用 Java 17，并选择与加载器匹配的文件：

| 加载器 | 运行环境 | 发行文件 |
| --- | --- | --- |
| Forge | Forge 47.x，构建版本为 47.4.16 | `tickweave-forge-1.20.1-2.1.11-all.jar` |
| Fabric | Fabric Loader 0.19.3+，以及对应 1.20.1 的 Fabric API | `tickweave-fabric-1.20.1-2.1.11.jar` |

将 JAR 放入 `mods`。专用服务器的玩家客户端不必安装；单人游戏需要在客户端安装。更换 tick 处理模组前备份存档。安装时移除旧版 TickWeave、HariMultiThread 或 Async，不要同时运行这些实现。

## 功能

- 实体按执行归属分组调度，通用批处理按实测成本调整任务大小，主线程处理工作线程提交的必要请求。
- 有界线程池，各处理阶段等待本阶段任务完成。
- 已进入游戏的玩家连接按实体归属分组并行，连接关闭、监听器替换和网络包处理共享连接生命周期边界。
- 在加载阶段检查共享集合的字段和访问方式，对符合限定条件的缓存、集合及共享对象操作添加并发保护；保留原集合类型和回调行为。
- 工作线程缓存区块查询并合并相同的未完成请求，缺失区块交给服务器执行器处理。
- 实体区段有序索引和成员快照在成员变化前复用，区块 holder 快照供重复遍历复用。
- 原生碰撞查询仅在变换后的方法及类型条件能证明安全排除时缓存候选类；自定义碰撞行为和实体动态状态保留原检查。
- 按需计算地形噪声的 Z 插值，保留浮点运算顺序；安装 C2ME、Noisium 或 HariChunk 时由这些模组处理。
- 可选并行自然刷怪及异常诊断；失败的实体 tick 不会重新执行。
- POI 查询、惰性查询流的遍历和存储变更在服务器主线程执行，保留其他模组提供的存储索引。
- 实时查看服务器 tick 耗时、工作线程活动和实体开销。
- 实验性随机 tick 批处理默认关闭；方块和流体回调仍在服务器主线程执行。

所有实体类型都参与工作线程调度；`AsyncCompatible` 注解和旧同步实体名单不再控制准入。乘客随根载具递归执行，原生 Ownable/Traceable 源关系及检测到的共享可变状态用于合组，跨实体调用先取得参与者资源。协作等待时允许队列中的回调执行，整个实体 tick 不具备事务隔离。世界登记、POI 存储等需要主线程的工作仍在服务器线程执行。Fabric 中，在 Mixin 准备前已加载的标量集合类保留原存储，并在日志列出。共享集合检查只覆盖已识别的访问形式，不能保证任意静态状态、深层对象别名或所有模组回调都具备线程安全性。并行处理会改变实体执行顺序，收益取决于世界、CPU 和整合包，不保证固定提升。实体模拟使用 CPU。

## 配置

配置文件为 `config/tickweave.toml`。Forge 将以下键放在 `["Async Config"]` 下；Fabric 使用顶层键。从 `harimt.toml` 迁移时，将设置值复制到同一加载器新生成的配置中。两种加载器的配置文件不能直接互换。

| 配置项 | 默认值 | 用途 |
| --- | --- | --- |
| `disabled` | `false` | 关闭异步处理 |
| `paraMax` | `-1` | 工作线程数，-1 自动选择，上限为可用处理器数；修改后重启 |
| `enableAsyncSpawn` | `true` | 并行自然刷怪 |
| `enableAsyncRandomTicks` | `false` | 实验性随机 tick 准备阶段 |
| `enableAffinityRouting` | `true` | 保留的旧配置值；当前由执行归属决定调度 |
| `enableCircuitBreaker` | `true` | 收集实体异常诊断，不将实体类型退回同步执行 |
| `entitiesPerWorker` | `25` | 通用批处理大小上限；实体归属组不会因此拆开，Forge 范围为 5–200 |
| `staleTaskTimeoutMs` | `200` | 慢批次告警阈值，单位毫秒；不会取消正在运行的 tick |
| `synchronizedEntities` | 内置名单 | 为迁移保留的旧条目，在全实体调度中不生效 |

管理命令：

```text
/tickweave stats
/tickweave stats entity 10 100
/tickweave config toggle
/tickweave config reload
/tickweave config setAsyncEntitySpawn false
/tickweave config setAsyncRandomTicks false
/tickweave config synchronizedEntities
```

`stats entity 10 100` 采集 100 个服务器 tick，列出开销最高的十种实体。实体耗时会在线程间重叠，不能当作墙钟时间节省量。`Completed Worker Entity Ticks` 表示工作线程实际完成的实体 tick 数；线程池初始化日志本身不能证明并行处理生效。

## 兼容性

不要与 Moonrise 同时使用。两种加载器的实体注册都在服务器线程执行，也适用于 Cupboard 对注册线程的检查；Cupboard 的注入保持启用。Clickable Advancements 和 Biome Music 分别提供进度交互与音乐功能，TickWeave 不替代这些功能，也不注册 MixinSquared 取消器。Carpet 的 `lagFreeSpawning` 规则与并行刷怪冲突，使用该规则时请关闭并行刷怪。兼容补丁只在对应模组安装时加载。2.1.1 更新了 Forge SophisticatedCore 的 `SlotValueMap` 兼容处理，对应 Core 1.3.21.1676 / Backpacks 3.24.35.1675。

评估新构建时使用整合包和世界副本。当前调度器不会因修改 `synchronizedEntities` 而绕过出错的回调。通过 [GitHub Issues](https://github.com/kltyton/TickWeave/issues) 提交加载器、模组版本、`latest.log`、崩溃报告和复现步骤。

## 构建

使用 Java 17 和仓库内的 Gradle Wrapper：

```sh
./gradlew :forge:build :fabric:build
./gradlew :forge:runClient
./gradlew :fabric:runClient
```

Windows 使用 `gradlew.bat`。Forge 发行包位于 `forge/build/libs`，选择 `-all.jar`；Fabric 位于 `fabric/build/libs`，选择重映射后的 JAR，不使用源码包。现有 `common/libs/Harium-1.0.0.jar` 仅供可选集成编译，不打包进发行文件。

## 致谢与许可证

本项目衍生自 HariMultiThread 和 Async。感谢 HariMT、Axalotl、Alchemy、Bliss、FurryMileon、Grider、jediminer543 的上游工作，以及 PaperMC / Folia 的线程所有权设计参考。TickWeave 不是 Folia 服务器，也不代表上游团队背书。

采用 [GPL-3.0](LICENSE)。详见[第三方来源说明](THIRD_PARTY_NOTICES.md)和[更新记录](CHANGELOG.md)。
