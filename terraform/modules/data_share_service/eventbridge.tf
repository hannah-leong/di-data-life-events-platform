resource "aws_cloudwatch_event_rule" "tunnel_notification" {
  count = var.database_tunnel_alerts_enabled ? 1 : 0

  name        = "${var.environment}-bastion-tunnel-notification"
  description = "Send an SNS notification when a tunnel is initiated to the bastion host"

  event_pattern = jsonencode({
    "source" : ["aws.ssm"],
    "detail-type" : ["AWS API Call via CloudTrail"],
    "detail" : {
      "eventSource" : ["ssm.amazonaws.com"],
      "eventName" : ["StartSession"]
    }
  })
}

resource "aws_cloudwatch_event_target" "tunnel_notification" {
  count = var.database_tunnel_alerts_enabled ? 1 : 0

  rule      = aws_cloudwatch_event_rule.tunnel_notification[0].name
  target_id = "SendToSNS"
  arn       = module.sns.topic_arn
}
