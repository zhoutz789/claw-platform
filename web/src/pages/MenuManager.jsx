import { useMemo, useState } from 'react';
import {
  Tree, Card, Input, Switch, Button, Space, message, Typography, Divider, Alert, Tag, Menu,
} from 'antd';
import {
  AppstoreOutlined, PlusOutlined, SaveOutlined, ReloadOutlined, DeleteOutlined, EyeInvisibleOutlined,
  ArrowUpOutlined, ArrowDownOutlined,
} from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { useMenuNav, getNav, setNav, resetNav, ICON_BY_KEY, sanitizeNav } from '../menuStore';

const { Text, Paragraph, Title } = Typography;

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

// 由编辑态 treeData 还原为导航结构（剥离展示字段，保留 key/label/path/hidden/children）
const rebuildNav = (tree) =>
  tree.map((t) => {
    const n = { ...t.dataRef };
    if (t.children && t.children.length) n.children = rebuildNav(t.children);
    else delete n.children;
    return n;
  });

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

// antd Tree 落点守卫：不允许把节点拖入它自身的后代中（会造成父子循环，渲染即崩）
const allowDrop = ({ dragNode, dropNode }) => !isDescendant(dragNode, dropNode);

// 经典 antd Tree 拖拽落点算法（克隆后操作，避免改动原引用）
const cloneTree = (arr) =>
  arr.map((n) => ({
    key: n.key,
    title: n.title,
    className: n.className,
    children: n.children ? cloneTree(n.children) : undefined,
    dataRef: n.dataRef,
  }));

const applyDrop = (tree, info) => {
  const dropKey = info.node.key;
  const dragKey = info.dragNode.key;
  const dropPos = info.node.pos.split('-');
  const dropPosition = info.dropPosition - Number(dropPos[dropPos.length - 1]);
  const loop = (data, key, callback) => {
    for (let i = 0; i < data.length; i++) {
      if (data[i].key === key) return callback(data[i], i, data);
      if (data[i].children && loop(data[i].children, key, callback)) return true;
    }
    return false;
  };
  const data = cloneTree(tree);
  let dragObj;
  loop(data, dragKey, (item, index, arr) => {
    arr.splice(index, 1);
    dragObj = item;
  });
  if (!info.dropToGap) {
    loop(data, dropKey, (item) => {
      item.children = item.children || [];
      item.children.push(dragObj);
    });
  } else {
    let ar = data;
    let i;
    loop(data, dropKey, (item, index, arr) => {
      ar = arr;
      i = index;
    });
    if (dropPosition === -1) ar.splice(i, 0, dragObj);
    else ar.splice(i + 1, 0, dragObj);
  }
  return data;
};

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

// 迷你侧边栏预览：与真实渲染一致（可见导航 + 图标回退）
const MiniPreview = ({ nav }) => {
  const build = (nodes) =>
    nodes.map((n) => {
      const Icon = n.icon || ICON_BY_KEY[n.key] || AppstoreOutlined;
      return n.children
        ? { key: n.key, icon: <Icon />, label: n.label, children: build(n.children) }
        : { key: n.key, label: n.label };
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
  const liveNav = useMenuNav(); // 订阅，保存后即时反映
  const [tree, setTree] = useState(() => toTreeData(getNav()));
  const [selectedKey, setSelectedKey] = useState(null);

  const selected = selectedKey ? findNode(tree, selectedKey) : null;

  const dirty = useMemo(
    () => JSON.stringify(rebuildNav(tree)) !== JSON.stringify(getNav()),
    [tree, liveNav]
  );

  const onDrop = (info) => setTree(applyDrop(tree, info));

  const save = () => {
    setNav(sanitizeNav(rebuildNav(tree)));
    message.success('已保存菜单布局，侧边栏已实时更新');
  };
  const reset = () => {
    resetNav();
    setTree(toTreeData(getNav()));
    setSelectedKey(null);
    message.success('已恢复默认菜单布局');
  };
  const addGroup = () => {
    const k = 'grp_' + Date.now();
    setTree([...tree, { key: k, title: '新建分组', className: undefined, children: [], dataRef: { key: k, label: '新建分组', children: [] } }]);
  };
  const delGroup = () => {
    if (!selected || selected.dataRef.path) {
      message.warning('仅可删除无页面的空分组');
      return;
    }
    setTree(removeNode(tree, selected.key));
    setSelectedKey(null);
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
          {node.className === 'wb-menu-hidden' && <Tag color="default" style={{ marginLeft: 6 }}>隐藏</Tag>}
        </span>
        <Space size={4} onClick={(e) => e.stopPropagation()}>
          <Button
            type="link"
            size="small"
            icon={<ArrowUpOutlined />}
            title="上移"
            disabled={idx === 0}
            onClick={(e) => {
              e.stopPropagation();
              setTree(moveSibling(tree, node.key, 'up'));
            }}
          >
            上移
          </Button>
          <Button
            type="link"
            size="small"
            icon={<ArrowDownOutlined />}
            title="下移"
            disabled={idx === len - 1}
            onClick={(e) => {
              e.stopPropagation();
              setTree(moveSibling(tree, node.key, 'down'));
            }}
          >
            下移
          </Button>
        </Space>
      </div>
    );
  };

  return (
    <PageCard
      title="菜单管理（自定义导航）"
      extra={
        <Space>
          <Button icon={<PlusOutlined />} onClick={addGroup}>新增分组</Button>
          <Button icon={<ReloadOutlined />} onClick={reset}>恢复默认</Button>
          <Button type="primary" icon={<SaveOutlined />} onClick={save} disabled={!dirty}>保存布局</Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="选中节点可改显示名 / 显隐；点击节点右侧「上移 / 下移」按钮调整同级顺序，或拖拽实现跨中心归类。保存后左侧栏与客户演示视图即时生效（本地持久化，刷新不丢失）。"
      />

      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        {/* 左：结构编辑器 */}
        <Card className="wb-card" title="导航结构（拖拽排序 / 跨中心归类）" style={{ flex: '1 1 420px', minWidth: 360 }}>
          <Tree
            draggable
            blockNode
            treeData={tree}
            titleRender={titleRender}
            onDrop={onDrop}
            allowDrop={allowDrop}
            onSelect={(keys) => setSelectedKey(keys[0] || null)}
            selectedKeys={selectedKey ? [selectedKey] : []}
            defaultExpandAll
          />
          <Paragraph type="secondary" style={{ marginTop: 12, fontSize: 12 }}>
            提示：点节点右侧的「上移 / 下移」按钮，可在同一分组内调整顺序；拖拽节点到别的分组标题上，可跨中心归类（如把页面从「运营中心」拖到「资产管理」）。
          </Paragraph>
        </Card>

        {/* 右：属性 + 预览 */}
        <div style={{ flex: '1 1 360px', minWidth: 320 }}>
          <Card className="wb-card" title="选中节点属性" style={{ marginBottom: 16 }}>
            {selected ? (
              <Space direction="vertical" style={{ width: '100%' }} size="middle">
                <div>
                  <Text type="secondary">显示名</Text>
                  <Input
                    value={selected.dataRef.label}
                    onChange={(e) => setTree(updateNode(tree, selected.key, { label: e.target.value }))}
                    placeholder="用于客户交互的展示名称"
                  />
                </div>
                <div>
                  <Text type="secondary">路由</Text>
                  <div>{selected.dataRef.path ? <Tag color="blue">{selected.dataRef.path}</Tag> : <Tag>分组（无独立页面）</Tag>}</div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <Space>
                    <EyeInvisibleOutlined />
                    <Text>在菜单中隐藏（仍可通过链接访问）</Text>
                  </Space>
                  <Switch
                    checked={!!selected.dataRef.hidden}
                    onChange={(v) => setTree(updateNode(tree, selected.key, { hidden: v }))}
                  />
                </div>
                <Button danger icon={<DeleteOutlined />} onClick={delGroup} disabled={!!selected.dataRef.path}>
                  删除该分组
                </Button>
              </Space>
            ) : (
              <Text type="secondary">在左侧选中一个节点进行编辑。</Text>
            )}
          </Card>

          <Card className="wb-card" title="实时预览（客户将看到的侧边栏）">
            <MiniPreview nav={sanitizeNav(getVisibleNavFromTree(tree))} />
          </Card>
        </div>
      </div>

      {dirty && (
        <Alert type="warning" showIcon style={{ marginTop: 16 }}
          message="当前有未保存的改动，点击右上角「保存布局」后才会应用到侧边栏。" />
      )}
    </PageCard>
  );
}

// 由编辑态 tree 还原可见导航用于预览
function getVisibleNavFromTree(tree) {
  const nav = rebuildNav(tree);
  const filterHidden = (nodes) => {
    const out = [];
    for (const n of nodes) {
      if (n.hidden) continue;
      if (n.children) {
        const kids = filterHidden(n.children);
        if (kids.length) out.push({ ...n, children: kids });
      } else out.push(n);
    }
    return out;
  };
  return filterHidden(nav);
}
