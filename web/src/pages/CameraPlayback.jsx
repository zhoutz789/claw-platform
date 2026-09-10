import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Input, Button, Table, Tag, Card, Spin, message, Descriptions, Empty, List, Space, Typography,
} from 'antd';
import { SearchOutlined, PlayCircleOutlined, VideoCameraOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import {
  cameraListByAssetNo, cameraLive, cameraSegments, cameraTimeline,
} from '../api/camera';
import dayjs from 'dayjs';

const { Text, Paragraph } = Typography;

// 摄像头状态 → 徽标配色（与后端 CameraStatus 枚举对齐）
const STATUS_COLOR = {
  WORKING: 'green',   // 作业中
  CLOSED: 'default',  // 关闭
  PENDING: 'orange',  // 待作业
  OFFLINE: 'default', // 离线
  FAULT: 'red',       // 故障
};
const STATUS_LABEL = {
  WORKING: '作业中', CLOSED: '关闭', PENDING: '待作业', OFFLINE: '离线', FAULT: '故障',
};

// 分段存储层级配色（tier: 1热/2温/3冷/4永久）
const TIER_COLOR = { 1: 'green', 2: 'blue', 3: 'purple', 4: 'gold' };
const TIER_LABEL = { 1: '热·边缘', 2: '温·标准', 3: '冷·归档', 4: '永久·留证' };

export default function CameraPlayback() {
  const [assetNo, setAssetNo] = useState('');
  const [searching, setSearching] = useState(false);
  const [cameras, setCameras] = useState([]);
  const [selected, setSelected] = useState(null); // { camera, live, segments, timeline }
  const [loadingStream, setLoadingStream] = useState(false);
  const [searchParams] = useSearchParams();

  // 资产列表「历史回放」按钮可带 ?assetNo= 深链进入并自动查询
  useEffect(() => {
    const q = searchParams.get('assetNo');
    if (q) {
      setAssetNo(q);
      (async () => {
        setSearching(true);
        try {
          const list = await cameraListByAssetNo(q);
          setCameras(Array.isArray(list) ? list : []);
        } catch (e) {
          message.error(e.message || '查询失败');
          setCameras([]);
        } finally {
          setSearching(false);
        }
      })();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSearch = async () => {
    const no = assetNo.trim();
    if (!no) { message.warning('请输入资产编号'); return; }
    setSearching(true);
    setSelected(null);
    try {
      const list = await cameraListByAssetNo(no);
      setCameras(Array.isArray(list) ? list : []);
      if (!list || list.length === 0) message.info('该资产编号下暂无摄像头');
    } catch (e) {
      message.error(e.message || '查询失败');
      setCameras([]);
    } finally {
      setSearching(false);
    }
  };

  const handleSelect = async (cam) => {
    setLoadingStream(true);
    setSelected({ camera: cam, live: null, segments: [], timeline: null });
    try {
      const [live, segments, timeline] = await Promise.all([
        cameraLive(cam.id),
        cameraSegments(cam.id),
        cameraTimeline(cam.id),
      ]);
      setSelected({ camera: cam, live, segments: segments || [], timeline });
    } catch (e) {
      message.error(e.message || '加载取流地址失败');
    } finally {
      setLoadingStream(false);
    }
  };

  const camCols = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '路号', dataIndex: 'cameraIdx', width: 70, render: (v) => `第${v}路` },
    { title: '名称/位置', dataIndex: 'name' },
    { title: '协议', dataIndex: 'protocol', width: 90, render: (v) => <Tag>{v}</Tag> },
    { title: '分辨率', dataIndex: 'resolution', width: 90 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v) => <Tag color={STATUS_COLOR[v] || 'default'}>{STATUS_LABEL[v] || v}</Tag>,
    },
    {
      title: '操作',
      width: 120,
      render: (_, r) => (
        <Button
          size="small"
          type="primary"
          icon={<PlayCircleOutlined />}
          loading={selected?.camera?.id === r.id && loadingStream}
          onClick={() => handleSelect(r)}
        >
          回放
        </Button>
      ),
    },
  ];

  const segCols = [
    { title: '起始', dataIndex: 'startTs', render: (v) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-') },
    { title: '结束', dataIndex: 'endTs', render: (v) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-') },
    { title: '层级', dataIndex: 'tier', width: 110, render: (v) => <Tag color={TIER_COLOR[v] || 'default'}>{TIER_LABEL[v] || v}</Tag> },
    { title: '事件', dataIndex: 'eventTag', render: (v) => (v ? <Tag color="volcano">{v}</Tag> : <Text type="secondary">常规</Text>) },
    { title: '大小', dataIndex: 'sizeBytes', width: 110, render: (v) => (v ? `${(v / 1048576).toFixed(1)} MB` : '-') },
    {
      title: '存储键',
      dataIndex: 'objectKey',
      ellipsis: true,
      render: (v) => <Text copyable={{ text: v }} style={{ fontSize: 12 }}>{v}</Text>,
    },
  ];

  return (
    <PageCard title="数据回放" reload={handleSearch} loading={searching}>
      <Space style={{ marginBottom: 16 }} wrap>
        <Input
          placeholder="输入资产编号查询历史回放（如 DEV-2026-D001）"
          value={assetNo}
          onChange={(e) => setAssetNo(e.target.value)}
          onPressEnter={handleSearch}
          style={{ width: 360 }}
          prefix={<VideoCameraOutlined />}
          allowClear
        />
        <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch} loading={searching}>
          查询
        </Button>
      </Space>

      <Table
        rowKey="id"
        size="small"
        columns={camCols}
        dataSource={cameras}
        pagination={false}
        locale={{ emptyText: <Empty description="按资产编号查询后列出其下摄像头" /> }}
      />

      {selected && (
        <Card
          style={{ marginTop: 16 }}
          title={
            <Space>
              <VideoCameraOutlined />
              {selected.camera.name}
              <Tag color={STATUS_COLOR[selected.camera.status] || 'default'}>
                {STATUS_LABEL[selected.camera.status] || selected.camera.status}
              </Tag>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {selected.camera.protocol} · {selected.camera.resolution}
              </Text>
            </Space>
          }
        >
          {loadingStream ? (
            <div style={{ textAlign: 'center', padding: 40 }}><Spin tip="加载取流地址…" /></div>
          ) : (
            <Space direction="vertical" style={{ width: '100%' }} size="middle">
              <Descriptions size="small" column={1} bordered>
                <Descriptions.Item label="实时取流地址">
                  {selected.live?.playUrl || '-'}
                </Descriptions.Item>
                <Descriptions.Item label="HLS 回放地址">
                  <Text copyable={{ text: selected.live?.hlsUrl }} style={{ fontSize: 12 }}>
                    {selected.live?.hlsUrl || '-'}
                  </Text>
                </Descriptions.Item>
                <Descriptions.Item label="WebRTC 低延迟">
                  <Space>
                    <Text copyable={{ text: selected.live?.webrtcUrl }} style={{ fontSize: 12 }}>
                      {selected.live?.webrtcUrl || '-'}
                    </Text>
                    {selected.live?.webrtcUrl && (
                      <Button size="small" href={selected.live.webrtcUrl} target="_blank">
                        打开
                      </Button>
                    )}
                  </Space>
                </Descriptions.Item>
              </Descriptions>

              {/* 实时 / 回放视频窗：边缘媒体节点产出 HLS 时由浏览器原生播放（Safari）；
                  其余浏览器可经 WebRTC「打开」按钮低延迟观看。原型阶段不引入 hls.js 依赖。 */}
              <video
                controls
                style={{ width: '100%', maxHeight: 420, background: '#000', borderRadius: 6 }}
                src={selected.live?.hlsUrl}
              >
                您的浏览器不支持内嵌视频，请使用「WebRTC 打开」按钮。
              </video>

              <Card type="inner" title="历史分段">
                <Table
                  rowKey="id"
                  size="small"
                  columns={segCols}
                  dataSource={selected.segments}
                  pagination={{ pageSize: 8 }}
                  locale={{ emptyText: '近 24h 暂无分段（边缘 recorder 未上报）' }}
                />
              </Card>

              <Card type="inner" title="时间轴 · 事件留证">
                {selected.timeline?.events?.length ? (
                  <List
                    size="small"
                    dataSource={selected.timeline.events}
                    renderItem={(ev) => (
                      <List.Item>
                        <Tag color="volcano">{ev.eventTag || '事件'}</Tag>
                        <Text style={{ fontSize: 12 }}>
                          {ev.startTs ? dayjs(ev.startTs).format('YYYY-MM-DD HH:mm:ss') : '-'}
                          {ev.permanent ? ' · 永久留证' : ''}
                        </Text>
                      </List.Item>
                    )}
                  />
                ) : (
                  <Text type="secondary">近 24h 暂无事件标记（异常事件会在此永久留证）</Text>
                )}
              </Card>
            </Space>
          )}
        </Card>
      )}
    </PageCard>
  );
}
