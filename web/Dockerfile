# Claw 前端（管理后台）生产镜像：多阶段构建
# 阶段 1：用 Node 构建 Vite 产物
FROM node:20-alpine AS build
WORKDIR /app
# CN 服务器构建走 npmmirror 镜像，避免直连 npmjs.org 超时
RUN npm config set registry https://registry.npmmirror.com
# 仅复制依赖清单先装，利用镜像层缓存
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# 阶段 2：nginx 托管静态产物（SPA，需 history 模式回退到 index.html）
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1
