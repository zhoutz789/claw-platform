import React, { useState } from 'react';
import { Card, Form, InputNumber, Input, Button, message, Tabs, Table, Tag, Empty, Select } from 'antd';
import PageCard from '../components/PageCard';
import api from '../api';
import { useTranslation } from 'react-i18next';

const q = (url, params) => api.post(url + '?' + new URLSearchParams(params).toString());

export default function Recovery() {  const { t } = useTranslation('common');

  const [result, setResult] = useState(null);
  const [blacklist, setBlacklist] = useState([]);
  const [valuationForm] = Form.useForm();
  const [cashForm] = Form.useForm();
  const [tradeForm] = Form.useForm();
  const [scoreForm] = Form.useForm();
  const [blForm] = Form.useForm();

  const show = (d) => setResult(d);

  const loadBlacklist = () => api.get('/v1/admin/recovery/blacklist').then(setBlacklist).catch((e) => message.error(e.message));

  const valuationTab = (
    <div>
      <Card size="small" title={t('common:m212')} style={{ marginBottom: 12 }}>
        <Form form={valuationForm} layout="vertical" onFinish={(v) => q('/v1/admin/recovery/valuations', {}).catch(() => {})
          .then(() => api.post('/v1/admin/recovery/valuations', v).then(show).catch((e) => message.error(e.message)))}>
          <Form.Item name="assetId" label={t('common:m213')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownerUserId" label={t('common:m214')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="soh" label="SOH(%)"><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="usageYears" label={t('common:m215')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="brand" label={t('common:m90')}><Input /></Form.Item>
          <Form.Item name="model" label={t('common:m136')}><Input /></Form.Item>
          <Form.Item name="cycleCount" label={t('common:m216')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m217')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('common:m218')}>
        <Form layout="vertical" onFinish={(v) => Promise.resolve()
          .then(() => v.systemEstimate != null && q('/v1/admin/recovery/valuations/system-estimate', { valuationId: v.valuationId, estimate: v.systemEstimate }).then(show))
          .then(() => v.stationEstimate != null && q('/v1/admin/recovery/valuations/station-estimate', { valuationId: v.valuationId, estimate: v.stationEstimate }).then(show))
          .then(() => v.thirdPartyEstimate != null && q('/v1/admin/recovery/valuations/third-party-estimate', { valuationId: v.valuationId, estimate: v.thirdPartyEstimate, thirdPartyName: v.thirdPartyName || '第三方', reportUrl: v.reportUrl || '' }).then(show))
          .then(() => v.finalize && q('/v1/admin/recovery/valuations/' + v.valuationId + '/finalize', {}).then(show))
          .catch((e) => message.error(e.message))}>
          <Form.Item name="valuationId" label={t('common:m219')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="systemEstimate" label={t('common:m220')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="stationEstimate" label={t('common:m221')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="thirdPartyEstimate" label={t('common:m222')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="thirdPartyName" label={t('common:m223')}><Input /></Form.Item>
          <Form.Item name="reportUrl" label={t('common:m224')}><Input /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m225')}</Button>
        </Form>
      </Card>
    </div>
  );

  const orderTab = (
    <div>
      <Card size="small" title={t('common:m226')} style={{ marginBottom: 12 }}>
        <Form form={cashForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/recovery/cash', v).then(show).catch((e) => message.error(e.message))}>
          <Form.Item name="assetId" label={t('common:m213')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownerUserId" label={t('common:m214')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="valuationId" label={t('common:m219')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownershipId" label={t('common:m227')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="recoveryPrice" label={t('common:m228')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="processingFee" label={t('common:m229')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m230')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('common:m231')}>
        <Form form={tradeForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/recovery/trade-in', v).then(show).catch((e) => message.error(e.message))}>
          <Form.Item name="assetId" label={t('common:m232')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownerUserId" label={t('common:m214')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="valuationId" label={t('common:m219')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ownershipId" label={t('common:m227')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="oldValuation" label={t('common:m233')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Form.Item name="newAssetId" label={t('common:m234')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="newAssetPrice" label={t('common:m235')}><InputNumber style={{ width: '100%' }} precision={2} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m236')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('common:m237')}>
        <Form layout="vertical" onFinish={(v) => Promise.resolve()
          .then(() => v.confirmId && q('/v1/admin/recovery/orders/' + v.confirmId + '/confirm', {}).then(show))
          .then(() => v.completeId && q('/v1/admin/recovery/orders/' + v.completeId + '/complete', { ledgerTxnId: v.ledgerTxnId || ('TXN-' + v.completeId) }).then(show))
          .catch((e) => message.error(e.message))}>
          <Form.Item name="confirmId" label={t('common:m238')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="completeId" label={t('common:m239')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="ledgerTxnId" label={t('common:m240')}><Input /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m241')}</Button>
        </Form>
      </Card>
    </div>
  );

  const scoreTab = (
    <Card size="small" title={t('common:m242')}>
      <Form form={scoreForm} layout="vertical" onFinish={(v) => {
        if (v.delta != null) q('/v1/admin/recovery/scores/' + v.userId, { delta: v.delta, eventType: v.eventType || 'ADJUST', detail: v.detail || '' })
          .then(show).catch((e) => message.error(e.message));
        else api.get('/v1/admin/recovery/scores/' + v.userId).then(show).catch((e) => message.error(e.message));
      }}>
        <Form.Item name="userId" label={t('common:m243')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="delta" label={t('common:m244')}><InputNumber style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="eventType" label={t('common:m245')}><Input /></Form.Item>
        <Form.Item name="detail" label={t('common:m211')}><Input /></Form.Item>
        <Button type="primary" htmlType="submit">{t('common:m246')}</Button>
      </Form>
    </Card>
  );

  const blacklistTab = (
    <div>
      <Card size="small" title={t('common:m247')} extra={<Button onClick={loadBlacklist}>{t('common:m248')}</Button>}>
        <Table rowKey="id" dataSource={blacklist} pagination={false} size="small"
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: t('common:m36'), dataIndex: 'type', width: 100 },
            { title: t('common:m249'), dataIndex: 'targetId', width: 90 },
            { title: t('common:m250'), dataIndex: 'reason', ellipsis: true },
            { title: t('common:m251'), key: '_r', width: 90, render: (_, r) => <Button size="small" type="link" onClick={() => q('/v1/admin/recovery/blacklist/' + r.id + '/resolve', { resolvedBy: 1 }).then(() => { message.success(t('common:m252')); loadBlacklist(); }).catch((e) => message.error(e.message))}>{t('common:m251')}</Button> },
          ]} />
        {blacklist.length === 0 && <Empty description={t('common:m253')} />}
      </Card>
      <Card size="small" title={t('common:m254')} style={{ marginTop: 12 }}>
        <Form form={blForm} layout="vertical" onFinish={(v) => api.post('/v1/admin/recovery/blacklist', v).then(() => { message.success(t('common:m255')); loadBlacklist(); blForm.resetFields(); }).catch((e) => message.error(e.message))}>
          <Form.Item name="type" label={t('common:m36')} rules={[{ required: true }]}><Select options={[{ label: t('common:m256'), value: 'USER_BANNED' }]} /></Form.Item>
          <Form.Item name="targetId" label={t('common:m249')} rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="reason" label={t('common:m250')}><Input /></Form.Item>
          <Form.Item name="description" label={t('common:m211')}><Input /></Form.Item>
          <Form.Item name="blacklistedBy" label={t('common:m257')}><InputNumber style={{ width: '100%' }} /></Form.Item>
          <Button type="primary" htmlType="submit">{t('common:m254')}</Button>
        </Form>
      </Card>
    </div>
  );

  return (
    <PageCard title={t('common:m258')} subtitle="估价三方流程 / 现金回收 / 以旧换新 / 信用分 / 黑名单">
      <Tabs items={[
        { key: 'v', label: t('common:m259'), children: valuationTab },
        { key: 'o', label: t('common:m260'), children: orderTab },
        { key: 's', label: t('common:m261'), children: scoreTab },
        { key: 'b', label: t('common:m247'), children: blacklistTab },
      ]} />
      {result && <Card size="small" title={t('common:m262')} style={{ marginTop: 12 }}><pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12, maxHeight: 320, overflow: 'auto' }}>{JSON.stringify(result, null, 2)}</pre></Card>}
    </PageCard>
  );
}
