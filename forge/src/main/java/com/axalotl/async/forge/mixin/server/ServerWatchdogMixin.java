package com.axalotl.async.forge.mixin.server;

import com.axalotl.async.common.diagnostics.thread.ThreadDump;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.server.dedicated.ServerWatchdog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.management.ThreadInfo;

/** Retains the watchdog's full failure-time snapshot once, before shutdown can remove workers. */
@Mixin(ServerWatchdog.class)
public class ServerWatchdogMixin {

    @Inject(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/CrashReport;addCategory(Ljava/lang/String;)Lnet/minecraft/CrashReportCategory;", ordinal = 0), require = 1)
    private void addCustomCrashReport(CallbackInfo ci, @Local ThreadInfo[] snapshot, @Local CrashReport report) {
        CrashReportCategory threadDumpSection = report.addCategory("Async thread dump");
        threadDumpSection.setDetail("All Threads", ThreadDump.format(snapshot));
    }

}
