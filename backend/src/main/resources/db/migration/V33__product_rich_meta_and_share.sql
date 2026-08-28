-- V33：商品富文本元信息 + 分享能力
-- 支撑后台「发布商品」真实落库：品牌 / 类目 / 参数 / 封面图 / 详情 / 视频 / 直播 / 分享码 / 分润。
-- 说明：category 与 assets.asset_type 采用同一枚举文案（EV/BATTERY/CHARGER/DRONE/PV_STATION），
-- 但 products.category 为独立 varchar 列（前端直接透传，不做枚举强约束）。

ALTER TABLE claw.products
    ADD COLUMN brand             varchar(120),
    ADD COLUMN category          varchar(60),
    ADD COLUMN params_json       text,
    ADD COLUMN cover_images_json text,
    ADD COLUMN detail            text,
    ADD COLUMN video_url         varchar(500),
    ADD COLUMN live_enabled      boolean NOT NULL DEFAULT false,
    ADD COLUMN live_url          varchar(500),
    ADD COLUMN share_code        varchar(40) UNIQUE,
    ADD COLUMN reward_rate       numeric(10,2) NOT NULL DEFAULT 0;
