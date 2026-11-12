#!/usr/bin/env bash
set -euo pipefail

# An Academy account may have LabRole but not the LabEksClusterRole described in
# another lab version. Never attempt to create IAM roles: select only existing
# roles that the EKS control plane / EC2 nodes are actually allowed to assume.
role_trusts() {
  local policy
  policy=$(aws iam get-role --role-name "$1" --query 'Role.AssumeRolePolicyDocument' --output json 2>/dev/null) || return 1
  ROLE_SERVICE="$2" python3 -c '
import json
import os
import sys

policy = json.load(sys.stdin)
statements = policy.get("Statement", [])
if isinstance(statements, dict):
    statements = [statements]
for statement in statements:
    if statement.get("Effect") != "Allow":
        continue
    principal = statement.get("Principal", {})
    if principal == "*":
        sys.exit(0)
    services = principal.get("Service", [])
    if isinstance(services, str):
        services = [services]
    if os.environ["ROLE_SERVICE"] in services or "*" in services:
        sys.exit(0)
sys.exit(1)
' <<< "$policy"
}

select_role() {
  local requested="$1" service="$2"
  shift 2
  if [ -n "$requested" ]; then
    if role_trusts "$requested" "$service"; then
      printf '%s\n' "$requested"
      return
    fi
    printf 'IAM role %s is missing or does not trust %s. Check its trust policy.\n' "$requested" "$service" >&2
    return 1
  fi
  local candidate
  for candidate in "$@"; do
    if role_trusts "$candidate" "$service"; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  printf 'No existing lab role trusts %s. Set the OS Actions variable EKS_%s_ROLE_NAME to an existing role with that trust, or check the lab IAM roles.\n' "$service" "$([ "$service" = eks.amazonaws.com ] && printf CLUSTER || printf NODE)" >&2
  return 1
}

cluster_role=$(select_role "${EKS_CLUSTER_ROLE_NAME:-}" eks.amazonaws.com LabEksClusterRole LabRole)
node_role=$(select_role "${EKS_NODE_ROLE_NAME:-}" ec2.amazonaws.com LabEksNodeRole LabEksClusterRole LabRole)

printf 'EKS cluster role: %s; node role: %s\n' "$cluster_role" "$node_role"
{
  printf 'TF_VAR_cluster_role_name=%s\n' "$cluster_role"
  printf 'TF_VAR_node_role_name=%s\n' "$node_role"
} >> "${GITHUB_ENV:?Run this script from the GitHub Actions deployment job}"
