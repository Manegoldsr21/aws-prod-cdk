package com.smanegold.cdk.stacks;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.patterns.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnTargetGroup;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.logs.*;
import software.amazon.awscdk.services.opensearchservice.*;
import software.amazon.awscdk.services.opensearchservice.EngineVersion;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;
import software.constructs.Construct;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConductorStack extends Stack {
    public static String ecsClusterName = "ConductorCluster";
    public static String ecsServiceName = "ConductorService";
    public static String rdsInstanceName = "ConductorDb";
    public ConductorStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // 1. VPC: Public Subnets (No NAT Gateways to save cost)
        Vpc vpc = Vpc.Builder.create(this, "ConductorVpc")
                .maxAzs(2)
                .natGateways(0)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .name("public")
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                .build()
                ))
                .build();

        // 2. DATABASE: RDS PostgreSQL
        Secret dbPasswordSecret = Secret.Builder.create(this, "ConductorDBPassword")
                .secretName("conductor-db-password")
                .generateSecretString(SecretStringGenerator.builder()
                        .secretStringTemplate("{\"username\":\"conductor\"}")
                        .generateStringKey("password")
                        .passwordLength(32)
                        .excludePunctuation(true)
                        .build())
                .build();

        DatabaseInstance db = DatabaseInstance.Builder.create(this, ConductorStack.rdsInstanceName)
                .engine(DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder()
                        .version(PostgresEngineVersion.VER_15).build()))
                .instanceType(InstanceType.of(InstanceClass.T4G, InstanceSize.MICRO))
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
                .credentials(Credentials.fromSecret(dbPasswordSecret))
                .publiclyAccessible(true) 
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // 3. SEARCH: OpenSearch (Single Node)
        String myPublicIp = "65.31.164.160"; 
        Domain search = Domain.Builder.create(this, "ConductorSearch")
                .version(EngineVersion.OPENSEARCH_2_11)
                .capacity(CapacityConfig.builder()
                        .dataNodes(1)
                        .dataNodeInstanceType("t3.small.search")
                        .multiAzWithStandbyEnabled(false)
                        .build())
                .ebs(EbsOptions.builder().volumeSize(10).build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
        
        // Allow your local IP to access the Dashboard
        search.addAccessPolicies(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .principals(List.of(new AnyPrincipal()))
                .actions(List.of("es:*"))
                .resources(List.of(search.getDomainArn() + "/*"))
                .conditions(Map.of("IpAddress", Map.of("aws:SourceIp", List.of(myPublicIp))))
                .build());

        // 4. COMPUTE: Fargate Task with Redis Sidecar
        FargateTaskDefinition taskDef = FargateTaskDefinition.Builder.create(this, "ConductorTask")
                .cpu(1024)
                .memoryLimitMiB(2048)
                .build();

        // REDIS SIDECAR
        taskDef.addContainer("RedisSidecar", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("redis:alpine"))
                .logging(LogDriver.awsLogs(AwsLogDriverProps.builder().streamPrefix("conductor-redis").build()))
                .portMappings(List.of(PortMapping.builder().containerPort(6379).build()))
                .build());

        // MAIN CONDUCTOR CONTAINER
        Map<String, String> environment = new HashMap<>();
        environment.put("spring.datasource.url", "jdbc:postgresql://" + db.getDbInstanceEndpointAddress() + ":5432/conductor");
        environment.put("spring.datasource.username", "conductor");
        environment.put("conductor.db.type", "postgres");
        
        // OpenSearch Config
        environment.put("conductor.indexing.enabled", "true");
        environment.put("conductor.indexing.type", "opensearch"); 
        environment.put("conductor.elasticsearch.url", "https://" + search.getDomainEndpoint());
        environment.put("conductor.elasticsearch.clusterHealthColor", "yellow"); // Important for single-node
        
        // Redis Config
        environment.put("conductor.queue.type", "redis_standalone");
        environment.put("conductor.redis.hosts", "localhost:6379:us-east-1");
        
        environment.put("JAVA_OPTS", "-Xmx1536m"); 

        taskDef.addContainer("ConductorContainer", ContainerDefinitionOptions.builder()
                // FIX: Use the 'community' image tag for public access
                .image(ContainerImage.fromRegistry("orkesio/orkes-conductor-community:latest"))
                .environment(environment)
                .secrets(Map.of("spring.datasource.password", software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(dbPasswordSecret, "password")))
                .portMappings(List.of(
                        PortMapping.builder().containerPort(8080).build(), // API
                        PortMapping.builder().containerPort(8127).build()  // Modern UI
                ))
                .logging(LogDriver.awsLogs(AwsLogDriverProps.builder().streamPrefix("conductor-app").build()))
                .build());

        search.grantReadWrite(taskDef.getTaskRole());

        // 5. SERVICE: Load Balanced Fargate
        ApplicationLoadBalancedFargateService service = ApplicationLoadBalancedFargateService.Builder.create(this, "ConductorService")
                .cluster(Cluster.Builder.create(this, "ConductorCluster").vpc(vpc).build())
                .taskDefinition(taskDef)
                .publicLoadBalancer(true)
                .assignPublicIp(true) 
                .listenerPort(80) 
                .build();

        // Direct the default ALB traffic to the UI port (8127)
        service.getTargetGroup().configureHealthCheck(software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck.builder()
                .path("/health")
                .port("8080") // Check API health to decide if UI is ready
                .build());

        // Update target group to point to 8127 (UI)
        ((CfnTargetGroup) service.getTargetGroup().getNode().getDefaultChild()).setPort(8127);

        // Open API port on the Load Balancer security group
        service.getLoadBalancer().getConnections().allowFromAnyIpv4(Port.tcp(8080), "Allow API access");
        
        // Allow ECS to reach RDS
        db.getConnections().allowDefaultPortFrom(service.getService());

        // 6. OUTPUTS
        new CfnOutput(this, "ConductorUI", CfnOutputProps.builder()
                .value("http://" + service.getLoadBalancer().getLoadBalancerDnsName()).build());
        
        new CfnOutput(this, "ConductorAPI", CfnOutputProps.builder()
                .value("http://" + service.getLoadBalancer().getLoadBalancerDnsName() + ":8080").build());

    }
}