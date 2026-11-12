terraform {
  required_version = ">= 1.10.0"
  backend "s3" {}
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}

provider "aws" {
  region = var.aws_region
  default_tags { tags = { Project = "mechanic-shop", Owner = "os-service", Environment = "academy" } }
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
  validation {
    condition     = contains(["us-east-1", "us-west-2"], var.aws_region)
    error_message = "The Learner Lab permits us-east-1 or us-west-2 only."
  }
}
variable "cluster_name" {
  type    = string
  default = "mechanic-shop-academy"
}
variable "cluster_role_name" {
  type    = string
  default = "LabEksClusterRole"
}
variable "node_role_name" {
  type    = string
  default = "LabEksClusterRole"
}
variable "node_instance_type" {
  type    = string
  default = "t3.medium"
  validation {
    condition     = can(regex("^[a-z0-9]+\\.(nano|micro|small|medium|large)$", var.node_instance_type))
    error_message = "Learner Lab supports nano through large instance sizes only."
  }
}
variable "db_username" {
  type    = string
  default = "shopadmin"
}
variable "db_password" {
  type      = string
  sensitive = true
  validation {
    condition     = length(var.db_password) >= 8
    error_message = "RDS requires a database password of at least 8 characters."
  }
}

data "aws_vpc" "default" { default = true }
data "aws_subnets" "public" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
  filter {
    name   = "availability-zone"
    values = [for suffix in ["a", "b", "c"] : "${var.aws_region}${suffix}"]
  }
}
data "aws_iam_role" "cluster" { name = var.cluster_role_name }
data "aws_iam_role" "nodes" { name = var.node_role_name }

resource "aws_eks_cluster" "shared" {
  name     = var.cluster_name
  role_arn = data.aws_iam_role.cluster.arn

  vpc_config {
    subnet_ids              = data.aws_subnets.public.ids
    endpoint_public_access  = true
    endpoint_private_access = true
  }
  access_config {
    authentication_mode                         = "API_AND_CONFIG_MAP"
    bootstrap_cluster_creator_admin_permissions = true
  }
}

resource "aws_eks_node_group" "shared" {
  cluster_name    = aws_eks_cluster.shared.name
  node_group_name = "mechanic-shop-nodes"
  node_role_arn   = data.aws_iam_role.nodes.arn
  subnet_ids      = data.aws_subnets.public.ids
  instance_types  = [var.node_instance_type]
  disk_size       = 30
  scaling_config {
    desired_size = 2
    min_size     = 2
    max_size     = 3
  }
  update_config { max_unavailable = 1 }
}

resource "aws_security_group" "os_db" {
  name_prefix = "mechanic-shop-os-db-"
  description = "OS PostgreSQL reachable only from within the lab VPC"
  vpc_id      = data.aws_vpc.default.id
  ingress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.default.cidr_block]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_db_subnet_group" "os" {
  name       = "mechanic-shop-os-db"
  subnet_ids = data.aws_subnets.public.ids
}

resource "aws_db_instance" "os" {
  identifier                 = "mechanic-shop-os"
  engine                     = "postgres"
  instance_class             = "db.t3.micro"
  allocated_storage          = 20
  storage_type               = "gp2"
  db_name                    = "osdb"
  username                   = var.db_username
  password                   = var.db_password
  db_subnet_group_name       = aws_db_subnet_group.os.name
  vpc_security_group_ids     = [aws_security_group.os_db.id]
  publicly_accessible        = false
  multi_az                   = false
  monitoring_interval        = 0
  skip_final_snapshot        = true
  deletion_protection        = false
  auto_minor_version_upgrade = true
}

output "cluster_name" { value = aws_eks_cluster.shared.name }
output "vpc_id" { value = data.aws_vpc.default.id }
output "os_jdbc_url" { value = "jdbc:postgresql://${aws_db_instance.os.address}:5432/osdb" }
output "os_db_username" { value = var.db_username }
