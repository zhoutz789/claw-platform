import { useState, useEffect, useMemo, useCallback } from 'react';
import { Card, Select, Space, message, Spin, Empty } from 'antd';
import PageCard from '../components/PageCard';
import TrajectoryPlayback from '../components/TrajectoryPlayback';
import { listVehicleAssets } from '../api/vehicle';
import { useTranslation } from 'react-i18next';

/**
 * 车辆轨迹回放（独立页）。先选车辆资产，再内嵌 TrajectoryPlayback 组件回放其最近 24h 轨迹。
 * @returns {JSX.Element}
 */
export default function VehicleTrajectory() {
  const { t } = useTranslation(['common', 'task']);
  const [assets, setAssets] = useState([]);
  const [loading, setLoading] = useState(false);
  const [assetId, setAssetId] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    // VEHICLE 与 EV 均视为可回放车辆资产
    Promise.all([listVehicleAssets('VEHICLE'), listVehicleAssets('EV')])
      .then(([v, ev]) => {
        const merged = [...(v || []), ...(ev || [])];
        const seen = new Set();
        const uniq = merged.filter((a) => (a.id != null && !seen.has(a.id) ? (seen.add(a.id), true) : false));
        setAssets(uniq);
        setAssetId((cur) => cur ?? (uniq[0] && uniq[0].id) ?? null);
      })
      .catch((e) => {
        message.error(t('task:vehicle.playback.loadAssetsFailed', { message: e.message }));
        setAssets([]);
      })
      .finally(() => setLoading(false));
  }, [t]);

  useEffect(() => { load(); }, [load]);

  const options = useMemo(
    () => assets.map((a) => ({ label: `${a.assetNo || a.assetType} · #${a.id}`, value: a.id })),
    [assets]
  );

  return (
    <PageCard title={t('task:vehicle.playback.pageTitle')}>
      <Space style={{ marginBottom: 14 }} wrap>
        <span>{t('task:vehicle.playback.selectAsset')}:</span>
        {loading ? <Spin /> : (
          <Select
            showSearch
            optionFilterProp="label"
            style={{ minWidth: 260 }}
            placeholder={assets.length ? t('task:vehicle.playback.selectAsset') : t('task:vehicle.playback.noAsset')}
            value={assetId}
            onChange={setAssetId}
            options={options}
            disabled={assets.length === 0}
          />
        )}
        <Button onClick={load}>{t('task:vehicle.playback.reload')}</Button>
      </Space>

      {assetId == null ? (
        <Empty description={t('task:vehicle.playback.noAsset')} style={{ padding: '40px 0' }} />
      ) : (
        <TrajectoryPlayback assetId={assetId} height={360} />
      )}
    </PageCard>
  );
}
