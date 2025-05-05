package com.uniswap.predictor.config;

import jakarta.annotation.PostConstruct;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.memory.conf.WorkspaceConfiguration;
import org.nd4j.linalg.api.memory.enums.AllocationPolicy;
import org.nd4j.linalg.api.memory.enums.LearningPolicy;
import org.nd4j.linalg.api.memory.enums.MirroringPolicy;
import org.nd4j.linalg.api.memory.enums.SpillPolicy;
import org.nd4j.linalg.api.ops.executioner.OpExecutioner;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Dl4jConfig {
    @PostConstruct
    public void configure() {
        // Use half precision for faster training
        Nd4j.setDefaultDataTypes(DataType.HALF, DataType.HALF);

        // Enable optimization
        Nd4j.getExecutioner().setProfilingMode(OpExecutioner.ProfilingMode.SCOPE_PANIC);

        // Set memory workspace
        WorkspaceConfiguration wsConfig = WorkspaceConfiguration.builder()
                .initialSize(10000000)
                .overallocationLimit(0.3)
                .policyAllocation(AllocationPolicy.OVERALLOCATE)
                .policyLearning(LearningPolicy.FIRST_LOOP)
                .policyMirroring(MirroringPolicy.FULL)
                .policySpill(SpillPolicy.EXTERNAL)
                .build();

        Nd4j.getWorkspaceManager().setDefaultWorkspaceConfiguration(wsConfig);
    }
}
