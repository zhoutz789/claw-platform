-- =====================================================================
-- Claw 平台 V59 增量（入驻管理 · 增量 C 第一批）
-- 依据：增量设计-入驻管理.md §2.1
-- 范围（5 张新表）：
--   · onboarding_contracts              入驻说明与合同版本（富文本 + 扫描件 + 版本号）
--   · onboarding_applications           入驻申请单（STATION / MANUFACTURER / MERCHANT 三类通用）
--   · onboarding_material_requirements  材料清单配置（按 applicant_type 差异）
--   · onboarding_attachments            资料附件（attach_type 驱动，含逐项审核结论）
--   · onboarding_application_logs       审批 / 操作留痕时间轴
--
-- 全量幂等：CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS /
--           INSERT ... ON CONFLICT DO NOTHING。
-- 注意：applications.deposit_tier_id 的外键延到 V60（档位表在 V60 建），
--       此处只建列，避免 V59 单脚本执行时引用尚未存在的表。
-- =====================================================================

SET search_path = claw;

-- ---------- (1) onboarding_contracts — 入驻说明与合同版本 ----------
CREATE TABLE IF NOT EXISTS onboarding_contracts (
    id                 BIGSERIAL PRIMARY KEY,
    applicant_type     VARCHAR(20)  NOT NULL,                 -- STATION / MANUFACTURER / MERCHANT
    version            VARCHAR(20)  NOT NULL,                 -- v{major}.{minor}，如 v1.0
    title              VARCHAR(160) NOT NULL,
    content_html       TEXT         NOT NULL,                 -- 富文本合作要点
    contract_file_url  VARCHAR(500),                          -- 公司签章合同扫描件
    lang               VARCHAR(8)   NOT NULL DEFAULT 'zh',    -- zh / km / en
    status             VARCHAR(16)  NOT NULL DEFAULT 'DRAFT', -- DRAFT / PUBLISHED / ARCHIVED
    published_at       TIMESTAMPTZ,
    published_by       BIGINT REFERENCES users (id),
    content_hash       VARCHAR(64),                           -- 内容指纹，版本对比用
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_onb_contract_ver
    ON onboarding_contracts (applicant_type, lang, version);
-- 同一主体类型 + 语言同时最多一个生效版本
CREATE UNIQUE INDEX IF NOT EXISTS uq_onb_contract_published
    ON onboarding_contracts (applicant_type, lang) WHERE status = 'PUBLISHED';

-- ---------- (2) onboarding_applications — 入驻申请单（三类通用） ----------
CREATE TABLE IF NOT EXISTS onboarding_applications (
    id                    BIGSERIAL PRIMARY KEY,
    application_no        VARCHAR(40)  NOT NULL,              -- ONB{yyyyMMdd}{4位序号}
    applicant_type        VARCHAR(20)  NOT NULL,
    applicant_user_id     BIGINT       NOT NULL REFERENCES users (id),
    status                VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    -- 主体信息
    applicant_name        VARCHAR(80),
    contact_phone         VARCHAR(40),
    contact_email         VARCHAR(120),
    home_address          TEXT,
    id_card_no            VARCHAR(255),                       -- 身份证编号（AES-GCM 加密存储）
    kyc_record_id         BIGINT REFERENCES kyc_records (id),
    kyc_status            VARCHAR(16)  DEFAULT 'PENDING',     -- PENDING / VERIFIED / REJECTED
    business_scope        TEXT,
    -- 场地信息
    land_intro            TEXT,
    cooperation_plan      TEXT,
    ownership_type        VARCHAR(16),                        -- OWNED 自有 / LEASED 租赁
    lat                   NUMERIC(10,6),
    lng                   NUMERIC(10,6),
    geo_address           TEXT,
    located_at            TIMESTAMPTZ,
    -- 合同与档位
    contract_id           BIGINT REFERENCES onboarding_contracts (id),
    contract_version      VARCHAR(20)  NOT NULL DEFAULT 'v1.0', -- 冗余快照，防合同表被改后争议
    agreed_at             TIMESTAMPTZ,
    deposit_tier_id       BIGINT,                             -- FK 由 V60 补（档位表 V60 建）
    -- 审批与产出
    submitted_at          TIMESTAMPTZ,
    reviewed_at           TIMESTAMPTZ,
    approved_at           TIMESTAMPTZ,
    paid_at               TIMESTAMPTZ,
    activated_at          TIMESTAMPTZ,
    reviewed_by           BIGINT REFERENCES users (id),
    reject_reason         TEXT,
    principal_id          BIGINT,                             -- 激活后创建的主体 ID
    expire_at             TIMESTAMPTZ,                        -- 缴款超时时点
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_onb_app_no ON onboarding_applications (application_no);
CREATE INDEX IF NOT EXISTS idx_onb_app_type_status ON onboarding_applications (applicant_type, status);
CREATE INDEX IF NOT EXISTS idx_onb_app_user       ON onboarding_applications (applicant_user_id);
CREATE INDEX IF NOT EXISTS idx_onb_app_principal  ON onboarding_applications (applicant_type, principal_id);
-- 草稿唯一性：同一用户 + 同一主体类型同时最多 1 条非终态申请。
-- ACTIVATED 不在列表内 —— 激活后允许该用户为同一主体类型再次申请开新店。
CREATE UNIQUE INDEX IF NOT EXISTS uq_onb_app_active
    ON onboarding_applications (applicant_user_id, applicant_type)
 WHERE status IN ('DRAFT','SUBMITTED','REVIEWING','APPROVED','RETURNED',
                  'PENDING_PAY_CONFIRM','DEPOSIT_PAID');

-- ---------- (3) onboarding_material_requirements — 材料清单配置 ----------
CREATE TABLE IF NOT EXISTS onboarding_material_requirements (
    id              BIGSERIAL PRIMARY KEY,
    applicant_type  VARCHAR(20)  NOT NULL,
    material_code   VARCHAR(40)  NOT NULL,
    material_name   VARCHAR(80)  NOT NULL,
    input_type      VARCHAR(16)  NOT NULL DEFAULT 'TEXT',   -- TEXT/TEXTAREA/IMAGE/IMAGES/FILE/LOCATION
    required        BOOLEAN      NOT NULL DEFAULT TRUE,
    min_count       INT,
    max_count       INT,
    hint            TEXT,
    sort_no         INT          NOT NULL DEFAULT 0,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (applicant_type, material_code)
);

-- ---------- (4) onboarding_attachments — 资料附件（统一表） ----------
CREATE TABLE IF NOT EXISTS onboarding_attachments (
    id              BIGSERIAL PRIMARY KEY,
    application_id  BIGINT       NOT NULL REFERENCES onboarding_applications (id) ON DELETE CASCADE,
    attach_type     VARCHAR(32)  NOT NULL,                  -- 材料码
    file_url        VARCHAR(500) NOT NULL,
    file_name       VARCHAR(255),
    file_size       BIGINT,                                 -- 单张 ≤ 10MB（应用侧校验）
    mime_type       VARCHAR(80),                            -- JPG / PNG / PDF 白名单
    remark          TEXT,                                   -- 相关附件说明
    sort_no         INT          NOT NULL DEFAULT 0,        -- 多图排序；SIGNBOARD 首图 = 对外展示图标
    uploaded_by     BIGINT REFERENCES users (id),
    uploaded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    review_status   VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING / PASSED / REJECTED
    review_remark   VARCHAR(255)                             -- 如「营业执照照片模糊」
);
CREATE INDEX IF NOT EXISTS idx_onb_att_app ON onboarding_attachments (application_id, attach_type);

-- ---------- (5) onboarding_application_logs — 审批/操作留痕时间轴 ----------
CREATE TABLE IF NOT EXISTS onboarding_application_logs (
    id              BIGSERIAL PRIMARY KEY,
    application_id  BIGINT       NOT NULL REFERENCES onboarding_applications (id) ON DELETE CASCADE,
    from_status     VARCHAR(24),
    to_status       VARCHAR(24),
    action          VARCHAR(24)  NOT NULL,                  -- CREATE/SAVE_DRAFT/SUBMIT/APPROVE/REJECT/RETURN/PAY_SUBMIT/PAY_CONFIRM/PAY_REJECT/ACTIVATE/CANCEL/EXPIRE
    operator_id     BIGINT REFERENCES users (id),           -- SYSTEM 动作为 NULL
    operator_type   VARCHAR(16)  NOT NULL,                  -- PLATFORM / APPLICANT / SYSTEM
    remark          TEXT,
    payload_json    JSONB,                                  -- 变更明细（哪个字段/材料被驳回）
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_onb_log_app ON onboarding_application_logs (application_id, created_at DESC);

-- =====================================================================
-- 种子：三类主体的材料清单（设计 §2.5）
-- 服务站：全量（含土地证明 / 场地照片 / 定位 / 门头）
-- 厂家：无土地类材料，另加生产资质 / 品牌授权
-- 商家：独立主体，无土地证明与场地照片，需经营品类 + 门头 + 定位
-- =====================================================================
INSERT INTO onboarding_material_requirements
    (applicant_type, material_code, material_name, input_type, required, min_count, max_count, hint, sort_no, enabled)
VALUES
    -- ---- STATION ----
    ('STATION', 'APPLICANT_NAME',    '姓名',             'TEXT',     TRUE,  NULL, NULL, '请输入与身份证一致的姓名', 10, TRUE),
    ('STATION', 'CONTACT',           '联系方式',         'TEXT',     TRUE,  NULL, NULL, '手机号或邮箱',             20, TRUE),
    ('STATION', 'HOME_ADDRESS',      '家庭地址',         'TEXTAREA', TRUE,  NULL, NULL, '详细到门牌号',             30, TRUE),
    ('STATION', 'ID_CARD',           '身份证编号',       'TEXT',     TRUE,  NULL, NULL, '提交后将进行实名认证',     40, TRUE),
    ('STATION', 'BUSINESS_LICENSE',  '营业执照',         'IMAGE',    TRUE,  NULL, NULL, '请上传清晰的营业执照原件照片', 50, TRUE),
    ('STATION', 'BUSINESS_SCOPE',    '经营范围',         'TEXTAREA', TRUE,  NULL, NULL, '与营业执照一致',           60, TRUE),
    ('STATION', 'LEGAL_REP_CERT',    '法人证明',         'IMAGE',    TRUE,  NULL, NULL, '法人身份证或法人证明书',   70, TRUE),
    ('STATION', 'LAND_CERT',         '土地证明',         'IMAGE',    TRUE,  NULL, NULL, '土地权属或不动产权证',     80, TRUE),
    ('STATION', 'OWNERSHIP_LEASE',   '所有权/租赁证明',  'IMAGE',    TRUE,  NULL, NULL, '自有提供产权证，租赁提供租赁合同', 90, TRUE),
    ('STATION', 'SIGNBOARD',         '门头照片',         'IMAGE',    TRUE,  NULL, NULL, '门头照片将作为对外展示图标，需审核通过后展示', 100, TRUE),
    ('STATION', 'SITE_PHOTO',        '场地照片',         'IMAGES',   TRUE,  3,    9,   '请上传 3–9 张场地实拍（含土地附属物）', 110, TRUE),
    ('STATION', 'LAND_INTRO',        '土地介绍',         'TEXTAREA', TRUE,  NULL, NULL, '面积、地形、可建设施等',   120, TRUE),
    ('STATION', 'COOPERATION_PLAN',  '合作申请计划',     'TEXTAREA', TRUE,  NULL, NULL, '拟投入设备数量与经营计划', 130, TRUE),
    ('STATION', 'LOCATION',          '场地定位',         'LOCATION', TRUE,  NULL, NULL, '在地图上点选场地位置，用户将据此找到您的站', 140, TRUE),
    ('STATION', 'SIGNED_AGREEMENT',  '用户签署协议',     'IMAGE',    TRUE,  NULL, NULL, '请下载协议、签字盖章后上传扫描件', 150, TRUE),
    ('STATION', 'OTHER',             '其他附件',         'IMAGES',   FALSE, NULL, NULL, '选填，其他补充说明材料',   900, TRUE),
    -- ---- MANUFACTURER ----
    ('MANUFACTURER', 'APPLICANT_NAME',   '姓名',           'TEXT',     TRUE,  NULL, NULL, '请输入与身份证一致的姓名', 10, TRUE),
    ('MANUFACTURER', 'CONTACT',          '联系方式',       'TEXT',     TRUE,  NULL, NULL, '手机号或邮箱',             20, TRUE),
    ('MANUFACTURER', 'HOME_ADDRESS',     '家庭地址',       'TEXTAREA', TRUE,  NULL, NULL, '详细到门牌号',             30, TRUE),
    ('MANUFACTURER', 'ID_CARD',          '身份证编号',     'TEXT',     TRUE,  NULL, NULL, '提交后将进行实名认证',     40, TRUE),
    ('MANUFACTURER', 'BUSINESS_LICENSE', '营业执照',       'IMAGE',    TRUE,  NULL, NULL, '请上传清晰的营业执照原件照片', 50, TRUE),
    ('MANUFACTURER', 'BUSINESS_SCOPE',   '经营范围',       'TEXTAREA', TRUE,  NULL, NULL, '与营业执照一致',           60, TRUE),
    ('MANUFACTURER', 'LEGAL_REP_CERT',   '法人证明',       'IMAGE',    TRUE,  NULL, NULL, '法人身份证或法人证明书',   70, TRUE),
    ('MANUFACTURER', 'MFG_QUALIFICATION','生产资质',       'IMAGE',    TRUE,  NULL, NULL, '生产许可证或行业资质证明', 80, TRUE),
    ('MANUFACTURER', 'BRAND_AUTH',       '品牌授权',       'IMAGE',    TRUE,  NULL, NULL, '代理品牌需提供品牌方授权书', 90, TRUE),
    ('MANUFACTURER', 'LAND_INTRO',       '土地介绍',       'TEXTAREA', FALSE, NULL, NULL, '选填：厂房/厂区情况介绍',  120, TRUE),
    ('MANUFACTURER', 'COOPERATION_PLAN', '合作申请计划',   'TEXTAREA', TRUE,  NULL, NULL, '拟供货品类、产能与合作方式', 130, TRUE),
    ('MANUFACTURER', 'SIGNED_AGREEMENT', '用户签署协议',   'IMAGE',    TRUE,  NULL, NULL, '请下载协议、签字盖章后上传扫描件', 150, TRUE),
    ('MANUFACTURER', 'OTHER',            '其他附件',       'IMAGES',   FALSE, NULL, NULL, '选填，其他补充说明材料',   900, TRUE),
    -- ---- MERCHANT（独立主体，无土地证明 / 场地照片） ----
    ('MERCHANT', 'APPLICANT_NAME',   '姓名',           'TEXT',     TRUE,  NULL, NULL, '请输入与身份证一致的姓名', 10, TRUE),
    ('MERCHANT', 'CONTACT',          '联系方式',       'TEXT',     TRUE,  NULL, NULL, '手机号或邮箱',             20, TRUE),
    ('MERCHANT', 'HOME_ADDRESS',     '家庭地址',       'TEXTAREA', TRUE,  NULL, NULL, '详细到门牌号',             30, TRUE),
    ('MERCHANT', 'ID_CARD',          '身份证编号',     'TEXT',     TRUE,  NULL, NULL, '提交后将进行实名认证',     40, TRUE),
    ('MERCHANT', 'BUSINESS_LICENSE', '营业执照',       'IMAGE',    TRUE,  NULL, NULL, '请上传清晰的营业执照原件照片', 50, TRUE),
    ('MERCHANT', 'BUSINESS_SCOPE',   '经营范围',       'TEXTAREA', TRUE,  NULL, NULL, '与营业执照一致',           60, TRUE),
    ('MERCHANT', 'LEGAL_REP_CERT',   '法人证明',       'IMAGE',    TRUE,  NULL, NULL, '法人身份证或法人证明书',   70, TRUE),
    ('MERCHANT', 'SIGNBOARD',        '门头照片',       'IMAGE',    TRUE,  NULL, NULL, '门头照片将作为对外展示图标，需审核通过后展示', 100, TRUE),
    ('MERCHANT', 'COOPERATION_PLAN', '合作申请计划',   'TEXTAREA', TRUE,  NULL, NULL, '拟经营品类与合作方式',     130, TRUE),
    ('MERCHANT', 'LOCATION',         '场地定位',       'LOCATION', TRUE,  NULL, NULL, '在地图上点选经营场所位置', 140, TRUE),
    ('MERCHANT', 'SIGNED_AGREEMENT', '用户签署协议',   'IMAGE',    TRUE,  NULL, NULL, '请下载协议、签字盖章后上传扫描件', 150, TRUE),
    ('MERCHANT', 'MERCH_CATEGORY',   '经营品类',       'TEXT',     TRUE,  NULL, NULL, '主营品类，多个用逗号分隔', 160, TRUE),
    ('MERCHANT', 'BRAND_AUTH',       '品牌授权',       'IMAGE',    FALSE, NULL, NULL, '选填：代理品牌需提供授权书', 170, TRUE),
    ('MERCHANT', 'OTHER',            '其他附件',       'IMAGES',   FALSE, NULL, NULL, '选填，其他补充说明材料',   900, TRUE)
ON CONFLICT (applicant_type, material_code) DO NOTHING;

-- =====================================================================
-- 种子：三类主体的入驻说明（Q14：首期中文 + 柬语）
-- 状态 PUBLISHED，合同扫描件为空（由平台在「说明与合同配置页」上传后发新版本）
-- =====================================================================
INSERT INTO onboarding_contracts
    (applicant_type, version, title, content_html, lang, status, published_at, content_hash)
VALUES
    ('STATION', 'v1.0', '服务站入驻合作说明',
     '<h2>一、合作模式</h2><p>服务站作为平台在当地的线下交付与服务中心，负责设备的接收、保管、交付与售后接待。平台按履约单量向服务站结算服务费。</p>'
     || '<h2>二、准入条件</h2><ul><li>具备合法经营主体资格（营业执照、法人证明齐全）</li><li>拥有自有或长期租赁的经营场地，面积满足设备存放与作业需求</li><li>场地具备水电、网络与基本安防条件</li><li>按档位缴纳入驻保证金</li></ul>'
     || '<h2>三、保证金与授信额度</h2><p>保证金分三档：5,000 / 20,000 / 50,000 USD。授信额度 = 保证金 × 3，即该服务站可持有的寄售设备名义货值上限（15,000 / 60,000 / 150,000 USD）。额度仅用于计量寄售设备的名义货值天花板，<b>非现金授信、非贷款</b>。</p>'
     || '<h2>四、权利与义务</h2><ul><li>服务站须妥善保管寄售设备，因保管不善导致的损失由服务站承担</li><li>须配合平台完成设备盘点、调拨交接与回收回流</li><li>须保持门头与场地信息真实有效，变更须提前报备</li></ul>'
     || '<h2>五、费用与结算</h2><p>服务费按平台公示的结算规则定期结算；保证金在退出且结清全部款项后按约定退还。</p>',
     'zh', 'PUBLISHED', now(), 'station-zh-v1.0'),

    ('MANUFACTURER', 'v1.0', '厂家入驻合作说明',
     '<h2>一、合作模式</h2><p>厂家作为设备的生产与提供方，通过平台向服务站铺货寄售，设备货权在售出前始终归厂家所有。</p>'
     || '<h2>二、准入条件</h2><ul><li>具备合法生产或经营主体资格</li><li>提供有效的生产资质与品牌授权文件</li><li>按档位缴纳入驻保证金</li></ul>'
     || '<h2>三、保证金与授信额度</h2><p>保证金分三档：5,000 / 20,000 / 50,000 USD。授信额度 = 保证金 × 3，用于计量厂家可铺出的寄售设备名义货值上限。</p>'
     || '<h2>四、权利与义务</h2><ul><li>厂家须保证设备的质量与合规，并承担产品责任</li><li>须配合平台完成合格证出具、调拨与回收回流</li><li>寄售设备货权在用户取货前不转移</li></ul>',
     'zh', 'PUBLISHED', now(), 'manufacturer-zh-v1.0'),

    ('MERCHANT', 'v1.0', '商家入驻合作说明',
     '<h2>一、合作模式</h2><p>商家是与厂家、服务站并列的<b>独立经营主体</b>，拥有独立的账号、保证金与授信额度，不挂靠任何服务站。</p>'
     || '<h2>二、准入条件</h2><ul><li>具备合法经营主体资格（营业执照、法人证明齐全）</li><li>明确主营经营品类；代理品牌须提供品牌授权</li><li>拥有固定经营场所，并上传门头照片与场地定位</li><li>按档位缴纳入驻保证金</li></ul>'
     || '<h2>三、保证金与授信额度</h2><p>保证金分三档：5,000 / 20,000 / 50,000 USD。授信额度 = 保证金 × 3，即该商家可持有的寄售设备名义货值上限。</p>'
     || '<h2>四、权利与义务</h2><ul><li>商家自主经营、自负盈亏，独立承担经营责任</li><li>门头与场地信息须真实有效，变更须提前报备</li><li>可选关联经营所在地服务站，仅用于「附近商家」检索，不构成任何归属关系</li></ul>',
     'zh', 'PUBLISHED', now(), 'merchant-zh-v1.0'),

    ('STATION', 'v1.0', 'ការណែនាំអំពីការចូលរួមរបស់ស្ថានីយ',
     '<h2>១. គំរូសហការ</h2><p>ស្ថានីយជាមជ្ឈមណ្ឌលដឹកជញ្ជូន និងសេវាកម្មក្នុងតំបន់ ទទួលខុសត្រូវលើការទទួល រក្សាទុក និងប្រគល់ឧបករណ៍។</p>'
     || '<h2>២. លក្ខខណ្ឌចូលរួម</h2><ul><li>មានអាជ្ញាប័ណ្ណអាជីវកម្មត្រឹមត្រូវ</li><li>មានទីតាំងផ្ទាល់ខ្លួន ឬជួលរយៈពេលវែង</li><li>បង់ប្រាក់បញ្ចាំតាមកម្រិត</li></ul>'
     || '<h2>៣. ប្រាក់បញ្ចាំ និងដែនកំណត់ឥណទាន</h2><p>ប្រាក់បញ្ចាំមាន ៣ កម្រិត: 5,000 / 20,000 / 50,000 USD។ ដែនកំណត់ឥណទាន = ប្រាក់បញ្ចាំ × ៣។</p>',
     'km', 'PUBLISHED', now(), 'station-km-v1.0'),

    ('MANUFACTURER', 'v1.0', 'ការណែនាំអំពីការចូលរួមរបស់រោងចក្រ',
     '<h2>១. គំរូសហការ</h2><p>រោងចក្រជាអ្នកផលិត និងផ្គត់ផ្គង់ឧបករណ៍ ដាក់ទុកជាបញ្ចាំនៅស្ថានីយ។</p>'
     || '<h2>២. លក្ខខណ្ឌចូលរួម</h2><ul><li>មានអាជ្ញាប័ណ្ណផលិតកម្មត្រឹមត្រូវ</li><li>មានឯកសារអនុញ្ញាតម៉ាក</li><li>បង់ប្រាក់បញ្ចាំតាមកម្រិត</li></ul>',
     'km', 'PUBLISHED', now(), 'manufacturer-km-v1.0'),

    ('MERCHANT', 'v1.0', 'ការណែនាំអំពីការចូលរួមរបស់អាជីវករ',
     '<h2>១. គំរូសហការ</h2><p>អាជីវករជាអង្គភាព<b>ឯករាជ្យ</b> មានគណនី ប្រាក់បញ្ចាំ និងដែនកំណត់ឥណទានផ្ទាល់ខ្លួន។</p>'
     || '<h2>២. លក្ខខណ្ឌចូលរួម</h2><ul><li>មានអាជ្ញាប័ណ្ណអាជីវកម្មត្រឹមត្រូវ</li><li>មានទីតាំងអាជីវកម្ម និងរូបថតផ្លាកសញ្ញា</li><li>បង់ប្រាក់បញ្ចាំតាមកម្រិត</li></ul>',
     'km', 'PUBLISHED', now(), 'merchant-km-v1.0')
ON CONFLICT (applicant_type, lang, version) DO NOTHING;
