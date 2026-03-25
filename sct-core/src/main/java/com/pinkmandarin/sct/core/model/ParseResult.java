package com.pinkmandarin.sct.core.model;

import java.util.List;

public record ParseResult(
        List<Property> properties,
        List<Environment> environments,
        String lifecycleOrder,
        String regionOrder
) {
    public ParseResult(List<Property> properties, List<Environment> environments) {
        this(properties, environments, null, null);
    }

    public boolean hasConfig() {
        return lifecycleOrder != null || regionOrder != null;
    }
}
