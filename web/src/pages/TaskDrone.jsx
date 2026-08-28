import TaskPublish from './TaskPublish';

// 无人机任务子菜单：复用 TaskPublish 的 drone 实现（真实写入 /v1/drone-missions）
export default function TaskDrone() {
  return <TaskPublish mode="drone" />;
}
