#!/usr/bin/env bash
set -euo pipefail

# Run from any directory; the same bucket holds three isolated state keys.
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
region="${AWS_REGION:-us-east-1}"
account="$(aws sts get-caller-identity --query Account --output text)"
bucket="mechanic-shop-tfstate-${account}-${region}"
existing_bucket="$(aws s3api list-buckets --query "Buckets[?Name=='$bucket'].Name" --output text)"
if [ "$existing_bucket" != "$bucket" ]; then
  if [ "$region" = us-east-1 ]; then
    aws s3api create-bucket --bucket "$bucket" --region "$region"
  else
    aws s3api create-bucket --bucket "$bucket" --region "$region" --create-bucket-configuration "LocationConstraint=$region"
  fi
  aws s3api put-bucket-versioning --bucket "$bucket" --versioning-configuration Status=Enabled
  aws s3api put-public-access-block --bucket "$bucket" --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
  aws s3api put-bucket-encryption --bucket "$bucket" --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
fi
aws s3api head-bucket --bucket "$bucket" >/dev/null
terraform -chdir="$repo_dir/terraform" init -input=false \
  -backend-config="bucket=$bucket" -backend-config="key=os/terraform.tfstate" \
  -backend-config="region=$region" -backend-config="use_lockfile=true"
