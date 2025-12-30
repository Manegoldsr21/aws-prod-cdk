package com.smanegold.cdk.stacks;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.patterns.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
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

        // 1. VPC
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

        // 2. DATABASE
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
                .databaseName("conductor")
                .build();

        // 3. SEARCH
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

        search.addAccessPolicies(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .principals(List.of(new AnyPrincipal()))
                .actions(List.of("es:*"))
                .resources(List.of(search.getDomainArn() + "/*"))
                .conditions(Map.of("IpAddress", Map.of("aws:SourceIp", List.of(myPublicIp))))
                .build());


        // A. Create Service Security Group explicitly
        SecurityGroup serviceSg = SecurityGroup.Builder.create(this, "ConductorServiceSG")
                .vpc(vpc)
                .description("Security Group for Conductor Fargate Service")
                .allowAllOutbound(true)
                .build();

        // B. Create Load Balancer Security Group explicitly
        SecurityGroup lbSg = SecurityGroup.Builder.create(this, "ConductorLBSG")
                .vpc(vpc)
                .description("Security Group for Conductor ALB")
                .allowAllOutbound(true)
                .build();

        // Allow public access to LB on ports 80 and 8080
        lbSg.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "Allow HTTP traffic");
        lbSg.addIngressRule(Peer.anyIpv4(), Port.tcp(8080), "Allow API traffic");

        // C. Manually connect the Service and LB (The Magic Fix)
        // Allow LB to reach Service on UI port
        serviceSg.addIngressRule(lbSg, Port.tcp(5000), "Allow UI traffic from ALB");
        // Allow LB to reach Service on API port
        serviceSg.addIngressRule(lbSg, Port.tcp(8080), "Allow API traffic from ALB");

        // D. Create the ALB Manually
        ApplicationLoadBalancer alb = ApplicationLoadBalancer.Builder.create(this, "ConductorALB")
                .vpc(vpc)
                .internetFacing(true)
                .securityGroup(lbSg) // Pass the explicit SG
                .build();

        // 4. COMPUTE
        FargateTaskDefinition taskDef = FargateTaskDefinition.Builder.create(this, "ConductorTask")
                .cpu(1024)
                .memoryLimitMiB(2048)
                .build();

        taskDef.addContainer("RedisSidecar", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("redis:alpine"))
                .logging(LogDriver.awsLogs(AwsLogDriverProps.builder().streamPrefix("conductor-redis").build()))
                .portMappings(List.of(PortMapping.builder().containerPort(6379).build()))
                .build());

        Map<String, String> environment = new HashMap<>();
        environment.put("conductor.db.type", "redis_standalone");
        environment.put("spring.datasource.url", "jdbc:postgresql://" + db.getDbInstanceEndpointAddress() + ":5432/conductor");
        environment.put("spring.datasource.username", "conductor");
        environment.put("conductor.queue.type", "redis_standalone");
        environment.put("conductor.redis.hosts", "localhost:6379:us-east-1");
        environment.put("conductor.app.workflowExecutionLockEnabled", "true");
        environment.put("conductor.workflow-execution-lock.type", "redis");
        environment.put("conductor.redis-lock.serverAddress", "redis://localhost:6379");
        environment.put("conductor.indexing.enabled", "true");
        environment.put("conductor.indexing.type", "opensearch");
        environment.put("conductor.elasticsearch.url", "https://" + search.getDomainEndpoint());
        environment.put("conductor.elasticsearch.clusterHealthColor", "yellow");
        environment.put("conductor.elasticsearch.indexReplicas", "0");
        environment.put("JAVA_OPTS", "-Xmx1536m");

        ContainerDefinition conductorContainer = taskDef.addContainer("ConductorContainer", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("orkesio/orkes-conductor-community:latest"))
                .environment(environment)
                .secrets(Map.of("spring.datasource.password", software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(dbPasswordSecret, "password")))
                .portMappings(List.of(
                        PortMapping.builder().containerPort(5000).name("ui-port").build(),
                        PortMapping.builder().containerPort(8080).name("api-port").build()
                ))
                .logging(LogDriver.awsLogs(AwsLogDriverProps.builder().streamPrefix("conductor-app").build()))
                .build());

        taskDef.setDefaultContainer(conductorContainer);

        search.grantReadWrite(taskDef.getTaskRole());

        // 5. SERVICE: Load Balanced Fargate (Updated to use manual resources)
        ApplicationLoadBalancedFargateService service = ApplicationLoadBalancedFargateService.Builder.create(this, "ConductorService")
                .cluster(Cluster.Builder.create(this, "ConductorCluster").vpc(vpc).build())
                .taskDefinition(taskDef)
                .loadBalancer(alb)
                .securityGroups(List.of(serviceSg))
                .assignPublicIp(true)
                .listenerPort(80)
                .targetProtocol(ApplicationProtocol.HTTP)
                // Disable "openListener" because we manage the LB SG manually above
                .openListener(false)
                .build();

        service.getTargetGroup().configureHealthCheck(software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck.builder()
                .path("/health")
                .port("8080")
                .interval(Duration.seconds(60))
                .timeout(Duration.seconds(5))
                .healthyThresholdCount(2)
                .build());

        // 2nd Listener for API
        ApplicationListener apiListener = service.getLoadBalancer().addListener("ApiListener", BaseApplicationListenerProps.builder()
                .port(8080)
                .protocol(ApplicationProtocol.HTTP)
                .open(false) // Set to false because we already allowed 8080 in the LB SG manually
                .build());

        apiListener.addTargets("ApiTarget", AddApplicationTargetsProps.builder()
                .port(8080)
                .targets(List.of(service.getService().loadBalancerTarget(LoadBalancerTargetOptions.builder()
                        .containerName("ConductorContainer")
                        .containerPort(8080)
                        .build())))
                .healthCheck(software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck.builder()
                        .path("/health")
                        .port("8080")
                        .build())
                .build());

        // Fix the DB access using the explicit SG
        db.getConnections().allowDefaultPortFrom(serviceSg);

        // 6. OUTPUTS
        new CfnOutput(this, "ConductorUI", CfnOutputProps.builder()
                .value("http://" + service.getLoadBalancer().getLoadBalancerDnsName()).build());

        new CfnOutput(this, "ConductorAPI", CfnOutputProps.builder()
                .value("http://" + service.getLoadBalancer().getLoadBalancerDnsName() + ":8080").build());
    }
}