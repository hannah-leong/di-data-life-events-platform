assume-role.exe gdx | Invoke-Expression

$EC2_INSTANCE = (aws ec2 describe-instances --region eu-west-2 --filters "Name=tag:Name,Values=dev-rds-bastion-host" "Name=instance-state-name,Values=running" | ConvertFrom-Json).Reservations[0].Instances[0].InstanceId

aws ssm start-session `
    --region eu-west-2 `
    --target $EC2_INSTANCE `
    --document-name AWS-StartPortForwardingSessionToRemoteHost `
    --parameters "host=dev-rds-db.cluster-chfyfnfoqhf6.eu-west-2.rds.amazonaws.com,portNumber=45678,localPortNumber=5434"
