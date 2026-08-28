import TaskPublish from './TaskPublish';

// 录像数据子菜单：复用 TaskPublish 的 video 实现
export default function TaskVideo() {
  return <TaskPublish mode="video" />;
}
