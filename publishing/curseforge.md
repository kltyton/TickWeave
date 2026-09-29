# CurseForge listing copy — TickWeave 2.1.12

## 简体中文

**一句话总结：** 面向 Minecraft 1.20.1 Forge/Fabric 的实验性多核实体优化模组，旨在缓解大量怪物和玩家带来的服务器卡顿。

**简介**

TickWeave 继承 HariMultiThread 和 Async 的工作，让 Minecraft 服务器把更多实体处理分配给多个 CPU 核心。它面向怪物密集、多人战斗和探索新区块时的服务器 tick 卡顿：如果服务器主要受实体处理限制，工作线程有机会分担主线程的压力。它不会提高显卡渲染性能，也不承诺固定的 TPS 或帧率提升；实际效果取决于 CPU、整合包、实体数量和世界状态。

与上游相比，TickWeave 不再只让预先标记为适合异步处理的实体参与调度，而是将所有实体类型纳入工作线程调度，并把已进入游戏的玩家连接处理纳入同一套执行归属规则。乘客与载具、具有主人关系的实体，以及已识别会共享可变数据的对象会被协调处理，减少并行执行时相互踩踏的机会。玩家较多或模组实体较复杂的服务器因此有更多工作可以分散到 CPU 核心，但某些相互依赖的实体仍会一起执行，并不意味着每个实体都能同时运行。

TickWeave 还改进了工作线程与服务器主线程之间的区块请求和等待处理，对部分共享集合及跨实体调用增加保护，并保留必要的主线程工作，例如世界登记与 POI 存储。任务失败时，它会记录诊断信息，不会把失败的实体 tick 悄悄重放。管理员可用 `/tickweave stats` 查看服务器 tick、工作线程和实体处理情况；`/tickweave stats entity 10 100` 可采样 100 个 tick，找出耗时较高的实体类型。这些功能便于判断是否真的在并行工作，也有助于定位问题。

这是对上游多线程方案的扩展，不是对所有模组的兼容保证。并行处理可能改变实体的执行先后顺序，其他模组未受保护的静态状态或特殊回调仍可能出错。建议先在整合包和世界副本中试用，观察真实玩法、日志及服务器 tick 表现。

**2.1.14 更新：** 在250模组、四假玩家、约2600–2800实体的隔离Forge高压场景，预热180秒后五个正式一分钟窗口为20.00/20.00/20.00/20.00/19.93 TPS；P95为49.2/60.3/56.8/54.7/59.6ms，仍有超过50ms的窗口。Fabric另在10模组兼容负载、约1200实体下独立得到五窗约20TPS；两种负载不能直接比较。Forge无敌目标的无效攻击现在不再触发`LivingAttackEvent`及相关脚本，普通可受伤目标仍触发。2.1.14还修复专用服务端实体字段缺类崩溃和Terra Entity刷怪谓词共享状态竞争；区块刷怪任务继续并行。两端均用正式JAR在本地保存退出，真实服务器与客户端人工验收尚未覆盖此版。

**具体功能**

- **实体与多人战斗：** 调度所有实体类型及玩家连接工作；载具和乘客、主人和召唤物、共享数据的实体按关系协调。批处理会参考实际耗时调整大小，避免把独立实体一律塞进同一个任务。
- **跑图与区块：** 工作线程重复查询区块时复用本线程缓存，合并相同的待完成请求；需要服务器线程处理的区块工作仍交回服务器线程。实体区段与区块 holder 的遍历快照在内容未变化时复用，减少反复扫描。
- **世界生成：** 地形噪声中的部分插值按需计算，保持原计算顺序。与 C2ME、Noisium 或 HariChunk 共装时，该部分优化会让位，避免重复改写同一路径；这不等于保证与它们的所有功能兼容。
- **碰撞与刷怪：** 对可证明安全的原生碰撞查询减少重复类型检查，保留模组自定义碰撞判断。自然刷怪可并行处理；实验性随机 tick 批处理默认关闭，方块和流体回调仍由服务器线程执行。
- **兼容与排错：** 对已识别的共享集合、跨实体调用和区块请求加以协调；世界登记和 POI 存储继续由服务器线程负责。慢批次与任务异常会留下诊断信息，`/tickweave stats` 可查看实际工作线程活动。

**测试数据**：以下旧服 Spark 记录采自 Minecraft 1.20.1／Forge 47.4.16 的 4 名真实玩家。旧服当时**没有安装 TickWeave**，但安装了上游 HariMultiThread 2.0；服主反馈上游并未解决实际卡顿，因此开发了 TickWeave。后两行是 TickWeave 2.1.11 CPU 实现的本地工程验收：Java 17，4 名模拟玩家，先预热 180 秒，再记录 300 秒战斗与跑图混合负载；Forge 为完整测试整合包，Fabric 为独立兼容负载。

| 采样窗口 | TPS | MSPT | 实体快照 |
| --- | ---: | ---: | ---: |
| 旧服 Spark：未安装 TickWeave，导出时最近 1 分钟 | 13.95 | 平均 68.79 ms；P95 102.35 ms | 546 |
| 旧服 Spark：卡顿窗口第 1 分钟 | 9.99 | 中位数 89.88 ms | 682 |
| 旧服 Spark：卡顿窗口第 2 分钟 | 9.98 | 中位数 91.40 ms | 650 |
| 本地 Forge：TickWeave 2.1.11 混合负载 5 分钟 | 最低分钟值 20.0000 | P95 49.92 ms | 2,426–3,249（6 次采样，含玩家） |
| 本地 Fabric：TickWeave 2.1.11 混合负载 5 分钟 | 最低分钟值 19.9833 | P95 32.90 ms | 1,438–1,591（6 次采样，含玩家） |

旧服 Spark 约 29 分钟的后台采样中，LivingTick 及其他实体 tick 占近期两分钟主线程样本的 65.46%，区块系统另占 9.69%；这只能说明当时的负载位置，不能换算加速。两场本地 TickWeave 验收均未记录本项目异步失败，世界正常保存并退出；Forge／Fabric 最大单次 tick 分别为 397.14／339.12 ms，玩家连接工作线程 tick 分别为 36,804／36,804 和 38,236／38,236。Forge P95 已接近 50 ms，不称完全无卡顿。

旧服装载上游 HariMultiThread 的事实**不等于它消除了卡顿**；但现存资料没有证明它当时完全没有执行，所以不能把这份 Spark 样本称为“无任何多线程实体模组”。真实玩家与模拟玩家、世界、模组版本、实体数量及采样口径均不同，本表是两组独立记录，**不是同输入 A/B 测试**，不据此计算 TickWeave 的提速百分比。2.1.12 沿用该 CPU 实现，但两个正式 JAR 尚未以同一完整混合负载独立重跑；2.1.12 Forge 正式包在真实服上获服主反馈可用，未取得同口径线上性能数字。

仅支持 **Minecraft 1.20.1、Java 17**。请选择对应的 Forge 或 Fabric 文件。专用服务器只需在服务端安装；单人游戏需要在客户端安装。不要与 Async、HariMultiThread 或 Moonrise 同时使用；更换实体处理模组前，请先备份世界。

**警告：TickWeave 仍处于测试阶段，可能与许多模组或整合包不兼容，甚至导致崩溃或世界行为异常。** 请先在世界副本中测试。发现问题时，请到 [GitHub Issues](https://github.com/kltyton/TickWeave/issues) 提交加载器、模组版本、复现步骤及 `latest.log`／崩溃报告。

**致谢：** 感谢 HariMT、Axalotl、Alchemy、Bliss、FurryMileon、Grider、jediminer543 及其他上游贡献者。项目基于 HariMultiThread／Async 的工作继续开发；来源、所含第三方库及许可证见 [第三方声明](https://github.com/kltyton/TickWeave/blob/main/THIRD_PARTY_NOTICES.md)。

## English

**One-sentence summary:** An experimental multicore entity mod for Minecraft 1.20.1 Forge and Fabric, aimed at easing server lag when worlds have many mobs and players.

**Description**

TickWeave builds on HariMultiThread and Async to spread more Minecraft server entity work across CPU cores. It targets server tick stalls in mob-heavy worlds, multiplayer combat and new-chunk exploration. When entity work is the bottleneck, workers can take some load off the server thread. TickWeave does not accelerate GPU rendering or promise a fixed TPS or FPS gain; results depend on the CPU, modpack, entity population and world.

Unlike its upstream projects, TickWeave schedules every entity type for worker processing instead of admitting only entities marked as async-compatible. It also brings connected players' connection ticks into the same ownership scheme. Passengers and vehicles, owner-linked entities, and detected users of shared mutable data are coordinated so they are less likely to interfere with one another. This lets more work use multiple cores in busy servers, although related entities may still run together rather than all at once.

TickWeave also coordinates chunk requests between workers and the server thread, protects selected shared collections and cross-entity calls, and keeps world registration and POI storage on the server thread. When a task fails, it records diagnostics instead of silently replaying the failed entity tick. Admins can run `/tickweave stats` to inspect tick and worker activity, or `/tickweave stats entity 10 100` to sample 100 ticks and identify costly entity types. These tools help confirm that workers are active and narrow down lag or compatibility reports.

This extends the upstream multithreading approach without guaranteeing compatibility with every mod. Parallel work can change the order in which entities run, and another mod's unprotected global state or unusual callbacks can still fail. Try TickWeave on a copy of your modpack and world, then watch actual gameplay, logs and server tick times.

**2.1.14 update:** In an isolated Forge pressure run with 250 mods, four fake players and roughly 2,600–2,800 entities, the five one-minute windows after a 180-second warm-up measured 20.00/20.00/20.00/20.00/19.93 TPS. Tick-time P95 was 49.2/60.3/56.8/54.7/59.6 ms, so several windows still exceeded 50 ms. A separate ten-mod Fabric workload with roughly 1,200 entities measured about 20 TPS in all five windows; the two workloads are not directly comparable. Forge now skips `LivingAttackEvent` and related scripts for attacks rejected by the target's native invulnerability check; vulnerable targets still fire the event. This version also addresses a dedicated-server crash from an unavailable entity field type and Terra Entity's shared spawn-predicate state while keeping chunk spawning parallel. Both exact release JARs saved and stopped locally; live-server and manual client acceptance for this version remain untested.

**What it does**

- **Entities and multiplayer combat:** Schedules every entity type and connected-player work. Vehicles and passengers, owner-linked mobs, and detected sharers of mutable data are coordinated. General batches adjust their size using measured work cost.
- **Exploration and chunks:** Reuses worker-local chunk lookups and combines identical pending requests, while sending required chunk work back to the server thread. Entity-section and chunk-holder snapshots avoid repeating unchanged traversal work.
- **World generation:** Computes part of terrain-noise interpolation on demand without changing floating-point operation order. This path yields to C2ME, Noisium or HariChunk when installed; that does not imply universal compatibility with those mods.
- **Collisions and spawning:** Reduces repeated type checks only in native collision queries where the exclusion is proven safe; custom mod collision behavior keeps its normal checks. Natural spawning can run in parallel. Experimental random-tick batching is off by default, and block/fluid callbacks remain on the server thread.
- **Compatibility and diagnostics:** Coordinates recognized shared collections, cross-entity calls and chunk requests. World registration and POI storage stay on the server thread. Slow batches and failed tasks produce diagnostics, and `/tickweave stats` shows actual worker activity.

**Test data:** The historical Spark rows come from four real players on a Minecraft 1.20.1 / Forge 47.4.16 server. TickWeave was **not installed**; upstream HariMultiThread 2.0 was installed, but the server owner reported that it did not resolve the lag, which motivated TickWeave. The last two rows are local engineering tests of TickWeave's 2.1.11 CPU implementation on Java 17: four simulated players, a 180-second warm-up, then 300 seconds of mixed combat and exploration. Forge used the full test modpack; Fabric used a separate compatible workload.

| Sampling window | TPS | MSPT | Entity snapshot |
| --- | ---: | ---: | ---: |
| Historical Spark, no TickWeave: most recent minute at export | 13.95 | Mean 68.79 ms; P95 102.35 ms | 546 |
| Historical Spark: first minute of a lagging interval | 9.99 | Median 89.88 ms | 682 |
| Historical Spark: second minute of that interval | 9.98 | Median 91.40 ms | 650 |
| Local Forge: TickWeave 2.1.11 mixed load, 5 minutes | Lowest one-minute value 20.0000 | P95 49.92 ms | 2,426–3,249 (six snapshots, including players) |
| Local Fabric: TickWeave 2.1.11 mixed load, 5 minutes | Lowest one-minute value 19.9833 | P95 32.90 ms | 1,438–1,591 (six snapshots, including players) |

In the roughly 29-minute historical Spark capture, LivingTick plus other entity ticks accounted for 65.46% of recent server-thread samples and chunk work for another 9.69%. Those shares locate work; they are not predicted speedups. Neither local TickWeave run recorded an async failure, and both saved and exited normally. The longest single tick was 397.14 ms on Forge and 339.12 ms on Fabric; connected-player worker ticks were 36,804 / 36,804 and 38,236 / 38,236 respectively. Forge's P95 was close to 50 ms, so this is not a claim of stall-free play.

Having upstream HariMultiThread installed **did not solve that server's reported lag**, but the surviving evidence does not show that it performed no work at all. The historical capture is therefore not a server without any entity-threading mod. Real versus simulated players, worlds, mod versions, entity counts and measurement methods also differ. These are **separate observations, not a matched A/B test**; do not calculate a TickWeave speedup percentage from them. The 2.1.12 release carries the same CPU implementation, but its two exact release JARs were not independently rerun through the full mixed load. The owner reported that the 2.1.12 Forge file worked on a live server; no comparable live performance figures were collected.

TickWeave supports **Minecraft 1.20.1 and Java 17**. Choose the file for your Forge or Fabric loader. Dedicated servers install it on the server only; single-player worlds install it on the client. Do not run it alongside Async, HariMultiThread or Moonrise, and back up your world before changing entity-ticking mods.

**Warning: TickWeave is still in testing. Many modpack incompatibilities may remain, and crashes or incorrect world behavior are possible.** Try it on a copy of your world first. Report problems through [GitHub Issues](https://github.com/kltyton/TickWeave/issues) with your loader, mod versions, reproduction steps and `latest.log` or crash report.

**Credits:** Thanks to HariMT, Axalotl, Alchemy, Bliss, FurryMileon, Grider, jediminer543 and other upstream contributors. TickWeave builds on HariMultiThread and Async; see the [third-party notices](https://github.com/kltyton/TickWeave/blob/main/THIRD_PARTY_NOTICES.md) for source lineage, bundled libraries and licenses.
