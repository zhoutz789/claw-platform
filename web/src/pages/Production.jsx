import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  App, Button, Descriptions, Form, Input, InputNumber, Modal, Select, Space, Table, Tag,
} from 'antd';
import {
  PlusOutlined, CheckCircleOutlined, FileSearchOutlined, PrinterOutlined, ReloadOutlined,
} from '@ant-design/icons';
import PageCard from '../components/PageCard';
import { Perm } from '../components/Perm';
import { EMPTY, asArray, fmtTime, useSupplyOptions } from '../components/supplyShared';
import {
  completeProductionTask, createProductionTask, getCertificateByDevice, listProductionTasks,
  reprintCertificate,
} from '../api/supplyChain';

/**
 * 生产管理页（增量 B · R3/B1/B4）。
 *
 * 链路：基于商品模版（product）建生产任务 → 完成生产时按数量实例化 device（序列号）
 *      → 生成合格证（certificates，生成即写库不可事后补）→ 入厂家自有库存。
 * 对接后端 AdminProductionController（/api/v1/admin/production）。
 */
export default function Production() {
  const { t } = useTranslation(['common', 'supply']);
  const { message } = App.useApp();
  const { manufacturerOptions, products, manufacturerName, productName } = useSupplyOptions();

  // 表单实例必须先于 Form.useWatch 声明，否则会命中 const 的暂时性死区（TDZ）。
  const [form] = Form.useForm();
  const [certForm] = Form.useForm();

  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [manufacturerId, setManufacturerId] = useState(undefined);

  // 弹窗模式：null=关闭 / 'create'=新建任务 / 'complete'=完成生产 / 'cert'=合格证
  const [mode, setMode] = useState(null);
  const [editing, setEditing] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [cert, setCert] = useState(null);
  const [certLoading, setCertLoading] = useState(false);

  // 商品下拉跟随「厂家」联动（后端 GET /admin/manufacturer/products?manufacturerId= 的同一份数据本地过滤）。
  const watchedManufacturerId = Form.useWatch('manufacturerId', form);
  const productOptions = useMemo(() => {
    const list = asArray(products).filter(
      (p) => watchedManufacturerId == null || p.manufacturerId == null || p.manufacturerId === watchedManufacturerId
    );
    return list.map((p) => ({ label: p.name || p.model || `#${p.id}`, value: p.id }));
  }, [products, watchedManufacturerId]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await listProductionTasks(manufacturerId);
      setRows(Array.isArray(d) ? d : []);
    } catch (e) {
      message.error(t('msg.loadFailed', { msg: e.message }));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [manufacturerId, message, t]);

  useEffect(() => { load(); }, [load]);

  const closeModal = () => {
    setMode(null);
    setEditing(null);
    setCert(null);
    form.resetFields();
    certForm.resetFields();
  };

  const openCreate = () => {
    setEditing(null);
    setMode('create');
    form.resetFields();
    form.setFieldsValue({ planQuantity: 1 });
  };

  const openComplete = (record) => {
    setEditing(record);
    setMode('complete');
    form.resetFields();
    form.setFieldsValue({ producedQuantity: null });
  };

  const openCert = () => {
    setMode('cert');
    setCert(null);
    certForm.resetFields();
  };

  /** 提交新建 / 完成生产。 */
  const submit = async () => {
    const v = await form.validateFields();
    setSubmitting(true);
    try {
      if (mode === 'create') {
        await createProductionTask({
          manufacturerId: v.manufacturerId,
          productId: v.productId ?? null,
          planQuantity: v.planQuantity ?? 0,
          specJson: v.specJson || null,
        });
        message.success(t('supply:production.msg.taskCreated'));
      } else {
        await completeProductionTask(editing.id, v.producedQuantity ?? null);
        message.success(t('supply:production.msg.taskCompleted'));
      }
      closeModal();
      load();
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setSubmitting(false);
    }
  };

  /** 按设备ID查询合格证；不存在时后端返回 40401，此处给出明确提示。 */
  const queryCert = async () => {
    const v = await certForm.validateFields();
    setCertLoading(true);
    setCert(null);
    try {
      const d = await getCertificateByDevice(v.deviceId);
      setCert(d);
    } catch (e) {
      message.error(`${t('supply:production.cert.notFound')}（${e.message}）`);
    } finally {
      setCertLoading(false);
    }
  };

  /** 补打：仅重新输出已存在的合格证，不生成新的 cert_no（Q8）。 */
  const reprint = async () => {
    const v = certForm.getFieldValue('deviceId');
    if (v === null || v === undefined || v === '') {
      message.warning(t('supply:production.msg.inputDeviceId'));
      return;
    }
    setCertLoading(true);
    try {
      const d = await reprintCertificate(v);
      setCert(d);
      message.success(t('supply:production.cert.reprinted'));
    } catch (e) {
      message.error(t('msg.opFailed', { msg: e.message }));
    } finally {
      setCertLoading(false);
    }
  };

  const columns = [
    { title: t('supply:production.col.id'), dataIndex: 'id', width: 90 },
    {
      title: t('supply:production.col.manufacturerId'),
      dataIndex: 'manufacturerId',
      width: 150,
      render: (v) => manufacturerName(v),
    },
    {
      title: t('supply:production.col.productId'),
      dataIndex: 'productId',
      width: 160,
      render: (v) => (v == null ? EMPTY : `${productName(v)} (#${v})`),
    },
    { title: t('supply:production.col.planQuantity'), dataIndex: 'planQuantity', width: 110 },
    {
      title: t('supply:production.col.producedQuantity'),
      dataIndex: 'producedQuantity',
      width: 110,
      render: (v, r) => (
        <span>
          {v ?? 0}
          {r.planQuantity ? <span style={{ color: '#999' }}> / {r.planQuantity}</span> : null}
        </span>
      ),
    },
    {
      title: t('supply:production.col.status'),
      dataIndex: 'status',
      width: 110,
      render: (v) => <Tag color={v === 'COMPLETED' ? 'green' : 'blue'}>{v || EMPTY}</Tag>,
    },
    {
      title: t('supply:production.col.specJson'),
      dataIndex: 'specJson',
      ellipsis: true,
      render: (v) => v || EMPTY,
    },
    { title: t('supply:production.col.createdBy'), dataIndex: 'createdBy', width: 100 },
    {
      title: t('supply:production.col.createdAt'),
      dataIndex: 'createdAt',
      width: 150,
      render: (v) => fmtTime(v),
    },
    {
      title: t('table.actions'),
      key: '_actions',
      width: 260,
      fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Perm code="mfg:production:create">
            <Button
              size="small"
              type="link"
              icon={<CheckCircleOutlined />}
              disabled={r.status === 'COMPLETED'}
              onClick={() => openComplete(r)}
            >
              {t('supply:production.completeTask')}
            </Button>
          </Perm>
          <Perm any={['mfg:certificate:view', 'mfg:production:view']}>
            <Button size="small" type="link" icon={<FileSearchOutlined />} onClick={openCert}>
              {t('supply:production.viewCert')}
            </Button>
          </Perm>
        </Space>
      ),
    },
  ];

  return (
    <PageCard
      title={t('supply:production.title')}
      subtitle={t('supply:production.subtitle')}
      extra={
        <Space>
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t('supply:common.selectManufacturer')}
            style={{ width: 200 }}
            options={manufacturerOptions}
            value={manufacturerId}
            onChange={setManufacturerId}
          />
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            {t('action.refresh')}
          </Button>
          <Perm code="mfg:certificate:print">
            <Button icon={<PrinterOutlined />} onClick={openCert}>
              {t('supply:production.reprintCert')}
            </Button>
          </Perm>
          <Perm code="mfg:production:create">
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              {t('supply:production.newTask')}
            </Button>
          </Perm>
        </Space>
      }
    >
      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        columns={columns}
        size="middle"
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
      />

      {/* 新建生产任务 / 完成生产（实例化 device + 生成合格证 + 入自有库存） */}
      <Modal
        title={mode === 'create' ? t('supply:production.newTask') : t('supply:production.completeTask')}
        open={mode === 'create' || mode === 'complete'}
        onOk={submit}
        confirmLoading={submitting}
        onCancel={closeModal}
        destroyOnClose
        width={520}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          {mode === 'create' ? (
            <>
              <Form.Item
                name="manufacturerId"
                label={t('supply:production.field.manufacturerId')}
                rules={[{ required: true, message: t('form.required', { label: t('supply:production.field.manufacturerId') }) }]}
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  allowClear
                  placeholder={t('supply:common.selectManufacturer')}
                  options={manufacturerOptions}
                />
              </Form.Item>
              <Form.Item
                name="productId"
                label={t('supply:production.field.productId')}
                rules={[{ required: true, message: t('form.required', { label: t('supply:production.field.productId') }) }]}
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  allowClear
                  placeholder={t('supply:common.selectProduct')}
                  options={productOptions}
                />
              </Form.Item>
              <Form.Item
                name="planQuantity"
                label={t('supply:production.field.planQuantity')}
                rules={[{ required: true, message: t('form.required', { label: t('supply:production.field.planQuantity') }) }]}
              >
                <InputNumber min={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="specJson" label={t('supply:production.field.specJson')}>
                <Input.TextArea rows={3} placeholder={t('supply:production.ph.specJson')} />
              </Form.Item>
            </>
          ) : (
            <Form.Item name="producedQuantity" label={t('supply:production.field.producedQuantity')}>
              <InputNumber min={0} precision={0} style={{ width: '100%' }} placeholder={t('supply:production.ph.producedQuantity')} />
            </Form.Item>
          )}
        </Form>
      </Modal>

      {/* 合格证查看 / 补打（Q8：生成即写库，补打不产生新证号） */}
      <Modal
        title={t('supply:production.cert.title')}
        open={mode === 'cert'}
        onCancel={closeModal}
        destroyOnClose
        width={640}
        footer={[
          <Button key="close" onClick={closeModal}>{t('action.cancel')}</Button>,
          <Perm key="print" code="mfg:certificate:print">
            <Button icon={<PrinterOutlined />} loading={certLoading} onClick={reprint}>
              {t('supply:production.reprintCert')}
            </Button>
          </Perm>,
          <Button key="query" type="primary" loading={certLoading} onClick={queryCert}>
            {t('action.search')}
          </Button>,
        ]}
      >
        <Form form={certForm} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item
            name="deviceId"
            label={t('supply:production.field.deviceId')}
            rules={[{ required: true, message: t('form.required', { label: t('supply:production.field.deviceId') }) }]}
          >
            <InputNumber style={{ width: '100%' }} placeholder={t('supply:production.ph.deviceId')} />
          </Form.Item>
        </Form>
        {cert ? (
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('supply:production.cert.certNo')}>{cert.certNo || EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:production.cert.deviceId')}>{cert.deviceId ?? EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:production.cert.issuedAt')}>{fmtTime(cert.issuedAt)}</Descriptions.Item>
            <Descriptions.Item label={t('supply:production.cert.issuedBy')}>{cert.issuedBy ?? EMPTY}</Descriptions.Item>
            <Descriptions.Item label={t('supply:production.cert.specJson')}>
              <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12 }}>
                {cert.specJson || EMPTY}
              </pre>
            </Descriptions.Item>
          </Descriptions>
        ) : (
          <div style={{ color: '#999' }}>{t('supply:production.cert.notFound')}</div>
        )}
        <div style={{ color: '#999', marginTop: 8, fontSize: 12 }}>{t('supply:production.cert.printTip')}</div>
      </Modal>
    </PageCard>
  );
}
