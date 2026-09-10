// 摄像头 / 录像域（增量）的接口封装。
//
// 全部对接 claw-platform/backend 真实 CameraController（基路径 /api/v1/cameras）。
// 视频流本身由边缘媒体节点（edge-media）承载，本模块只取「索引与元数据」：
//   列表 / 实时取流地址 / 历史段 / 时间轴（稀疏帧 + 事件）。
//
// 依赖方向铁律：页面一律通过本模块取数，不得直写 api.get('/v1/cameras/...')。
import api from '../api.js';

/**
 * 按资产 ID 列出其下全部摄像头。
 * @param {number|string} assetId
 * @returns {Promise<Array<Object>>} Camera[]
 */
export const cameraListByAsset = (assetId) =>
  api.get('/v1/cameras', { params: { assetId } });

/**
 * 按资产编号（资产编号）反查其下摄像头 —— 数据回放页主入口。
 * @param {string} assetNo
 * @returns {Promise<Array<Object>>} Camera[]
 */
export const cameraListByAssetNo = (assetNo) =>
  api.get(`/v1/cameras/by-asset-no/${encodeURIComponent(assetNo)}`);

/**
 * 取某路摄像头的实时取流地址（WebRTC / HLS 由边缘媒体节点基于 streamUrl 拼接）。
 * @param {number|string} id 摄像头 ID
 * @returns {Promise<Object>} { cameraId, protocol, playUrl, hlsUrl, webrtcUrl }
 */
export const cameraLive = (id) => api.get(`/v1/cameras/${id}/live`);

/**
 * 历史视频段（默认近 24h，可由 from/to 覆盖）。
 * @param {number|string} id 摄像头 ID
 * @param {string} [from] ISO 时间戳
 * @param {string} [to] ISO 时间戳
 * @returns {Promise<Array<Object>>} VideoSegment[]
 */
export const cameraSegments = (id, from, to) =>
  api.get(`/v1/cameras/${id}/segments`, { params: { from, to } });

/**
 * 时间轴：稀疏帧 + 事件标记，供前端长程回溯。
 * @param {number|string} id 摄像头 ID
 * @returns {Promise<{frames: Array, events: Array}>}
 */
export const cameraTimeline = (id) => api.get(`/v1/cameras/${id}/timeline`);
