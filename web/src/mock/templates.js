import { TEMPLATES } from '../mockData';

// 产品模板字段的本地可编辑层：用户新增/编辑/删除的字段存 localStorage，
// 覆盖 mockData 里的只读定义，使"产品模板加字段"真正可用，并向下游产品/设备联动。
const KEY = 'claw_tpl_field_overrides';

function read() {
  try {
    return JSON.parse(localStorage.getItem(KEY)) || {};
  } catch (e) {
    return {};
  }
}

function write(o) {
  localStorage.setItem(KEY, JSON.stringify(o));
}

// 返回合并了本地覆盖的模板列表（模板为「类」，fields 为可自定义字段）
export function getTemplates() {
  const ov = read();
  return TEMPLATES.map((t) => (ov[t.id] ? { ...t, fields: ov[t.id] } : { ...t }));
}

// 保存某模板的字段数组（全量替换）
export function setTemplateFields(tplId, fields) {
  const ov = read();
  ov[tplId] = fields;
  write(ov);
}

// 取某模板的字段（含本地覆盖）
export function getTemplateFields(tplId) {
  const all = getTemplates();
  return (all.find((t) => t.id === tplId) || {}).fields || [];
}

let seq = 0;
export function genFieldKey() {
  seq += 1;
  return 'f_' + Date.now().toString(36) + '_' + seq;
}
