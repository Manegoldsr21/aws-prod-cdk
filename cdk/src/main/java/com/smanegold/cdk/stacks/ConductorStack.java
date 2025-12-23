package com.smanegold.cdk.stacks;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.patterns.*;
import software.amazon.awscdk.services.rds.*;
import software.constructs.Construct;
import software.amazon.awscdk.services.opensearchservice.*;
import software.amazon.awscdk.services.opensearchservice.EngineVersion;
import software.amazon.awscdk.services.logs.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConductorStack extends Stack {
    
    public static String ecsClusterName = "ConductorCluster";
    public static String ecsServiceName = "ConductorService";
    public static String rdsInstanceName = "ConductorDb";

    public ConductorStack(final Construct scope, final String id) {
        super(scope, id, null);
    }

    public ConductorStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // -----------------------
        // VPC (1 AZ, No NAT)
        // -----------------------
        Vpc vpc = Vpc.Builder.create(this, "ConductorVpc")
                .maxAzs(1)
                .natGateways(0)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .name("public")
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                .build()
                ))
                .build();

        // -----------------------
        // ECS Cluster
        // -----------------------
        Cluster cluster = Cluster.Builder.create(this, ConductorStack.ecsClusterName)
                .vpc(vpc)
                .build();

        // -----------------------
        // RDS PostgreSQL
        // -----------------------
        software.amazon.awscdk.services.secretsmanager.Secret dbPasswordSecret = software.amazon.awscdk.services.secretsmanager.Secret.Builder.create(this, "ConductorDBPassword")
                .secretName("conductor-db-password")
                .generateSecretString(software.amazon.awscdk.services.secretsmanager.SecretStringGenerator.builder()
                    .secretStringTemplate("{\"username\":\"conductor\"}")
                    .generateStringKey("password")
                    .excludePunctuation(true)
                    .passwordLength(32)
                    .build())
                .build();


        DatabaseInstance db = DatabaseInstance.Builder.create(this, ConductorStack.rdsInstanceName)
                .engine(DatabaseInstanceEngine.postgres(
                        PostgresInstanceEngineProps.builder()
                                .version(PostgresEngineVersion.VER_15)
                                .build()))
                .instanceType(InstanceType.of(
                        InstanceClass.T4G,
                        InstanceSize.MICRO))
                .allocatedStorage(20)
                //.maxAllocatedStorage(20)
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)
                        .build())
                .credentials(Credentials.fromSecret(dbPasswordSecret))
                .multiAz(false)
                .publiclyAccessible(true)
                .backupRetention(Duration.days(1))
                .deletionProtection(false)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // -----------------------
        // OpenSearch (Single Node)
        // -----------------------
        Domain search = Domain.Builder.create(this, "ConductorSearch")
                .version(EngineVersion.OPENSEARCH_2_11)
                .capacity(CapacityConfig.builder()
                        .dataNodes(1)
                        .dataNodeInstanceType("t3.small.search")
                        .multiAzWithStandbyEnabled(false)
                        .build())
                .ebs(EbsOptions.builder()
                        .volumeSize(10)
                        .build())
                .zoneAwareness(ZoneAwarenessConfig.builder()
                        .enabled(false)
                        .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // -----------------------
        // Fargate Task Definition
        // -----------------------
        FargateTaskDefinition taskDef =
                FargateTaskDefinition.Builder.create(this, "ConductorTask")
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .build();

        LogGroup logGroup = LogGroup.Builder.create(this, "ConductorLogs")
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Map<String, Secret> secrets = new HashMap<>();
        secrets.put("spring.datasource.password", 
            software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(
                dbPasswordSecret, "password"));

        Map<String, String> environment = new HashMap<>();
        environment.put("spring.datasource.url", 
            String.format("jdbc:postgresql://%s:5432/conductor", db.getDbInstanceEndpointAddress()));
        environment.put("spring.datasource.username", "conductor");
        environment.put("conductor.db.type", "postgres");
        environment.put("conductor.elasticsearch.url", 
            String.format("https://%s:443", search.getDomainEndpoint()));
        environment.put("conductor.elasticsearch.version", "7");
        environment.put("conductor.elasticsearch.indexPrefix", "conductor");
        environment.put("conductor.indexing.enabled", "true");
        environment.put("conductor.queue.type", "redis_standalone");
        environment.put("conductor.redis.hosts", "redis://localhost:6379");
        environment.put("JAVA_OPTS", "-Xmx2048m");

        taskDef.addContainer("ConductorContainer",
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromRegistry("netflixoss/conductor-server:latest"))
                        .logging(LogDriver.awsLogs(
                                AwsLogDriverProps.builder()
                                        .logGroup(logGroup)
                                        .streamPrefix("conductor")
                                        .build()))
                        .portMappings(List.of(
                                PortMapping.builder()
                                        .containerPort(8080)
                                        .build()
                        ))
                        // .environment(Map.of(
                        //         "DB", "postgres",
                        //         "DB_HOST", db.getDbInstanceEndpointAddress(),
                        //         "DB_NAME", "conductor",
                        //         "DB_USER", "conductor",
                        //         "INDEXING_ENABLED", "true",
                        //         "INDEXING_BACKEND", "opensearch",
                        //         "OPENSEARCH_URL", "https://" + search.getDomainEndpoint()
                        // ))
                        .environment(environment)
                        .secrets(secrets)
                        .build()
        );

        // -----------------------
        // ECS Fargate Service + ALB
        // -----------------------
        ApplicationLoadBalancedFargateService service =
                ApplicationLoadBalancedFargateService.Builder
                        .create(this, ConductorStack.ecsServiceName)
                        .cluster(cluster)
                        .taskDefinition(taskDef)
                        .desiredCount(1)
                        .publicLoadBalancer(true)
                        .build();

        // -----------------------
        // Outputs
        // -----------------------
        new CfnOutput(this, "ConductorUrl",
                CfnOutputProps.builder()
                        .value("http://" +
                                service.getLoadBalancer()
                                        .getLoadBalancerDnsName())
                        .build());
    }
}
