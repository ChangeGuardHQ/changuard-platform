#!/bin/bash
# Optional EC2 user data for a fresh Ubuntu instance; installs tooling, without application secrets.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

# Use Docker's signed Ubuntu repository so Engine, Buildx and Compose are installed together.
. /etc/os-release
if [ "$ID" != ubuntu ]; then
    printf '%s\n' 'This bootstrap supports Ubuntu only.' >&2
    exit 1
fi
apt-get update
apt-get install -y ca-certificates curl git
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
docker_arch="$(dpkg --print-architecture)"
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $docker_arch
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
# A fresh SSH login picks up group membership; Docker access grants host administration privileges.
usermod -aG docker ubuntu
