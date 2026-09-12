import TaskPublish from './TaskPublish';

/**
 * 车辆自动驾驶任务发布子页（任务发布中心 → 车辆）。
 * 直接复用 TaskPublish 的共享实现，仅以 mode="vehicle" 进入车辆分支。
 * @returns {JSX.Element}
 */
export default function TaskVehicle() {
  return <TaskPublish mode="vehicle" />;
}
