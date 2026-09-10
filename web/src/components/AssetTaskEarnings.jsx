// 资产「任务收益」通用区块。
//
// 后端端点（TaskController）：
//   GET /api/v1/tasks/assets/{assetId}/task-earnings
//   → ApiResult<List<TaskEarningView>>，api.js 自动解包 body.data，故 api.get 直接返回数组。
//   TaskEarningView = { taskId, assignmentId, assetId, amount, bizRef, memo, createdAt }
//
// 本模块对外导出三件东西，供任务大厅与资产详情共用，避免两处各写一份渲染逻辑：
//   1. assetTaskEarningsPath(assetId) / fetchAssetTaskEarnings(assetId)：纯取数。
//   2. <EarningsBlock earnings assetId />：纯渲染（任务大厅「完成」后回填收益用）。
//   3. <AssetTaskEarnings assetId />：自带 loading / 空 / 错误三态的自取数组件（资产详情 Tab 用）。
//
// 文案 key 全部落在 task 命名空间：task:earn.* / task:hall.earningsSummary / task:trace.*。
import { useCallback, useEffect, Fragment, useState } from 'react';
import { Alert, Button, Descriptions, Empty, Space, Spin, Statistic } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import api from '../api';
import dayjs from 'dayjs';

/**
 * 资产任务收益端点路径。
 * 注意：是 /v1/tasks/assets/... 而非 /v1/assets/...（后端仅在 TaskController 暴露）。
 * @param {number|string} assetId 资产 ID
 * @returns {string} 相对 baseURL 的路径
 */
export function assetTaskEarningsPath(assetId) {
  return `/v1/tasks/assets/${assetId}/task-earnings`;
}

/**
 * 拉取某资产的任务收益明细。
 * @param {number|string} assetId 资产 ID
 * @returns {Promise<Array<Object>>} TaskEarningView[]，异常向上抛
 */
export function fetchAssetTaskEarnings(assetId) {
  return api.get(assetTaskEarningsPath(assetId));
}

/** 金额格式化：null / undefined 显示为 $0.00。 */
const fmtAmount = (v) => `$${(Number(v) || 0).toFixed(2)}`;

/** 时间格式化：非法值显示占位符。 */
const fmtTime = (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—');

/**
 * 收益明细纯渲染区块（无取数副作用）。
 * @param {{earnings: Array<Object>|null|undefined, assetId: number|string, showTotal?: boolean}} props
 * @returns {JSX.Element|null} 无数据时返回 null
 */
export function EarningsBlock({ earnings, assetId, showTotal = false }) {
  const { t } = useTranslation(['task']);
  if (!earnings || earnings.length === 0) return null;
  const total = earnings.reduce((sum, e) => sum + (Number(e && e.amount) || 0), 0);
  return (
    <Alert
      type="success"
      showIcon
      style={{ marginTop: 10 }}
      message={t('task:hall.earningsSummary', { assetId: assetId ?? '—', count: earnings.length })}
      description={
        <>
          {showTotal && (
            <Statistic
              title={t('task:earn.total')}
              value={total}
              precision={2}
              prefix="$"
              valueStyle={{ fontSize: 20, marginBottom: 8 }}
            />
          )}
          <Descriptions size="small" column={2} bordered>
            {earnings.map((e, i) => (
              <Fragment key={`${e.taskId ?? 'task'}-${e.assignmentId ?? 'asg'}-${i}`}>
                <Descriptions.Item label={t('task:earn.taskId')}>{e.taskId ?? '—'}</Descriptions.Item>
                <Descriptions.Item label={t('task:earn.assignmentId')}>{e.assignmentId ?? '—'}</Descriptions.Item>
                <Descriptions.Item label={t('task:earn.amount')}>{fmtAmount(e.amount)}</Descriptions.Item>
                <Descriptions.Item label={t('task:earn.bizRef')}>{e.bizRef ?? '—'}</Descriptions.Item>
                <Descriptions.Item label={t('task:earn.memo')}>{e.memo ?? '—'}</Descriptions.Item>
                <Descriptions.Item label={t('task:earn.createdAt')}>{fmtTime(e.createdAt)}</Descriptions.Item>
              </Fragment>
            ))}
          </Descriptions>
        </>
      }
    />
  );
}

/**
 * 资产详情内的「任务收益」区块：自取数 + loading / 空 / 错误三态 + 手动刷新。
 * @param {{assetId: number|string|null}} props
 * @returns {JSX.Element}
 */
export default function AssetTaskEarnings({ assetId }) {
  const { t } = useTranslation(['task']);
  const [earnings, setEarnings] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const load = useCallback(() => {
    if (assetId == null || assetId === '') {
      setEarnings([]);
      setError(null);
      setLoading(false);
      return undefined;
    }
    setLoading(true);
    setError(null);
    return fetchAssetTaskEarnings(assetId)
      .then((d) => setEarnings(Array.isArray(d) ? d : []))
      .catch((e) => {
        setEarnings([]);
        setError(e && e.message ? e.message : String(e));
      })
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assetId]);

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assetId]);

  if (assetId == null || assetId === '') {
    return <Empty description={t('task:trace.assetIdHint')} />;
  }

  return (
    <div>
      <Space style={{ marginBottom: 12 }} align="center">
        <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={load}>
          {t('task:trace.reload')}
        </Button>
        <span style={{ color: 'var(--muted)', fontSize: 12 }}>{t('task:earn.hint')}</span>
      </Space>
      <Spin spinning={loading}>
        {error && (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 12 }}
            message={`${t('task:earn.loadFailed')}：${error}`}
          />
        )}
        {!loading && !error && earnings.length === 0 ? (
          <Empty description={t('task:earn.empty')} />
        ) : (
          <EarningsBlock earnings={earnings} assetId={assetId} showTotal />
        )}
      </Spin>
    </div>
  );
}
