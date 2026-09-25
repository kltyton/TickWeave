# CurseForge listing copy — TickWeave 2.1.12

## 简体中文

**一句话总结：** 面向 Minecraft 1.20.1 Forge/Fabric 的实验性多核实体优化模组，旨在缓解大量怪物和玩家带来的服务器卡顿。

**简介**

TickWeave 继承了 HariMultiThread 和 Async 的多线程实体处理，并继续针对 Minecraft 1.20.1 的 Forge 与 Fabric 开发。它让实体和玩家相关的服务器工作更充分地利用多核 CPU，目标是在怪物密集、多人战斗或探索新区块时减轻服务器主线程的压力。实际效果取决于整合包、世界和硬件，不保证每个服务器都能提速。

相较于上游实现，TickWeave 扩大了参与并行处理的实体与玩家连接范围，并增加了对共享数据、区块请求及任务异常的协调和诊断。这些改动旨在减少卡顿，也尽量降低多线程与其他模组交互时的风险；它们不能保证与所有模组兼容。管理员可用 `/tickweave stats` 查看服务器 tick 和工作线程状态。

仅支持 **Minecraft 1.20.1、Java 17**。请选择对应的 Forge 或 Fabric 文件。专用服务器只需在服务端安装；单人游戏需要在客户端安装。不要与 Async、HariMultiThread 或 Moonrise 同时使用；更换实体处理模组前，请先备份世界。

**警告：TickWeave 仍处于测试阶段，可能与许多模组或整合包不兼容，甚至导致崩溃或世界行为异常。** 请先在世界副本中测试。发现问题时，请到 [GitHub Issues](https://github.com/kltyton/TickWeave/issues) 提交加载器、模组版本、复现步骤及 `latest.log`／崩溃报告。

**致谢：** 感谢 HariMT、Axalotl、Alchemy、Bliss、FurryMileon、Grider、jediminer543 及其他上游贡献者。项目基于 HariMultiThread／Async 的工作继续开发；来源、所含第三方库及许可证见 [第三方声明](https://github.com/kltyton/TickWeave/blob/main/THIRD_PARTY_NOTICES.md)。

## English

**One-sentence summary:** An experimental multicore entity mod for Minecraft 1.20.1 Forge and Fabric, aimed at easing server lag when worlds have many mobs and players.

**Description**

TickWeave continues the multithreaded entity work of HariMultiThread and Async for Minecraft 1.20.1 on Forge and Fabric. It lets entity and player-related server work use more CPU cores, aiming to ease main-thread pressure during mob-heavy play, multiplayer combat and exploration. Results depend on the modpack, world and hardware; a speedup is not guaranteed.

Compared with its upstream projects, TickWeave extends parallel scheduling to more entity types and player connection work, and adds coordination and diagnostics for shared data, chunk requests and failed tasks. These changes aim to reduce stalls and make interactions with other mods safer, but they cannot guarantee compatibility with every mod. Server admins can use `/tickweave stats` to inspect tick and worker activity.

TickWeave supports **Minecraft 1.20.1 and Java 17**. Choose the file for your Forge or Fabric loader. Dedicated servers install it on the server only; single-player worlds install it on the client. Do not run it alongside Async, HariMultiThread or Moonrise, and back up your world before changing entity-ticking mods.

**Warning: TickWeave is still in testing. Many modpack incompatibilities may remain, and crashes or incorrect world behavior are possible.** Try it on a copy of your world first. Report problems through [GitHub Issues](https://github.com/kltyton/TickWeave/issues) with your loader, mod versions, reproduction steps and `latest.log` or crash report.

**Credits:** Thanks to HariMT, Axalotl, Alchemy, Bliss, FurryMileon, Grider, jediminer543 and other upstream contributors. TickWeave builds on HariMultiThread and Async; see the [third-party notices](https://github.com/kltyton/TickWeave/blob/main/THIRD_PARTY_NOTICES.md) for source lineage, bundled libraries and licenses.
