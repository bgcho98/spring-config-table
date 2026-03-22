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
    void envComparator_baseLifecycleOrder() {
        var envs = Arrays.asList("real", "alpha", "default", "beta-dr", "dev", "beta", "local", "release", "dr");
        envs.sort(Environment.ENV_COMPARATOR);
        assertThat(envs).containsExactly("default", "local", "dev", "alpha", "beta", "beta-dr", "real", "release", "dr");
    }

    @Test
    void envComparator_regionsAfterBase() {
        var envs = Arrays.asList("gov-beta", "beta", "default", "gov", "ncgn-real", "real", "beta-gov");
        envs.sort(Environment.ENV_COMPARATOR);

        // Base first: default, beta, real
        // Then gov region: beta-gov, gov-beta, gov
        // Then ncgn region: ncgn-real
        assertThat(envs.get(0)).isEqualTo("default");
        assertThat(envs.get(1)).isEqualTo("beta");
        assertThat(envs.get(2)).isEqualTo("real");
        // gov group
        assertThat(envs.indexOf("beta-gov")).isGreaterThan(envs.indexOf("real"));
        assertThat(envs.indexOf("gov-beta")).isGreaterThan(envs.indexOf("beta-gov")); // alpha tiebreaker
        assertThat(envs.indexOf("gov")).isGreaterThan(envs.indexOf("gov-beta"));
        // ncgn group
        assertThat(envs.indexOf("ncgn-real")).isGreaterThan(envs.indexOf("gov"));
    }

    @Test
    void envComparator_withinRegion_lifecycleOrder() {
        var envs = Arrays.asList("gov", "gov-real", "gov-beta", "gov-alpha");
        envs.sort(Environment.ENV_COMPARATOR);
        // alpha < beta < real < gov(plain)
        assertThat(envs).containsExactly("gov-alpha", "gov-beta", "gov-real", "gov");
    }

    @Test
    void envComparator_unknownAtEnd() {
        var envs = Arrays.asList("custom", "default", "beta", "zzz");
        envs.sort(Environment.ENV_COMPARATOR);
        assertThat(envs.get(0)).isEqualTo("default");
        assertThat(envs.get(1)).isEqualTo("beta");
        assertThat(envs.indexOf("custom")).isGreaterThan(envs.indexOf("beta"));
    }

    @Test
    void customComparator() {
        var lifecycle = List.of("dev", "staging", "prod");
        var regions = List.of("us", "eu", "ap");
        var comp = Environment.comparator(lifecycle, regions);

        var envs = Arrays.asList("eu-prod", "prod", "dev", "us-staging", "staging");
        envs.sort(comp);
        // Base: dev, staging, prod
        // us: us-staging
        // eu: eu-prod
        assertThat(envs).containsExactly("dev", "staging", "prod", "us-staging", "eu-prod");
    }
}
