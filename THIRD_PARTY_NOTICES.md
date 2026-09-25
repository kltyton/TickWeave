# Third-party notices

TickWeave is a modified continuation of earlier Minecraft entity-threading projects. The TickWeave source and release are licensed under [GPL version 3](LICENSE). This file identifies inherited work, libraries shipped inside the release JARs, and projects studied for design. A design reference is not a claim that its code was copied or that its authors endorse TickWeave.

## Source lineage and contributors

- [HariMultiThread](https://github.com/JustHari01/HariMultiThread) by HariMT / JustHari01 is a direct predecessor. Its repository contains a [GPLv3 license](https://github.com/JustHari01/HariMultiThread/blob/main/LICENSE).
- [Async](https://github.com/AxalotLDev/Async) and its [Minecraft 1.20.1 port](https://github.com/Bliss-tbh/Async-1.20.1) are part of the source lineage. The version-matched `0.1.8_alpha.1` port informed TickWeave's entity scheduling, batching, chunk lookups, optional spawning and compatibility behavior. The 1.20.1 port publishes a GPL-3.0 license. [Async's `ParallelProcessor` at the reviewed revision](https://github.com/AxalotLDev/Async/blob/43725f705d226395d97a4842604331f1d7a47694/common/src/main/java/com/axalotl/async/common/ParallelProcessor.java) was also reviewed when adapting scheduling for Java 17 and Minecraft 1.20.1.
- Thanks to HariMT, Axalotl, Alchemy, Bliss, FurryMileon, Grider and jediminer543 for the work preserved through this lineage. The Forge `ClassInstanceMultiMapMixin` also retains an `@author prydaran` attribution in its source. [MCMTFabric](https://github.com/himekifee/MCMTFabric) and [JMT-MCMT](https://github.com/jediminer543/JMT-MCMT) are acknowledged historical predecessors; MCMTFabric's phase-ordering approach was reviewed, but no Folia-style region executor is included.

## Libraries included in release JARs

| Library | Where included | License and source |
| --- | --- | --- |
| [MixinExtras 0.5.4](https://github.com/LlamaLad7/MixinExtras/tree/0.5.4) by LlamaLad7 | Nested in both Forge and Fabric releases | [MIT](https://github.com/LlamaLad7/MixinExtras/blob/0.5.4/LICENSE); its nested JAR contains `LICENSE_MixinExtras`. |
| [Night Config 3.8.1](https://github.com/TheElectronWill/night-config/tree/v3.8.1) by TheElectronWill (`core` and `toml`) | Nested in the Fabric release | [LGPL version 3](https://github.com/TheElectronWill/night-config/blob/v3.8.1/LICENSE); a verbatim copy is included as [`LICENSE_NightConfig`](fabric/src/main/resources/LICENSE_NightConfig) in the Fabric release. The release also includes TickWeave's GPLv3 license. |

`common/libs/Harium-1.0.0.jar` is used for optional compile-time integration and is not bundled in the release JARs. MixinSquared is not bundled.

## Design references, not bundled code

TickWeave's implementation research also examined [PaperMC/Folia](https://github.com/PaperMC/Folia), [C2ME](https://github.com/RelativityMC/C2ME-fabric), [Lithium](https://github.com/CaffeineMC/lithium-fabric), [ModernFix](https://github.com/embeddedt/ModernFix), [Noisium](https://github.com/Steveplays28/noisium) and [BiomeSpy](https://github.com/MoePus/BiomeSpy). These projects informed design and compatibility decisions; their code and performance figures are not presented as TickWeave's own. TickWeave is not a Folia server and does not use GPU/CUDA for server entity simulation.
