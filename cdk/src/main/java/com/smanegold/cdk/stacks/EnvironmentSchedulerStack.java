package com.smanegold.cdk.stacks;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.events.*;
import software.amazon.awscdk.services.events.targets.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class EnvironmentSchedulerStack extends Stack {

    public EnvironmentSchedulerStack(
            final Construct scope,
            final String id,
            final StackProps props,
            final String ecsClusterName,
            final String ecsServiceName,
            final String dbInstanceIdentifier
    ) {
        super(scope, id, props);

        // -----------------------
        // Lambda Function
        // -----------------------
        Function scheduler = Function.Builder.create(this, "EnvScheduler")
                .runtime(Runtime.JAVA_21)
                .handler("com.smanegold.lambda.EnvironmentScheduler::handleRequest")
                .code(Code.fromAsset("lambda/target/scheduler-lambda-1.0.0.jar"))
                .timeout(Duration.seconds(60))
                .memorySize(512)
                .environment(Map.of(
                        "ECS_CLUSTER", ecsClusterName,
                        "ECS_SERVICE", ecsServiceName,
                        "DB_INSTANCE", dbInstanceIdentifier
                ))
                .build();

        // -----------------------
        // IAM Permissions
        // -----------------------
        scheduler.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "ecs:UpdateService",
                        "rds:StartDBInstance",
                        "rds:StopDBInstance"
                ))
                .resources(List.of("*"))
                .build());

        // -----------------------
        // Stop Rule (10 PM local = 03:00 UTC CST)
        // -----------------------
        Rule stopRule = Rule.Builder.create(this, "StopAtNight")
                .schedule(Schedule.cron(
                        CronOptions.builder()
                                .minute("0")
                                .hour("3")
                                .build()))
                .build();

        stopRule.addTarget(new LambdaFunction(scheduler,
                LambdaFunctionProps.builder()
                        .event(RuleTargetInput.fromObject(Map.of("action", "stop")))
                        .build()));
 
        // -----------------------
        // Start Rule (7 AM local = 12:00 UTC CST)
        // -----------------------
        Rule startRule = Rule.Builder.create(this, "StartInMorning")
                .schedule(Schedule.cron(
                        CronOptions.builder()
                                .minute("0")
                                .hour("12")
                                .build()))
                .build();

        startRule.addTarget(new LambdaFunction(scheduler,
                LambdaFunctionProps.builder()
                        .event(RuleTargetInput.fromObject(Map.of("action", "start")))
                        .build()));
    }
}
