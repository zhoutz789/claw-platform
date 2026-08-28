import TaskPublish from './TaskPublish';

// 附近车辆子菜单：复用 TaskPublish 的 near 实现（读取真实资产 /v1/assets）
export default function TaskNear() {
  return <TaskPublish mode="near" />;
}
