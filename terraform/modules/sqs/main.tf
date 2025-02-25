data "aws_caller_identity" "current" {}

resource "aws_kms_key" "sqs_key" {
  enable_key_rotation = true
  description         = "Key used to encrypt sqs queue for ${var.queue_name}"
}

resource "aws_kms_alias" "sqs_key_alias" {
  name          = "alias/${var.environment}/sqs-queue-${var.queue_name}"
  target_key_id = aws_kms_key.sqs_key.arn
}

resource "aws_sqs_queue" "queue" {
  name                      = var.queue_name
  kms_master_key_id         = aws_kms_key.sqs_key.arn
  message_retention_seconds = var.message_retention_seconds
}

resource "aws_kms_key" "dead_letter_queue_kms_key" {
  enable_key_rotation = true
  description         = "Key used to encrypt DLQ for ${var.queue_name}"
}

resource "aws_kms_alias" "dead_letter_queue_kms_key_alias" {
  name          = "alias/${var.environment}/sqs-dead-letter-queue-${var.queue_name}"
  target_key_id = aws_kms_key.dead_letter_queue_kms_key.arn
}

resource "aws_sqs_queue" "dead_letter_queue" {
  name              = "${var.queue_name}-dlq"
  kms_master_key_id = aws_kms_key.dead_letter_queue_kms_key.arn
  message_retention_seconds = (var.dlq_message_retention_seconds != null ?
    var.dlq_message_retention_seconds :
    min(2 * var.message_retention_seconds, 1209600) # cap at 14 days
  )
}

resource "aws_sqs_queue_redrive_policy" "queue" {
  queue_url = aws_sqs_queue.queue.id
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letter_queue.arn
    maxReceiveCount     = 4
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "dead_letter_queue" {
  queue_url = aws_sqs_queue.dead_letter_queue.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue",
    sourceQueueArns   = [aws_sqs_queue.queue.arn]
  })
}

data "aws_iam_policy_document" "default_queue_policy" {
  statement {
    sid     = "httpsonly"
    actions = ["sqs:*"]
    effect  = "Deny"
    condition {
      test     = "Bool"
      values   = ["false"]
      variable = "aws:SecureTransport"
    }
  }
}

resource "aws_sqs_queue_policy" "queue_policy" {
  policy    = coalesce(var.queue_policy, data.aws_iam_policy_document.default_queue_policy.json)
  queue_url = aws_sqs_queue.queue.id
}

resource "aws_sqs_queue_policy" "dlq_policy" {
  policy    = data.aws_iam_policy_document.default_queue_policy.json
  queue_url = aws_sqs_queue.dead_letter_queue.id
}
