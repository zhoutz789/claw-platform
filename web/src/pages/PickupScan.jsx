import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  App, Alert, Button, Card, Descriptions, Form, Input, Select, Space,
} from 'antd';
import { QrcodeOutlined } from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EnumTag, EMPTY, fmtTime, useSupplyOptions } from '../components/supplyShared';
import { getFulfillmentOrder, pickupFulfillmentOrder } from '../api/supplyChain';
import { FULFILLMENT_STATUS_COLOR, FULFILLMENT_STATUS_LABEL } from '../enums';

/**
 * 取货扫码页（增量 B · R6/R7/B6）。
 *
 * 扫码原子完成三件事：① 扣服务站寄售库存；② 建用户设备授权（设备进用户项目）；
 * ③ 写 outbox 触发异步结算（物流费 → 服务站提成 → 余额归厂家）。
 * 对接后端 AdminFulfillmentController：POST /admin/fulfillment/orders/{id}/pickup。
 */
export default function PickupScan() {
  const { t } = useTranslation(['common', 'supply']);
  const { message } = App.useApp();
  const [params, setParams] = useSearchParams();
  const { manufacturerName, stationName } = useSupplyOptions();

  // 支持从待履约订单页带 orderId 跳入：/pickup-scan?orderId=123
  const [keyword, setKeyword] = useState(params.get('orderId') || '');
  const [order, setOrder] = useState(null);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [deviceIds, setDeviceIds] = useState([]);
  const [result, setResult] = useState(null);
  const [form] = Form.useForm();

  const loadOrder = useCallback(async (kw) => {
    const key = String(kw || '').trim();
    if (!key) {
      message.warning(t('supply:pickup.msg.needOrder'));
      return;
    }
    setLoading(true);
    setOrder(null);
    setResult(null);
    try {
      // 后端只提供「按 id 查详情」，故扫码得到订单号时先按 id 解析，非数字则提示改用订单ID。
      const id = Number.parseInt(key, 10);
      if (Number.isNaN(id)) {
        message.warning(t('supply:pickup.msg.needOrder'));
        return;
      }
      const d = await getFulfillmentOrder(id);
      setOrder(d);
      setParams({ orderId: String(id) }, { replace: true });
    } catch (e) {
      message.error(t('msg.detailLoadFailed', { msg: e.message }));
    } finally {
      setLoading(false);
    }
  }, [message, setParams, t]);

  useEffect(() => {
    if (params.get('orderId')) loadOrder(params.get('orderId'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 提交取货扫码：扣寄售库 + 授权进用户项目 + 触发结算。 */
  const submitPickup = async () => {
    if (!order) {
      message.warning(t('supply:pickup.msg.needOrder'));
      return;
    }
    setSubmitting(true);
    try {
      const ids = (deviceIds || []).map((x) => Number(x)).filter((x) => !Number.isNaN(x));
      const d = await pickupFulfillmentOrder(order.id, ids);
      setOrder(d);
      setResult(d);
      message.success(t('supply:pickup.msg.succeed'));
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <PageCard title={t('supply:pickup.title')} subtitle={t('supply:pickup.subtitle')}>
      <Card size="small" title={t('supply:pickup.scan')} style={{ marginBottom: 12 }}>
        <Space wrap>
          <Input
            allowClear
            autoFocus
            prefix={<QrcodeOutlined />}
            style={{ width: 320 }}
            placeholder={t('supply:pickup.scanPlaceholder')}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={() => loadOrder(keyword)}
          />
          <Button type="primary" loading={loading} onClick={() => loadOrder(keyword)}>
            {t('supply:pickup.scan')}
          </Button>
        </Space>
        <div style={{ color: '#999', marginTop: 8, fontSize: 12 }}>{t('supply:pickup.hint')}</div>
      </Card>

      {order && (
        <Card size="small" title={t('supply:pickup.orderInfo')} style={{ marginBottom: 12 }}>
          <Descriptions bordered size="small" column={2}>
            <Descriptions.Item label={t('supply:fulfillment.col.orderNo')}>{order.orderNo || EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.status')}>
              <EnumTag value={order.status} labelMap={FULFILLMENT_STATUS_LABEL} colorMap={FULFILLMENT_STATUS_COLOR} />
            </Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.customerUserId')}>{order.customerUserId ?? EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.manufacturerId')}>{manufacturerName(order.manufacturerId)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.stationId')}>{stationName(order.stationId)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.totalAmount')}>
              {order.totalAmount == null ? EMPTY : Number(order.totalAmount).toFixed(2)}
            </Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.frozenAmount')}>
              {order.frozenAmount == null ? EMPTY : Number(order.frozenAmount).toFixed(2)}
            </Descriptions.Item>
            <Descriptions.Item label={t('supply:fulfillment.col.receivedAt')}>{fmtTime(order.receivedAt)}</Descriptions.Item>
          </Descriptions>

          <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
            <Form.Item
              label={t('supply:pickup.deviceIds')}
              extra={t('supply:pickup.deviceIdsTip')}
              style={{ maxWidth: 520 }}
            >
              <Select
                mode="multiple"
                allowClear
                tokenSeparators={[',', '，', ' ', ';']}
                placeholder={t('supply:pickup.deviceIds')}
                value={deviceIds}
                onChange={setDeviceIds}
                options={(deviceIds || []).map((v) => ({ label: String(v), value: v }))}
              />
            </Form.Item>
          </Form>

          <Perm any={['order:pickup:scan', 'station:pickup:confirm']}>
            <Button type="primary" loading={submitting} onClick={submitPickup}>
              {t('supply:pickup.submit')}
            </Button>
          </Perm>
        </Card>
      )}

      {result && (
        <Card size="small" title={t('supply:pickup.result')}>
          <Alert
            type="success"
            showIcon
            style={{ marginBottom: 12 }}
            message={t('supply:pickup.msg.succeed')}
            description={t('supply:pickup.hint')}
          />
          <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12, maxHeight: 360, overflow: 'auto' }}>
            {JSON.stringify(result, null, 2)}
          </pre>
        </Card>
      )}
    </PageCard>
  );
}
