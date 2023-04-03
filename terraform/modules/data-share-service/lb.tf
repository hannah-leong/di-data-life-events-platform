# We want the load balancer available to our cloudfront distribution, it is locked down from the wider internet with
# security group rules
#tfsec:ignore:aws-elb-alb-not-public
resource "aws_lb" "load_balancer" {
  name                       = "${var.environment}-lb"
  load_balancer_type         = "application"
  subnets                    = module.vpc.public_subnet_ids
  security_groups            = [aws_security_group.lb.id]
  drop_invalid_header_fields = true
  enable_deletion_protection = true
}

# We would use name_prefix, but it has a length limit of 6 characters
resource "random_id" "http_sufix" {
  byte_length = 2
}

#TODO-https://github.com/alphagov/gdx-data-share-poc/issues/20: When we have our own route53 we can lock this to HTTPS
#tfsec:ignore:aws-elb-http-not-used
resource "aws_lb_listener" "listener_http" {
  load_balancer_arn = aws_lb.load_balancer.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.green.arn
  }

  lifecycle {
    ignore_changes = [default_action]
  }

  depends_on = [aws_lb_target_group.green]
}

#tfsec:ignore:aws-elb-http-not-used
resource "aws_lb_listener" "test_listener_http" {
  load_balancer_arn = aws_lb.load_balancer.arn
  port              = 8080
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"

    fixed_response {
      status_code  = "403"
      content_type = "text/plain"
    }
  }

  lifecycle {
    ignore_changes = [default_action]
  }

  depends_on = [aws_lb_target_group.blue]
}

resource "random_password" "test_auth_header" {
  length  = 64
  special = false
}

resource "aws_lb_listener_rule" "protected_test_forward" {
  listener_arn = aws_lb_listener.test_listener_http.arn
  priority     = 100

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.blue.arn
  }

  condition {
    http_header {
      values           = [random_password.test_auth_header.result]
      http_header_name = "X-TEST-AUTH"
    }
  }

  lifecycle {
    ignore_changes = [action]
  }
}

resource "aws_lb_target_group" "green" {
  name        = "${var.environment}-green-${random_id.http_sufix.hex}"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = module.vpc.vpc_id

  health_check {
    path = "/health"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb_target_group" "blue" {
  name        = "${var.environment}-blue-${random_id.http_sufix.hex}"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = module.vpc.vpc_id

  health_check {
    path = "/health"
  }

  lifecycle {
    create_before_destroy = true
  }
}
