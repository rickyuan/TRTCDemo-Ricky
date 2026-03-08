#!/usr/bin/env bash
# deploy.sh — Build, push images to TCR, and apply k8s manifests to TKE
#
# Prerequisites:
#   1. Docker installed and logged in to TCR:
#      docker login ccr.ccs.tencentyun.com -u <your_account_id> -p <your_token>
#   2. kubectl configured to point to your TKE cluster:
#      tke-credential-helper or kubeconfig from TKE console
#   3. Set the variables below before running.
#
# Usage:
#   chmod +x deploy.sh
#   ./deploy.sh

set -euo pipefail

# ─── Configuration — EDIT THESE ───────────────────────────────────────────────
TCR_REGISTRY="ccr.ccs.tencentyun.com"
TCR_NAMESPACE="YOUR_NAMESPACE"          # e.g. trtc-demo
IMAGE_TAG="${IMAGE_TAG:-latest}"

SERVER_IMAGE="${TCR_REGISTRY}/${TCR_NAMESPACE}/trtc-server:${IMAGE_TAG}"
WEB_IMAGE="${TCR_REGISTRY}/${TCR_NAMESPACE}/trtc-web:${IMAGE_TAG}"

# Your production web domain (used for CORS and app.js SERVER_URL)
# e.g. https://trtc.yourdomain.com  or the CLB IP http://1.2.3.4
WEB_DOMAIN="https://YOUR_WEB_DOMAIN"
SERVER_DOMAIN="http://YOUR_SERVER_CLB_IP:3000"
# ──────────────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> [1/5] Patching web/app.js SERVER_URL to ${SERVER_DOMAIN}"
sed -i.bak "s|const SERVER_URL = .*|const SERVER_URL = '${SERVER_DOMAIN}';|" \
  "${SCRIPT_DIR}/web/app.js"

echo "==> [2/5] Building Docker images"
docker build -t "${SERVER_IMAGE}" "${SCRIPT_DIR}/server"
docker build -t "${WEB_IMAGE}"    "${SCRIPT_DIR}/web"

echo "==> [3/5] Pushing images to TCR"
docker push "${SERVER_IMAGE}"
docker push "${WEB_IMAGE}"

echo "==> [4/5] Patching k8s/deployment.yaml with image names and CORS origin"
sed -i.bak \
  -e "s|ccr.ccs.tencentyun.com/YOUR_NAMESPACE/trtc-server:latest|${SERVER_IMAGE}|g" \
  -e "s|ccr.ccs.tencentyun.com/YOUR_NAMESPACE/trtc-web:latest|${WEB_IMAGE}|g" \
  -e "s|https://YOUR_WEB_DOMAIN|${WEB_DOMAIN}|g" \
  "${SCRIPT_DIR}/k8s/deployment.yaml"

echo "==> [5/5] Applying k8s manifests"
kubectl apply -f "${SCRIPT_DIR}/k8s/secret.yaml"
kubectl apply -f "${SCRIPT_DIR}/k8s/deployment.yaml"

echo ""
echo "✅ Deployment complete!"
echo "   Check pod status:  kubectl get pods -n trtc-demo"
echo "   Get CLB IPs:       kubectl get svc -n trtc-demo"
echo ""
echo "   After CLB IPs are assigned:"
echo "   1. Update SERVER_DOMAIN in this script with the server CLB IP"
echo "   2. Update SERVER_URL in web/app.js"
echo "   3. Update ALLOWED_ORIGINS in k8s/deployment.yaml"
echo "   4. Re-run this script"
