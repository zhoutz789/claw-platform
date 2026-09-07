# ③ 合格证：可编辑识别信息 + 可定制 EAV 模板 + A4 打印

> 日期：2026-09-06 · 关联：用户拍板「合格证模板复用 EAV；打印布局前端 A4 + 可编辑 + 导出 PDF」
> 前序：④ 容量预定/服务站合约菜单（V77）、② 类别管理（V78）已完成并验证。

## 一、做了什么

把原本「只有查看 + 补打」、且前端按钮全是 no-op 的合格证模块，补齐为完整闭环：

1. **可编辑识别信息（③-编辑入口）**
   - `device_certificates` 新增 `data_json`（jsonb，出厂后可维护的识别字段值）。
   - 原 `spec_json` 保持**不可变出证快照**，出证号 `cert_no` / `issued_at` 同样不可变 —— 编辑只动 `data_json`。
   - 后端 `CertificateService.updateData` + `GET/PUT /api/v1/admin/certificates/device/{id}`
     （`mfg:certificate:edit` 权限位）。

2. **可定制 EAV 模板（③-模板）**
   - 新建全局合格证模板表 `certificate_template_fields`（复用 `product_template_fields` 范式，
     区别：合格证模板全局，以 `field_key` UNIQUE）。
   - 默认播种 5 个字段：检验员(text,必填) / 检验结论(select[合格,不合格],必填) /
     出厂编号(text) / 出厂日期(date) / 备注(text)。
   - 后端 `CertificateTemplateService` + `AdminCertificateController`（`GET/POST/PUT/DELETE
     /template[/{id}]`，`mfg:certificate:template` 权限位）。

3. **A4 打印 / 导出 PDF（③-打印）**
   - 前端 `Certificate.jsx` 重写：设备选择 + 合格证卡片 + 「编辑识别信息」「模板配置」
     「打印设置」「打印/导出PDF」四个功能按钮。
   - 编辑弹窗按模板字段**动态生成表单**（number/text/select/date/boolean 控件）。
   - 打印设置：可改**标题/副标题/页脚**，并勾选**显示哪些字段**，存 `localStorage`。
   - 打印区 `.cert-print-root` 经 `@media print` 样式仅打印该区，调 `window.print()`
     即出 A4；用户在打印对话框选「另存为 PDF」即完成存档（零外部依赖、断网可用）。

## 二、变更文件

**后端（Java 21 / Spring Boot 3.3.4）**
- `db/migration/V79__certificate_template_and_edit.sql` — data_json 列 + 模板表 + 默认字段 + 权限种子
- `domain/certificate/Certificate.java` — 新增 `dataJson` 字段
- `domain/certificate/CertificateTemplateField.java`（新）— 模板字段实体
- `domain/certificate/CertificateTemplateFieldRepository.java`（新）
- `domain/certificate/CertificateService.java` — 新增 `getDto` / `updateData`
- `domain/certificate/CertificateTemplateService.java`（新）
- `common/dto/CertificateDtos.java`（新）— DTO / 请求 / 视图记录
- `web/v1/AdminCertificateController.java`（新）— /api/v1/admin/certificates 全部端点

**前端（React 18 + antd5 + Vite）**
- `api/certificate.js`（新）— 接口封装
- `pages/Certificate.jsx`（重写）— 编辑 / 模板管理 / 打印
- `theme.css` — 追加 A4 打印样式
- `i18n/locales/{zh,en,km}/common.json` — 新增 m1049–m1070

## 三、验证（端到端，真实后端 + 真实库）

- `mvn -o compile` → BUILD SUCCESS；`npm run build` → 3296 模块 0 错误。
- 重启后端：Flyway **已应用 V79**（schema 现 at version v79）。
- 登录平台管理员（13800000007）后实打实调接口：
  - `GET /template` → 返回 5 个默认模板字段 ✅
  - `POST/PUT/DELETE /template` → 新增(id=6)→改名+改必填→删除，全部 `code:0` ✅
  - `GET /device/8` → 返回 cert，`dataJson=null`，`specJson` 为不可变快照 ✅
  - `PUT /device/8`（写识别信息）→ `dataJson` 落库；再次 `GET` 确认**持久化**，
    且 `cert_no`/`spec_json`/`issued_at` 未变（不可变铁律守住）✅
  - 测试数据已复位为 null，库保持干净。
- Web dev server :5173 返回 200，HMR 已加载新页面。

## 四、使用路径

后台左侧「生产」组 →「合格证」(`/certificate`) → 选设备 →
「编辑识别信息」填模板字段 → 「模板配置」增删改模板字段 →
「打印设置」调标题/页脚/勾选字段 → 「打印/导出PDF」→ 打印对话框另存 PDF。

## 五、权限说明

- `mfg:certificate:view`（查看）、`mfg:certificate:print`（补打）沿用既有，挂在
  MANUFACTURER / PLATFORM_ADMIN / REGULATOR(view)。
- 新增 `mfg:certificate:edit`、`mfg:certificate:template`，挂在 MANUFACTURER + PLATFORM_ADMIN；
  STATION 仅查看。PLATFORM_ADMIN 仍走通配符，未降级。
- 权限真源 `roles.grants` 已按 V77/V78 范式回写（仅 MANUFACTURER/STATION）。
