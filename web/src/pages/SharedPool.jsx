import React, { useState } from 'react';
import { Card, Form, InputNumber, Input, Button, message, Tabs, Table, Tag, Empty, Select } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';
import { useTranslation } from 'react-i18next';

const q = (url, params) => api.post(url + '?' + new URLSearchParams(params).toString());

export default function SharedPool() {  const { t } = useTranslation('common');

  const [result, setResult] = useState(null);
  const [available, setAvailable] = useState([]);
  const [owner, setOwner] = useState([]);
  const [renter, setRenter] = useState([]);
  const [poolForm] = Form.useForm();
  const [rentalForm] = Form.useForm();

  const show = (d) => setResult(d);
  const loadAvailable = (stationId) => stationId && api.get('/v1/admin/shared-pool/available?stationId=' + stationId).then(setAvailable).catch((e) => message.error(e.message));
  const loadOwner = (ownerUserId) => ownerUserId && api.get('/v1/admin/shared-pool/owner?ownerUserId=' + ownerUserId).then(setOwner).catch((e) => message.error(e.message));
  const loadRenter = (renterUserId) => renterUserId && api.get('/v1/admin/shared-pool/renter?renterUserId=' + renterUserId).then(setRenter).catch((e) => message.error(e.message));

  const poolTab = (
    <div>
      <Card size="small" title={t('common:m552')} style={{ marginBottom: 12 }}>
        <Form form={poolForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/shared-pool/entries', v).then((d) => { show(d); loadAvailable(v.stationId); }).catch((e) => message.error(e.message))}>
          <Form.Item name="assetId" label={t('common:m213')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownerUserId" label={t('common:m553')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="stationId" label={t('common:m503')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownerSplitRate" label={t('common:m554')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={4} /></Form.Item>
          <Form.Item name="stationSplitRate" label={t('common:m555')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={4} /></Form.Item>
          <Form.Item name="dailyUsageFee" label={t('common:m556')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="perSwapFee" label={t('common:m557')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m558')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('common:m559')} extra={<Button onClick={() => loadAvailable(poolForm.getFieldValue('stationId'))}>{t('common:m248')}</Button>}>
        <Table rowKey="id" dataSource={available} pagination={false} size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: t('common:m560'), dataIndex: 'assetId', width: 90 },
            { title: t('common:m561'), dataIndex: 'ownerUserId', width: 90 },
            { title: t('common:m8'), dataIndex: 'status', width: 100, render: (v) => <Tag>{v}</Tag> },
          ]} />
        {available.length === 0 && <Empty description={t('common:m562')} />}
      </Card>
    </div>
  );

  const rentalTab = (
    <div>
      <Card size="small" title={t('common:m563')} style={{ marginBottom: 12 }}>
        <Form form={rentalForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/shared-pool/rentals', v).then(show).catch((e) => message.error(e.message))}>
          <Form.Item name="assetId" label={t('common:m213')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="renterUserId" label={t('common:m564')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="stationId" label={t('common:m503')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="rentalType" label={t('common:m565')} rules={[{ required: true }]}>
            <Select placeholder={t('common:m566')} options={[
              { value: 'BATTERY_EXCHANGE', label: t('common:m567') },
              { value: 'VEHICLE_RENTAL', label: t('common:m568') },
            ]} />
          </Form.Item>
          <Form.Item name="poolEntryId" label={t('common:m569')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m563')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('common:m570')} style={{ marginBottom: 12 }}>
        <Form layout="vertical" onFinish={(v) => q('/v1/admin/shared-pool/rentals/' + v.rentalOrderId + '/complete', { totalFee: v.totalFee }).then((d) => { show(d); loadRenter(v.renterUserId); }).catch((e) => message.error(e.message))}>
          <Form.Item name="rentalOrderId" label={t('common:m571')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="totalFee" label={t('common:m572')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="renterUserId" label={t('common:m573')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m574')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('common:m575')}>
        <Form layout="vertical" onFinish={(v) => q('/v1/admin/shared-pool/entries/' + v.poolEntryId + '/remove', {}).then((d) => { show(d); loadAvailable(poolForm.getFieldValue('stationId')); }).catch((e) => message.error(e.message))}>
          <Form.Item name="poolEntryId" label={t('common:m569')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m576')}</Button>
        </Form>
      </Card>
    </div>
  );

  const myTab = (
    <div>
      <Card size="small" title={t('common:m577')} extra={<Button onClick={() => loadOwner(poolForm.getFieldValue('ownerUserId'))}>{t('common:m248')}</Button>}>
        <Table rowKey="id" dataSource={owner} pagination={false} size="small"
          columns={[{ title: 'ID', dataIndex: 'id', width: 70 }, { title: t('common:m560'), dataIndex: 'assetId', width: 90 }, { title: t('common:m8'), dataIndex: 'status', render: (v) => <Tag>{v}</Tag> }]} />
        {owner.length === 0 && <Empty description={t('common:m578')} />}
      </Card>
      <Card size="small" title={t('common:m579')} style={{ marginTop: 12 }} extra={<Button onClick={() => loadRenter(rentalForm.getFieldValue('renterUserId'))}>{t('common:m248')}</Button>}>
        <Table rowKey="id" dataSource={renter} pagination={false} size="small"
          columns={[{ title: 'ID', dataIndex: 'id', width: 70 }, { title: t('common:m560'), dataIndex: 'assetId', width: 90 }, { title: t('common:m8'), dataIndex: 'status', width: 100, render: (v) => <Tag>{v}</Tag> }, { title: t('common:m115'), dataIndex: 'totalFee', width: 90 }]} />
        {renter.length === 0 && <Empty description={t('common:m578')} />}
      </Card>
    </div>
  );

  return (
    <PageCard title={t('common:m580')} subtitle="资产入池 / 租赁 / 完成分账 / 出池">
      <Tabs items={[
        { key: 'p', label: t('common:m558'), children: poolTab },
        { key: 'r', label: t('common:m581'), children: rentalTab },
        { key: 'm', label: t('common:m582'), children: myTab },
      ]} />
      {result && <Card size="small" title={t('common:m262')} style={{ marginTop: 12 }}><pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12, maxHeight: 320, overflow: 'auto' }}>{JSON.stringify(result, null, 2)}</pre></Card>}
    </PageCard>
  );
}
