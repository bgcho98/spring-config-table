package com.pinkmandarin.sct.core.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

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

    @Test
    void envComparator_lifecycleOrder() {
        var envs = Arrays.asList(
                "real", "gov-beta", "alpha", "default", "beta-dr", "ncgn-real",
                "dev", "beta", "gov", "ngcc-beta", "local", "release", "dr"
        );
        envs.sort(Environment.ENV_COMPARATOR);

        // default → local → dev → alpha → beta → beta-dr → real → release → dr → gov → gov-beta → ncgn-real → ngcc-beta
        assertThat(envs.get(0)).isEqualTo("default");
        assertThat(envs.get(1)).isEqualTo("local");
        assertThat(envs.get(2)).isEqualTo("dev");
        assertThat(envs.get(3)).isEqualTo("alpha");
        assertThat(envs.get(4)).isEqualTo("beta");
        assertThat(envs.get(5)).isEqualTo("beta-dr");

        // beta-dr comes after beta (variant of beta group)
        assertThat(envs.indexOf("beta-dr")).isGreaterThan(envs.indexOf("beta"));
        // gov-beta comes after gov
        assertThat(envs.indexOf("gov-beta")).isGreaterThan(envs.indexOf("gov"));
        // ncgn-real, ngcc-beta at end (region variants)
        assertThat(envs.indexOf("ncgn-real")).isGreaterThan(envs.indexOf("gov-beta"));
    }

    @Test
    void envComparator_unknownProfilesAtEnd() {
        var envs = Arrays.asList("custom-env", "default", "beta", "zzz");
        envs.sort(Environment.ENV_COMPARATOR);

        assertThat(envs.get(0)).isEqualTo("default");
        assertThat(envs.get(1)).isEqualTo("beta");
        // unknown at end, alphabetical
        assertThat(envs.indexOf("custom-env")).isGreaterThan(envs.indexOf("beta"));
        assertThat(envs.indexOf("zzz")).isGreaterThan(envs.indexOf("custom-env"));
    }
}
