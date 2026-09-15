package com.platform.entitlements.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;

/**
 * @EnableAsync backs MeteringEventPublishListener's @Async method.
 * @EnableScheduling backs MeteringPublishReconciler's @Scheduled sweep.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AwsConfig {

    @Bean
    public EventBridgeClient eventBridgeClient(@Value("${aws.region}") String region) {
        // Credentials resolved via the default provider chain (IAM role in
        // real deployments, env vars / ~/.aws/credentials locally) — never
        // hardcode keys here.
        return EventBridgeClient.builder()
                .region(Region.of(region))
                .build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(@Value("${aws.region}") String region) {
        DynamoDbClient lowLevelClient = DynamoDbClient.builder()
                .region(Region.of(region))
                .build();
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(lowLevelClient)
                .build();
    }
}
