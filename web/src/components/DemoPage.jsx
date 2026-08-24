import { Table } from 'antd';

const STATUS_MAP = {
  完成: 'green', 正常: 'green', 启用: 'green', 生效: 'green', 已冻结: 'green', 已隔离: 'green', 已完成: 'green',
  进行中: 'amber', 待处理: 'amber', 告警: 'amber', 审理中: 'amber', 评估中: 'amber',
  待复核: 'blue',
  异常: 'coral', 高危: 'coral', 冻结: 'coral', 禁用: 'coral',
};

export function statusTag(v) {
  const cls = STATUS_MAP[v] || 'blue';
  return <span className={`tag tag-${cls}`}>{v}</span>;
}

export default function DemoPage({ title, desc, columns, rows, pageSize = 8, extra }) {
  return (
    <div className="panel">
      <div className="panel-title">
        <span>
          {title}
          <span className="demo-badge">演示数据 · 后端接口待补</span>
        </span>
        {extra}
      </div>
      {desc && <div style={{ fontSize: 12, color: 'var(--ink-2)', marginBottom: 12 }}>{desc}</div>}
      <Table
        rowKey={(_, i) => i}
        size="small"
        pagination={rows && rows.length > pageSize ? { pageSize } : false}
        columns={columns}
        dataSource={rows || []}
        locale={{ emptyText: '暂无数据' }}
      />
    </div>
  );
}
