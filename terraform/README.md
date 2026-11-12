# AWS Academy deployment (OS and shared resources)

The OS repository owns the shared EKS cluster, its node group, the `mechanic-shop` namespace, RabbitMQ (broker and shared exchanges), Kong, Mailpit, metrics-server, the OS queue, and its own PostgreSQL RDS instance. Billing and Execution never create the cluster or shared namespace. The AWS stack reuses the **default VPC and its default subnets** in the selected lab region, and the lab-provided EKS roles; it does not create IAM roles, a NAT gateway, or an additional VPC.

`terraform/main.tf` provisions EKS with two `t3.medium` nodes (maximum three), plus a private single-AZ `db.t3.micro` PostgreSQL RDS instance on 20 GB gp2. It is designed for the lab's region, instance and storage limits. The existing `k8s/` manifests deploy the remaining resources. To preserve the lab budget, destroy the stacks when no longer needed, in the reverse deployment order.

## First deployment via GitHub Actions

1. Start the Learner Lab; in **AWS Details → AWS CLI**, copy its temporary access key, secret access key and session token into the three repositories' GitHub Actions secrets. Refresh them whenever the lab session credentials rotate.
2. Create three **public** Docker Hub repositories under your Docker Hub account: `mechanic-shop-backend`, `mechanic-shop-billing`, and `mechanic-shop-execution`. Configure the remaining secrets and variables below in **Settings → Secrets and variables → Actions**. Do not place credentials in `.tfvars` or committed files. Use the **same** JWT and RabbitMQ credentials in all three repositories. Each SQL database uses a **different** password.
3. Push/merge to `master` in **OS** first. Its pipeline checks AWS access, creates (or reuses) the single encrypted/versioned S3 state bucket `mechanic-shop-tfstate-<account-id>-<region>`, applies the OS stack, then deploys the shared Kubernetes resources and OS app. The three state keys are `os/terraform.tfstate`, `billing/terraform.tfstate`, and `execution/terraform.tfstate`; S3 lockfiles prevent concurrent writes to a key.
4. Wait for the OS workflow's RabbitMQ, OS and Kong rollouts. Then push/merge Billing and Execution (in either order). Each discovers the existing cluster and deploys only its own database and application resources.
5. Inspect `kubectl get pods -n mechanic-shop`, `kubectl get svc kong -n mechanic-shop`, and `aws rds describe-db-instances --db-instance-identifier mechanic-shop-os`. The Kong service is exposed as `http://<load-balancer-hostname>:8000`.

### GitHub configuration

| Repository | Required Actions secrets | Required Actions variables |
|---|---|---|
| All three | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` (lab AWS Details); `DOCKERHUB_TOKEN`; `JWT_SECRET` (same long random value); `RABBITMQ_USERNAME` (same non-`guest` value); `RABBITMQ_PASSWORD` (same long random value) | `DOCKERHUB_USERNAME` (Docker Hub account) |
| OS | `DATABASE_PASSWORD` (OS RDS master password); `SEED_DEFAULT_PASSWORD` (initial staff login) | — |
| Billing | `DATABASE_PASSWORD` (different from OS) | — |
| Execution | `MONGODB_PASSWORD` (16+ characters, letters/digits/`_`/`-`); `ATLAS_PUBLIC_KEY`; `ATLAS_PRIVATE_KEY` | `ATLAS_ORG_ID` |

Set the optional `AWS_REGION` variable **identically in all repositories** (`us-east-1` default; `us-west-2` also supported). `EKS_CLUSTER_NAME` defaults to `mechanic-shop-academy` and likewise must match in all repositories. The OS pipeline checks existing IAM roles and their trust policies: it chooses `LabEksClusterRole` or `LabRole` for the EKS cluster (`eks.amazonaws.com`) and `LabEksNodeRole`, `LabEksClusterRole`, or `LabRole` for nodes (`ec2.amazonaws.com`). It never creates IAM roles. To override discovery, set the OS Actions variables `EKS_CLUSTER_ROLE_NAME` and `EKS_NODE_ROLE_NAME` to existing roles with the required trust and permissions. If neither suitable role exists, the pipeline fails before Terraform; ask the lab administrator to supply the supported EKS roles. For manual Terraform use, supply `TF_VAR_cluster_role_name` and `TF_VAR_node_role_name` explicitly when the defaults are absent. `APP_BASE_URL` is optional: the OS and Billing pipelines derive `http://<Kong load-balancer>:8000` automatically. Provide a stable public URL in both if you have one.

Optional per-repo secrets: `NEW_RELIC_LICENSE_KEY`, `SONAR_TOKEN`; Billing also accepts `MERCADOPAGO_ACCESS_TOKEN` and `MERCADOPAGO_WEBHOOK_SECRET` if `PAYMENT_PROVIDER=mercadopago` (otherwise it defaults to `simulated`). Optional variables: `NEW_RELIC_APP_NAME`, `SONAR_PROJECT_KEY`, `SONAR_ORGANIZATION`, `SONAR_HOST_URL`. OS uses `POST /api/auth/login` (email/password) to issue JWTs; no CPF Lambda or Lambda-specific variable is required. The legacy `/internal/customers/{document}/status` controller is not routed publicly; if needed internally, set OS secret `INTERNAL_API_SECRET`, otherwise the pipeline generates a random value each deployment.

The pipelines take their database URL and username from Terraform outputs. Do **not** configure `DATABASE_URL`, `DATABASE_USERNAME`, `MONGODB_URI`, or `AWS_ROLE_TO_ASSUME` as GitHub secrets for these workflows.

## Existing resources and manual use

The pipeline checks AWS identity and reads cluster/database inventory before trying to create named resources. A cluster or database with the same name but **outside the repository's Terraform state** causes an actionable failure; it is never silently taken over. Import it into its owner's state after checking its configuration, or choose a different cluster name. For example, with active lab credentials and the OS secrets exported as `TF_VAR_db_password`:

```bash
export AWS_REGION=us-east-1 TF_VAR_aws_region=us-east-1
export TF_VAR_db_password='your-os-rds-password'
bash scripts/terraform-init.sh
terraform -chdir=terraform plan
terraform -chdir=terraform import aws_eks_cluster.shared mechanic-shop-academy
terraform -chdir=terraform import aws_eks_node_group.shared mechanic-shop-academy:mechanic-shop-nodes
```

Import other pre-existing named resources individually before applying (for example, `aws_db_instance.os` with ID `mechanic-shop-os`, or the existing subnet group). Review `terraform plan` after importing: it may propose changes to an existing cluster whose configuration does not match this stack. For a fresh lab no import is needed. Avoid running `terraform destroy` on the shared stack while Billing/Execution still depend on it.

The S3 backend stores database passwords in Terraform state; only grant state-bucket access to the lab credentials and do not upload state as a public artifact. The OS-owned Kong and RabbitMQ are redeployed idempotently with `kubectl apply` on subsequent OS pushes.
