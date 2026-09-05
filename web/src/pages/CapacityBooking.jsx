import React, { useState } from 'react';
import { Card, Form, InputNumber, Button, message, Tabs, Table, Tag, Empty, Select, Progress } from 'antd';
import PageCard from '../components/PageCard';
import { useTranslation } from 'react-i18next';
import {
  createPlan,
  listPlans,
  subscribe,
  listSubscriptions,
  listOpenPlans,
  listRebates,
} from '../api/capacity';

// 状态 → antd Tag 颜色，覆盖容量计划 / 预订 / 回佣 / 合约常见状态值。
const STATUS_COLOR = {
  ACTIVE: 'green',
  INACTIVE: 'default',
  OPEN: 'blue',
  CLOSED: 'red',
  PENDING: 'gold',
  SETTLED: 'green',
  COMPLETED: 'green',
  EXIT_REQUESTED: 'orange',
  REFUNDED: 'cyan',
};

const statusTag = (v) => <Tag color={STATUS_COLOR[v] || 'default'}>{v}</Tag>;

export default function CapacityBooking() {
  const { t } = useTranslation('common');
  const [planForm] = Form.useForm();
  const [subForm] = Form.useForm();
  const [plans, setPlans] = useState([]);
  const [subs, setSubs] = useState([]);
  const [rebates, setRebates] = useState([]);
  const [openPlans, setOpenPlans] = useState({});
  const [loadingPlans, setLoadingPlans] = useState(false);
  const [loadingSubs, setLoadingSubs] = useState(false);
  const [loadingRebates, setLoadingRebates] = useState(false);
  const [assetId, setAssetId] = useState(null);

  const loadPlans = (ownerUserId) => {
    if (!ownerUserId) return;
    setLoadingPlans(true);
    listPlans(ownerUserId)
      .then(setPlans)
      .catch((e) => message.error(e.message))
      .finally(() => setLoadingPlans(false));
  };
  const loadSubs = (subscriberUserId) => {
    if (!subscriberUserId) return;
    setLoadingSubs(true);
    listSubscriptions(subscriberUserId)
      .then(setSubs)
      .catch((e) => message.error(e.message))
      .finally(() => setLoadingSubs(false));
  };
  const loadRebates = (subscriberUserId) => {
    if (!subscriberUserId) return;
    setLoadingRebates(true);
    listRebates(subscriberUserId)
      .then(setRebates)
      .catch((e) => message.error(e.message))
      .finally(() => setLoadingRebates(false));
  };
  // 载入某资产的开放计划，建立 planId → {subscribedUnits,totalUnits} 映射，供预订进度条关联。
  const loadOpenPlans = (id) => {
    if (!id) return;
    listOpenPlans(id)
      .then((arr) => {
        const map = {};
        (arr || []).forEach((p) => {
          map[p.id] = p;
        });
        setOpenPlans(map);
      })
      .catch(() => {});
  };

  const planTab = (
    <div>
      <Card size="small" title={t('common:m1005')} style={{ marginBottom: 12 }}>
        <Form
          form={planForm}
          layout="vertical"
          onFinish={(v) =>
            createPlan(v)
              .then(() => {
                message.success(t('common:m997'));
                loadPlans(v.ownerUserId);
              })
              .catch((e) => message.error(e.message))
          }
        >
          <Form.Item name="assetId" label={t('common:m213')} rules={[{ required: true }]}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="ownerUserId" label={t('common:m214')} rules={[{ required: true }]}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="poolEntryId" label={t('common:m569')}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="totalUnits" label={t('common:m1009')} rules={[{ required: true }]}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="unitPrice" label={t('common:m1010')} rules={[{ required: true }]}>
            <InputNumber style={{ width: '100%' }} precision={2} />
          </Form.Item>
          <Form.Item name="capacityType" label={t('common:m1011')} rules={[{ required: true }]}>
            <Select
              placeholder={t('common:m1011')}
              options={[
                { value: 'SERIAL', label: 'SERIAL' },
                { value: 'PARALLEL', label: 'PARALLEL' },
              ]}
            />
          </Form.Item>
          <Form.Item name="rebateRate" label={t('common:m1012')}>
            <InputNumber style={{ width: '100%' }} precision={4} />
          </Form.Item>
          <Form.Item name="windowStart" label="windowStart">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="windowEnd" label="windowEnd">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Button type="primary" htmlType="submit">
            {t('common:m1005')}
          </Button>
        </Form>
      </Card>
      <Card
        size="small"
        title={t('common:m1005')}
        extra={<Button onClick={() => loadPlans(planForm.getFieldValue('ownerUserId'))}>{t('common:m248')}</Button>}
      >
        <Table
          rowKey="id"
          dataSource={plans}
          loading={loadingPlans}
          pagination={false}
          size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: t('common:m213'), dataIndex: 'assetId', width: 90 },
            { title: t('common:m1009'), dataIndex: 'totalUnits', width: 90 },
            { title: t('common:m1013'), dataIndex: 'subscribedUnits', width: 90 },
            { title: t('common:m1010'), dataIndex: 'unitPrice', width: 90 },
            { title: t('common:m1011'), dataIndex: 'capacityType', width: 100, render: (v) => statusTag(v) },
            { title: t('common:m1012'), dataIndex: 'rebateRate', width: 90 },
            { title: t('common:m8'), dataIndex: 'status', width: 90, render: (v) => statusTag(v) },
          ]}
        />
        {!loadingPlans && plans.length === 0 && <Empty description={t('common:m1022')} />}
      </Card>
    </div>
  );

  const subTab = (
    <div>
      <Card size="small" title={t('common:m1008')} style={{ marginBottom: 12 }}>
        <Form
          form={subForm}
          layout="vertical"
          onFinish={(v) =>
            subscribe(v)
              .then(() => {
                message.success(t('common:m997'));
                loadSubs(v.subscriberUserId);
              })
              .catch((e) => message.error(e.message))
          }
        >
          <Form.Item name="planId" label={t('common:m1015')} rules={[{ required: true }]}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="subscriberUserId" label={t('common:m1016')} rules={[{ required: true }]}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="unitCount" label={t('common:m1017')} rules={[{ required: true }]}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Button type="primary" htmlType="submit">
            {t('common:m1008')}
          </Button>
        </Form>
        <div style={{ marginTop: 12 }}>
          <InputNumber
            placeholder={t('common:m213')}
            value={assetId}
            onChange={setAssetId}
            style={{ width: 180, marginRight: 8 }}
          />
          <Button onClick={() => loadOpenPlans(assetId)}>{t('common:m248')}</Button>
        </div>
      </Card>
      <Card
        size="small"
        title={t('common:m1006')}
        extra={<Button onClick={() => loadSubs(subForm.getFieldValue('subscriberUserId'))}>{t('common:m248')}</Button>}
      >
        <Table
          rowKey="id"
          dataSource={subs}
          loading={loadingSubs}
          pagination={false}
          size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: t('common:m1015'), dataIndex: 'planId', width: 90 },
            { title: t('common:m1017'), dataIndex: 'unitCount', width: 80 },
            { title: t('common:m1014'), dataIndex: 'prepaidAmount', width: 100 },
            {
              title: t('common:m1025'),
              dataIndex: 'progress',
              width: 200,
              render: (_, r) => {
                const p = openPlans[r.planId];
                if (p && p.totalUnits) {
                  const percent = Math.round(((p.subscribedUnits || 0) / p.totalUnits) * 100);
                  return <Progress percent={percent} size="small" />;
                }
                return (
                  <span>
                    {t('common:m1017')}: {r.unitCount}
                  </span>
                );
              },
            },
            { title: t('common:m8'), dataIndex: 'status', width: 90, render: (v) => statusTag(v) },
          ]}
        />
        {!loadingSubs && subs.length === 0 && <Empty description={t('common:m1023')} />}
      </Card>
    </div>
  );

  const rebateTab = (
    <div>
      <Card
        size="small"
        title={t('common:m1007')}
        extra={<Button onClick={() => loadRebates(subForm.getFieldValue('subscriberUserId'))}>{t('common:m248')}</Button>}
      >
        <Table
          rowKey="id"
          dataSource={rebates}
          loading={loadingRebates}
          pagination={false}
          size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: t('common:m1018'), dataIndex: 'settlementNo', width: 120 },
            { title: t('common:m1019'), dataIndex: 'rentalOrderId', width: 120 },
            { title: t('common:m1017'), dataIndex: 'unitCount', width: 80 },
            { title: t('common:m1020'), dataIndex: 'ratio', width: 80 },
            { title: t('common:m1021'), dataIndex: 'amount', width: 100 },
            { title: t('common:m8'), dataIndex: 'status', width: 90, render: (v) => statusTag(v) },
          ]}
        />
        {!loadingRebates && rebates.length === 0 && <Empty description={t('common:m1024')} />}
      </Card>
    </div>
  );

  return (
    <PageCard title={t('common:m1004')} subtitle="容量计划 / 预订 / 回佣">
      <Tabs
        items={[
          { key: 'plans', label: t('common:m1005'), children: planTab },
          { key: 'subs', label: t('common:m1006'), children: subTab },
          { key: 'rebates', label: t('common:m1007'), children: rebateTab },
        ]}
      />
    </PageCard>
  );
}
