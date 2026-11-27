package com.psar.common.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.net.URI;

@Slf4j
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                // MinIO serves buckets as http://host:9000/{bucket}, not {bucket}.host
                .forcePathStyle(true)
                // MinIO / R2 don't support every default checksum of newer SDKs; only send them when required
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    /**
     * Dev only ({@code app.storage.create-bucket=true}): creates the bucket with anonymous read access,
     * so image URLs work in the browser. Never fails startup: without MinIO the app still runs, only uploads fail.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.storage", name = "create-bucket", havingValue = "true")
    public ApplicationRunner storageBucketInitializer(S3Client s3, StorageProperties properties) {
        return args -> {
            String bucket = properties.bucket();
            try {
                try {
                    s3.headBucket(b -> b.bucket(bucket));
                    return;
                } catch (NoSuchBucketException missing) {
                    // create it below
                }
                s3.createBucket(b -> b.bucket(bucket));
                s3.putBucketPolicy(b -> b.bucket(bucket).policy(publicReadPolicy(bucket)));
                log.info("Created storage bucket '{}' with public read access", bucket);
            } catch (RuntimeException ex) {
                log.warn("Could not check or create storage bucket '{}': {}", bucket, ex.getMessage());
            }
        };
    }

    private static String publicReadPolicy(String bucket) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},\
                "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}""".formatted(bucket);
    }
}
