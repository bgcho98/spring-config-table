package com.pinkmandarin.sct.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentTest {

    @Test
    void toFileName_default() {
        assertThat(new Environment("default").toFileName()).isEqualTo("application.yml");
    }

    @Test
    void toFileName_profile() {
        assertThat(new Environment("beta").toFileName()).isEqualTo("application-beta.yml");
    }

    @Test
    void toDisplayName_instance() {
        assertThat(new Environment("default").toDisplayName()).isEqualTo("_default");
        assertThat(new Environment("beta").toDisplayName()).isEqualTo("beta");
    }

    @Test
    void fromDisplayName_factory() {
        assertThat(Environment.fromDisplayName("_default")).isEqualTo(new Environment("default"));
        assertThat(Environment.fromDisplayName("beta")).isEqualTo(new Environment("beta"));
    }
}
