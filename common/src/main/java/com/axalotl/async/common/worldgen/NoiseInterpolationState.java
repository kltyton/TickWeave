package com.axalotl.async.common.worldgen;

public interface NoiseInterpolationState {
    boolean tickweave$deferZ(double fraction, String updateMethod);

    void tickweave$markCustomInterpolator();

    long tickweave$zRevision();

    double tickweave$zFraction();

    interface Reader {
        void tickweave$invokeUpdateForZ(double fraction);

        void tickweave$resolveZ();
    }
}
