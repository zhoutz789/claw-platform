// 车辆（地面自动驾驶 / 换电）域接口封装（T8 增量）。
//
// 全部对接 claw-platform/backend 的真实 Controller，无任何 mock 数据。
// Controller 与基路径对照（后端文件位于 web/v1/）：
//   VehicleProductClassAdminController  /api/v1/admin/vehicle-product-classes
//   VehicleController                  /api/v1/vehicles/{vehicleId}
//   （轨迹 / 自动驾驶 / 安全 / 任务 / 平台地面围栏均挂在 VehicleController 子路径下）
//
// 依赖方向铁律：页面一律通过本模块取数，不得直写 api.get('/v1/vehicles/...')。
// 此处同样重新声明一份本地 cleanParams（与 drone.js 同约定，避免平级模块互相 import 产生环）。
import api from '../api.js';

/**
 * 去掉未填的查询参数，避免拼出 "assetId=undefined" 这类脏 query。
 * @param {Object} [params] 原始查询参数
 * @returns {Object} 清洗后的查询参数
 */
export function cleanParams(params = {}) {
  return Object.fromEntries(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  );
}

/* ------------------------------ 车型（产品类目）管理 ------------------------------ */

/** 车型列表（全量，后端无分页）。@returns {Promise<Array<Object>>} VehicleProductClass[] */
export const listProductClasses = () => api.get('/v1/admin/vehicle-product-classes');

/**
 * 新增车型。
 * @param {{code:string,nameZh:string,nameEn:string,nameKm:string,scenario:string,
 *          autonomyLevel:string,capabilityTags?:string[],defaultDeviceTypes?:string[],
 *          requiredCerts?:string[],attrSchema?:string,geofencePreset?:string}} body 请求体
 * @returns {Promise<Object>} 新建的 VehicleProductClass
 */
export const createProductClass = (body) => api.post('/v1/admin/vehicle-product-classes', body);

/** 种子 8 个默认车型（幂等）。@returns {Promise<number>} 实际写入的车型数量 */
export const seedProductClasses = () => api.post('/v1/admin/vehicle-product-classes/seed');

/** 单个车型详情。@param {string} code 车型编码 @returns {Promise<Object>} */
export const getProductClass = (code) => api.get(`/v1/admin/vehicle-product-classes/${encodeURIComponent(code)}`);

/** 某车型的场景属性列表。@param {string} code 车型编码 @returns {Promise<Array<Object>>} */
export const getProductClassAttrs = (code) => api.get(`/v1/admin/vehicle-product-classes/${encodeURIComponent(code)}/attrs`);

/**
 * 为某车型新增场景属性。
 * @param {string} code 车型编码
 * @param {{attrKey:string,attrType:string,unit?:string,required?:boolean,
 *          labelZh:string,labelEn:string,labelKm:string,sortOrder?:number}} body 请求体
 * @returns {Promise<Object>} 新建的属性
 */
export const createProductClassAttr = (code, body) => api.post(`/v1/admin/vehicle-product-classes/${encodeURIComponent(code)}/attrs`, body);

/* ------------------------------ 车辆本体 ------------------------------ */

/** 车辆概要。@param {number} vehicleId @returns {Promise<Object>} */
export const getVehicle = (vehicleId) => api.get(`/v1/vehicles/${vehicleId}`);

/** 能源视图（当前电池 + 近期换电）。@param {number} vehicleId @returns {Promise<Object>} */
export const getVehicleEnergy = (vehicleId) => api.get(`/v1/vehicles/${vehicleId}/energy`);

/** 电池绑定历史。@param {number} vehicleId @returns {Promise<Object>} */
export const getVehicleBattery = (vehicleId) => api.get(`/v1/vehicles/${vehicleId}/battery`);

/**
 * 轨迹点列表（最近 24h；可传 from/to 限定）。
 * @param {number} vehicleId
 * @param {{from?:string,to?:string}} [params] ISO-8601 UTC 字符串
 * @returns {Promise<Array<Object>|Object>} 轨迹点数组（或 {points:[...]}）
 */
export const getVehicleTrajectory = (vehicleId, params) =>
  api.get(`/v1/vehicles/${vehicleId}/trajectory`, { params: cleanParams(params) });

/** 轨迹最新点。@param {number} vehicleId @returns {Promise<Object>} */
export const getVehicleTrajectoryLatest = (vehicleId) => api.get(`/v1/vehicles/${vehicleId}/trajectory/latest`);

/* ------------------------------ 自动驾驶模块 ------------------------------ */

/** 当前自动驾驶模块（algoVersion / driveMode）。@param {number} vehicleId @returns {Promise<Object>} */
export const getAutonomyModule = (vehicleId) => api.get(`/v1/vehicles/${vehicleId}/autonomy/module`);

/** 设定自动驾驶模块（算法版本 + 驾驶模式）。@param {number} vehicleId @param {{algoVersion:string,driveMode:string}} body */
export const setAutonomyModule = (vehicleId, body) => api.post(`/v1/vehicles/${vehicleId}/autonomy/module`, body);

/** 切换驾驶模式。@param {number} vehicleId @param {{driveMode:string}} body */
export const setDriveMode = (vehicleId, body) => api.post(`/v1/vehicles/${vehicleId}/autonomy/drive-mode`, body);

/** 进入远程遥控（teleop）。@param {number} vehicleId */
export const enterTeleop = (vehicleId) => api.post(`/v1/vehicles/${vehicleId}/autonomy/teleop/enter`);

/** 退出远程遥控（teleop）。@param {number} vehicleId */
export const exitTeleop = (vehicleId) => api.post(`/v1/vehicles/${vehicleId}/autonomy/teleop/exit`);

/** 下发语音指令。@param {number} vehicleId @param {{direction:string,text:string,lang:string}} body */
export const sendVoice = (vehicleId, body) => api.post(`/v1/vehicles/${vehicleId}/autonomy/voice`, body);

/** 当前安全态。@param {number} vehicleId @returns {Promise<Object>} */
export const getAutonomySafety = (vehicleId) => api.get(`/v1/vehicles/${vehicleId}/autonomy/safety`);

/** 安全锁机。@param {number} vehicleId */
export const lockSafety = (vehicleId) => api.post(`/v1/vehicles/${vehicleId}/autonomy/safety/lock`);

/* ------------------------------ 自动驾驶任务 ------------------------------ */

/** 任务列表。@param {number} vehicleId @returns {Promise<Array<Object>>} */
export const listAutonomyTasks = (vehicleId) => api.get(`/v1/vehicles/${vehicleId}/autonomy/tasks`);

/**
 * 创建任务。
 * @param {number} vehicleId
 * @param {{subtype:string,pathJson:string}} body subtype ∈ {DELIVERY,SWEEP,PATROL}，pathJson 为 JSON 字符串
 */
export const createAutonomyTask = (vehicleId, body) => api.post(`/v1/vehicles/${vehicleId}/autonomy/tasks`, body);

/**
 * 派发任务。
 * @param {number} vehicleId
 * @param {{taskId:number,subtype:string,pathJson:string}} body
 */
export const dispatchAutonomyTask = (vehicleId, body) => api.post(`/v1/vehicles/${vehicleId}/autonomy/tasks/dispatch`, body);

/** 更新某任务进度。@param {number} vehicleId @param {number} taskId @param {{pct:number}} body */
export const updateTaskProgress = (vehicleId, taskId, body) =>
  api.post(`/v1/vehicles/${vehicleId}/autonomy/tasks/${taskId}/progress`, body);

/** 上报进度（批量）。@param {number} vehicleId @param {{pct:number}} body */
export const reportProgress = (vehicleId, body) => api.post(`/v1/vehicles/${vehicleId}/autonomy/tasks/report-progress`, body);

/* ------------------------------ 平台地面围栏 ------------------------------ */

/** 平台级地面围栏列表（全量）。@returns {Promise<Array<Object>>} */
export const listGeofences = () => api.get('/v1/vehicles/geofences');

/**
 * 校验路径是否越界围栏。
 * @param {{assetId:number,pathJson:string}} body
 * @returns {Promise<Object>} 校验结果（是否越界 + 命中围栏等）
 */
export const validateGeofence = (body) => api.post('/v1/vehicles/geofences/validate', body);

/* ------------------------------ 资产（车辆） ------------------------------ */

/**
 * 车辆资产列表（assetType = VEHICLE；可选 EV）。
 * @param {string} [assetType='VEHICLE'] 资产类型
 * @returns {Promise<Array<Object>>} AssetView[]
 */
export const listVehicleAssets = (assetType = 'VEHICLE') =>
  api.get('/v1/assets', { params: { assetType } });

/* ------------------------------ 车辆资产生命周期（建档 / 入网） ------------------------------ */

/**
 * 新建车辆资产（assetType = VEHICLE）。后端 AssetController.POST /v1/assets/vehicle。
 * @param {{assetNo:string,model:string,qrCode?:string,vin?:string,frameNo?:string,
 *          motorNo?:string,lessorId?:number,protocolVer?:string,ownerId?:number}} body 请求体
 * @returns {Promise<Object>} 新建的 AssetView
 */
export const createVehicleAsset = (body) => api.post('/v1/assets/vehicle', body);

/**
 * 设备上线部署（绑定到站点 / 产权人，写产权链首笔 + 补建 IoT 设备行）。
 * 后端 AssetController.POST /v1/assets/{assetId}/bind；assetId 同时写入 body 以满足后端 @NotNull。
 * @param {number} assetId 资产 ID
 * @param {{stationId?:number,imei?:string,deviceType?:string,location?:string}} [body] 请求体
 * @returns {Promise<Object>} 绑定后的 AssetView
 */
export const bindVehicleDevice = (assetId, body = {}) =>
  api.post(`/v1/assets/${assetId}/bind`, { assetId, ...body });

export default {
  cleanParams,
  listProductClasses,
  createProductClass,
  seedProductClasses,
  getProductClass,
  getProductClassAttrs,
  createProductClassAttr,
  getVehicle,
  getVehicleEnergy,
  getVehicleBattery,
  getVehicleTrajectory,
  getVehicleTrajectoryLatest,
  getAutonomyModule,
  setAutonomyModule,
  setDriveMode,
  enterTeleop,
  exitTeleop,
  sendVoice,
  getAutonomySafety,
  lockSafety,
  listAutonomyTasks,
  createAutonomyTask,
  dispatchAutonomyTask,
  updateTaskProgress,
  reportProgress,
  listGeofences,
  validateGeofence,
  listVehicleAssets,
  createVehicleAsset,
  bindVehicleDevice,
};
