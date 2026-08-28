import { useState } from 'react';
import { Tabs, Form, InputNumber, Input, Select, Button, Table, Tag, Alert, Space, Card, message, Typography, Divider } from 'antd';
import { ApiOutlined, CloudUploadOutlined, DownloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import api from '../api';

const { Paragraph, Text } = Typography;

// 联动方向 → 颜色 / 下游影响说明（与后端 LinkageDirection 一一对应）
const DIR_META = {
  ASSET_UPDATE: { color: 'blue', label: '资产档案更新', downstream: '刷新资产运营态快照（telemetry_latest），数字孪生实时可见' },
  REVENUE: { color: 'green', label: '收益分账', downstream: '触发收益分账重算意图（payload 携带用量快照），由资金域消费' },
  RISK: { color: 'red', label: '风控告警', downstream: '无人机低电量等 → 锁机/告警（DroneSafetyEvent）' },
  LIFECYCLE: { color: 'purple', label: '全生命周期', downstream: 'SOH<70% → 资产退役（AssetService.autoTransition），闭环到回收/残值' },
};

const DEVICE_TYPES = [
  { value: 'BATTERY_BMS', label: '电池 BMS' },
  { value: 'VEHICLE_TCU', label: '车辆 TCU' },
  { value: 'DRONE_FCU', label: '无人机 FCU' },
  { value: 'CHARGER', label: '充电桩' },
];

const ts = () => new Date().toISOString().slice(0, 19).replace('T', ' ');

// 本地模拟四向联动（与后端 TelemetryLinkageService 阈值一致），保证演示态也能看到闭环
function simulateLinkage({ deviceType, soc, soh, faults }) {
  const out = [];
  out.push({ direction: 'ASSET_UPDATE', trigger: 'OPERATIONAL_SYNC', triggered: true, payload: null });
  out.push({ direction: 'REVENUE', trigger: 'USAGE', triggered: true, payload: `{soc:${soc},soh:${soh}}` });

  const isDrone = (deviceType || '').startsWith('DRONE');
  const lowSoc = soc != null && soc < 20;
  const hasFault = faults && faults.trim() && faults.trim() !== '[]' && faults.trim() !== 'null';
  if (isDrone && lowSoc) {
    out.push({ direction: 'RISK', trigger: 'LOW_SOC', triggered: true, payload: 'LOW_BATTERY' });
  } else if (hasFault) {
    out.push({ direction: 'RISK', trigger: 'FAULT', triggered: true, payload: faults });
  } else {
    out.push({ direction: 'RISK', trigger: lowSoc ? 'LOW_SOC' : 'FAULT', triggered: false, payload: null });
  }

  const lowSoh = soh != null && soh < 70;
  out.push({ direction: 'LIFECYCLE', trigger: 'SOH_LOW', triggered: lowSoh, payload: lowSoh ? `SOH=${soh}` : null });
  return out;
}

function appendLog(setLog, rows) {
  setLog((prev) => [...rows.map((r, i) => ({ id: ts() + '-' + Math.random().toString(36).slice(2, 7) + i, ts: ts(), ...r })), ...prev].slice(0, 200));
}

function toFaultsJson(text) {
  if (!text || !text.trim()) return null;
  const arr = text.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
  return arr.length ? JSON.stringify(arr) : null;
}

export default function DeviceDataAccess() {
  const [tab, setTab] = useState('manual');
  const [form] = Form.useForm();
  const [log, setLog] = useState([]);
  const [lastTelemetry, setLastTelemetry] = useState(null);
  const [backendLinkage, setBackendLinkage] = useState(null);
  const [csvSummary, setCsvSummary] = useState(null);

  const pushTelemetry = async (payload, deviceType) => {
    let view = null;
    try {
      const resp = await api.post('/v1/iot/telemetry', payload);
      view = resp && resp.data ? resp.data : resp;
      setLastTelemetry(view);
      message.success('已上报遥测（' + (window.__CLAW_MOCK__ ? '演示态' : '真实后端') + '）');
    } catch (e) {
      setLastTelemetry(null);
      message.warning('上报未达后端，已用本地模拟展示联动：' + e.message);
    }
    // 本地模拟四向联动（永远可见）
    const sim = simulateLinkage({ deviceType, soc: payload.soc, soh: payload.soh, faults: payload.faults });
    appendLog(setLog, sim);
    // 真实态：拉取后端审计（device_linkage_events）
    const assetId = view && view.assetId;
    if (assetId && !window.__CLAW_MOCK__) {
      try {
        const r = await api.get('/v1/assets/' + assetId + '/linkage');
        setBackendLinkage((r && r.data) || r || []);
      } catch (e) { /* noop */ }
    } else {
      setBackendLinkage(null);
    }
    return sim;
  };

  const submitManual = (v) => {
    const payload = {
      imei: v.imei,
      speed: v.speed, soc: v.soc, soh: v.soh, temp: v.temp, humid: v.humid,
      faults: toFaultsJson(v.faults),
      lat: v.lat, lng: v.lng,
    };
    pushTelemetry(payload, v.deviceType);
  };

  const runPreset = (preset) => {
    form.setFieldsValue(preset.form);
    pushTelemetry(preset.payload, preset.form.deviceType || 'BATTERY_BMS');
  };

  const presets = [
    { label: '常规上报（资产+收益）', payload: { imei: 'IMEI-B001', speed: 20, soc: 80, soh: 95, temp: 30, humid: 55, faults: null, lat: 11.56, lng: 104.89 }, form: { imei: 'IMEI-B001', speed: 20, soc: 80, soh: 95, temp: 30, humid: 55, faults: '', lat: 11.56, lng: 104.89, deviceType: 'BATTERY_BMS' } },
    { label: '电池低SOH（→退役）', payload: { imei: 'IMEI-B002', speed: 10, soc: 80, soh: 60, temp: 32, humid: 55, faults: null, lat: 11.56, lng: 104.89 }, form: { imei: 'IMEI-B002', speed: 10, soc: 80, soh: 60, temp: 32, humid: 55, faults: '', lat: 11.56, lng: 104.89, deviceType: 'BATTERY_BMS' } },
    { label: '无人机低电量（→锁机）', payload: { imei: 'DRONE-IMEI-001', speed: 0, soc: 12, soh: 92, temp: 28, humid: 50, faults: null, lat: 11.57, lng: 104.90 }, form: { imei: 'DRONE-IMEI-001', speed: 0, soc: 12, soh: 92, temp: 28, humid: 50, faults: '', lat: 11.57, lng: 104.90, deviceType: 'DRONE_FCU' } },
  ];

  // ---- CSV 批量导入 ----
  const parseCsv = (text) => {
    const lines = text.split(/\r?\n/).filter((l) => l.trim());
    if (!lines.length) return [];
    const header = lines[0].split(',').map((h) => h.trim());
    return lines.slice(1).map((line) => {
      const cells = line.split(',');
      const row = {};
      header.forEach((h, i) => { row[h] = (cells[i] || '').trim(); });
      return row;
    });
  };

  const onCsv = (e) => {
    const file = e.target.files && e.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = async () => {
      const rows = parseCsv(String(reader.result));
      let ok = 0; let triggered = 0;
      for (const r of rows) {
        const payload = {
          imei: r.imei,
          speed: r.speed ? Number(r.speed) : null,
          soc: r.soc ? Number(r.soc) : null,
          soh: r.soh ? Number(r.soh) : null,
          temp: r.temp ? Number(r.temp) : null,
          humid: r.humid ? Number(r.humid) : null,
          faults: toFaultsJson(r.faults),
          lat: r.lat ? Number(r.lat) : null,
          lng: r.lng ? Number(r.lng) : null,
        };
        const sim = await pushTelemetry(payload, r.deviceType || 'BATTERY_BMS');
        ok += 1;
        triggered += sim.filter((s) => s.triggered).length;
      }
      setCsvSummary({ total: rows.length, ok, triggered });
      message.success(`CSV 导入完成：${rows.length} 条，触发联动 ${triggered} 次`);
    };
    reader.readAsText(file);
    e.target.value = '';
  };

  const downloadCsvTemplate = () => {
    const header = 'imei,deviceType,soc,soh,speed,temp,humid,faults,lat,lng';
    const sample = [
      'IMEI-B001,BATTERY_BMS,80,95,20,30,55,,11.56,104.89',
      'IMEI-B002,BATTERY_BMS,80,60,10,32,55,,11.56,104.89',
      'DRONE-IMEI-001,DRONE_FCU,12,92,0,28,50,,11.57,104.90',
    ].join('\n');
    const blob = new Blob([header + '\n' + sample], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = 'telemetry_template.csv'; a.click();
    URL.revokeObjectURL(url);
  };

  const logColumns = [
    { title: '时间', dataIndex: 'ts', width: 170 },
    { title: '联动方向', dataIndex: 'direction', width: 120, render: (d) => <Tag color={DIR_META[d]?.color}>{DIR_META[d]?.label}</Tag> },
    { title: '触发类型', dataIndex: 'trigger', render: (v) => <Tag>{v}</Tag> },
    { title: '下游影响', render: (_, r) => DIR_META[r.direction]?.downstream },
    { title: '载荷', dataIndex: 'payload', render: (v) => v || <Text type="secondary">-</Text> },
    { title: '状态', dataIndex: 'triggered', width: 110, render: (t) => t ? <Tag color="green">已触发</Tag> : <Tag>未达阈值</Tag> },
  ];

  const contractCurl = `curl -X POST http://localhost:8080/api/v1/iot/telemetry \\
  -H "Content-Type: application/json" \\
  -d '{"imei":"IMEI-B001","speed":20,"soc":80,"soh":95,"temp":30,
       "humid":55,"faults":null,"lat":11.56,"lng":104.89}'`;

  return (
    <PageCard title="物联网 / 设备数据接入" extra={<Tabs activeKey={tab} onChange={setTab} size="small" items={[
      { key: 'manual', label: '手动录入' },
      { key: 'csv', label: 'CSV 批量导入' },
      { key: 'contract', label: '接入契约' },
      { key: 'log', label: '四向联动日志' },
    ]} />}>
      <Alert type="info" showIcon style={{ marginBottom: 14 }}
        message="设备数据四类接入（手动 / CSV / API / MQTT）已全部打通；任一渠道上报后，平台自动驱动四向业务闭环：① 资产档案更新 ② 收益分账 ③ 风控告警 ④ 全生命周期。"
      />

      {tab === 'manual' && (
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Card size="small" title="快速演示（一键触发不同联动）">
            <Space wrap>
              {presets.map((p) => (
                <Button key={p.label} icon={<ThunderboltOutlined />} onClick={() => runPreset(p)}>{p.label}</Button>
              ))}
            </Space>
          </Card>

          <Card size="small" title="手动录入 / 编辑遥测并上报">
            <Form form={form} layout="vertical" onFinish={submitManual} initialValues={{ deviceType: 'BATTERY_BMS', soc: 80, soh: 95, speed: 20, temp: 30, humid: 55, lat: 11.56, lng: 104.89 }}>
              <Space wrap>
                <Form.Item label="设备 IMEI" name="imei" rules={[{ required: true, message: '必填' }]}><Input style={{ width: 200 }} placeholder="如 IMEI-B001 / DRONE-IMEI-001" /></Form.Item>
                <Form.Item label="演示设备类型(本地模拟判定)" name="deviceType"><Select style={{ width: 160 }} options={DEVICE_TYPES} /></Form.Item>
                <Form.Item label="电量 SOC %" name="soc"><InputNumber min={0} max={100} style={{ width: 120 }} /></Form.Item>
                <Form.Item label="健康度 SOH %" name="soh"><InputNumber min={0} max={100} style={{ width: 120 }} /></Form.Item>
                <Form.Item label="速度 km/h" name="speed"><InputNumber style={{ width: 120 }} /></Form.Item>
                <Form.Item label="温度 ℃" name="temp"><InputNumber style={{ width: 120 }} /></Form.Item>
                <Form.Item label="湿度 %" name="humid"><InputNumber style={{ width: 120 }} /></Form.Item>
                <Form.Item label="纬度" name="lat"><InputNumber step={0.0001} style={{ width: 140 }} /></Form.Item>
                <Form.Item label="经度" name="lng"><InputNumber step={0.0001} style={{ width: 140 }} /></Form.Item>
                <Form.Item label="故障码(逗号分隔)" name="faults"><Input style={{ width: 200 }} placeholder="如 BMS_OVERTEMP" /></Form.Item>
              </Space>
              <Button type="primary" htmlType="submit" icon={<ApiOutlined />}>上报遥测并触发联动</Button>
            </Form>
          </Card>

          {lastTelemetry && (
            <Card size="small" title="上报结果（最新遥测）">
              <pre style={{ margin: 0 }}>{JSON.stringify(lastTelemetry, null, 2)}</pre>
            </Card>
          )}
          {backendLinkage && backendLinkage.length > 0 && (
            <Card size="small" title="后端审计（device_linkage_events 真实落库）">
              <Table rowKey="id" size="small" pagination={false} dataSource={backendLinkage} columns={[
                { title: '方向', dataIndex: 'direction', render: (d) => <Tag color={DIR_META[d]?.color}>{d}</Tag> },
                { title: '触发', dataIndex: 'triggerType' },
                { title: '载荷', dataIndex: 'payload' },
                { title: '时间', dataIndex: 'triggeredAt' },
              ]} />
            </Card>
          )}
        </Space>
      )}

      {tab === 'csv' && (
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Card size="small">
            <Space wrap>
              <Button icon={<DownloadOutlined />} onClick={downloadCsvTemplate}>下载 CSV 模板</Button>
              <Button icon={<CloudUploadOutlined />} onClick={() => document.getElementById('csv-input').click()}>选择 CSV 导入</Button>
              <input id="csv-input" type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={onCsv} />
            </Space>
            <Paragraph type="secondary" style={{ marginTop: 10, marginBottom: 0 }}>
              模板列：imei, deviceType, soc, soh, speed, temp, humid, faults, lat, lng。每行一次上报，批量触发四向联动。
            </Paragraph>
          </Card>
          {csvSummary && (
            <Alert type="success" showIcon message={`导入 ${csvSummary.total} 条，成功 ${csvSummary.ok} 条，累计触发联动 ${csvSummary.triggered} 次`} />
          )}
        </Space>
      )}

      {tab === 'contract' && (
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Card size="small" title="标准 API 对接（你们系统推 / 拉）">
            <Typography>
              <Paragraph><Text strong>POST /api/v1/iot/telemetry</Text> — 遥测上报（更新最新遥测 + 落轨迹点 + 驱动四向联动）</Paragraph>
              <Paragraph><Text strong>GET /api/v1/assets/{'{assetId}'}/telemetry</Text> — 资产最新遥测</Paragraph>
              <Paragraph><Text strong>GET /api/v1/assets/{'{assetId}'}/tracks?from=&to=</Text> — 资产轨迹</Paragraph>
              <Paragraph><Text strong>GET /api/v1/assets/{'{assetId}'}/linkage?direction=</Text> — 资产联动事件审计（ASSET_UPDATE|REVENUE|RISK|LIFECYCLE）</Paragraph>
            </Typography>
            <Divider />
            <Paragraph><Text strong>请求体（TelemetryReport）</Text></Paragraph>
            <pre style={{ background: 'var(--line)', padding: 12, borderRadius: 8 }}>{`{
  "imei": "IMEI-B001",
  "speed": 20.00, "soc": 80.00, "soh": 95.00,
  "temp": 30.00, "humid": 55.00,
  "faults": null,          // JSON 数组字符串，如 ["BMS_OVERTEMP"]
  "lat": 11.56, "lng": 104.89
}`}</pre>
            <Paragraph><Text strong>cURL 示例</Text></Paragraph>
            <pre style={{ background: 'var(--line)', padding: 12, borderRadius: 8 }}>{contractCurl}</pre>
          </Card>
          <Card size="small" title="MQTT / 设备网关实时上报（EMQX 已规划）">
            <Typography>
              <Paragraph>Broker 由环境变量注入，订阅主题默认 <Text code>claw/telemetry/+</Text>，Payload 为上述 JSON（字段同名）。</Paragraph>
              <Paragraph>启用：<Text code>claw.iot.emqx.enabled=true</Text> + <Text code>CLAW_IOT_EMQX_BROKER_URL / USERNAME / PASSWORD / TOPIC</Text>。真实上报与 REST 共用 <Text code>IoTService.reportTelemetry</Text> 同一持久化与联动逻辑。</Paragraph>
            </Typography>
          </Card>
        </Space>
      )}

      {tab === 'log' && (
        <Card size="small" title="四向联动演示日志">
          <Alert type="warning" showIcon style={{ marginBottom: 12 }}
            message="演示态：下方为本地按后端相同阈值模拟的联动决策；真实后端连接后，资产联动审计来自 device_linkage_events 表（见「手动录入」中的后端审计）。" />
          <Table rowKey="id" size="small" pagination={{ pageSize: 12 }} dataSource={log} columns={logColumns} />
        </Card>
      )}
    </PageCard>
  );
}
