#!/usr/bin/env bash
# RepoMind 云服务器初始化脚本
# 适用：Ubuntu 22.04 / 24.04、Debian 12（腾讯云、阿里云轻量服务器均可）
# 用法：以 root 身份执行  bash init-server.sh

set -euo pipefail

APP_DIR=/opt/repomind
SWAP_SIZE=2G

log() { echo -e "\n[init] $*"; }

if [ "$(id -u)" -ne 0 ]; then
  echo "请用 root 执行：sudo bash init-server.sh" >&2
  exit 1
fi

# ---------- 1. 换国内软件源（仅 Ubuntu 有效，可跳过）----------
if [ -f /etc/apt/sources.list ] && grep -q "archive.ubuntu.com" /etc/apt/sources.list; then
  log "切换 APT 源为阿里云镜像"
  sed -i 's|http://.*archive.ubuntu.com|https://mirrors.aliyun.com|g' /etc/apt/sources.list
fi

log "更新软件包索引"
apt-get update -y

# ---------- 2. 安装 Docker 与 Compose ----------
if ! command -v docker >/dev/null 2>&1; then
  log "安装 Docker"
  apt-get install -y ca-certificates curl gnupg
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
else
  log "Docker 已安装，跳过"
fi

# ---------- 3. 配置镜像加速（拉取镜像快 10 倍）----------
log "写入 Docker 镜像加速配置"
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://docker.mirrors.ustc.edu.cn"
  ],
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
EOF
systemctl daemon-reload
systemctl enable --now docker
systemctl restart docker

# ---------- 4. 添加 Swap（2C2G/2C4G 必需，防止构建或索引时 OOM）----------
if ! swapon --show | grep -q "/swapfile"; then
  log "创建 ${SWAP_SIZE} Swap"
  fallocate -l ${SWAP_SIZE} /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q "/swapfile" /etc/fstab || echo "/swapfile none swap sw 0 0" >> /etc/fstab
  # 有 swap 时降低内存换出倾向，避免频繁抖动
  sysctl -w vm.swappiness=10
  grep -q "vm.swappiness" /etc/sysctl.conf || echo "vm.swappiness=10" >> /etc/sysctl.conf
else
  log "Swap 已存在，跳过"
fi

# ---------- 5. 防火墙：只放行 SSH 与 Web 端口 ----------
if command -v ufw >/dev/null 2>&1 || apt-get install -y ufw; then
  log "配置 ufw 防火墙"
  ufw allow 22/tcp
  ufw allow 8080/tcp
  ufw allow 80/tcp
  ufw allow 443/tcp
  ufw --force enable
fi

# ---------- 6. 部署目录 ----------
log "创建部署目录 ${APP_DIR}"
mkdir -p "${APP_DIR}/initdb"

log "完成。后续步骤："
echo "  1) 把 deploy/docker-compose.prod.yml 上传到 ${APP_DIR}/"
echo "  2) 复制 .env.example 为 .env 并填写真实密钥"
echo "  3) cd ${APP_DIR} && docker compose -f docker-compose.prod.yml up -d"
echo "  4) 浏览器访问 http://<服务器公网IP>:8080"
