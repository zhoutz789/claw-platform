// 无人机 / 低空经济域（增量 D）的接口封装。
//
// 全部对接 claw-platform/backend 的真实 Controller，无任何 mock 数据（mock/api.js 中
// drone 端点没有落点，请求必然打到真接口）。
// Controller 与基路径对照（后端文件位于 web/v1/）：
//   AirspaceController       /api/v1/airspace        （zones / flight-plans / pilot-licenses，各 GET+POST，无 PUT/DELETE）
//   DroneMissionController   /api/v1/drone-missions  （GET 支持 assetId 过滤 / POST）
//   DroneSafetyController    /api/v1/drone-safety    （status / events / simulate / resolve）
//   AssetController          /api/v1/assets          （列表，按 assetType=DRONE 过滤）
//
// 依赖方向铁律：页面一律通过本模块取数，不得直写 api.get('/v1/airspace/...')。
import api from '../api.js';

/**
 * 去掉未填的查询参数，避免拼出 "assetId=undefined" 这类脏 query。
 *
 * 刻意不在 api/supplyChain.js 复用同名函数：api 层是平级模块，跨域 import 会在未来
 * 拆包时产生环。这里重新声明一份同实现的本地函数（与 supplyChain.cleanParams 行为一致）。
 *
 * @param {Object} [params] 原始查询参数
 * @returns {Object} 清洗后的查询参数
 */
export function cleanParams(params = {}) {
  return Object.fromEntries(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  );
}

/* ------------------------------ 空域分区 zones ------------------------------ */

/**
 * 空域分区列表（全量，后端无分页）。
 * @returns {Promise<Array<Object>>} AirspaceZone[]
 */
export const listZones = () => api.get('/v1/airspace/zones');

/**
 * 新增空域分区。
 *
 * ⚠️ 字段名陷阱：请求用 `lat` / `lng`，响应返回 `centerLat` / `centerLng`。
 * 本函数不做转换（那是后端契约），此处保持原样透传。空域一经创建不可修改、不可停用。
 *
 * @param {{name: string, level: string, lat: number, lng: number, radiusM: number, country?: string, note?: string}} body 请求体
 * @returns {Promise<Object>} 新建的 AirspaceZone
 */
export const createZone = (body) => api.post('/v1/airspace/zones', body);

/* ------------------------------ 飞行计划 flight-plans ----------------------------- */

/**
 * 飞行计划列表（全量，后端无分页）。
 * @returns {Promise<Array<Object>>} FlightPlan[]
 */
export const listFlightPlans = () => api.get('/v1/airspace/flight-plans');

/**
 * 新增飞行计划。后端校验：空域须存在且 level === 'OPERATIONAL'，创建即 APPROVED。
 *
 * @param {{assetId: number, zoneId: number, pilotId: number, plannedAt?: string, routeNote?: string}} body 请求体
 * plannedAt 为 ISO-8601 UTC 字符串（dayjs(v).toISOString()），留空则由后端取 Instant.now()。
 * @returns {Promise<Object>} 新建的 FlightPlan（status 恒为 APPROVED）
 */
export const createFlightPlan = (body) => api.post('/v1/airspace/flight-plans', body);

/* ------------------------------ 飞手资质 pilot-licenses ---------------------------- */

/**
 * 飞手资质列表（全量，后端无分页）。
 * @returns {Promise<Array<Object>>} PilotLicense[]
 */
export const listLicenses = () => api.get('/v1/airspace/pilot-licenses');

/**
 * 新增飞手资质。licenseNo 唯一（后端唯一约束）。
 *
 * ⚠️ expiryDate 是 LocalDate（YYYY-MM-DD），不是 Instant，不要与时分秒混用。
 *
 * @param {{licenseNo: string, holderName: string, ltype: string, issuer?: string, expiryDate: string}} body 请求体
 * @returns {Promise<Object>} 新建的 PilotLicense
 */
export const createLicense = (body) => api.post('/v1/airspace/pilot-licenses', body);

/* ------------------------------ 作业计量 drone-missions --------------------------- */

/**
 * 作业计量列表，可按资产过滤。
 * @param {{assetId?: number}} [params] 查询参数（后端仅支持 assetId）
 * @returns {Promise<Array<Object>>} DroneMission[]
 */
export const listMissions = (params) => api.get('/v1/drone-missions', { params: cleanParams(params) });

/**
 * 登记作业计量。
 *
 * @param {{assetId: number, missionType: string, payloadDesc?: string, areaHa?: number,
 *          trips?: number, flightMinutes?: number, pilotId: number, executedAt?: string}} body 请求体
 * @returns {Promise<Object>} 新建的 DroneMission
 */
export const createMission = (body) => api.post('/v1/drone-missions', body);

/* ------------------------------ 飞行安全 drone-safety ---------------------------- */

/**
 * 取资产当前安全态。
 * @param {number} assetId 资产 ID
 * @returns {Promise<'NORMAL'|'LOCKED'>} 裸枚举字符串（不是对象）
 */
export const getSafetyStatus = (assetId) => api.get(`/v1/drone-safety/${assetId}`);

/**
 * 取资产安全事件列表（后端已按 createdAt 倒序，前端不再排序）。
 * @param {number} assetId 资产 ID
 * @returns {Promise<Array<Object>>} DroneSafetyEvent[]
 */
export const listSafetyEvents = (assetId) => api.get(`/v1/drone-safety/${assetId}/events`);

/**
 * 模拟触发锁机。
 * @param {number} assetId 资产 ID
 * @param {{cause: string, detail?: string}} body 触发原因与说明
 * @returns {Promise<Object>} 新建的 DroneSafetyEvent（status = OPEN）
 */
export const simulateLock = (assetId, body) => api.post(`/v1/drone-safety/${assetId}/simulate`, body);

/**
 * 解除锁机。后端按 createdAt 升序 FIFO 解除最早的一条 OPEN 事件；
 * 无 OPEN 事件时返回 409 / bizCode 40961。
 * @param {number} assetId 资产 ID
 * @returns {Promise<Object>} 被解除的 DroneSafetyEvent
 */
export const resolveLock = (assetId) => api.post(`/v1/drone-safety/${assetId}/resolve`);

/* ------------------------------ 资产（无人机） ------------------------------ */

/**
 * 无人机资产列表（assetType = DRONE）。
 * @returns {Promise<Array<Object>>} AssetView[]
 */
export const listDroneAssets = () => api.get('/v1/assets', { params: { assetType: 'DRONE' } });

/* ------------------------------ 设备电子围栏 geofences（iot 域，AdminIotController） ---------------------------- */
// 说明：围栏是「每台设备」的私有地理围栏（与平台级 airspace_zones 无关）。
// 该端点具备完整 CRUD（G45 核实）：GET?ownerType=&ownerId= / POST / PUT/{id} / DELETE/{id}，
// 因此比 airspace_zones 多了一层「可编辑可删除」，不在 Q1 的「不可修改」约束内（C2 裁定）。

/**
 * 设备电子围栏列表（按 owner 过滤）。
 * @param {{ownerType?: string, ownerId?: number}} [params] ownerType='ASSET' / ownerId=资产 id
 * @returns {Promise<Array<Object>>} Geofence[]
 */
export const listGeofences = (params) => api.get('/v1/iot/geofences', { params: cleanParams(params) });

/**
 * 新增设备电子围栏。
 * @param {{ownerType: string, ownerId: number, fenceType: 'RADIUS'|'POLYGON',
 *          centerLat?: number, centerLng?: number, radiusM?: number,
 *          polygonWkt?: string, triggerAction?: string, name?: string}} body 请求体
 * @returns {Promise<Object>} 新建的 Geofence（status 默认 ENABLED）
 */
export const createGeofence = (body) => api.post('/v1/iot/geofences', body);

/**
 * 更新设备电子围栏（全字段可选，仅覆盖非空项；含启用 / 停用 status）。
 * @param {number} id 围栏 id
 * @param {Object} body 部分字段
 * @returns {Promise<Object>} 更新后的 Geofence
 */
export const updateGeofence = (id, body) => api.put(`/v1/iot/geofences/${id}`, body);

/**
 * 删除设备电子围栏。
 * @param {number} id 围栏 id
 * @returns {Promise<void>}
 */
export const deleteGeofence = (id) => api.delete(`/v1/iot/geofences/${id}`);

export default {
  cleanParams,
  listZones,
  createZone,
  listFlightPlans,
  createFlightPlan,
  listLicenses,
  createLicense,
  listMissions,
  createMission,
  getSafetyStatus,
  listSafetyEvents,
  simulateLock,
  resolveLock,
  listDroneAssets,
  listGeofences,
  createGeofence,
  updateGeofence,
  deleteGeofence,
};
