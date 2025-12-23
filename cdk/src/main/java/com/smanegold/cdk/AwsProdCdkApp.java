package com.smanegold.cdk;

import com.smanegold.cdk.stacks.ConductorStack;
import com.smanegold.cdk.stacks.EnvironmentSchedulerStack;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class AwsProdCdkApp {
    public static void main(final String[] args) {
        App app = new App();

        new ConductorStack(app, "ConductorStack", StackProps.builder()
                .env(Environment.builder()
                                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                                .region(System.getenv("CDK_DEFAULT_REGION"))
                                .build())
                .build());

        new EnvironmentSchedulerStack(app, "EnvironmentSchedulerStack", StackProps.builder()
                .env(Environment.builder()
                        .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                        .region(System.getenv("CDK_DEFAULT_REGION"))
                        .build())
                .build(),
                ConductorStack.ecsClusterName,
                ConductorStack.ecsServiceName,
                ConductorStack.rdsInstanceName
        );

        app.synth();
    }
}

