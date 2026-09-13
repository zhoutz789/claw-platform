# Claw 生产部署运行手册（单主机 Docker Compose）

> 适用：在用户自有云服务器（腾讯云 CVM / 阿里云 ECS / 任意装了 Docker 的主机）上一键拉起
> 完整栈：PostgreSQL(TimescaleDB) + Redis + RabbitMQ + EMQX + Backend + Web(nginx) + Gateway(nginx/TLS)。
>
> **重要前提（本沙箱环境限制）**：当前 WorkBuddy 沙箱**无任何外网出口**（git/registry/docker hub 均不可达），
> 因此镜像构建、推送、文件传输都**无法从这里执行**。本手册是给用户在云服务器上直接运行用的。
> 代码需要先放到服务器（克隆仓库 或 整机打包传输，见第 1 步）。

---

## 0. 服务器前置条件

| 项 | 要求 |
|----|------|
| 系统 | Linux x86_64（推荐 Ubuntu 22.04 / 24.04 或 TencentOS） |
| Docker | Docker Engine 24+，且已装 **compose 插件**（`docker compose version` 可出结果，不是 docker-compose v1） |
| 内存 | ≥ 4 GB（开 EMQX 建议 ≥ 8 GB；可关 EMQX 省 ~1.5 GB） |
| 端口 | 安全组 / 防火墙放通 **80、443**（入站）；其余端口仅容器内网互通，不对外 |
| 域名 | 一个 A 记录指向本机公网 IP（TLS 证书用）；纯冒烟可暂用 IP + 自签证书（浏览器告警） |

装 Docker（若未装）：
```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER   # 然后重登使其生效
docker compose version          # 确认 compose 插件存在
```

---

## 1. 把代码放到服务器

**方式 A（推荐，若已有腾讯云/任意 Git 仓库）**：在服务器上克隆。
```bash
git clone <你的仓库地址> claw-platform && cd claw-platform
```
> 本仓库目前 `origin` 指向的是另一项目（weiyi），并非 claw-platform，请勿直接 push。
> 如需我在此处帮加一个正确的腾讯云远程地址，请把仓库 URL 给我（仅本地加 remote，不会自动推送）。

**方式 B（整机传输）**：在本机把项目打成包，再 `scp`/对象存储下载到服务器解压。
```bash
# 本机执行（排除大目录，保留源码与部署文件）
cd /Users/zhoutianzhi/WorkBuddy/Claw
tar czf claw-platform.tar.gz --exclude=claw-platform/backend/target \
  --exclude=claw-platform/web/node_modules --exclude=claw-platform/web/dist \
  --exclude=claw-platform/.git claw-platform
# 传到服务器后：tar xzf claw-platform.tar.gz && cd claw-platform
```
> 注：方式 B 传输时 `deploy/.env.prod`（含已生成的强随机密钥）会一并带走，无需重新生成。

---

## 2. 准备生产环境变量

若用方式 A 克隆（`.env.prod` 被 gitignore 不会随仓库下来），需重建；本仓库已带 `deploy/.env.prod.example`：
```bash
cd claw-platform
cp deploy/.env.prod.example deploy/.env.prod
# 编辑并替换以下占位符为强随机值（示例生成法见下）：
#   DB_PASSWORD / MQ_PASSWORD / CLAW_JWT_SECRET / CLAW_ID_CARD_KEY / CLAW_PAYMENT_CALLBACK_SECRET
```
生成强随机值（在服务器上执行，替换上面的占位符）：
```bash
openssl rand -base64 18 | head -c 24      # DB/MQ 密码
openssl rand -base64 48 | tr -d '\n'      # CLAW_JWT_SECRET (≥32 字节)
openssl rand -base64 32 | tr -d '\n'      # CLAW_ID_CARD_KEY (AES-256)
openssl rand -base64 32 | tr -d '\n'      # CLAW_PAYMENT_CALLBACK_SECRET
```
> 若用方式 B 且带过来了 `.env.prod`，**跳过本步**（密钥已就绪）。

---

## 3. TLS 证书（网关必需，否则 nginx 起不来）

`deploy/nginx/gateway.conf` 固定引用 `/etc/nginx/ssl/fullchain.pem` 与 `/etc/nginx/ssl/privkey.pem`，
而仓库自带的 `deploy/ssl/` 只有 `server.pem` / `server.key`（自签测试证书）。二选一：

**冒烟测试（自签，浏览器会告警，仅验证链路）**：
```bash
cd deploy/ssl
cp server.pem fullchain.pem
cp server.key privkey.pem
```

**生产（Let's Encrypt / 云证书）**：把正式证书命名为 `fullchain.pem` + `privkey.pem` 放进 `deploy/ssl/`。
自动签发示例（需域名已解析且 80 端口可达）：
```bash
sudo apt-get install -y certbot
sudo certbot certonly --webroot -w /tmp -d your.domain.com
sudo cp /etc/letsencrypt/live/your.domain.com/fullchain.pem deploy/ssl/
sudo cp /etc/letsencrypt/live/your.domain.com/privkey.pem  deploy/ssl/
```

---

## 4. 构建镜像

镜像名约定：`claw/claw-backend:<版本>` 与 `claw/claw-web:<版本>`（`DOCKER_REGISTRY` 留空时前缀为 `claw/`，
与 `docker-compose.prod.yml` 引用一致）。版本号建议用 git tag，首次可先用 `latest`。

```bash
cd claw-platform
# 构建 backend + web 镜像（需要服务器能访问 Docker Hub 与 Maven Central / npm 源）
./scripts/deploy-version.sh build latest
```
构建内容：
- backend：`maven:3.9-eclipse-temurin-21` 跑 `mvn package -DskipTests` → `eclipse-temurin:21-jre-alpine` 运行 `app.jar:8080`
- web：`node:20-alpine` 跑 `npm ci && npm run build` → `nginx:1.27-alpine` 托管 `dist`（SPA，含 history 回退）

> 若使用私有镜像仓库：在 `deploy/.env.prod` 填 `DOCKER_REGISTRY=registry.cn-xxx.aliyuncs.com/yourns`，
> 构建会自动打该前缀 tag，`deploy` 阶段改为 `docker compose pull`。

---

## 5. 部署上线

```bash
# 切到指定版本并 up -d + 等待健康
./scripts/deploy-version.sh deploy latest
# 查看容器状态
./scripts/deploy-version.sh status
```

部署脚本会：设 `CLAW_VERSION` → `docker compose up -d` → 等 `backend`(healthy) 与 `web`(healthy)。

首启注意（见第 7 步排错）：**Flyway 会前向应用 V1–V119+ 全部迁移**，初次可能耗时 > 60s，
若 backend 一直 (health: starting) 直至超时，属正常，按第 7 步调大 `start_period` 后重跑 `deploy`。

---

## 6. 健康检查与冒烟

```bash
# 容器层面
./scripts/deploy-version.sh status

# 后端健康（网关 443 上仅允许 127.0.0.1，故走 localhost）
curl -sk https://127.0.0.1/actuator/health      # 期望含 "UP"

# 前端冒烟：浏览器打开 https://<你的域名>  （自签证书会有安全告警，继续访问即可）
# 登录演示账号：13800000007（PLATFORM_ADMIN，通配权限）；dev 短信码走 /api/v1/auth/sms-code 回显。
```

---

## 7. 排错速查

| 现象 | 原因 / 处理 |
|------|------|
| `gateway` 起不来 / 反复重启 | `deploy/ssl/fullchain.pem` 或 `privkey.pem` 缺失（见第 3 步）。`docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod logs gateway` 看 nginx 报错。 |
| `backend` 一直 starting 超时 | 首次 Flyway 迁移慢。临时把 `backend` 的 `healthcheck.start_period: 60s` 调到 `300s`，再 `./scripts/deploy-version.sh deploy latest`。 |
| 前端能开但所有 API 403/空 body | 网关 `/api/` 的 `proxy_pass` 不能带末尾斜杠（已在 gateway.conf 注释说明）。确认没改坏。 |
| 镜像 pull 失败 | `DOCKER_REGISTRY` 留空则只用本地构建镜像；若填了 registry 但没先 push，先 `build` 或 `docker push`。 |
| 内存吃紧 / OOM | 编辑 `deploy/docker-compose.prod.yml`，把整个 `emqx:` 服务段注释掉（初期不上 IoT 可关，省 ~1.5 GB），重跑 `deploy`。 |
| 想回滚 | `./scripts/deploy-version.sh rollback`（回滚到上一个 `CLAW_VERSION`，依赖 `deploy/.claw-deploy-state`）。 |

---

## 8. 日常运维

```bash
./scripts/deploy-version.sh current   # 当前 / 上一个版本
./scripts/deploy-version.sh status    # 各容器状态
./scripts/deploy-version.sh rollback  # 回滚
# 升级：改代码 → git 拉新 → ./scripts/deploy-version.sh build <新版本> → deploy <新版本>
```

> 说明：数据卷 `pg_data / redis_data / mq_data` 由 compose 管理，删容器不丢数据；
> 升级若含新 Flyway 迁移（V91+），回滚前请先备份 DB 快照。
