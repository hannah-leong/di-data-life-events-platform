package uk.gov.gdx.datashare.services

import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.*
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.*
import software.amazon.awssdk.services.sts.StsClient
import uk.gov.gdx.datashare.helpers.builders.AcquirerSubscriptionBuilder
import uk.gov.gdx.datashare.queue.AwsQueueFactory
import uk.gov.gdx.datashare.queue.SqsProperties
import uk.gov.gdx.datashare.repositories.AcquirerSubscriptionRepository

class OutboundEventQueueServiceTest {
  private val environment = "test"
  private val acquirerSubscriptionRepository = mockk<AcquirerSubscriptionRepository>()
  private val awsQueueFactory = mockk<AwsQueueFactory>()
  private val sqsProperties = mockk<SqsProperties>()
  private val sqsClient = mockk<SqsClient>(relaxed = true)
  private val stsClient = mockk<StsClient>()
  private val cloudWatchClient = mockk<CloudWatchClient>()
  private val kmsClient = mockk<KmsClient>()
  private val secondSqsClient = mockk<SqsClient>(relaxed = true)

  private var underTest = OutboundEventQueueService(
    acquirerSubscriptionRepository,
    awsQueueFactory,
    sqsProperties,
    environment,
    "taskRoleArn",
  )
  private val queueName = "queue"
  private val queueUrl = "queueurl"
  private val dlqUrl = "dlqurl"

  @BeforeEach
  fun setup() {
    every {
      awsQueueFactory.getOrDefaultSqsClient(
        any<String>(),
        any<SqsProperties.QueueConfig>(),
        any<SqsProperties>(),
        any<SqsClient>(),
      )
    }.returns(sqsClient).andThen(secondSqsClient)

    mockkStatic(StsClient::class)
    every { StsClient.create() } returns stsClient
    every { stsClient.callerIdentity.account() } returns "1234"
    mockkStatic(CloudWatchClient::class)
    every { CloudWatchClient.create() } returns cloudWatchClient
    mockkStatic(KmsClient::class)
    every { KmsClient.create() } returns kmsClient
  }

  @AfterEach
  fun teardown() {
    unmockkStatic(StsClient::class)
    unmockkStatic(KmsClient::class)
    unmockkStatic(SqsClient::class)
  }

  @Test
  fun `sends message`() {
    underTest.sendMessage("queueone", "message", "id")

    verify(exactly = 1) {
      awsQueueFactory.getOrDefaultSqsClient(
        "queueone",
        any<SqsProperties.QueueConfig>(),
        any<SqsProperties>(),
        any<SqsClient>(),
      )
    }
    verify(exactly = 1) {
      sqsClient.sendMessage(any<SendMessageRequest>())
    }
  }

  @Test
  fun `sends a message to a fifo queue`() {
    underTest.sendMessage("queueone.fifo", "message", "testId")

    verify(exactly = 1) {
      awsQueueFactory.getOrDefaultSqsClient(
        "queueone.fifo",
        any<SqsProperties.QueueConfig>(),
        any<SqsProperties>(),
        any<SqsClient>(),
      )
    }
    verify(exactly = 1) {
      sqsClient.sendMessage(
        withArg<SendMessageRequest> {
          assertThat(it.messageDeduplicationId()).isEqualTo("testId")
        },
      )
    }
  }

  @Test
  fun `memoizes clients`() {
    underTest.sendMessage("queueone", "message", "id")
    underTest.sendMessage("queueone", "message", "id")

    verify(exactly = 1) {
      awsQueueFactory.getOrDefaultSqsClient(
        any<String>(),
        any<SqsProperties.QueueConfig>(),
        any<SqsProperties>(),
        any<SqsClient>(),
      )
    }
    verify(exactly = 2) {
      sqsClient.sendMessage(any<SendMessageRequest>())
    }
  }

  @Test
  fun `fetches new client for every queue`() {
    underTest.sendMessage("queueone", "message", "id")
    underTest.sendMessage("queuetwo", "message", "id")

    verifySequence {
      awsQueueFactory.getOrDefaultSqsClient(
        "queueone",
        any<SqsProperties.QueueConfig>(),
        any<SqsProperties>(),
        any<SqsClient>(),
      )
      awsQueueFactory.getOrDefaultSqsClient(
        "queuetwo",
        any<SqsProperties.QueueConfig>(),
        any<SqsProperties>(),
        any<SqsClient>(),
      )
    }

    verify(exactly = 1) {
      sqsClient.sendMessage(any<SendMessageRequest>())
    }
    verify(exactly = 1) {
      secondSqsClient.sendMessage(any<SendMessageRequest>())
    }
  }

  @Test
  fun `creates a queue and a dlq`() {
    mockAws()

    underTest.createAcquirerQueue("test", "principal")

    verify(exactly = 1) {
      sqsClient.createQueue(
        withArg<CreateQueueRequest> {
          assertThat(it.queueName()).isEqualTo("test_dlq")
          assertThat(it.attributes()[QueueAttributeName.KMS_MASTER_KEY_ID]).isEqualTo("dlqKeyId")
          assertThat(it.attributes()[QueueAttributeName.MESSAGE_RETENTION_PERIOD]).isEqualTo("${8 * 24 * 60 * 60}")
          assertThat(it.attributes()[QueueAttributeName.REDRIVE_POLICY]).isNull()
          assertThat(it.attributes()[QueueAttributeName.FIFO_QUEUE]).isNull()
        },
      )
    }

    verify(exactly = 1) {
      sqsClient.createQueue(
        withArg<CreateQueueRequest> {
          assertThat(it.queueName()).isEqualTo("test")
          assertThat(it.attributes()[QueueAttributeName.KMS_MASTER_KEY_ID]).isEqualTo("keyId")
          assertThat(it.attributes()[QueueAttributeName.MESSAGE_RETENTION_PERIOD]).isEqualTo("${4 * 24 * 60 * 60}")
          assertThat(it.attributes()[QueueAttributeName.REDRIVE_POLICY]).isEqualTo("""{"maxReceiveCount": "10", "deadLetterTargetArn": "dlq:arn"}""")
          assertThat(it.attributes()[QueueAttributeName.FIFO_QUEUE]).isNull()
        },
      )
    }
  }

  @Test
  fun `creates fifo queues and dlqs`() {
    mockAws()

    underTest.createAcquirerQueue("test.fifo", "principal")

    verify(exactly = 1) {
      sqsClient.createQueue(
        withArg<CreateQueueRequest> {
          assertThat(it.queueName()).isEqualTo("test_dlq.fifo")
          assertThat(it.attributes()[QueueAttributeName.KMS_MASTER_KEY_ID]).isEqualTo("dlqKeyId")
          assertThat(it.attributes()[QueueAttributeName.MESSAGE_RETENTION_PERIOD]).isEqualTo("${8 * 24 * 60 * 60}")
          assertThat(it.attributes()[QueueAttributeName.REDRIVE_POLICY]).isNull()
          assertThat(it.attributes()[QueueAttributeName.FIFO_QUEUE]).isEqualTo("true")
        },
      )
    }

    verify(exactly = 1) {
      sqsClient.createQueue(
        withArg<CreateQueueRequest> {
          assertThat(it.queueName()).isEqualTo("test.fifo")
          assertThat(it.attributes()[QueueAttributeName.KMS_MASTER_KEY_ID]).isEqualTo("keyId")
          assertThat(it.attributes()[QueueAttributeName.MESSAGE_RETENTION_PERIOD]).isEqualTo("${4 * 24 * 60 * 60}")
          assertThat(it.attributes()[QueueAttributeName.REDRIVE_POLICY]).isEqualTo("""{"maxReceiveCount": "10", "deadLetterTargetArn": "dlq:arn"}""")
          assertThat(it.attributes()[QueueAttributeName.FIFO_QUEUE]).isEqualTo("true")
        },
      )
    }
  }

  @Test
  fun `grants the acquirer access to the queue but not the dlq`() {
    mockAws()

    underTest.createAcquirerQueue("test", "principal")

    verify(exactly = 1) {
      sqsClient.createQueue(
        withArg<CreateQueueRequest> {
          assertThat(it.queueName()).isEqualTo("test_dlq")
          assertThat(it.attributes()[QueueAttributeName.POLICY]).isEqualToIgnoringWhitespace(
            """
        {
          "Version": "2012-10-17",
          "Id": "cross-account-access",
          "Statement": [
            {
              "Sid": "httpsonly",
              "Effect": "Deny",
              "Action": "sqs:*",
              "Resource": "*",
              "Condition": {
                "Bool": {
                  "aws:SecureTransport": "false"
                }
              }
            }
          ]
        }
            """.trimIndent(),
          )
        },
      )
    }

    verify(exactly = 1) {
      sqsClient.createQueue(
        withArg<CreateQueueRequest> {
          assertThat(it.queueName()).isEqualTo("test")
          assertThat(it.attributes()[QueueAttributeName.POLICY]).isEqualToIgnoringWhitespace(
            """
        {
          "Version": "2012-10-17",
          "Id": "cross-account-access",
          "Statement": [
            {
              "Sid": "acquirerAccess",
              "Principal": {
                "AWS": "principal"
              },
              "Effect": "Allow",
              "Resource": "*",
              "Action": [
                "SQS:GetQueueUrl",
                "SQS:GetQueueAttributes",
                "SQS:ChangeMessageVisibility",
                "SQS:ReceiveMessage",
                "SQS:DeleteMessage"
              ]
            },
            {
              "Sid": "httpsonly",
              "Effect": "Deny",
              "Action": "sqs:*",
              "Resource": "*",
              "Condition": {
                "Bool": {
                  "aws:SecureTransport": "false"
                }
              }
            }
          ]
        }
            """.trimIndent(),
          )
        },
      )
    }
  }

  @Test
  fun `creates two KMS keys`() {
    mockAws()

    underTest.createAcquirerQueue("test", "principal")

    verifyOrder {
      kmsClient.createKey(
        withArg<CreateKeyRequest> {
          assertThat(it.description()).isEqualTo("Key for sqs queue test_dlq in environment test")
          assertThat(it.policy()).isEqualToIgnoringWhitespace(
            """
            {
              "Version": "2012-10-17",
              "Id": "key-default-1",
              "Statement": [
                {
                  "Sid": "Enable IAM User Permissions",
                  "Effect": "Allow",
                  "Principal": {
                    "AWS": "arn:aws:iam::1234:root"
                  },
                  "Action": "kms:*",
                  "Resource": "*"
                },
                {
                  "Sid": "Give application control",
                  "Effect": "Allow",
                  "Principal": {
                    "AWS": "taskRoleArn"
                  },
                  "Action": "kms:*",
                  "Resource": "*"
                }
              ]
            }
            """.trimIndent(),
          )
        },
      )

      kmsClient.enableKeyRotation(
        withArg<EnableKeyRotationRequest> {
          assertThat(it.keyId()).isEqualTo("dlqKeyId")
        },
      )

      kmsClient.createAlias(
        withArg<CreateAliasRequest> {
          assertThat(it.aliasName()).isEqualTo("alias/test/sqs-test_dlq")
          assertThat(it.targetKeyId()).isEqualTo("dlqKeyId")
        },
      )

      kmsClient.tagResource(
        withArg<TagResourceRequest> {
          assertThat(it.keyId()).isEqualTo("dlqKeyId")
        },
      )

      kmsClient.createKey(
        withArg<CreateKeyRequest> {
          assertThat(it.description()).isEqualTo("Key for sqs queue test in environment test")
          assertThat(it.policy()).isEqualToIgnoringWhitespace(
            """
        {
          "Version": "2012-10-17",
          "Id": "key-default-1",
          "Statement": [
            {
              "Sid": "Enable IAM User Permissions",
              "Effect": "Allow",
              "Principal": {
                "AWS": "arn:aws:iam::1234:root"
              },
              "Action": "kms:*",
              "Resource": "*"
            },
            {
              "Sid": "Give application control",
              "Effect": "Allow",
              "Principal": {
                "AWS": "taskRoleArn"
              },
              "Action": "kms:*",
              "Resource": "*"
            },
            {
              "Sid": "Acquirer access",
              "Effect": "Allow",
              "Principal": {
                "AWS": "principal"
              },
              "Action": [
                "kms:GenerateDataKey",
                "kms:Decrypt"
              ],
              "Resource": "*"
            }
          ]
        }
            """.trimIndent(),
          )
        },
      )

      kmsClient.enableKeyRotation(
        withArg<EnableKeyRotationRequest> {
          assertThat(it.keyId()).isEqualTo("keyId")
        },
      )

      kmsClient.createAlias(
        withArg<CreateAliasRequest> {
          assertThat(it.aliasName()).isEqualTo("alias/test/sqs-test")
          assertThat(it.targetKeyId()).isEqualTo("keyId")
        },
      )

      kmsClient.tagResource(
        withArg<TagResourceRequest> {
          assertThat(it.keyId()).isEqualTo("keyId")
        },
      )
    }
  }

  @Test
  fun `grants the acquirer access to the queue key but not the dlq key`() {
  }

  @Test
  fun `deleteAcquirerQueueAndDlq calls delete queues and keys`() {
    mockAws()

    underTest.deleteAcquirerQueueAndDlq(queueName)

    verify(exactly = 1) {
      awsQueueFactory.getOrDefaultSqsClient(
        queueName,
        any<SqsProperties.QueueConfig>(),
        any<SqsProperties>(),
        any<SqsClient>(),
      )
      awsQueueFactory.getOrDefaultSqsClient(
        "${queueName}_dlq",
        any<SqsProperties.QueueConfig>(),
        any<SqsProperties>(),
        any<SqsClient>(),
      )
      sqsClient.deleteQueue(
        withArg<DeleteQueueRequest> {
          assertThat(it.queueUrl()).isEqualTo(queueUrl)
        },
      )
      secondSqsClient.deleteQueue(
        withArg<DeleteQueueRequest> {
          assertThat(it.queueUrl()).isEqualTo(dlqUrl)
        },
      )
      kmsClient.scheduleKeyDeletion(
        withArg<ScheduleKeyDeletionRequest> {
          assertThat(it.keyId()).isEqualTo("queueKeyId")
        },
      )
      kmsClient.scheduleKeyDeletion(
        withArg<ScheduleKeyDeletionRequest> {
          assertThat(it.keyId()).isEqualTo("dlqKeyId")
        },
      )
    }
  }

  @Test
  fun `getMetrics returns metrics for queues`() {
    mockAws()

    val acquirerSubscriptions = listOf(
      AcquirerSubscriptionBuilder(queueName = "queueone").build(),
      AcquirerSubscriptionBuilder(queueName = "queuetwo").build(),
      AcquirerSubscriptionBuilder(queueName = "queuethree").build(),
    )

    every { acquirerSubscriptionRepository.findAllByQueueNameIsNotNullAndWhenDeletedIsNull() } returns acquirerSubscriptions
    every { cloudWatchClient.getMetricData(any<GetMetricDataRequest>()).metricDataResults() } returns listOf(
      MetricDataResult.builder().id("queueone").values(listOf(1.0)).build(),
      MetricDataResult.builder().id("queuetwo").values(listOf(2.0)).build(),
      MetricDataResult.builder().id("queuethree").values(listOf(null)).build(),
      MetricDataResult.builder().id("queueone_dlq").values(listOf(1.0)).build(),
      MetricDataResult.builder().id("queuetwo_dlq").values(listOf(null)).build(),
      MetricDataResult.builder().id("queuethree_dlq").values(listOf(3.0)).build(),
    )

    val metrics = underTest.getMetrics()

    assertThat(metrics).isEqualTo(
      mapOf(
        "queueone" to QueueMetric(ageOfOldestMessage = 1, dlqLength = 1),
        "queuetwo" to QueueMetric(ageOfOldestMessage = 2, dlqLength = null),
        "queuethree" to QueueMetric(ageOfOldestMessage = null, dlqLength = 3),
      ),
    )
  }

  private fun mockAws() {
    val createKeyResponse = mockk<CreateKeyResponse>()
    every { createKeyResponse.keyMetadata().keyId() } returns "dlqKeyId" andThen "keyId"
    every { kmsClient.createKey(any<CreateKeyRequest>()) } returns createKeyResponse
    every { kmsClient.enableKeyRotation(any<EnableKeyRotationRequest>()) } returns mockk<EnableKeyRotationResponse>()
    every { kmsClient.createAlias(any<CreateAliasRequest>()) } returns mockk<CreateAliasResponse>()
    every { kmsClient.tagResource(any<TagResourceRequest>()) } returns mockk<TagResourceResponse>()
    every { kmsClient.scheduleKeyDeletion(any<ScheduleKeyDeletionRequest>()) } returns mockk<ScheduleKeyDeletionResponse>()

    mockkStatic(SqsClient::class)
    every { SqsClient.create() } returns sqsClient
    val createQueueResponse = mockk<CreateQueueResponse>()
    every { createQueueResponse.queueUrl() } returns queueUrl
    every { sqsClient.createQueue(any<CreateQueueRequest>()) } returns createQueueResponse
    val queueAttributesResponse = mockk<GetQueueAttributesResponse>()
    every { queueAttributesResponse.attributes()[QueueAttributeName.QUEUE_ARN] } returns "dlq:arn" andThen "queue:arn"
    every { queueAttributesResponse.attributes()[QueueAttributeName.KMS_MASTER_KEY_ID] } returns "queueKeyId" andThen "dlqKeyId"
    every { sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()) } returns queueAttributesResponse
    every { sqsClient.deleteQueue(any<DeleteQueueRequest>()) } returns mockk<DeleteQueueResponse>()
    every { sqsClient.getQueueUrl(any<GetQueueUrlRequest>()).queueUrl() } returns queueUrl
    every { secondSqsClient.getQueueUrl(any<GetQueueUrlRequest>()).queueUrl() } returns dlqUrl
  }
}
