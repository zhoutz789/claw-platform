import { Card, Button } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

/**
 * 页面通用卡片容器（标题 + 右上角操作区 + 可选刷新按钮）。
 *
 * @param {Object} props 组件属性
 * @param {React.ReactNode} props.title 卡片标题
 * @param {React.ReactNode} [props.extra] 右上角额外操作区
 * @param {Function} [props.reload] 提供时显示刷新按钮
 * @param {React.ReactNode} props.children 卡片内容
 * @param {boolean} [props.loading] 刷新按钮的加载态
 * @returns {JSX.Element} 卡片
 */
export default function PageCard({ title, extra, reload, children, loading }) {
  const { t } = useTranslation();
  return (
    <Card
      title={title}
      extra={
        <span>
          {extra}
          {reload && (
            <Button
              icon={<ReloadOutlined />}
              onClick={reload}
              loading={loading}
              style={{ marginLeft: 8 }}
            >
              {t('action.refresh')}
            </Button>
          )}
        </span>
      }
      style={{ marginBottom: 16 }}
    >
      {children}
    </Card>
  );
}
