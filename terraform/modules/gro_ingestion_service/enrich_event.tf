resource "aws_iam_role" "enrich_event_lambda" {
  name               = "${var.environment}-gro-ingestion-lambda-function-enrich-event"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_policy.json
}

data "aws_iam_policy_document" "enrich_event_lambda" {
  statement {
    sid = "LambdaKMSAccess"
    actions = [
      "kms:Decrypt",
      "kms:Encrypt",
      "kms:GenerateDataKey",
      "kms:GenerateDataKeyPair",
      "kms:ReEncryptFrom",
      "kms:ReEncryptTo"
    ]
    effect = "Allow"
    resources = [
      aws_kms_key.gro_ingestion.arn
    ]
  }

  statement {
    sid = "DynamoDBGetItemAccess"
    actions = [
      "dynamodb:GetItem"
    ]
    resources = [
      aws_dynamodb_table.gro_ingestion.arn
    ]
  }
}

resource "aws_iam_policy" "enrich_event_lambda" {
  name   = "${var.environment}-gro-ingestion-lambda-function-enrich-event"
  policy = data.aws_iam_policy_document.enrich_event_lambda.json
}

resource "aws_iam_role_policy_attachment" "enrich_event_lambda" {
  policy_arn = aws_iam_policy.enrich_event_lambda.arn
  role       = aws_iam_role.enrich_event_lambda.name
}

resource "aws_iam_role_policy_attachment" "enrich_event_lambda_xray_access" {
  policy_arn = data.aws_iam_policy.xray_access.arn
  role       = aws_iam_role.enrich_event_lambda.name
}

resource "aws_iam_role_policy_attachment" "enrich_event_lambda_logs_access" {
  policy_arn = aws_iam_policy.log_policy.arn
  role       = aws_iam_role.enrich_event_lambda.name
}

resource "aws_cloudwatch_log_group" "enrich_event_log" {
  name              = "/aws/lambda/${aws_lambda_function.enrich_event_lambda.function_name}"
  retention_in_days = var.cloudwatch_retention_period

  kms_key_id = aws_kms_key.gro_ingestion.arn
}

resource "aws_lambda_function" "enrich_event_lambda" {
  filename      = data.archive_file.lambda_function_source.output_path
  function_name = "${var.environment}-gro-ingestion-lambda-function-enrich-event"

  handler       = "index.handler"
  runtime       = local.lambda_runtime
  role          = aws_iam_role.enrich_event_lambda.arn
  timeout       = 10
  architectures = ["arm64"]

  source_code_hash = data.archive_file.lambda_function_source.output_sha

  environment {
    variables = {
      "FUNCTION_NAME" = "enrichEvent"
      "TABLE_NAME"    = aws_dynamodb_table.gro_ingestion.name
    }
  }
  tracing_config {
    mode = "Active"
  }
}
