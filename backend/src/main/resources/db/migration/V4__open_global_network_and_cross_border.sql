-- =====================================================================
-- Claw 平台 V4 增量表（开放全球网络 + 跨境物资转移结算）
-- 依据：PRD v1.1 决策 D33 修订（取消国家限制，全球一家） + 新增 D34（跨境结算各算各的）
-- 设计：
--   1) 取消「排除国」概念：countries 不再有 EXCLUDED 状态/分区，
--      所有国家都是全球网络中的一个互通节点。
--   2) node_role：国家在全球网络中的角色
--      OPERATOR(平台自营落地) / SUPPLIER(商品资产供给) / MARKET(消费与商家网络) / HUB(供给+市场枢纽)
--   3) trade_policy_json(jsonb)：各国对跨境物资转移「各算各的」自有规则
--      （进口关税 / 出口退税 / 本地增值税 / 结算币种 / 必备单证 / 合规说明）。
--   4) 中国 CHN = HUB：全球商品供给方，可服务全球商家网络。
--   5) 修正 V3 中误用 'EXCLUDED' 占位的 region，改为真实地理分区。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 新增列
-- ---------------------------------------------------------------------
ALTER TABLE claw.countries ADD COLUMN node_role VARCHAR(16) NOT NULL DEFAULT 'OPERATOR';
ALTER TABLE claw.countries ADD COLUMN trade_policy_json JSONB;

-- ---------------------------------------------------------------------
-- 2. 试点柬埔寨：平台自营运营节点
-- ---------------------------------------------------------------------
UPDATE claw.countries SET node_role = 'OPERATOR' WHERE code = 'KHM';

-- ---------------------------------------------------------------------
-- 3. 原 EXCLUDED 行 → 开放为全球节点（全球一家，互通有无）
--    CHN 设为 HUB（商品供给方 + 服务全球商家网络枢纽）；其余设为 MARKET
-- ---------------------------------------------------------------------
UPDATE claw.countries
SET region='EAST_ASIA', status='ACTIVE', node_role='HUB', data_residency=FALSE,
    regulatory_note='全球供给与商家网络枢纽：向全球供给车辆/电池/光伏设备，并服务全球商家网络'
WHERE code='CHN';

UPDATE claw.countries SET region='NORTH_AMERICA', status='ACTIVE', node_role='MARKET' WHERE code='USA';
UPDATE claw.countries SET region='EUROPE',        status='ACTIVE', node_role='MARKET' WHERE code='GBR';
UPDATE claw.countries SET region='EUROPE',        status='ACTIVE', node_role='MARKET' WHERE code='DEU';
UPDATE claw.countries SET region='EUROPE',        status='ACTIVE', node_role='MARKET' WHERE code='FRA';
UPDATE claw.countries SET region='EAST_ASIA',     status='ACTIVE', node_role='MARKET' WHERE code='JPN';
UPDATE claw.countries SET region='EAST_ASIA',     status='ACTIVE', node_role='MARKET' WHERE code='KOR';
UPDATE claw.countries SET region='OCEANIA',       status='ACTIVE', node_role='MARKET' WHERE code='AUS';
UPDATE claw.countries SET region='NORTH_AMERICA', status='ACTIVE', node_role='MARKET' WHERE code='CAN';
UPDATE claw.countries SET region='SEA',           status='ACTIVE', node_role='MARKET' WHERE code='SGP';
UPDATE claw.countries SET region='WEST_ASIA',     status='ACTIVE', node_role='MARKET' WHERE code='ARE';
UPDATE claw.countries SET region='WEST_ASIA',     status='ACTIVE', node_role='MARKET' WHERE code='QAT';
UPDATE claw.countries SET region='WEST_ASIA',     status='ACTIVE', node_role='MARKET' WHERE code='SAU';

-- ---------------------------------------------------------------------
-- 4. 规划中目标国：平台将自营落地的运营节点
-- ---------------------------------------------------------------------
UPDATE claw.countries SET node_role='OPERATOR' WHERE status='PLANNED';

-- ---------------------------------------------------------------------
-- 5. 跨境结算政策种子（代表国；其余国家引擎回退默认策略）
--    trade_policy_json 是「各算各的」的唯一事实来源：牵扯两国贸易的物资转移，
--    出口国按自身退税/单证计算，进口国按自身关税/增值税/单证计算。
-- ---------------------------------------------------------------------
UPDATE claw.countries SET trade_policy_json = '{
  "import_duty_rate": 0.15,
  "export_rebate_rate": 0.00,
  "vat_rate": 0.10,
  "fx_settlement": "LOCAL",
  "required_import_docs": ["commercial_invoice","bill_of_lading","import_license","customs_declaration"],
  "required_export_docs": ["export_declaration","commercial_invoice"],
  "compliance_note": "柬埔寨：进口电动车与电池可享投资优惠，单证经海关单一窗口线上提交"
}'::jsonb WHERE code='KHM';

UPDATE claw.countries SET trade_policy_json = '{
  "import_duty_rate": 0.07,
  "export_rebate_rate": 0.13,
  "vat_rate": 0.13,
  "fx_settlement": "OPEN",
  "required_import_docs": ["commercial_invoice","packing_list","import_customs_declaration","ccc_cert"],
  "required_export_docs": ["export_customs_declaration","commercial_invoice","export_rebate_filing"],
  "compliance_note": "中国：出口退税在线申报；作为全球供给枢纽向各运营国供货"
}'::jsonb WHERE code='CHN';

UPDATE claw.countries SET trade_policy_json = '{
  "import_duty_rate": 0.03,
  "export_rebate_rate": 0.00,
  "vat_rate": 0.00,
  "fx_settlement": "LOCAL",
  "required_import_docs": ["commercial_invoice","bill_of_lading","cbp_entry","fda_or_epa_as_needed"],
  "required_export_docs": ["export_declaration","commercial_invoice"],
  "compliance_note": "美国：关税依 HTS 编码；州销售税另计，不在进口环节统一征收"
}'::jsonb WHERE code='USA';

UPDATE claw.countries SET trade_policy_json = '{
  "import_duty_rate": 0.04,
  "export_rebate_rate": 0.00,
  "vat_rate": 0.19,
  "fx_settlement": "EUR",
  "required_import_docs": ["commercial_invoice","preference_cert_eur1","import_vat_filing"],
  "required_export_docs": ["export_declaration","commercial_invoice"],
  "compliance_note": "欧盟：共同对外关税 CET；进口增值税可在申报环节抵扣"
}'::jsonb WHERE code='DEU';

COMMENT ON COLUMN claw.countries.node_role IS '国家在全球网络中的角色：OPERATOR/SUPPLIER/MARKET/HUB';
COMMENT ON COLUMN claw.countries.trade_policy_json IS '跨境物资转移「各算各的」自有规则：关税/退税/增值税/结算币种/必备单证';
