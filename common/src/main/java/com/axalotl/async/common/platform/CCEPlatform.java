package com.axalotl.async.common.platform;

import dev.onyxstudios.cca.api.v3.component.Component;

import java.util.List;

public interface CCEPlatform {
    List<Object> getComponents(Object owner);
}