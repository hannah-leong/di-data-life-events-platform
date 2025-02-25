output "queue_name" {
  value = aws_sqs_queue.queue.name
}

output "dead_letter_queue_name" {
  value = aws_sqs_queue.dead_letter_queue.name
}

output "queue_arn" {
  value = aws_sqs_queue.queue.arn
}

output "queue_id" {
  value = aws_sqs_queue.queue.id
}

output "dead_letter_queue_arn" {
  value = aws_sqs_queue.dead_letter_queue.arn
}

output "queue_kms_key_arn" {
  value = aws_kms_key.sqs_key.arn
}

output "queue_kms_key_id" {
  value = aws_kms_key.sqs_key.id
}

output "dead_letter_queue_kms_key_arn" {
  value = aws_kms_key.dead_letter_queue_kms_key.arn
}
