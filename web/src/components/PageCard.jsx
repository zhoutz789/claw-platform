import { Card, Button } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';

export default function PageCard({ title, extra, reload, children, loading }) {
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
              刷新
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
