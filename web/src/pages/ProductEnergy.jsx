import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Input, Select, Space, Table, Tag, Empty } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';
import { categoryTree } from '../api/category';

/**
 * 能源品类商品页（能源品类商品的筛选视图）。
 *
 * 数据来源：
 *   - GET /v1/admin/manufacturer/products  商品列表（产品中心的真实模型：
 *       id / name / category(自由文本) / brand / assetType / status / paramsJson ...）
 *   - GET /v1/admin/categories/tree        多级分类树（via categoryTree()）
 *
 * 说明：商品模型的 category 为自由文本字段（无结构化 categoryId），因此筛选在
 * 前端按「分类名 contains」做模糊匹配；分类下拉由类别树拍扁得到。
 */

// 把「类别管理」多级分类树拍扁为 Select 选项：value 存分类名，label 展示「父 / 子」全路径。
// （与 ProductPublish 保持一致；产品表的 category 为自由文本，存分类名即可对齐展示。）
const flattenCategories = (nodes, prefix = '') =>
  (nodes || []).flatMap((n) => {
    const label = prefix ? `${prefix} / ${n.name}` : n.name;
    return [
      { value: n.name, label },
      ...flattenCategories(n.children, label),
    ];
  });

// 能源相关分类名关键字（用于自动预选能源顶类：能源 / energy / 光伏 / solar）。
const ENERGY_KEYWORDS = ['能源', 'energy', '光伏', 'solar'];

// 商品状态映射（后端枚举 → 中文 + antd Tag 颜色）。
const PRODUCT_STATUS = {
  ON_SALE: { label: '在售', color: 'green' },
  PREPARE: { label: '筹备中', color: 'orange' },
  OFF_SHELF: { label: '已下架', color: 'default' },
  DRAFT: { label: '草稿', color: 'default' },
};

// 资产类型中文（用于商品无结构化规格时兜底展示）。
const ASSET_TYPE_LABEL = {
  VEHICLE: '车辆',
  EV: '电动车',
  BATTERY: '电池',
  CHARGER: '充电桩',
  PV_STATION: '光伏站',
  DRONE: '无人机',
};

// 从 paramsJson（产品中心的动态字段定义）解析字段名列表。
const parseFields = (p) => {
  try {
    const j = JSON.parse((p && p.paramsJson) || '[]');
    return Array.isArray(j) ? j : [];
  } catch {
    return [];
  }
};

// 关键规格文案：优先取结构化字段名，否则兜底为「资产类型 · 型号」。
const getSpecsText = (p) => {
  const fields = parseFields(p);
  if (fields.length) return fields.map((f) => f.name).slice(0, 3).join('、');
  const parts = [];
  if (p && p.assetType) parts.push(ASSET_TYPE_LABEL[p.assetType] || p.assetType);
  if (p && p.model) parts.push(p.model);
  return parts.length ? parts.join(' · ') : '—';
};

const isEnergyName = (name) =>
  ENERGY_KEYWORDS.some((k) => (name || '').toLowerCase().includes(k.toLowerCase()));

const columns = [
  {
    title: '名称',
    dataIndex: 'name',
    key: 'name',
    width: 240,
    ellipsis: true,
    render: (v) => <span style={{ fontWeight: 600 }}>{v || '—'}</span>,
  },
  {
    title: '分类',
    dataIndex: 'category',
    key: 'category',
    width: 160,
    ellipsis: true,
    render: (v) =>
      v ? <Tag color="blue">{v}</Tag> : <span style={{ color: '#8a9099' }}>—</span>,
  },
  {
    title: '品牌',
    dataIndex: 'brand',
    key: 'brand',
    width: 140,
    ellipsis: true,
    render: (v) => v || <span style={{ color: '#8a9099' }}>—</span>,
  },
  {
    title: '价格',
    dataIndex: 'price',
    key: 'price',
    width: 120,
    align: 'right',
    render: (price) => {
      if (price == null) return <span style={{ color: '#8a9099' }}>—</span>;
      const num = typeof price === 'number' ? price : Number(price);
      if (Number.isNaN(num)) return <span style={{ color: '#8a9099' }}>—</span>;
      return `$${num.toLocaleString('en-US')}`;
    },
  },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    width: 110,
    render: (status) => {
      const s = PRODUCT_STATUS[status];
      if (!s) return <span style={{ color: '#8a9099' }}>{status || '—'}</span>;
      return <Tag color={s.color}>{s.label}</Tag>;
    },
  },
  {
    title: '关键规格',
    key: 'specs',
    ellipsis: true,
    render: (_, p) => getSpecsText(p),
  },
];

export default function ProductEnergy() {
  const { t } = useTranslation(['nav', 'common']);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [products, setProducts] = useState([]);
  const [catOptions, setCatOptions] = useState([]);
  const [energyAutoSelected, setEnergyAutoSelected] = useState(false);

  const [selectedCategory, setSelectedCategory] = useState(''); // '' = 全部
  const [nameQuery, setNameQuery] = useState('');

  // 加载商品列表 + 分类树（二者独立，分类失败不阻断商品展示）。
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    let alive = true;
    try {
      const [prodRes, catRes] = await Promise.allSettled([
        api.get('/v1/admin/manufacturer/products'),
        categoryTree(),
      ]);
      if (!alive) return;
      if (prodRes.status === 'fulfilled') {
        setProducts(Array.isArray(prodRes.value) ? prodRes.value : []);
      } else {
        setProducts([]);
        setError((prodRes.reason && prodRes.reason.message) || '商品加载失败，请稍后重试');
      }
      if (catRes.status === 'fulfilled') {
        setCatOptions(flattenCategories(catRes.value));
      } else {
        // 分类非阻断：缺分类树则仅提供「全部」选项。
        setCatOptions([]);
      }
    } catch (e) {
      if (alive) {
        setProducts([]);
        setError(e.message || '加载失败，请稍后重试');
      }
    } finally {
      if (alive) setLoading(false);
    }
  }, []);

  useEffect(() => {
    let alive = true;
    load();
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 分类树加载后，自动预选一个能源相关顶类（若存在）。
  useEffect(() => {
    if (energyAutoSelected || catOptions.length === 0) return;
    const energyOpts = catOptions.filter((o) => isEnergyName(o.value) || isEnergyName(o.label));
    if (energyOpts.length) {
      // 取 label 最短者（通常为能源顶类），避免误选过细的叶子。
      const pick = energyOpts.slice().sort((a, b) => a.label.length - b.label.length)[0];
      setSelectedCategory(pick.value);
    }
    setEnergyAutoSelected(true);
  }, [catOptions, energyAutoSelected]);

  const filtered = useMemo(() => {
    const cat = (selectedCategory || '').trim().toLowerCase();
    const q = nameQuery.trim().toLowerCase();
    return products.filter((p) => {
      const catOk = !cat || (p.category || '').toLowerCase().includes(cat);
      const nameOk = !q || (p.name || '').toLowerCase().includes(q);
      return catOk && nameOk;
    });
  }, [products, selectedCategory, nameQuery]);

  const categorySelectOptions = useMemo(
    () => [{ value: '', label: '全部' }, ...catOptions],
    [catOptions]
  );

  return (
    <PageCard
      title={t('nav:item.product-energy')}
      reload={load}
      loading={loading}
    >
      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          value={selectedCategory}
          onChange={setSelectedCategory}
          options={categorySelectOptions}
          style={{ width: 240 }}
          placeholder="选择能源分类"
          showSearch
          optionFilterProp="label"
          allowClear={false}
        />
        <Input
          allowClear
          value={nameQuery}
          onChange={(e) => setNameQuery(e.target.value)}
          placeholder="搜索商品名称"
          prefix={<SearchOutlined style={{ color: '#bfbfbf' }} />}
          style={{ width: 240 }}
        />
        <span style={{ color: '#8a9099', fontSize: 13 }}>
          共 {filtered.length} 条
        </span>
      </Space>

      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="商品数据加载失败"
          description={error}
          action={
            <Button icon={<ReloadOutlined />} size="small" onClick={load}>
              重试
            </Button>
          }
        />
      )}

      <Table
        rowKey="id"
        loading={loading}
        dataSource={filtered}
        columns={columns}
        size="middle"
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
        locale={{
          emptyText: (
            <Empty
              description={
                products.length === 0 && !loading
                  ? '暂无商品数据'
                  : '当前分类 / 搜索条件下暂无匹配商品'
              }
            />
          ),
        }}
      />
    </PageCard>
  );
}
