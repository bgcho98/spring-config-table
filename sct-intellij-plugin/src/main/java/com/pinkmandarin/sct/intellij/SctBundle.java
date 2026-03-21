package com.pinkmandarin.sct.intellij;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

public final class SctBundle extends DynamicBundle {

    private static final String BUNDLE = "messages.SctBundle";
    private static final SctBundle INSTANCE = new SctBundle();

    private SctBundle() {
        super(BUNDLE);
    }

    public static @NotNull String message(
            @NotNull @PropertyKey(resourceBundle = BUNDLE) String key,
            Object @NotNull ... params) {
        return INSTANCE.getMessage(key, params);
    }
}
