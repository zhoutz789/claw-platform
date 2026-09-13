import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card, Form, Input, InputNumber, Select, Button, message, Space, Divider, Alert,
} from 'antd';
import { PlusOutlined, ApiOutlined, CheckCircleTwoTone, ArrowRightOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { listProductClasses, createVehicleAsset, bindVehicleDevice } from '../api/vehicle';
import { listStations } from '../api/supplyChain';
import { useTranslation } from 'react-i18next';

const { TextArea } = Input;

// 车载终端类型（与后端 AssetRequests.BindDevice.deviceType 枚举一致）。
const DEVICE_TYPES = ['VEHICLE_TCU', 'BATTERY_BMS', 'CHARGER'];

/**
 * 新建车辆（独立导航页）。两步：① 建档（POST /v1/assets/vehicle）→ ② 立即入网绑定
 * （POST /v1/assets/{id}/bind）。建档后即展示绑定快捷流程，可跳过稍后绑定。
 * 全部对接真实后端，无 mock。
 * @returns {JSX.Element}
 */
export default function CreateVehicle() {
  const { t } = useTranslation(['common', 'task']);
  const navigate = useNavigate();

  const [models, setModels] = useState([]);
  const [stations, setStations] = useState([]);

  const [createForm] = Form.useForm();
  const [creating, setCreating] = useState(false);

  // 建档成功后写入，驱动「立即入网绑定」第二阶段。
  const [created, setCreated] = useState(null); // { id, assetNo, bound }
  const [bindForm] = Form.useForm();
  const [binding, setBinding] = useState(false);

  const load = useCallback(() => {
    listProductClasses()
      .then((d) => setModels(Array.isArray(d) ? d : []))
      .catch(() => setModels([]));
    listStations()
      .then((d) => setStations(Array.isArray(d) ? d : []))
      .catch(() => setStations([]));
  }, []);
  useEffect(() => { load(); }, [load]);

  const submitCreate = () => {
    createForm.validateFields().then(async (v) => {
      setCreating(true);
      try {
        const body = {
          assetNo: v.assetNo,
          model: v.model,
          qrCode: v.qrCode || '',
          vin: v.vin || '',
          frameNo: v.frameNo || '',
          motorNo: v.motorNo || '',
          ownerId: v.ownerId != null ? Number(v.ownerId) : null,
        };
        const asset = await createVehicleAsset(body);
        const id = asset && (asset.id != null ? asset.id : (asset.data && asset.data.id));
        if (id == null) throw new Error(t('task:vehicle.create.bind.noId'));
        setCreated({ id, assetNo: (asset && (asset.assetNo || asset.data?.assetNo)) || v.assetNo, bound: false });
        message.success(t('task:vehicle.create.success'));
        bindForm.resetFields();
      } catch (e) {
        message.error(t('task:vehicle.create.failed', { message: e.message }));
      } finally {
        setCreating(false);
      }
    }).catch(() => {});
  };

  const submitBind = () => {
    bindForm.validateFields().then(async (v) => {
      setBinding(true);
      try {
        await bindVehicleDevice(created.id, {
          stationId: v.stationId != null ? Number(v.stationId) : null,
          imei: v.imei || '',
          deviceType: v.deviceType,
          location: v.location || '',
        });
        message.success(t('task:vehicle.create.bind.success'));
        setCreated((c) => ({ ...c, bound: true }));
      } catch (e) {
        message.error(t('task:vehicle.create.bind.failed', { message: e.message }));
      } finally {
        setBinding(false);
      }
    }).catch(() => {});
  };

  const resetAll = () => {
    setCreated(null);
    createForm.resetFields();
    bindForm.resetFields();
  };

  const modelOptions = models.map((r) => ({ label: `${r.code} / ${r.nameZh || ''}`, value: r.code }));
  const stationOptions = stations.map((s) => ({ label: s.name || s.stationName || String(s.id), value: s.id }));

  return (
    <PageCard title={t('task:vehicle.create.title')}>
      {!created ? (
        <Form form={createForm} layout="vertical">
          <Form.Item label={t('task:vehicle.create.assetNo')} name="assetNo" rules={[{ required: true, message: t('task:vehicle.create.assetNoRequired') }]}>
            <Input placeholder="VEH-0001" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.create.model')} name="model" rules={[{ required: true, message: t('task:vehicle.create.modelRequired') }]}>
            <Select showSearch optionFilterProp="label" placeholder={t('task:vehicle.create.modelPlaceholder')} options={modelOptions} />
          </Form.Item>
          <Form.Item label={t('task:vehicle.create.qrCode')} name="qrCode">
            <Input placeholder="https://…/qr.png" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.create.vin')} name="vin">
            <Input placeholder="VIN" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.create.frameNo')} name="frameNo">
            <Input placeholder="车架号" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.create.motorNo')} name="motorNo">
            <Input placeholder="电机号" />
          </Form.Item>
          <Form.Item label={t('task:vehicle.create.ownerId')} name="ownerId">
            <InputNumber style={{ width: '100%' }} placeholder={t('task:vehicle.create.ownerIdPlaceholder')} />
          </Form.Item>
          <Space>
            <Button type="primary" icon={<PlusOutlined />} loading={creating} onClick={submitCreate}>{t('common:m97')}</Button>
            <Button onClick={() => navigate('/vehicle-product-classes')}>{t('common:m96')}</Button>
          </Space>
        </Form>
      ) : (
        <div>
          <Alert
            type="success"
            showIcon
            icon={<CheckCircleTwoTone twoToneColor="#52c41a" />}
            message={t('task:vehicle.create.bind.assetCreated', { assetNo: created.assetNo, id: created.id })}
            style={{ marginBottom: 16 }}
          />
          {!created.bound ? (
            <>
              <Divider orientation="left">{t('task:vehicle.create.bind.title')}</Divider>
              <Alert type="info" showIcon style={{ marginBottom: 14 }} message={t('task:vehicle.create.bind.hint')} />
              <Form form={bindForm} layout="vertical" initialValues={{ deviceType: 'VEHICLE_TCU' }}>
                <Space size="large" wrap>
                  <Form.Item label={t('task:vehicle.create.bind.station')} name="stationId" style={{ minWidth: 220 }}>
                    <Select showSearch optionFilterProp="label" placeholder={t('task:vehicle.create.bind.stationPlaceholder')} options={stationOptions} />
                  </Form.Item>
                  <Form.Item label={t('task:vehicle.create.bind.deviceType')} name="deviceType" rules={[{ required: true }]} style={{ minWidth: 200 }}>
                    <Select options={DEVICE_TYPES.map((d) => ({ label: d, value: d }))} />
                  </Form.Item>
                </Space>
                <Space size="large" wrap>
                  <Form.Item label={t('task:vehicle.create.bind.imei')} name="imei" style={{ minWidth: 220 }}>
                    <Input placeholder={t('task:vehicle.create.bind.imeiPlaceholder')} />
                  </Form.Item>
                  <Form.Item label={t('task:vehicle.create.bind.location')} name="location" style={{ minWidth: 200 }}>
                    <Input placeholder={t('task:vehicle.create.bind.locationPlaceholder')} />
                  </Form.Item>
                </Space>
                <Space>
                  <Button type="primary" icon={<ApiOutlined />} loading={binding} onClick={submitBind}>{t('task:vehicle.create.bind.submit')}</Button>
                  <Button onClick={() => navigate('/assets')}>{t('task:vehicle.create.bind.skip')}</Button>
                </Space>
              </Form>
            </>
          ) : (
            <Space direction="vertical">
              <Alert type="success" showIcon message={t('task:vehicle.create.bind.done')} />
              <Space>
                <Button type="primary" icon={<ArrowRightOutlined />} onClick={() => navigate('/assets')}>{t('task:vehicle.create.bind.viewAssets')}</Button>
                <Button onClick={resetAll}>{t('task:vehicle.create.bind.createAnother')}</Button>
              </Space>
            </Space>
          )}
        </div>
      )}
    </PageCard>
  );
}
