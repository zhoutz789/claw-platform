# Phase 1 落地清单：数据库高可用 + 跨区备份

> 目标：把当前单机自管 PostgreSQL 升级为 **托管高可用 PostgreSQL/TimescaleDB + 跨区只读副本 + WAL 持续归档（PITR）到对象存储并跨区拷贝**。
> 这一步直接满足老板的三条诉求：① 默认把数据备份到不同区域；② 主节点损毁自动切换、数据零丢失；③ 业务照常运营。
> 不改动任何应用代码——后端已用 `DB_HOST/DB_PORT/DB_USER/DB_PASSWORD` 环境变量接入，只需改 `deploy/.env.prod`。

---

## 0. 现状对照

| 项 | 现状 | Phase 1 目标 |
|----|------|-------------|
| 数据库 | 单机 `claw-postgres`（Docker，数据卷在单台机器） | 托管多可用区 PG（主备自动故障转移） |
| 备份 | 无（仅本地数据卷） | WAL 实时归档到对象存储 + 跨区拷贝 |
| 灾备 | 机器坏 = 数据可能丢失 | 跨区只读副本，主坏秒级提升 |
| 应用接入 | `deploy/.env.prod` 的 DB_* 指向 localhost | 改指向托管主库端点 |

---

## 1. 选型托管 PostgreSQL

- **阿里云 RDS PostgreSQL（支持 TimescaleDB 插件）/ AWS RDS for PostgreSQL / 腾讯云 PostgreSQL**。
- 若需时序遥测（车辆/电池/无人机轨迹）与交易库同实例：选 **TimescaleDB 兼容实例**；否则交易库用标准 PG，遥测库另建 TimescaleDB。
- 开启 **多可用区（Multi-AZ）高可用**：同区域主备，故障自动切换（RTO 秒~分钟，RPO≈0）。

## 2. 开跨区只读副本（灾备 + 读分摊）

- 在**第二个区域**建只读副本（如 金边主 → 新加坡副本）。
- 副本用途：① 承接只读查询（降低中心延迟）② 主库损毁时**提升为新主**，业务不停。
- 副本通过 PostgreSQL **流式复制**实时同步；核心资金链路可要求**同步副本**（至少 1 个）保证零丢失，非核心遥测用异步。

## 3. WAL 持续归档 + 跨区拷贝（"默认备份到不同区域"）

- 开启托管实例的 **自动备份 / PITR**，归档到对象存储（OSS / S3）。
- 配置 **跨区复制**：OSS 跨区复制 / S3 Cross-Region Replication → WAL 与备份自动落到另一区域。
- 这样即便主区域 + 副本区域同时损毁，仍可从对象存储跨区副本 **PITR 恢复**（RPO≈0，RTO 分钟级）。

## 4. 应用接入（改配置，不动代码）

编辑 `deploy/.env.prod`：
```
DB_HOST=<托管主库内网/公网端点>
DB_PORT=5432
DB_NAME=claw
DB_USER=claw
DB_PASSWORD=<强随机>
```
后端 `application.yml` 已用 `${DB_HOST:localhost}` 等环境变量，无需改代码。`docker compose up -d backend` 即生效。

## 5. Flyway 只在主库执行（关键坑）

- **副本是只读的，后端若在副本启动会触发 Flyway 失败** → 迁移必须只在 **Primary** 执行。
- 做法：中心节点首次启动用 `--migrate-only` 一次性任务跑 Flyway（或在 CI 里 `spring.flyway.enabled=true` 仅主库那次）；副本经流复制自动获得 schema。
- 逻辑复制场景更要小心 DDL 传播（见架构师详细设计文档）。

## 6. 灾备演练（dr-drill）

1. **模拟主库宕机**：控制台强制 failover，或 `pg_ctl promote` 提升副本。
2. **验证业务连续**：`GET /actuator/health` → UP；用演示号登录 → 下单 → 取货履约，全链路通过。
3. **验证 WAL 恢复**：从对象存储 PITR 恢复到新实例，校验关键账户余额恒等式（资金零丢失）。
4. **恢复常态**：演练后恢复原主，副本重新挂接。

> 可参考 `scripts/deploy-version.sh` 思路，把演练脚本化为 `scripts/dr-drill.sh`（架构师文档会给出骨架）。

## 7. 与版本切换机制的关系

- 每个节点（中心/边缘）各持 `deploy/.env.prod`，改 `CLAW_VERSION` + `deploy-version.sh deploy` 即可逐节点换版。
- **数据库层不随应用版本切换**；Flyway 前向迁移由中心 Primary 统一执行，副本跟随。
- 升级前对每个区域主库做一次快照（云控制台 / `pg_dump`），回滚规则同《云部署与版本切换方案》。

---

## 8. 验收标准（Phase 1 完成的定义）

- [ ] 托管多可用区 PG 已建，主备自动故障转移验证通过
- [ ] 跨区只读副本已建并实时同步
- [ ] WAL 持续归档到对象存储 + 跨区拷贝已开启
- [ ] `deploy/.env.prod` 指向托管主库，后端起得来、Flyway 在主库成功、副本不跑 Flyway
- [ ] 灾备演练通过：主库宕机→副本提升→业务连续 + WAL PITR 恢复一致

---

## 9. 下一步

- 跑通 Phase 1 后，规模上来再走 Phase 2（边缘节点自治 + 中心 CDC 聚合）／Phase 3（核心库换 CockroachDB/YugabyteDB）。
- 详细拓扑、迁移策略、CAP 分析、完整灾备 Runbook 见架构师出具的《分布式部署详细设计》。
