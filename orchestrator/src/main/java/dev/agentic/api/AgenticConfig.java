package dev.agentic.api;

import dev.agentic.ai.SpringAiLlmGateway;
import dev.agentic.core.agent.LlmGateway;
import dev.agentic.core.agent.MockLlmGateway;
import dev.agentic.core.engine.EngineConfig;
import dev.agentic.core.engine.OrchestratorEngine;
import dev.agentic.core.events.EventStore;
import dev.agentic.core.events.JsonlEventStore;
import dev.agentic.core.policy.PolicyEngine;
import dev.agentic.core.validation.*;
import dev.agentic.core.workflow.Workflows;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Path;
import java.time.Duration;

@Configuration
class AgenticConfig {

    /** Default: deterministic offline mock. Set agentic.llm=spring-ai to use the configured Spring AI chat model. */
    @Bean
    @ConditionalOnProperty(name = "agentic.llm", havingValue = "mock", matchIfMissing = true)
    LlmGateway mockGateway() { return new MockLlmGateway(); }

    @Bean
    @ConditionalOnProperty(name = "agentic.llm", havingValue = "spring-ai")
    LlmGateway springAiGateway(ChatClient.Builder builder) { return new SpringAiLlmGateway(builder.build()); }

    @Bean
    EventStore eventStore(@Value("${agentic.runs-dir:runs}") String dir, MeterRegistry meters) {
        EventStore store = new JsonlEventStore(Path.of(dir));
        store.addListener(e -> meters.counter("agentic.events", "type", e.type().name()).increment());   // success/retry/rollback rates in Prometheus
        return store;
    }

    /** simulated (default, labelled as such in every report) or real (allowlisted mvn commands in a temp workspace). */
    @Bean
    BuildValidationAgent buildValidator(@Value("${agentic.validation:simulated}") String mode,
                                        @Value("${agentic.baseline-dir:}") String baselineDir) {
        CommandRunner runner = "real".equalsIgnoreCase(mode)
            ? new AllowlistedCommandRunner(AllowlistedCommandRunner.mavenDefaults()) : new SimulatedCommandRunner();
        Path baseline = baselineDir.isBlank() ? null : Path.of(baselineDir);
        return new BuildValidationAgent(runner, baseline, AllowlistedCommandRunner.mavenDefaults(), Duration.ofMinutes(5));
    }

    @Bean
    OrchestratorEngine engine(LlmGateway gateway, EventStore store, BuildValidationAgent validator,
                              @Value("${agentic.agent-timeout-seconds:120}") long timeoutSeconds,
                              @Value("${agentic.attempt-budget:100}") int budget) {
        EngineConfig cfg = new EngineConfig(Duration.ofSeconds(timeoutSeconds), 500, 3, 3, budget);
        return new OrchestratorEngine(Workflows.agents(gateway, validator), PolicyEngine.defaults(), store, cfg);
    }
}
