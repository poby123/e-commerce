#!/usr/bin/env bash
# Fixed RSA signing key for user-service (needed for more than one instance, or to keep tokens valid across restarts).
# Then set ecommerce.jwt.private-key-location=file:./secrets/jwt-private.pem
set -euo pipefail
mkdir -p secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/jwt-private.pem 2>/dev/null
chmod 600 secrets/jwt-private.pem
echo "wrote secrets/jwt-private.pem (git-ignored)"
