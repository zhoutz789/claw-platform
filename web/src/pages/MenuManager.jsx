import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Tree, Card, Input, Switch, Button, Space, message, Typography, Alert, Tag, Menu,
  Modal, Form, Select, Popconfirm, Radio, Tooltip, Spin,
} from 'antd';
import {
  PlusOutlined, SaveOutlined, ReloadOutlined, DeleteOutlined, EyeInvisibleOutlined,
  ArrowUpOutlined, ArrowDownOutlined, ExportOutlined, ImportOutlined, ExclamationCircleOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import PageCard from '../components/PageCard';
import {
  sanitizeNav, filterHidden, resolveIcon, ICON_OPTIONS,
} from '../menuStore';
import { NAV, ROUTES, navLabel } from '../nav';
import api from '../api';
import { loadPermissions } from '../permStore';
import { Perm } from '../components/Perm';

// 后端菜单布局接口（唯一数据源）：GET / PUT /v1/admin/menu-layout
const LAYOUT_URL = '/v1/admin/menu-layout';

// 内置默认菜单的 key -> label 索引。
// 用途一：后端尚未存 name（空显示名）时兜底成内置 i18n key，避免侧边栏出现空白菜单；
// 用途二：「恢复默认」时把内置树原样推给后端。
const BASE_LABEL_BY_KEY = (() => {
  const map = new Map();
  const walk = (nodes) => {
    for (const n of nodes || []) {
      if (typeof n.label === 'string') map.set(n.key, n.label);
      if (n.children) walk(n.children);
    }
  };
  walk(NAV);
  return map;
})();

/**
 * 权限码 -> 菜单 key：'menu:orders' -> 'orders'。
 * @param {string} code 权限码
 * @returns {string} 菜单 key（无 'menu:' 前缀时原样返回）
 */
const navKeyOf = (code) => {
  const s = typeof code === 'string' ? code : String(code ?? '');
  return s.startsWith('menu:') ? s.slice(5) : s;
};

/**
 * 把后端返回的扁平布局（按 sortNo 有序）还原成 nav 形状的树。
 * - parentCode 无法解析（父级不存在 / 成环 / 指向自己）的项一律降级为根，绝不产生死循环；
 * - 同层按 sortNo 升序，sortNo 缺失时退回后端数组顺序（即「保持原序」）。
 * @param {Array} items 后端 GET /v1/admin/menu-layout 的扁平列表
 * @returns {Array} nav 形状的导航树
 */
const buildNavFromItems = (items) => {
  const list = Array.isArray(items) ? items : [];
  const nodes = new Map(); // key -> nav 节点
  const parentOf = new Map(); // key -> 父级 key | null
  const metaByKey = new Map(); // key -> { sortNo, order }

  list.forEach((it, idx) => {
    if (!it || typeof it !== 'object') return;
    const key = navKeyOf(it.code);
    if (!key || nodes.has(key)) return; // 无 key / 重复 key 直接丢弃
    const node = { key, label: it.name || BASE_LABEL_BY_KEY.get(key) || key };
    // path 即使是空字符串也保留：空路径 = 分组，与 sanitizeNav 的语义保持一致
    if (typeof it.path === 'string') node.path = it.path;
    if (typeof it.icon === 'string') node.icon = it.icon;
    if (it.hidden === true) node.hidden = true;
    if (it.custom === true) node.custom = true;
    nodes.set(key, node);
    const pk = it.parentCode ? navKeyOf(it.parentCode) : null;
    parentOf.set(key, pk && pk !== key ? pk : null);
    const sortNo = Number(it.sortNo);
    metaByKey.set(key, { sortNo: Number.isFinite(sortNo) ? sortNo : Number.MAX_SAFE_INTEGER, order: idx });
  });

  // 沿 parentCode 上溯，检测该节点所在的挂载链是否成环
  const inCycle = (key) => {
    const seen = new Set();
    let cur = key;
    while (cur) {
      if (seen.has(cur)) return true;
      seen.add(cur);
      cur = nodes.has(cur) ? (parentOf.get(cur) || null) : null;
    }
    return false;
  };

  const roots = [];
  nodes.forEach((node, key) => {
    const pk = parentOf.get(key) || null;
    const parent = pk && !inCycle(key) ? nodes.get(pk) : null;
    if (parent) {
      if (!parent.children) parent.children = [];
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  });

  const sortLevel = (arr) => {
    const rankOf = (n) => metaByKey.get(n.key) || { sortNo: 0, order: 0 };
    arr.sort((a, b) => {
      const ra = rankOf(a);
      const rb = rankOf(b);
      return (ra.sortNo - rb.sortNo) || (ra.order - rb.order);
    });
    for (const n of arr) if (n.children) sortLevel(n.children);
  };
  sortLevel(roots);
  return roots;
};

/**
 * 把编辑态 treeData 拍平为后端 PUT /v1/admin/menu-layout 需要的 items。
 * - code = `menu:${key}`，parentCode = 父级存在时 `menu:${parentKey}`，否则 null；
 * - sortNo 按同层下标 (idx + 1) * 10，保证后续插入仍有空隙；
 * - name 仅在显示名不是 i18n key（不以 'nav:' 开头）时才下发 —— 否则后端沿用已存显示名，
 *   避免把 'nav:item.orders' 这类内部 key 写进后端覆盖掉真实文案。
 * @param {Array} nodes 编辑态 treeData（含 dataRef）
 * @param {string|null} parentKey 父级 key
 * @param {Array} out 累加器
 * @returns {Array} 布局项列表
 */
const flattenTreeForLayout = (nodes, parentKey = null, out = []) => {
  (nodes || []).forEach((n, idx) => {
    const dataRef = n.dataRef || n;
    const rawLabel = dataRef.label;
    const isI18nKey = typeof rawLabel === 'string' && rawLabel.startsWith('nav:');
    const item = {
      code: `menu:${n.key}`,
      parentCode: parentKey ? `menu:${parentKey}` : null,
      sortNo: (idx + 1) * 10,
      path: typeof dataRef.path === 'string' ? dataRef.path : null,
      icon: typeof dataRef.icon === 'string' ? dataRef.icon : null,
      hidden: dataRef.hidden === true,
      custom: dataRef.custom === true,
    };
    if (!isI18nKey) item.name = typeof rawLabel === 'string' ? rawLabel : null;
    out.push(item);
    if (n.children && n.children.length) flattenTreeForLayout(n.children, n.key, out);
  });
  return out;
};

const { Text, Paragraph } = Typography;

// 顶级分组标志：新增时挂到根层级，成为一个一级菜单
const ROOT_KEY = '__root__';

// 深拷贝 nav -> treeData（dataRef 为 nav 节点的克隆，避免直接改动 BASE_NAV）
const cloneNav = (n) => {
  const c = { ...n };
  if (n.children) c.children = n.children.map(cloneNav);
  else delete c.children;
  return c;
};
const toTreeData = (nav) =>
  nav.map((n) => ({
    key: n.key,
    title: n.label,
    className: n.hidden ? 'wb-menu-hidden' : undefined,
    children: n.children ? toTreeData(n.children) : undefined,
    dataRef: cloneNav(n),
  }));

// 把编辑态 treeData 的 title 重新解析成「当前语言下的显示名」。
// 结构（dataRef）不动，只刷新展示文案，因此切换语言不会丢失任何编辑中的状态。
const relabelTree = (nodes) =>
  nodes.map((n) => ({
    ...n,
    title: navLabel(n.dataRef || n),
    children: n.children ? relabelTree(n.children) : n.children,
  }));

// 由编辑态 treeData 还原为导航结构（剥离展示字段，保留 key/label/path/icon/hidden/custom/children）
// 注意：path 即使是空字符串也要原样保留 —— menuStore.mergeWithBase 同样会接受空字符串，
// 两边保持一致，「保存后不再提示未保存」的判定才准；空路径在渲染层按「分组」处理。
const rebuildNav = (tree) =>
  tree.map((t) => {
    const n = { ...t.dataRef };
    if (n.hidden !== true) delete n.hidden;
    if (n.custom !== true) delete n.custom;
    if (t.children && t.children.length) n.children = rebuildNav(t.children);
    else delete n.children;
    return n;
  });

// 「已保存态」快照：与 rebuildNav(tree) 比对即可判定是否有未保存改动。
// 取代原先与 localStorage（getNav()）比对的写法 —— 现在布局的唯一数据源在后端。
const snapshotOf = (tree) => JSON.stringify(rebuildNav(tree));

const findNode = (tree, key) => {
  for (const t of tree) {
    if (t.key === key) return t;
    if (t.children) {
      const hit = findNode(t.children, key);
      if (hit) return hit;
    }
  }
  return null;
};

// 收集树中已存在的全部 key（用于校验新增项 key 不冲突）
const collectKeys = (nodes, out = []) => {
  for (const n of nodes) {
    out.push(n.key);
    if (n.children) collectKeys(n.children, out);
  }
  return out;
};

// 收集树中已存在的全部 path（用于校验路径不重复）
const collectPaths = (nodes, out = []) => {
  for (const n of nodes) {
    if (n.dataRef && n.dataRef.path) out.push(n.dataRef.path);
    if (n.children) collectPaths(n.children, out);
  }
  return out;
};

// 收集可作为父级的分组节点（有子节点的、或没有独立页面的空分组），带缩进前缀体现层级
const collectGroupOptions = (nodes, prefix = '', out = []) => {
  for (const n of nodes) {
    const isGroup = !!n.children || !n.dataRef?.path;
    if (isGroup) {
      out.push({ value: n.key, label: `${prefix}${n.title}` });
      if (n.children) collectGroupOptions(n.children, `${prefix}　　`, out);
    }
  }
  return out;
};

// 递归在 nodes 及其 children 里查找包含 key 的那一层兄弟数组与下标。
// 返回 { arr, index }（arr 为该层的兄弟数组引用，index 为节点在数组中的下标）；
// 找不到则返回 null。用于同级上移/下移。
const findParentChildren = (nodes, key) => {
  for (let i = 0; i < nodes.length; i++) {
    if (nodes[i].key === key) return { arr: nodes, index: i };
    if (nodes[i].children) {
      const hit = findParentChildren(nodes[i].children, key);
      if (hit) return hit;
    }
  }
  return null;
};

const updateNode = (tree, key, patch) =>
  tree.map((t) => {
    if (t.key === key) {
      const dataRef = { ...t.dataRef, ...patch };
      return { ...t, dataRef, title: dataRef.label, className: dataRef.hidden ? 'wb-menu-hidden' : undefined };
    }
    if (t.children) return { ...t, children: updateNode(t.children, key, patch) };
    return t;
  });

const removeNode = (tree, key) =>
  tree
    .filter((t) => t.key !== key)
    .map((t) => (t.children ? { ...t, children: removeNode(t.children, key) } : t));

// 判断 child 是否落在 parent 的子树内（用于禁止把节点拖进它自己的后代，避免循环引用）
const isDescendant = (parent, node) => {
  if (!parent || !parent.children) return false;
  for (const c of parent.children) {
    if (c.key === node.key) return true;
    if (isDescendant(c, node)) return true;
  }
  return false;
};

// 经典 antd Tree 拖拽落点算法（克隆后操作，避免改动原引用）
const cloneTree = (arr) =>
  arr.map((n) => ({
    key: n.key,
    title: n.title,
    className: n.className,
    children: n.children ? cloneTree(n.children) : undefined,
    dataRef: n.dataRef,
  }));

// 同级（同一父级）内移动节点位置。仅重排兄弟顺序，不改变所属父级，不影响跨中心归类。
// dir: 'up' 上移一位 / 'down' 下移一位；越界时 no-op 返回原 clone。
const moveSibling = (tree, key, dir) => {
  const clone = cloneTree(tree);
  const found = findParentChildren(clone, key);
  if (!found) return clone;
  const { arr, index } = found;
  const target = dir === 'up' ? index - 1 : index + 1;
  if (target < 0 || target >= arr.length) return clone;
  const [node] = arr.splice(index, 1);
  arr.splice(target, 0, node);
  return clone;
};

const findParentKey = (nodes, key, parent = null) => {
  for (const n of nodes) {
    if (n.key === key) return parent ? parent.key : ROOT_KEY;
    if (n.children) {
      const hit = findParentKey(n.children, key, n);
      if (hit !== null) return hit;
    }
  }
  return null;
};

const reparent = (tree, key, newParentKey) => {
  const currentParent = findParentKey(tree, key);
  if (newParentKey === currentParent || newParentKey === key) return tree;
  const clone = cloneTree(tree);
  const node = findNode(clone, key);
  if (!node) return clone;
  const newParent = newParentKey === ROOT_KEY ? null : findNode(clone, newParentKey);
  if (newParent && isDescendant(newParent, node)) return clone; // 禁止挂到自己的后代下
  const without = removeNode(clone, key);
  return insertNode(without, newParentKey, { ...node });
};

// 由路径推导菜单 key：/task-drone -> task-drone，保证与路由段一致（选中态与跳转依赖它）
const keyFromPath = (path) => String(path || '').trim().replace(/^\/+/, '').replace(/\//g, '-').replace(/\s+/g, '');

// 生成自建分组的唯一 key（用户自建项带 custom 标记，合并时不会被当作旧残留丢弃）
const newGroupKey = () => `grp_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;

// 迷你侧边栏预览：与真实渲染一致（可见导航 + 图标回退）
const MiniPreview = ({ nav }) => {
  const build = (nodes, depth = 0) =>
    nodes.map((n) => {
      const Icon = resolveIcon(n);
      return n.children
        ? { key: n.key, icon: <Icon />, label: navLabel(n), children: build(n.children, depth + 1) }
        : { key: n.key, icon: depth === 0 ? <Icon /> : undefined, label: navLabel(n) };
    });
  return (
    <Menu
      mode="inline"
      defaultOpenKeys={nav.filter((n) => n.children).map((n) => n.key)}
      items={build(nav)}
      style={{ background: 'transparent', borderInlineEnd: 'none' }}
    />
  );
};

export default function MenuManager() {
  const { t, i18n } = useTranslation();
  // 编辑态树（内存态）。初始为空，挂载后由 GET /v1/admin/menu-layout 填充。
  const [tree, setTree] = useState([]);
  // 已保存态快照（JSON 字符串），用于判定 dirty
  const [savedSnapshot, setSavedSnapshot] = useState('[]');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [selectedKey, setSelectedKey] = useState(null);
  const [addOpen, setAddOpen] = useState(false);
  const [addKind, setAddKind] = useState('page');
  const [form] = Form.useForm();
  const fileRef = useRef(null);

  const selected = selectedKey ? findNode(tree, selectedKey) : null;
  // 选中节点在当前语言下的显示名（改名输入框与提示文案都用它，避免把 i18n key 露给用户）
  const selectedDisplay = selected ? navLabel(selected.dataRef || selected) : '';

  // 展示用的树：结构与 tree 同源，title 随语言实时重解析
  const displayTree = useMemo(() => relabelTree(tree), [tree, i18n.language]);

  const groupOptions = useMemo(() => [
    { value: ROOT_KEY, label: t('system:menuManager.rootOption') },
    ...collectGroupOptions(displayTree),
  ], [displayTree, t, i18n.language]);

  const dirty = useMemo(() => snapshotOf(tree) !== savedSnapshot, [tree, savedSnapshot]);

  // 用后端扁平布局重建编辑树，并把快照同步为「已保存态」
  const applyItems = useCallback((items) => {
    const nextTree = toTreeData(buildNavFromItems(items));
    setTree(nextTree);
    setSavedSnapshot(snapshotOf(nextTree));
  }, []);

  // 挂载时从后端读取菜单布局（唯一数据源）。
  // 依赖刻意只留挂载这一次：语言切换会让 t 变更，若因此重新加载会把用户未保存的编辑冲掉。
  useEffect(() => {
    let alive = true;
    setLoading(true);
    api
      .get(LAYOUT_URL)
      .then((items) => { if (alive) applyItems(items); })
      .catch((e) => {
        if (alive) message.error(t('system:menuManager.msg.loadFailed', { msg: e.message }));
      })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * 保存：整棵树一次性原子提交到后端（PUT /v1/admin/menu-layout），
   * 成功后重新拉取「我的权限」刷新侧边栏，再回读布局重置快照。
   * @returns {Promise<void>}
   */
  const save = async () => {
    setSaving(true);
    try {
      await api.put(LAYOUT_URL, { items: flattenTreeForLayout(tree) });
      await loadPermissions(); // 侧边栏从 GET /v1/admin/permissions/mine 重新取菜单
      applyItems(await api.get(LAYOUT_URL));
      message.success(t('system:menuManager.msg.saved'));
    } catch (e) {
      message.error(t('system:menuManager.msg.saveFailed', { msg: e.message }));
    } finally {
      setSaving(false);
    }
  };

  /**
   * 恢复默认：把 nav.js 的内置菜单整棵推给后端（后端在一个事务里 upsert，
   * 并删除未出现在载荷里的 custom 行），再刷新权限与编辑树。
   * @returns {Promise<void>}
   */
  const reset = async () => {
    setSaving(true);
    try {
      await api.put(LAYOUT_URL, { items: flattenTreeForLayout(toTreeData(NAV)) });
      await loadPermissions();
      applyItems(await api.get(LAYOUT_URL));
      setSelectedKey(null);
      message.success(t('system:menuManager.msg.reset'));
    } catch (e) {
      message.error(t('system:menuManager.msg.resetFailed', { msg: e.message }));
    } finally {
      setSaving(false);
    }
  };

  const openAdd = (kind) => {
    setAddKind(kind);
    setAddOpen(true);
  };

  // 弹窗内的 Form 在 Modal 打开后才会挂载，因此表单初值在这里灌入（打开前 setFieldsValue 会被 initialValues 覆盖）
  useEffect(() => {
    if (!addOpen) return;
    form.setFieldsValue({
      kind: addKind,
      label: '',
      parentKey: selected && (!selected.dataRef?.path || selected.children) ? selected.key : ROOT_KEY,
      path: undefined,
      icon: undefined,
    });
  }, [addOpen, addKind]);

  // 新增菜单项 / 顶级分组：表单在提交时统一校验，避免产生重复 key、重复 path 或死链
  const submitAdd = async () => {
    let values;
    try {
      values = await form.validateFields();
    } catch (e) {
      return;
    }
    const label = String(values.label || '').trim();
    const parentKey = values.parentKey || ROOT_KEY;
    const kind = values.kind;

    if (kind === 'group') {
      const key = newGroupKey();
      const node = {
        key,
        title: label,
        className: undefined,
        children: [],
        dataRef: { key, label, custom: true, ...(values.icon ? { icon: values.icon } : {}) },
      };
      setTree(insertNode(tree, parentKey, node));
      setSelectedKey(key);
      message.success(t('system:menuManager.msg.groupAdded', { label }));
    } else {
      const path = String(values.path || '').trim();
      const key = keyFromPath(path);
      if (collectKeys(tree).includes(key)) {
        message.error(t('system:menuManager.msg.keyExists', { key }));
        return;
      }
      if (collectPaths(tree).includes(path)) {
        message.error(t('system:menuManager.msg.pathUsed', { path }));
        return;
      }
      const node = {
        key,
        title: label,
        className: undefined,
        children: undefined,
        dataRef: {
          key, label, path, custom: true, ...(values.icon ? { icon: values.icon } : {}),
        },
      };
      setTree(insertNode(tree, parentKey, node));
      setSelectedKey(key);
      message.success(t('system:menuManager.msg.itemAdded', { label }));
    }
    setAddOpen(false);
  };

  // 统计一个节点的全部子孙数量（用于删除前的二次确认提示）
  const countDescendants = (node) => {
    if (!node || !node.children || !node.children.length) return 0;
    return node.children.reduce((sum, kid) => sum + 1 + countDescendants(kid), 0);
  };

  const delGroup = () => {
    if (!selected) return;
    if (selected.dataRef.path) {
      message.warning(t('system:menuManager.msg.cannotDeletePage'));
      return;
    }
    const kidCount = countDescendants(selected);
    if (kidCount > 0) {
      message.warning(t('system:menuManager.msg.hasChildren', { label: selectedDisplay, count: kidCount }));
      return;
    }
    if (selected.dataRef.custom !== true) {
      message.warning(t('system:menuManager.msg.builtinGroup'));
      return;
    }
    setTree(removeNode(tree, selected.key));
    setSelectedKey(null);
    message.success(t('system:menuManager.msg.deleted'));
  };

  // 删除按钮的禁用原因（空串 = 可删）。规则与 delGroup 的校验一一对应，保证提示与行为一致
  const deleteHint = (() => {
    if (!selected) return '';
    if (selected.dataRef.path) return t('system:menuManager.msg.cannotDeletePage');
    const kidCount = countDescendants(selected);
    if (kidCount > 0) return t('system:menuManager.hintDelete.hasChildren', { count: kidCount });
    if (selected.dataRef.custom !== true) return t('system:menuManager.msg.builtinGroup');
    return '';
  })();

  // 导出：把当前编辑态（或已保存态）导出为 JSON 文件，便于备份 / 换机恢复
  const exportConfig = () => {
    const data = sanitizeNav(rebuildNav(tree));
    if (!data.length) {
      message.warning(t('system:menuManager.msg.exportEmpty'));
      return;
    }
    const stamp = new Date().toISOString().slice(0, 10).replace(/-/g, '');
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `claw-menu-${stamp}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    message.success(t('system:menuManager.msg.exported'));
  };

  // 导入：读取 JSON -> 清洗 -> 落库，并用合并后的结果重建编辑树
  const importConfig = (file) => {
    const reader = new FileReader();
    reader.onload = () => {
      let clean;
      try {
        const parsed = JSON.parse(String(reader.result || ''));
        clean = sanitizeNav(parsed);
      } catch (e) {
        // sanitizeNav 一并放进 try：结构异常（如循环引用、非法节点）也要给老板明确提示，而不是白屏
        console.warn('[MenuManager] 导入菜单配置失败：', e);
        message.error(t('system:menuManager.msg.importBadJson'));
        return;
      }
      if (!clean.length) {
        message.error(t('system:menuManager.msg.importEmpty'));
        return;
      }
      // 仅载入编辑区（标脏），由用户点「保存布局」一次性提交到后端
      setTree(toTreeData(clean));
      setSelectedKey(null);
      message.success(t('system:menuManager.msg.imported', { count: clean.length }));
    };
    reader.onerror = () => message.error(t('system:menuManager.msg.importReadError'));
    reader.readAsText(file);
  };

  const titleRender = (node) => {
    const siblingInfo = findParentChildren(tree, node.key);
    const sib = siblingInfo || { arr: [], index: 0 };
    const idx = sib.index;
    const len = sib.arr.length;
    return (
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8,
          opacity: node.className === 'wb-menu-hidden' ? 0.45 : 1,
        }}
      >
        <span>
          {node.title}
          {node.className === 'wb-menu-hidden' && <Tag color="default" style={{ marginLeft: 6 }}>{t('system:menuManager.tag.hidden')}</Tag>}
          {node.dataRef?.custom && <Tag color="green" style={{ marginLeft: 6 }}>{t('system:menuManager.tag.custom')}</Tag>}
        </span>
        <Space size={4} onClick={(e) => e.stopPropagation()}>
          <Button
            type="link"
            size="small"
            icon={<ArrowUpOutlined />}
            title={t('action.moveUp')}
            disabled={idx === 0}
            onClick={(e) => {
              e.stopPropagation();
              setTree(moveSibling(tree, node.key, 'up'));
            }}
          >
            {t('action.moveUp')}
          </Button>
          <Button
            type="link"
            size="small"
            icon={<ArrowDownOutlined />}
            title={t('action.moveDown')}
            disabled={idx === len - 1}
            onClick={(e) => {
              e.stopPropagation();
              setTree(moveSibling(tree, node.key, 'down'));
            }}
          >
            {t('action.moveDown')}
          </Button>
        </Space>
      </div>
    );
  };

  // 编辑已有项的路由：允许改，但必须是 App.jsx 里注册过的路由，否则点了会白屏
  const pathWarning = (() => {
    if (!selected) return null;
    const p = selected.dataRef.path;
    if (!p) return null;
    return ROUTES.includes(p) ? null : t('system:menuManager.warn.unregistered');
  })();

  return (
    <PageCard
      title={t('system:menuManager.title')}
      extra={
        <Space wrap>
          <Perm code="menu:update"><Button icon={<PlusOutlined />} onClick={() => openAdd('page')}>{t('action.addItem')}</Button></Perm>
          <Perm code="menu:update"><Button icon={<PlusOutlined />} onClick={() => openAdd('group')}>{t('action.addGroup')}</Button></Perm>
          <Perm code="menu:update"><Button icon={<ExportOutlined />} onClick={exportConfig}>{t('action.exportConfig')}</Button></Perm>
          <Perm code="menu:update"><Button icon={<ImportOutlined />} onClick={() => fileRef.current && fileRef.current.click()}>{t('action.importConfig')}</Button></Perm>
          <Perm code="menu:update">
            <Popconfirm
              title={t('confirm.resetMenu')}
              description={t('confirm.resetMenuDesc')}
              okText={t('confirm.resetOk')}
              cancelText={t('action.cancel')}
              okButtonProps={{ danger: true }}
              onConfirm={reset}
            >
              <Button icon={<ReloadOutlined />}>{t('action.resetDefault')}</Button>
            </Popconfirm>
          </Perm>
          <Perm code="menu:update">
            <Button
              type="primary"
              icon={<SaveOutlined />}
              onClick={save}
              disabled={!dirty}
              loading={saving}
            >
              {t('action.saveLayout')}
            </Button>
          </Perm>
          <input
            ref={fileRef}
            type="file"
            accept="application/json,.json"
            style={{ display: 'none' }}
            onChange={(e) => {
              const file = e.target.files && e.target.files[0];
              if (file) importConfig(file);
              e.target.value = ''; // 允许重复导入同一个文件
            }}
          />
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={t('system:menuManager.alert.title')}
        description={
          <span>
            {t('system:menuManager.alert.descPrefix')}
            <b>{t('system:menuManager.alert.descEmph')}</b>
            {t('system:menuManager.alert.descMid')}
            <b>{t('system:menuManager.alert.descEmph2')}</b>
            {t('system:menuManager.alert.descSuffix')}
          </span>
        }
      />

      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        {/* 左：结构编辑器 */}
        <Card className="wb-card" title={t('system:menuManager.card.structure')} style={{ flex: '1 1 420px', minWidth: 360 }}>
          {loading ? (
            <div style={{ padding: 24, textAlign: 'center' }}><Spin /></div>
          ) : (
            <Tree
              blockNode
              treeData={displayTree}
              titleRender={titleRender}
              onSelect={(keys) => setSelectedKey(keys[0] || null)}
              selectedKeys={selectedKey ? [selectedKey] : []}
              defaultExpandAll
            />
          )}
          <Paragraph type="secondary" style={{ marginTop: 12, fontSize: 12 }}>
            {t('system:menuManager.hint.reorder')}
          </Paragraph>
        </Card>

        {/* 右：属性 + 预览 */}
        <div style={{ flex: '1 1 360px', minWidth: 320 }}>
          <Card className="wb-card" title={t('system:menuManager.card.props')} style={{ marginBottom: 16 }}>
            {selected ? (
              <Space direction="vertical" style={{ width: '100%' }} size="middle">
                <div>
                  <Text type="secondary">{t('system:menuManager.prop.displayName')}</Text>
                  <Input
                    value={selectedDisplay}
                    onChange={(e) => setTree(updateNode(tree, selected.key, { label: e.target.value }))}
                    placeholder={t('system:menuManager.ph.displayName')}
                  />
                </div>
                <div>
                  <Text type="secondary">{t('system:menuManager.prop.path')}</Text>
                  <Input
                    value={selected.dataRef.path || ''}
                    onChange={(e) => setTree(updateNode(tree, selected.key, { path: e.target.value.trim() }))}
                    placeholder={t('system:menuManager.ph.path')}
                    allowClear
                  />
                  {pathWarning && (
                    <div style={{ marginTop: 6 }}>
                      <Tag icon={<ExclamationCircleOutlined />} color="warning">{pathWarning}</Tag>
                    </div>
                  )}
                </div>
                <div>
                  <Text type="secondary">{t('system:menuManager.prop.icon')}</Text>
                  <Select
                    allowClear
                    showSearch
                    style={{ width: '100%' }}
                    placeholder={t('system:menuManager.ph.icon')}
                    value={typeof selected.dataRef.icon === 'string' ? selected.dataRef.icon : undefined}
                    options={ICON_OPTIONS}
                    filterOption={(input, option) => String(option.value).toLowerCase().includes(String(input).toLowerCase())}
                    onChange={(v) => setTree(updateNode(tree, selected.key, { icon: v }))}
                  />
                </div>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <Space>
                    <EyeInvisibleOutlined />
                    <Text>{t('action.hideInMenu')}</Text>
                  </Space>
                  <Switch
                    checked={!!selected.dataRef.hidden}
                    onChange={(v) => setTree(updateNode(tree, selected.key, { hidden: v }))}
                  />
                </div>
                <div>
                  <Text type="secondary">{t('system:menuManager.form.parent')}</Text>
                  <Select
                    style={{ width: '100%' }}
                    value={findParentKey(tree, selected.key)}
                    options={groupOptions}
                    onChange={(pk) => setTree(reparent(tree, selected.key, pk))}
                  />
                </div>
                <Tooltip title={deleteHint}>
                  <span>
                    <Perm code="menu:update">
                      <Button danger icon={<DeleteOutlined />} onClick={delGroup} disabled={!!deleteHint} block>
                        {t('action.deleteGroup')}
                      </Button>
                    </Perm>
                  </span>
                </Tooltip>
              </Space>
            ) : (
              <Text type="secondary">{t('system:menuManager.empty.selectNode')}</Text>
            )}
          </Card>

          <Card className="wb-card" title={t('system:menuManager.card.preview')}>
            <MiniPreview nav={sanitizeNav(filterHidden(rebuildNav(tree)))} />
          </Card>
        </div>
      </div>

      {dirty && (
        <Alert type="warning" showIcon style={{ marginTop: 16 }}
          message={t('system:menuManager.warn.dirty')} />
      )}

      <Modal
        title={t('system:menuManager.modal.addTitle')}
        open={addOpen}
        onOk={submitAdd}
        onCancel={() => setAddOpen(false)}
        okText={t('system:menuManager.modal.addOk')}
        cancelText={t('action.cancel')}
      >
        <Form form={form} layout="vertical" initialValues={{ kind: 'page', parentKey: ROOT_KEY }} preserve={false}>
          <Form.Item name="kind" label={t('system:menuManager.form.kind')}>
            <Radio.Group
              optionType="button"
              buttonStyle="solid"
              options={[
                { value: 'page', label: t('system:menuManager.form.kindPage') },
                { value: 'group', label: t('system:menuManager.form.kindGroup') },
              ]}
              onChange={(e) => setAddKind(e.target.value)}
            />
          </Form.Item>
          <Form.Item
            name="label"
            label={t('system:menuManager.form.name')}
            rules={[
              { required: true, message: t('system:menuManager.validate.nameRequired') },
              { max: 30, message: t('system:menuManager.validate.nameMax') },
            ]}
          >
            <Input placeholder={t('system:menuManager.form.namePlaceholder')} />
          </Form.Item>
          <Form.Item name="parentKey" label={t('system:menuManager.form.parent')}>
            <Select options={groupOptions} placeholder={t('action.selectParentGroup')} />
          </Form.Item>
          {addKind === 'page' && (
            <Form.Item
              name="path"
              label={t('system:menuManager.form.path')}
              extra={t('system:menuManager.form.pathExtra')}
              rules={[
                { required: true, message: t('system:menuManager.validate.pathRequired') },
                {
                  validator: (_, v) => {
                    const p = String(v || '').trim();
                    if (!p) return Promise.resolve();
                    if (!p.startsWith('/')) return Promise.reject(new Error(t('system:menuManager.validate.pathSlash')));
                    if (collectPaths(tree).includes(p)) return Promise.reject(new Error(t('system:menuManager.validate.pathUsed')));
                    const key = keyFromPath(p);
                    if (collectKeys(tree).includes(key)) return Promise.reject(new Error(t('system:menuManager.validate.keyExists', { key })));
                    if (!ROUTES.includes(p)) return Promise.reject(new Error(t('system:menuManager.validate.pathUnregistered')));
                    return Promise.resolve();
                  },
                },
              ]}
            >
              <Select
                showSearch
                allowClear
                placeholder="/orders"
                options={ROUTES.filter((r) => !collectPaths(tree).includes(r)).map((r) => ({ value: r, label: r }))}
              />
            </Form.Item>
          )}
          <Form.Item name="icon" label={t('system:menuManager.form.icon')}>
            <Select
              allowClear
              showSearch
              placeholder={t('system:menuManager.form.iconPlaceholder')}
              options={ICON_OPTIONS}
              filterOption={(input, option) => String(option.value).toLowerCase().includes(String(input).toLowerCase())}
            />
          </Form.Item>
        </Form>
      </Modal>
    </PageCard>
  );
}

// 在指定父级下追加节点；parentKey 为 ROOT_KEY 时追加到根层级。
// 目标父级原本是叶子（无 children）时，会自动升级为分组。
const insertNode = (tree, parentKey, node) => {
  if (parentKey === ROOT_KEY) return [...tree, node];
  return tree.map((t) => {
    if (t.key === parentKey) {
      return { ...t, children: [...(t.children || []), node] };
    }
    if (t.children) return { ...t, children: insertNode(t.children, parentKey, node) };
    return t;
  });
};
