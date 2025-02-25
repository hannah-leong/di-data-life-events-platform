package uk.gov.gdx.datashare.queue

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import uk.gov.gdx.datashare.config.SqsTracingInterceptor
import java.net.URI

class AmazonSqsFactory(private val tracingEnabled: Boolean = false) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun awsSqsClient(queueId: String, queueName: String, region: String): SqsClient =
    awsAmazonSQS(region)
      .also { log.info("Created an AWS SQS client for queueId $queueId with name $queueName") }

  fun localStackSqsClient(queueId: String, queueName: String, localstackUrl: String, region: String): SqsClient =
    localStackAmazonSQS(localstackUrl, region)
      .also { log.info("Created a LocalStack SQS client for queueId $queueId with name $queueName") }

  fun awsSqsDlqClient(queueId: String, dlqName: String, region: String): SqsClient =
    awsAmazonSQS(region)
      .also { log.info("Created an AWS SQS DLQ client for queueId $queueId with name $dlqName") }

  fun localStackSqsDlqClient(queueId: String, dlqName: String, localstackUrl: String, region: String): SqsClient =
    localStackAmazonSQS(localstackUrl, region)
      .also { log.info("Created a LocalStack SQS DLQ client for queueId $queueId with name $dlqName") }

  private fun awsAmazonSQS(region: String): SqsClient {
    val builder = SqsClient.builder().region(Region.of(region))
    if (tracingEnabled) {
      builder.overrideConfiguration(
        ClientOverrideConfiguration.builder().addExecutionInterceptor(SqsTracingInterceptor()).build(),
      )
    }
    return builder.build()
  }

  private fun localStackAmazonSQS(localstackUrl: String, region: String): SqsClient {
    val builder = SqsClient.builder()
      .endpointOverride(URI.create(localstackUrl))
      .region(Region.of(region))
      .credentialsProvider(AnonymousCredentialsProvider.create())
    if (tracingEnabled) {
      builder.overrideConfiguration(
        ClientOverrideConfiguration.builder().addExecutionInterceptor(SqsTracingInterceptor()).build(),
      )
    }
    return builder.build()
  }
}
