package com.resq.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.mockito.Mockito.mock;

/** Explicitly selected integration tests only; connection guards run before Boot creates clients. */
@Tag("integration")
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.data.redis.port=16317")
@Import(IsolatedSpringIntegrationTest.NoBackgroundJobs.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IsolatedSpringIntegrationTest {
    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) throws Exception {
        IsolatedTestEnvironment.register(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NoBackgroundJobs {
        // Register scheduled jobs without executing unrelated expiry/eviction jobs during assertions.
        @Bean TaskScheduler taskScheduler() { return mock(TaskScheduler.class); }
    }
}
