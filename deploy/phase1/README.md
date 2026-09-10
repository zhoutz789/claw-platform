# Phase 1 高可用与异地备份原型

把《分布式部署详细设计》的 **Phase 1**（托管 PG 高可用 + 跨区只读副本 + WAL 异地归档 PITR）
落成一个**可在任意装有 Docker 的机器上直接跑**的最小原型，用于演示与验收，

## 拓扑

```
                 ┌──────────────────── 主库 pg-primary :6432 (读写)
   应用/后端 ───▶ │   wal_level=replica
                 │   archive_mode=on -> WAL 落 /mnt/archive (共享卷)
                 │        │ 流复制 (pg_basebackup -R 自举 standby)
                 ▼
            副本 pg-replica :6433 (热备只读；可提升为新主)
                 │
                 └─ WAL 归档共享卷 /mnt/archive ──▶ MinIO :9000/9001 (异地备份目标模拟)
```

- 主库宕机 → `pg_promote()` 提升副本为读写新主，业务零中断（演示见下）。
- WAL 持续归档到本地共享卷；生产改为 `mc cp`/`aws s3 cp` 直推异地对象存储 + 跨区复制。
- 核心账本零丢失策略：生产追加 `synchronous_standby_names='*'`（详见设计文档 CAP 取舍）。

## 运行

```bash
# 1) 启动（首次会 initdb 主库 + pg_basebackup 引导副本，约 10~30s）
bash deploy/phase1/setup.sh

# 2) 查看复制状态
docker exec phase1-pg-primary psql -U claw -c "SELECT * FROM pg_stat_replication;"

# 3) 灾备演练
bash scripts/phase1-dr-drill.sh all        # 故障切换 + 连续性验证 + PITR 说明
bash scripts/phase1-dr-drill.sh reseed     # 把原主重建为新副本（丢失节点再备份）

# 4) 停止
bash deploy/phase1/teardown.sh             # 保留数据
bash deploy/phase1/teardown.sh --purge     # 删除全部数据（含归档 WAL）
```

## 摄像头域（增量）验证

P0 起，本原型额外带了「边缘媒体 + 模拟摄像头 + 边缘 recorder」三件套，用于演示
资产摄像头「推流 → 转码 → HLS 回放 → 段索引落库」整链（详见 `docs/摄像头数据子系统技术方案-v1.md`）。

```bash
# 启动后，模拟摄像头会自动循环向 edge-media 推测试流（RTMP）。验证推流转码：
open http://localhost:18080/live/cam1.m3u8          # 浏览器（Safari 原生 HLS，Chrome 需 hls.js）

# 边缘 recorder 模拟器：向后端周期上报段索引（写 V91 索引表，验证 V92 种子相机 id=1）
# 先拿带 camera:manage 的 token（dev 短信码回显登录后取 Authorization）
export CLAW_TOKEN="<Bearer token>"
docker compose -f deploy/phase1/docker-compose.yml up -d camera-recorder
# 然后在前端「数据回放」页输入资产编号（种子相机挂在 asset_id=1），即可看到回放与历史分段。

# 仅起摄像头组件（不动 PG/MinIO）：
docker compose -f deploy/phase1/docker-compose.yml up -d edge-media mock-camera
```

> 说明：P0 用 SRS 作边缘媒体节点，HLS 地址即 `http://localhost:18080/live/cam1.m3u8`；
> 后端 `CameraService.live()` 返回 `base + ".m3u8"`（base 见 V92 种子 `stream_url`）。
> P1 会前置自建 edge-media 网关，统一暴露 `/hls`、`/rtc` 低延迟端点，届时仅改拼接约定、前端不变。

## 端口（刻意避开 5432/8080/5173）

| 服务 | 端口 | 说明 |
|------|------|------|
| pg-primary | 6432 | 主库（读写） |
| pg-replica | 6433 | 副本（热备只读；提升后可写） |
| minio | 9000 / 9001 | 异地归档目标模拟（console） |
| edge-media | 1935 / 11985 / 18080 / 8000(udp) | 边缘媒体节点（RTMP 接入 / API / HLS·WebRTC / WebRTC UDP） |
| mock-camera | — | 模拟摄像头（ffmpeg 推流，无暴露端口） |
| camera-recorder | — | 边缘 recorder 模拟器（周期上报段索引，无暴露端口） |

## 与《分布式部署详细设计》的映射

| 设计文档要求 | 本原型实现 |
|--------------|-----------|
| 主库高可用 | pg-primary（流复制单副本，可提升） |
| 跨区只读副本 | pg-replica（hot_standby，承接只读） |
| WAL 异地归档 + PITR | archive_mode=on → /mnt/archive；MinIO 模拟异地；drill `pitr` 给出生产流程 |
| Flyway 仅主库 | 迁移只在 primary 执行，副本经流复制获得 schema（与文档一致） |
| 灾备 Runbook | `scripts/phase1-dr-drill.sh` 实现 failover/verify/reseed/pitr |

> 注：本沙箱 Docker 守护进程未运行，文件集未经本机 `docker compose up` 实跑；
> 请在装有 Docker 的主机执行 `setup.sh` 验收。生产落地以《分布式部署详细设计》+《Phase1 数据库高可用与异地备份落地清单》为准。
