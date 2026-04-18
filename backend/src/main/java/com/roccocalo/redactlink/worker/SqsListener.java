package com.roccocalo.redactlink.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roccocalo.redactlink.service.SanitizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SqsListener {

    private final SqsClient sqsClient;
    private final S3Client s3Client;
    private final SanitizationService sanitizationService;
    private final ObjectMapper objectMapper;

    @Value("${aws.sqs.queue-url}")
    private String queueUrl;

    @Value("${aws.s3.raw-bucket}")
    private String rawBucket;

    @Scheduled(fixedDelay = 100)
    public void poll() {
        if (queueUrl == null || queueUrl.isBlank()) return;

        List<Message> messages;
        try {
            messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .build()).messages();
        } catch (Exception e) {
            log.warn("SQS receive failed: {}", e.getMessage());
            return;
        }

        for (Message message : messages) {
            try {
                handleMessage(message);
                deleteMessage(message);
            } catch (Exception e) {
                // Leave on queue — SQS redelivers after visibility timeout, DLQ after maxReceiveCount
                log.error("Failed to process SQS message {}: {}", message.messageId(), e.getMessage(), e);
            }
        }
    }

    private void handleMessage(Message message) throws Exception {
        JsonNode root = objectMapper.readTree(message.body());

        // Unwrap SNS envelope if present (S3 → SNS → SQS path)
        if (root.has("Message")) {
            root = objectMapper.readTree(root.get("Message").asText());
        }

        JsonNode records = root.get("Records");
        if (records == null || !records.isArray()) return;

        for (JsonNode record : records) {
            JsonNode s3Node = record.get("s3");
            if (s3Node == null) continue;

            String bucketName = s3Node.get("bucket").get("name").asText();
            // S3 encodes special characters in the object key
            String objectKey = URLDecoder.decode(
                    s3Node.get("object").get("key").asText(), StandardCharsets.UTF_8);

            if (!rawBucket.equals(bucketName)) {
                log.warn("Skipping event for unexpected bucket: {}", bucketName);
                continue;
            }

            // Object key pattern: "{fileId}/{originalFilename}"
            String fileId = objectKey.split("/")[0];

            log.info("Received S3 event: fileId={} objectKey={}", fileId, objectKey);

            byte[] fileBytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(rawBucket)
                    .key(objectKey)
                    .build()).asByteArray();

            String contentType = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(rawBucket)
                    .key(objectKey)
                    .build()).contentType();

            sanitizationService.process(fileId, objectKey, fileBytes, contentType);
        }
    }

    private void deleteMessage(Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }
}
