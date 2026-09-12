import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { Card, Button, Space, Slider, message, Spin, Empty, Typography, Tag } from 'antd';
import { PlayCircleOutlined, PauseCircleOutlined } from '@ant-design/icons';
import { getVehicleTrajectory } from '../api/vehicle';
import { useTranslation } from 'react-i18next';

const { Text } = Typography;

const VIEW_W = 800;

/**
 * 把后端轨迹响应规整成统一的点数组。
 * 兼容两种返回形态：①裸数组；② { points:[...] }。
 * 每个点的经纬度字段兼容 lat/latitude、lng/longitude、时间兼容 ts/time/timestamp。
 *
 * @param {Array|Object|null} raw 原始响应
 * @returns {Array<{lat:number,lng:number,ts:number,soc?:number,odometer?:number}>}
 */
function normalizePoints(raw) {
  if (!raw) return [];
  let list = Array.isArray(raw) ? raw : (raw.points || []);
  if (!Array.isArray(list)) return [];
  return list
    .map((p) => {
      if (!p) return null;
      const lat = Number(p.lat ?? p.latitude ?? p.y);
      const lng = Number(p.lng ?? p.longitude ?? p.lon ?? p.x);
      if (!isFinite(lat) || !isFinite(lng)) return null;
      const tsRaw = p.ts ?? p.time ?? p.timestamp ?? p.t;
      const ts = tsRaw ? new Date(tsRaw).getTime() : NaN;
      return {
        lat,
        lng,
        ts: isFinite(ts) ? ts : NaN,
        soc: p.soc != null ? Number(p.soc) : undefined,
        odometer: p.odometer != null ? Number(p.odometer) : undefined,
      };
    })
    .filter(Boolean);
}

/**
 * 依赖 SVG 的轻量轨迹回放组件（不引入任何地图库）。
 *
 * 行为：
 *   - 内部拉取 GET /v1/vehicles/{assetId}/trajectory（最近 24h）。
 *   - 将经纬度点缩放到 viewBox，绘制完整路径 polyline + 起点/终点标记。
 *   - 播放/暂停 + 1x/2x/4x 倍速，沿点时间戳推进一个移动标记。
 *   - 底部统计行：点数 / 距离提示 / 最新电量(SOC) / 里程(odometer)（若存在）。
 *   - 无数据时展示空态。
 *
 * @param {{assetId:number, height?:number}} props
 * @returns {JSX.Element}
 */
export default function TrajectoryPlayback({ assetId, height = 320 }) {
  const { t } = useTranslation(['common', 'task']);
  const [points, setPoints] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // 回放状态
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1); // 1x / 2x / 4x
  const [cursorTs, setCursorTs] = useState(null); // 当前虚拟时间(ms)
  const rafRef = useRef(null);
  const lastFrameRef = useRef(null);

  const load = useCallback(() => {
    if (assetId == null) return;
    setLoading(true);
    setError(null);
    getVehicleTrajectory(assetId)
      .then((d) => {
        const pts = normalizePoints(d);
        setPoints(pts);
        setCursorTs(pts.length ? (pts[0].ts && !isNaN(pts[0].ts) ? pts[0].ts : 0) : null);
        setPlaying(false);
      })
      .catch((e) => {
        setError(e.message || 'unknown');
        setPoints([]);
        message.error(t('task:vehicle.playback.loadFailed', { message: e.message }));
      })
      .finally(() => setLoading(false));
  }, [assetId, t]);

  useEffect(() => { load(); }, [load]);

  // 轨迹时间范围
  const { t0, t1 } = useMemo(() => {
    const tsList = points.map((p) => p.ts).filter((x) => isNaN(x) === false);
    if (tsList.length === 0) return { t0: 0, t1: 0 };
    return { t0: Math.min(...tsList), t1: Math.max(...tsList) };
  }, [points]);

  // 经纬度边界 → 映射到 viewBox 的坐标
  const geo = useMemo(() => {
    if (points.length === 0) return null;
    let minLat = Infinity, maxLat = -Infinity, minLng = Infinity, maxLng = -Infinity;
    for (const p of points) {
      if (p.lat < minLat) minLat = p.lat;
      if (p.lat > maxLat) maxLat = p.lat;
      if (p.lng < minLng) minLng = p.lng;
      if (p.lng > maxLng) maxLng = p.lng;
    }
    const pad = 40;
    const w = VIEW_W - pad * 2;
    const h = height - pad * 2;
    const dLat = maxLat - minLat || 1e-6;
    const dLng = maxLng - minLng || 1e-6;
    // 等比缩放，避免被极度压扁
    const scale = Math.min(w / dLng, h / dLat);
    const drawW = dLng * scale;
    const drawH = dLat * scale;
    const offX = pad + (w - drawW) / 2;
    const offY = pad + (h - drawH) / 2;
    const toXY = (lng, lat) => ({
      x: offX + (lng - minLng) * scale,
      // 纬度向上为正 → y 轴翻转
      y: offY + (maxLat - lat) * scale,
    });
    return { minLat, maxLat, minLng, maxLng, toXY };
  }, [points, height]);

  const polyPoints = useMemo(
    () => (geo ? points.map((p) => { const { x, y } = geo.toXY(p.lng, p.lat); return `${x.toFixed(1)},${y.toFixed(1)}`; }).join(' ') : ''),
    [geo, points]
  );

  // 依据虚拟时间在两点间线性插值出当前标记坐标
  const currentXY = useMemo(() => {
    if (!geo || points.length === 0) return null;
    if (cursorTs == null) return geo.toXY(points[0].lng, points[0].lat);
    // 找到 cursorTs 所在的相邻两点
    let a = points[0];
    let b = points[points.length - 1];
    for (let i = 0; i < points.length - 1; i++) {
      if (points[i].ts <= cursorTs && points[i + 1].ts >= cursorTs) {
        a = points[i]; b = points[i + 1]; break;
      }
    }
    const span = (b.ts - a.ts) || 1;
    const r = Math.max(0, Math.min(1, (cursorTs - a.ts) / span));
    const lng = a.lng + (b.lng - a.lng) * r;
    const lat = a.lat + (b.lat - a.lat) * r;
    return geo.toXY(lng, lat);
  }, [geo, points, cursorTs]);

  // 已走过的路径（用于高亮）
  const traveledPoly = useMemo(() => {
    if (!geo || points.length === 0) return '';
    const idxBefore = [];
    for (const p of points) { if (p.ts <= cursorTs || isNaN(p.ts)) idxBefore.push(p); }
    if (idxBefore.length === 0) return '';
    return idxBefore.map((p) => { const { x, y } = geo.toXY(p.lng, p.lat); return `${x.toFixed(1)},${y.toFixed(1)}`; }).join(' ');
  }, [geo, points, cursorTs]);

  // 动画循环：按真实时长 × 倍速 推进虚拟时间
  useEffect(() => {
    if (!playing) {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
      return undefined;
    }
    lastFrameRef.current = performance.now();
    const step = (now) => {
      const dtReal = now - lastFrameRef.current;
      lastFrameRef.current = now;
      setCursorTs((prev) => {
        const next = prev + dtReal * speed;
        if (next >= t1) {
          setPlaying(false);
          return t1;
        }
        return next;
      });
      rafRef.current = requestAnimationFrame(step);
    };
    rafRef.current = requestAnimationFrame(step);
    return () => { if (rafRef.current) cancelAnimationFrame(rafRef.current); rafRef.current = null; };
  }, [playing, speed, t1]);

  const onPlayPause = () => {
    if (points.length === 0) return;
    if (!playing && cursorTs >= t1) setCursorTs(t0); // 放完后再点 = 重头
    setPlaying((p) => !p);
  };

  const onSeek = (val) => {
    setPlaying(false);
    setCursorTs(val);
  };

  // 统计：距离提示（经纬 Haversine 近似，单位 km）、最新 SOC / odometer
  const stats = useMemo(() => {
    if (points.length === 0) return null;
    let dist = 0;
    for (let i = 1; i < points.length; i++) {
      dist += haversineKm(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng);
    }
    const last = points[points.length - 1];
    return {
      dist,
      soc: points.map((p) => p.soc).filter((x) => x != null).pop(),
      odometer: last.odometer != null ? last.odometer : points.map((p) => p.odometer).filter((x) => x != null).pop(),
    };
  }, [points]);

  const fmtTime = (ms) => (isNaN(ms) ? '—' : new Date(ms).toLocaleString());

  return (
    <Card
      size="small"
      title={t('task:vehicle.playback.title')}
      extra={<Button size="small" onClick={load}>{t('task:vehicle.playback.reload')}</Button>}
    >
      {loading ? (
        <div style={{ height, display: 'grid', placeItems: 'center' }}><Spin /></div>
      ) : error ? (
        <Empty description={error} />
      ) : points.length === 0 ? (
        <Empty description={t('task:vehicle.playback.empty')} style={{ padding: '40px 0' }} />
      ) : (
        <>
          <svg width="100%" viewBox={`0 0 ${VIEW_W} ${height}`} style={{ background: '#fafafa', borderRadius: 8, border: '1px solid #f0f0f0' }} role="img" aria-label="trajectory">
            {/* 完整路径 */}
            <polyline points={polyPoints} fill="none" stroke="#bfbfbf" strokeWidth="2" />
            {/* 已走过路径 */}
            {traveledPoly && <polyline points={traveledPoly} fill="none" stroke="#1677ff" strokeWidth="2.5" />}
            {/* 起点 */}
            {geo && <circle cx={geo.toXY(points[0].lng, points[0].lat).x} cy={geo.toXY(points[0].lng, points[0].lat).y} r="5" fill="#52c41a" />}
            {/* 终点 */}
            {geo && <circle cx={geo.toXY(points[points.length - 1].lng, points[points.length - 1].lat).x} cy={geo.toXY(points[points.length - 1].lng, points[points.length - 1].lat).y} r="5" fill="#fa8c16" />}
            {/* 移动标记 */}
            {currentXY && <circle cx={currentXY.x} cy={currentXY.y} r="6" fill="#1677ff" stroke="#fff" strokeWidth="2" />}
          </svg>

          <Space style={{ marginTop: 10 }} wrap>
            <Button icon={playing ? <PauseCircleOutlined /> : <PlayCircleOutlined />} onClick={onPlayPause}>
              {playing ? t('task:vehicle.playback.pause') : t('task:vehicle.playback.play')}
            </Button>
            <Space size={4}>
              <Text type="secondary">{t('task:vehicle.playback.speed')}:</Text>
              <SegmentedLike value={speed} onChange={setSpeed} />
            </Space>
            <Text type="secondary">
              {t('task:vehicle.playback.points')}: {points.length}
              {stats && stats.dist > 0 ? ` · ${t('task:vehicle.playback.dist')} ≈ ${stats.dist.toFixed(2)} km` : ''}
            </Text>
          </Space>

          <div style={{ marginTop: 6 }}>
            <Slider
              min={t0}
              max={t1}
              step={1}
              value={cursorTs ?? t0}
              onChange={onSeek}
              tooltip={{ formatter: (v) => new Date(v).toLocaleTimeString() }}
              disabled={t0 === t1}
            />
            <Text type="secondary" style={{ fontSize: 12 }}>
              {fmtTime(cursorTs)} / {fmtTime(t1)}
            </Text>
          </div>

          <Space style={{ marginTop: 4 }} wrap>
            {stats && stats.soc != null && (
              <Tag color="green">{t('task:vehicle.playback.latestSoc')}: {stats.soc}%</Tag>
            )}
            {stats && stats.odometer != null && (
              <Tag color="blue">{t('task:vehicle.playback.odometer')}: {stats.odometer} km</Tag>
            )}
          </Space>
        </>
      )}
    </Card>
  );
}

/** 倍速选择器（用 antd Segmented 风格的最小实现，避免再引依赖）。 */
function SegmentedLike({ value, onChange }) {
  const opts = [1, 2, 4];
  return (
    <Space size={4}>
      {opts.map((o) => (
        <Button key={o} size="small" type={value === o ? 'primary' : 'default'} onClick={() => onChange(o)}>{o}x</Button>
      ))}
    </Space>
  );
}

/** 两经纬点间近似地面距离（km），用于统计展示。 */
function haversineKm(lat1, lng1, lat2, lng2) {
  const R = 6371;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}
