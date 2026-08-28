import TaskPublish from './TaskPublish';

// 资产出租子菜单：复用 TaskPublish 的 rent 实现
export default function TaskRent() {
  return <TaskPublish mode="rent" />;
}
