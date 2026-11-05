package com.axalotl.async.forge.platform;

import com.axalotl.async.common.platform.CCEPlatform;
import dev.onyxstudios.cca.api.v3.component.Component;
import dev.onyxstudios.cca.api.v3.component.ComponentProvider;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class ForgeCCEPlatform implements CCEPlatform {
    @Override
    public List<Object> getComponents(Object entity) {
        if (entity instanceof ComponentProvider provider) {
            var container = provider.getComponentContainer();
            List<Object> components = new ArrayList<>(container.keys().size());
            for (var key : container.keys()) {
                Object comp = key.get(provider);
                components.add(comp);
            }
            return components;
        }
        return Collections.emptyList();
    }
}